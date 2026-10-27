using System.Globalization;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyro.Core.Diagnostics;

namespace Veyro.ViewModels;

/// <summary>
/// The active phone's media for the sidebar card (ADR-026). Talks only to
/// <see cref="IPhoneMediaService"/>; never to sockets. Position is extrapolated locally and the
/// one-second tick only runs while the card is on screen and the session is playing.
/// </summary>
public sealed class PhoneMediaViewModel : BaseViewModel
{
    private static readonly TimeSpan TickInterval = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan SendDelay = TimeSpan.FromMilliseconds(250);
    /// <summary>After a local seek or volume change, ignore the phone's older value for this long.</summary>
    private static readonly TimeSpan EchoHold = TimeSpan.FromMilliseconds(1500);

    private readonly IPhoneMediaService _media;
    private readonly DispatcherQueue? _dispatcher;
    private readonly DispatcherQueueTimer? _tick;
    private readonly DispatcherQueueTimer? _seekSend;
    private readonly DispatcherQueueTimer? _volumeSend;
    private string? _peerId;
    private PhoneMediaSnapshot? _snapshot;
    private MediaSessionInfo? _session;
    private string? _artSource;
    private ImageSource? _art;
    private bool _viewActive;
    private double _pendingSeekSeconds;
    private double _pendingVolume;
    private DateTimeOffset _seekHoldUntil;
    private DateTimeOffset _volumeHoldUntil;
    private double _positionSeconds;
    private double _volume;
    private bool _isVolumeOpen;

    public PhoneMediaViewModel(IPhoneMediaService media, IDeviceManager deviceManager)
    {
        _media = media;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _peerId = deviceManager.ActiveDevice?.DeviceId;
        if (_dispatcher is not null)
        {
            _tick = _dispatcher.CreateTimer();
            _tick.Interval = TickInterval;
            _tick.IsRepeating = true;
            _tick.Tick += (_, _) => UpdatePosition();
            _seekSend = OneShot(SendDelay, () => _ = SendSeekAsync());
            _volumeSend = OneShot(SendDelay, () => _ = SendVolumeAsync());
        }

        _media.Changed += peerId => Post(() =>
        {
            if (string.Equals(peerId, _peerId, StringComparison.OrdinalIgnoreCase)) Sync();
        });
        deviceManager.ActiveDeviceChanged += device => Post(() =>
        {
            _peerId = device?.DeviceId;
            Sync();
        });
        Sync();
    }

    /// <summary>True while the view applies a model value; slider handlers ignore those changes.</summary>
    public bool IsApplying { get; private set; }

    public bool HasSession => _session is not null;
    public bool HasNoSession => _session is null;
    public bool CanControl => _session is not null && _peerId is not null && _media.CanControl(_peerId);
    public string Title => _session?.Title ?? string.Empty;
    public bool HasTitle => !string.IsNullOrWhiteSpace(_session?.Title);

    /// <summary>Sefirah's volume button reveals the volume slider under the track.</summary>
    public bool IsVolumeOpen
    {
        get => _isVolumeOpen;
        set
        {
            if (SetProperty(ref _isVolumeOpen, value)) OnPropertyChanged(nameof(ShowVolume));
        }
    }

    public bool ShowVolume => _isVolumeOpen && HasVolume;
    public string VolumeText => $"{Math.Round(_volume)}";
    public string Artist => _session?.Artist ?? string.Empty;
    public bool HasArtist => !string.IsNullOrWhiteSpace(_session?.Artist);
    public string AppName => _session?.App ?? string.Empty;
    public bool HasAppName => !string.IsNullOrWhiteSpace(_session?.App);
    public ImageSource? Art => _art;
    public bool HasArt => _art is not null;
    public bool HasNoArt => _art is null;
    public bool IsPlaying => _session?.Playing == true;
    public string PlayPauseGlyph => IsPlaying ? "\uE769" : "\uE768";
    public string PlayPauseLabel => IsPlaying ? "Pause" : "Play";
    public bool CanPrev => CanControl && _session?.CanPrev == true;
    public bool CanNext => CanControl && _session?.CanNext == true;
    public bool CanSeek => _session?.CanSeek == true && _session.DurationMs is > 0;
    public bool CanSeekNow => CanSeek && CanControl;
    public double DurationSeconds => Math.Max(1, (_session?.DurationMs ?? 0) / 1000.0);
    public string DurationText => Format(_session?.DurationMs ?? 0);
    public double PositionSeconds => _positionSeconds;
    public string PositionText => Format((long)(_positionSeconds * 1000));
    public bool HasVolume => _snapshot?.State.Volume is not null;
    public double Volume => _volume;
    public string VolumeGlyph => _snapshot?.State.Muted == true || _volume == 0 ? "\uE74F" : "\uE767";

    /// <summary>Called by the view when it is shown or hidden (window minimised, control unloaded).</summary>
    public void SetViewActive(bool active)
    {
        _viewActive = active;
        if (active) UpdatePosition();
        UpdateTick();
    }

    public Task TogglePlayPauseAsync() => SendSessionAsync(PeerMessage.MediaCommand.Toggle);
    public Task NextAsync() => SendSessionAsync(PeerMessage.MediaCommand.Next);
    public Task PreviousAsync() => SendSessionAsync(PeerMessage.MediaCommand.Prev);

    /// <summary>User moved the seek slider. Sends the last value after a short pause.</summary>
    public void RequestSeek(double seconds)
    {
        if (!CanSeekNow) return;
        _pendingSeekSeconds = seconds;
        _seekHoldUntil = DateTimeOffset.UtcNow + EchoHold;
        _positionSeconds = seconds;
        OnPropertyChanged(nameof(PositionText));
        Restart(_seekSend, () => _ = SendSeekAsync());
    }

    /// <summary>User moved the volume slider. Sends the last value after a short pause.</summary>
    public void RequestVolume(double value)
    {
        if (!CanControl) return;
        _pendingVolume = value;
        _volumeHoldUntil = DateTimeOffset.UtcNow + EchoHold;
        _volume = value;
        OnPropertyChanged(nameof(VolumeGlyph));
        OnPropertyChanged(nameof(VolumeText));
        Restart(_volumeSend, () => _ = SendVolumeAsync());
    }

    private async Task SendSessionAsync(string action, long? value = null)
    {
        if (_peerId is not { } peer || _session is not { } session) return;
        try { await _media.SendCommandAsync(peer, action, session.Id, value); }
        catch (Exception error) { VeyroTrace.Error($"Phone media '{action}' failed", error); }
    }

    private Task SendSeekAsync()
    {
        _seekHoldUntil = DateTimeOffset.UtcNow + EchoHold;
        return SendSessionAsync(PeerMessage.MediaCommand.Seek, (long)(_pendingSeekSeconds * 1000));
    }

    private async Task SendVolumeAsync()
    {
        if (_peerId is not { } peer) return;
        _volumeHoldUntil = DateTimeOffset.UtcNow + EchoHold;
        try { await _media.SendCommandAsync(peer, PeerMessage.MediaCommand.Volume, null, (long)Math.Round(_pendingVolume)); }
        catch (Exception error) { VeyroTrace.Error("Phone media 'volume' failed", error); }
    }

    private void Sync()
    {
        _snapshot = _peerId is null ? null : _media.GetState(_peerId);
        IReadOnlyList<MediaSessionInfo> sessions = _snapshot?.State.Sessions ?? [];
        // Prefer a playing session, and among equals the one already shown.
        string? shownId = _session?.Id;
        _session = sessions.FirstOrDefault(s => s.Playing && s.Id == shownId)
                   ?? sessions.FirstOrDefault(s => s.Playing)
                   ?? sessions.FirstOrDefault(s => s.Id == shownId)
                   ?? sessions.FirstOrDefault();
        if (_session?.Id != shownId) _seekHoldUntil = default;

        if (_snapshot?.State.Volume is { } volume && DateTimeOffset.UtcNow >= _volumeHoldUntil) _volume = volume;
        _ = UpdateArtAsync(_session?.Art);

        Apply(() =>
        {
            foreach (string name in new[]
                     {
                         nameof(HasSession), nameof(HasNoSession), nameof(CanControl), nameof(Title), nameof(HasTitle),
                         nameof(ShowVolume), nameof(VolumeText), nameof(Artist),
                         nameof(HasArtist), nameof(AppName), nameof(HasAppName), nameof(IsPlaying), nameof(PlayPauseGlyph),
                         nameof(PlayPauseLabel), nameof(CanPrev), nameof(CanNext), nameof(CanSeek), nameof(CanSeekNow),
                         nameof(DurationSeconds), nameof(DurationText), nameof(HasVolume), nameof(Volume), nameof(VolumeGlyph)
                     })
            {
                OnPropertyChanged(name);
            }
        });
        UpdatePosition();
        UpdateTick();
    }

    private void UpdatePosition()
    {
        // While the user drags (and briefly after), the slider shows their value, not ours.
        if (DateTimeOffset.UtcNow < _seekHoldUntil) return;
        long ms = 0;
        if (_session is not null && _snapshot is not null)
        {
            ms = MediaSnapshots.ExtrapolatePositionMs(
                _session, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), _snapshot.ReceivedAtMs) ?? 0;
        }
        _positionSeconds = ms / 1000.0;
        Apply(() =>
        {
            OnPropertyChanged(nameof(PositionSeconds));
            OnPropertyChanged(nameof(PositionText));
        });
    }

    private void UpdateTick()
    {
        if (_tick is null) return;
        bool run = _viewActive && IsPlaying;
        if (run && !_tick.IsRunning) _tick.Start();
        else if (!run && _tick.IsRunning) _tick.Stop();
    }

    private async Task UpdateArtAsync(string? base64)
    {
        if (base64 == _artSource) return;
        _artSource = base64;
        ImageSource? image = null;
        if (base64 is not null)
        {
            try
            {
                byte[] bytes = Convert.FromBase64String(base64);
                var bitmap = new BitmapImage { DecodePixelWidth = 80 };
                using var stream = new MemoryStream(bytes);
                await bitmap.SetSourceAsync(stream.AsRandomAccessStream());
                image = bitmap;
            }
            catch (Exception error)
            {
                // Untrusted bytes: anything that does not decode is simply not shown.
                VeyroTrace.Error("Decoding phone media artwork failed", error);
            }
        }
        if (base64 != _artSource) return; // a newer track arrived while decoding
        _art = image;
        OnPropertyChanged(nameof(Art));
        OnPropertyChanged(nameof(HasArt));
        OnPropertyChanged(nameof(HasNoArt));
    }

    private void Apply(Action raise)
    {
        IsApplying = true;
        try { raise(); }
        finally { IsApplying = false; }
    }

    private DispatcherQueueTimer OneShot(TimeSpan interval, Action action)
    {
        DispatcherQueueTimer timer = _dispatcher!.CreateTimer();
        timer.Interval = interval;
        timer.IsRepeating = false;
        timer.Tick += (_, _) => action();
        return timer;
    }

    private static void Restart(DispatcherQueueTimer? timer, Action fallback)
    {
        if (timer is null)
        {
            fallback();
            return;
        }
        timer.Stop();
        timer.Start();
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }

    private static string Format(long ms)
    {
        var time = TimeSpan.FromMilliseconds(Math.Max(0, ms));
        return time.TotalHours >= 1
            ? time.ToString(@"h\:mm\:ss", CultureInfo.InvariantCulture)
            : time.ToString(@"m\:ss", CultureInfo.InvariantCulture);
    }
}
