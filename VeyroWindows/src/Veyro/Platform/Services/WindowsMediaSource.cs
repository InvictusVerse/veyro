using Veyro.Core.Diagnostics;
using Veyro.Platform.Interop;
using Windows.ApplicationModel;
using Windows.Foundation;
using Windows.Graphics.Imaging;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace Veyro.Platform.Services;

/// <summary>
/// The PC's media sessions (GlobalSystemMediaTransportControlsSessionManager) and master volume
/// (Core Audio) as a <c>mediaState</c> snapshot for the phone (ADR-026). Session events trigger a
/// debounced rebuild; volume is polled once a second because Core Audio change callbacks would
/// need a managed COM server. Media metadata is user data: never logged here.
/// </summary>
public sealed class WindowsMediaSource : IWindowsMediaSource
{
    private const uint MaxArtEdge = 160;
    private const int MaxArtBytes = 24 * 1024;
    private static readonly TimeSpan RefreshDebounce = TimeSpan.FromMilliseconds(150);
    private static readonly TimeSpan VolumePollInterval = TimeSpan.FromSeconds(1);

    private readonly object _gate = new();
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private readonly CoreAudioInterop.MasterVolume _masterVolume = new();
    private readonly Dictionary<string, string> _appNames = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, (string Key, string? Art)> _artCache = new(StringComparer.Ordinal);
    private GlobalSystemMediaTransportControlsSessionManager? _manager;
    private List<GlobalSystemMediaTransportControlsSession> _subscribed = [];
    private Dictionary<string, GlobalSystemMediaTransportControlsSession> _byId = new(StringComparer.Ordinal);
    private PeerMessage.MediaState _current = MediaSnapshots.Empty;
    private Timer? _volumeTimer;
    private int _refreshQueued;
    private int _resubscribe = 1;
    private int _polling;
    private bool _disposed;

    public event Action? Changed;

    public PeerMessage.MediaState Current
    {
        get { lock (_gate) return _current; }
    }

    public async Task StartAsync()
    {
        try
        {
            _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            _manager.SessionsChanged += OnSessionsChanged;
            QueueRefresh();
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Media session manager unavailable", error);
        }
        // Not polled until a phone connects (SetVolumePolling): each read opens the audio endpoint.
        _volumeTimer = new Timer(_ => PollVolume(), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        if (_volumeInterval is { } interval) _volumeTimer.Change(TimeSpan.Zero, interval);
    }

    private TimeSpan? _volumeInterval;

    public void SetVolumePolling(TimeSpan? interval)
    {
        lock (_gate)
        {
            if (_disposed || interval == _volumeInterval) return;
            bool resumed = _volumeInterval is null && interval is not null;
            _volumeInterval = interval;
            if (interval is { } every) _volumeTimer?.Change(resumed ? TimeSpan.Zero : every, every);
            else _volumeTimer?.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        }
    }

    public void Dispose()
    {
        lock (_gate) _disposed = true;
        _volumeTimer?.Dispose();
        if (_manager is not null) _manager.SessionsChanged -= OnSessionsChanged;
        Unsubscribe();
    }

    public async Task ExecuteAsync(PeerMessage.MediaCommand command, CancellationToken ct = default)
    {
        switch (command.Action)
        {
            case PeerMessage.MediaCommand.Volume when command.Value is { } volume:
                _masterVolume.SetVolume((int)volume);
                PollVolume();
                return;
            case PeerMessage.MediaCommand.Mute when command.Value is { } mute:
                _masterVolume.SetMute(mute != 0);
                PollVolume();
                return;
        }

        GlobalSystemMediaTransportControlsSession? session;
        lock (_gate) _byId.TryGetValue(command.Id ?? string.Empty, out session);
        if (session is null) return;

        bool accepted = command.Action switch
        {
            PeerMessage.MediaCommand.Play => await session.TryPlayAsync(),
            PeerMessage.MediaCommand.Pause => await session.TryPauseAsync(),
            PeerMessage.MediaCommand.Toggle => await session.TryTogglePlayPauseAsync(),
            PeerMessage.MediaCommand.Next => await session.TrySkipNextAsync(),
            PeerMessage.MediaCommand.Prev => await session.TrySkipPreviousAsync(),
            PeerMessage.MediaCommand.Seek when command.Value is { } ms =>
                await session.TryChangePlaybackPositionAsync(
                    session.GetTimelineProperties().StartTime.Ticks + ms * TimeSpan.TicksPerMillisecond),
            _ => false
        };
        if (!accepted) VeyroTrace.Info($"Media session declined '{command.Action}'");
    }

    private void OnSessionsChanged(GlobalSystemMediaTransportControlsSessionManager sender, SessionsChangedEventArgs args)
    {
        Interlocked.Exchange(ref _resubscribe, 1);
        QueueRefresh();
    }

    private void OnMediaPropertiesChanged(GlobalSystemMediaTransportControlsSession sender, MediaPropertiesChangedEventArgs args) => QueueRefresh();

    private void OnPlaybackInfoChanged(GlobalSystemMediaTransportControlsSession sender, PlaybackInfoChangedEventArgs args) => QueueRefresh();

    private void OnTimelineChanged(GlobalSystemMediaTransportControlsSession sender, TimelinePropertiesChangedEventArgs args) => QueueRefresh();

    /// <summary>Bursts of session events collapse into one rebuild.</summary>
    private void QueueRefresh()
    {
        if (Interlocked.Exchange(ref _refreshQueued, 1) == 1) return;
        _ = Task.Run(async () =>
        {
            await Task.Delay(RefreshDebounce).ConfigureAwait(false);
            Interlocked.Exchange(ref _refreshQueued, 0);
            await RefreshAsync().ConfigureAwait(false);
        });
    }

    private async Task RefreshAsync()
    {
        await _refreshGate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_manager is null) return;
            IReadOnlyList<GlobalSystemMediaTransportControlsSession> sessions = _manager.GetSessions();
            if (Interlocked.Exchange(ref _resubscribe, 0) == 1) Resubscribe(sessions);

            var infos = new List<MediaSessionInfo>();
            var byId = new Dictionary<string, GlobalSystemMediaTransportControlsSession>(StringComparer.Ordinal);
            var perApp = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
            foreach (GlobalSystemMediaTransportControlsSession session in sessions.Take(MediaSnapshots.MaxSessions))
            {
                try
                {
                    string aumid = string.IsNullOrWhiteSpace(session.SourceAppUserModelId) ? "unknown" : session.SourceAppUserModelId;
                    int ordinal = perApp[aumid] = perApp.GetValueOrDefault(aumid) + 1;
                    string id = ordinal == 1 ? aumid : $"{aumid}#{ordinal}";
                    infos.Add(await DescribeAsync(session, id, aumid).ConfigureAwait(false));
                    byId[id] = session;
                }
                catch (Exception error)
                {
                    VeyroTrace.Error("Reading a media session failed", error);
                }
            }

            foreach (string stale in _artCache.Keys.Where(key => !byId.ContainsKey(key)).ToList()) _artCache.Remove(stale);

            Publish(byId, previous => new PeerMessage.MediaState(infos, previous.Volume, previous.Muted));
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Refreshing media sessions failed", error);
        }
        finally
        {
            _refreshGate.Release();
        }
    }

    private async Task<MediaSessionInfo> DescribeAsync(GlobalSystemMediaTransportControlsSession session, string id, string aumid)
    {
        GlobalSystemMediaTransportControlsSessionMediaProperties? properties = null;
        try { properties = await session.TryGetMediaPropertiesAsync(); }
        catch (Exception error) { VeyroTrace.Error("Reading media properties failed", error); }

        GlobalSystemMediaTransportControlsSessionTimelineProperties timeline = session.GetTimelineProperties();
        GlobalSystemMediaTransportControlsSessionPlaybackInfo playback = session.GetPlaybackInfo();
        GlobalSystemMediaTransportControlsSessionPlaybackControls controls = playback.Controls;

        long? duration = null;
        long? position = null;
        long? sampledAt = null;
        TimeSpan length = timeline.EndTime - timeline.StartTime;
        if (length > TimeSpan.Zero)
        {
            duration = (long)length.TotalMilliseconds;
            position = Math.Clamp((long)(timeline.Position - timeline.StartTime).TotalMilliseconds, 0, duration.Value);
            long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            long updated = timeline.LastUpdatedTime.ToUnixTimeMilliseconds();
            // Position was sampled at LastUpdatedTime; apps that never set it report year 1.
            sampledAt = updated > 0 && updated <= now + 1_000 ? updated : now;
        }

        string? title = Blank(properties?.Title);
        string? artist = Blank(properties?.Artist) ?? Blank(properties?.AlbumArtist);
        string? album = Blank(properties?.AlbumTitle);
        return new MediaSessionInfo(
            id,
            App: AppName(aumid),
            Title: title,
            Artist: artist,
            Album: album,
            Playing: playback.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing,
            PositionMs: position,
            DurationMs: duration,
            Ts: sampledAt,
            CanPrev: controls.IsPreviousEnabled,
            CanNext: controls.IsNextEnabled,
            CanSeek: controls.IsPlaybackPositionEnabled && duration is not null,
            Art: await ArtAsync(id, $"{title}\n{artist}\n{album}", properties?.Thumbnail).ConfigureAwait(false));
    }

    /// <summary>Thumbnail scaled into 160x160 as JPEG; omitted above 24 KiB (04-PROTOCOL.md 6.2).</summary>
    private async Task<string?> ArtAsync(string id, string trackKey, IRandomAccessStreamReference? thumbnail)
    {
        if (_artCache.TryGetValue(id, out var cached) && cached.Key == trackKey) return cached.Art;
        string? art = null;
        if (thumbnail is not null)
        {
            try
            {
                using IRandomAccessStreamWithContentType input = await thumbnail.OpenReadAsync();
                BitmapDecoder decoder = await BitmapDecoder.CreateAsync(input);
                uint width = decoder.PixelWidth;
                uint height = decoder.PixelHeight;
                if (width > 0 && height > 0)
                {
                    double scale = Math.Min(1.0, Math.Min((double)MaxArtEdge / width, (double)MaxArtEdge / height));
                    uint targetWidth = Math.Max(1, (uint)Math.Round(width * scale));
                    uint targetHeight = Math.Max(1, (uint)Math.Round(height * scale));
                    PixelDataProvider pixels = await decoder.GetPixelDataAsync(
                        BitmapPixelFormat.Bgra8,
                        BitmapAlphaMode.Ignore,
                        new BitmapTransform { ScaledWidth = targetWidth, ScaledHeight = targetHeight, InterpolationMode = BitmapInterpolationMode.Fant },
                        ExifOrientationMode.IgnoreExifOrientation,
                        ColorManagementMode.DoNotColorManage);

                    using var output = new InMemoryRandomAccessStream();
                    var options = new BitmapPropertySet { ["ImageQuality"] = new BitmapTypedValue(0.8f, PropertyType.Single) };
                    BitmapEncoder encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, output, options);
                    encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Ignore, targetWidth, targetHeight, 96, 96, pixels.DetachPixelData());
                    await encoder.FlushAsync();

                    if (output.Size is > 0 and <= MaxArtBytes)
                    {
                        byte[] bytes = new byte[output.Size];
                        using DataReader reader = new(output.GetInputStreamAt(0));
                        await reader.LoadAsync((uint)output.Size);
                        reader.ReadBytes(bytes);
                        art = Convert.ToBase64String(bytes);
                    }
                }
            }
            catch (Exception error)
            {
                VeyroTrace.Error("Encoding media artwork failed", error);
            }
        }
        _artCache[id] = (trackKey, art);
        return art;
    }

    private string AppName(string aumid)
    {
        lock (_gate)
        {
            if (_appNames.TryGetValue(aumid, out string? known)) return known;
        }
        string name = aumid;
        try
        {
            // Packaged apps resolve; plain desktop executables throw and keep the AUMID.
            string? display = AppInfo.GetFromAppUserModelId(aumid)?.DisplayInfo?.DisplayName;
            if (!string.IsNullOrWhiteSpace(display)) name = display;
        }
        catch (Exception) { }
        lock (_gate) _appNames[aumid] = name;
        return name;
    }

    private void PollVolume()
    {
        if (Interlocked.Exchange(ref _polling, 1) == 1) return;
        try
        {
            (int Volume, bool Muted)? reading = _masterVolume.Read();
            Publish(null, previous => previous with { Volume = reading?.Volume, Muted = reading?.Muted });
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Reading master volume failed", error);
        }
        finally
        {
            Interlocked.Exchange(ref _polling, 0);
        }
    }

    /// <summary>Swaps in a new snapshot and raises Changed only if peers must hear about it.</summary>
    private void Publish(
        Dictionary<string, GlobalSystemMediaTransportControlsSession>? byId,
        Func<PeerMessage.MediaState, PeerMessage.MediaState> update)
    {
        bool significant;
        lock (_gate)
        {
            if (_disposed) return;
            PeerMessage.MediaState next = update(_current);
            significant = MediaSnapshots.IsSignificantChange(_current, next);
            if (byId is not null) _byId = byId;
            // Keep the previous sample when nothing significant moved: its ts and position still
            // extrapolate correctly, and it is what peers last received.
            if (significant) _current = next;
        }
        if (significant) Changed?.Invoke();
    }

    private void Resubscribe(IReadOnlyList<GlobalSystemMediaTransportControlsSession> sessions)
    {
        Unsubscribe();
        var subscribed = new List<GlobalSystemMediaTransportControlsSession>();
        foreach (GlobalSystemMediaTransportControlsSession session in sessions)
        {
            try
            {
                session.MediaPropertiesChanged += OnMediaPropertiesChanged;
                session.PlaybackInfoChanged += OnPlaybackInfoChanged;
                session.TimelinePropertiesChanged += OnTimelineChanged;
                subscribed.Add(session);
            }
            catch (Exception error)
            {
                VeyroTrace.Error("Subscribing to a media session failed", error);
            }
        }
        _subscribed = subscribed;
    }

    private void Unsubscribe()
    {
        foreach (GlobalSystemMediaTransportControlsSession session in _subscribed)
        {
            try
            {
                session.MediaPropertiesChanged -= OnMediaPropertiesChanged;
                session.PlaybackInfoChanged -= OnPlaybackInfoChanged;
                session.TimelinePropertiesChanged -= OnTimelineChanged;
            }
            catch (Exception) { }
        }
        _subscribed = [];
    }

    private static string? Blank(string? value) => string.IsNullOrWhiteSpace(value) ? null : value;
}
