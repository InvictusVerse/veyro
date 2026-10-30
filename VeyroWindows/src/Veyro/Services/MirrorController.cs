using Veyro.Core.Diagnostics;
using Veyro.Core.Mirroring;
using Veyro.Core.Protocol.Media;
using Veyro.Platform.Media;

namespace Veyro.Services;

/// <summary>
/// One mirror window's session (ADR-066): plans the quality from this PC's settings, opens the
/// media listener, sends <c>streamStart</c>, follows the phone's answers through
/// <see cref="MirrorSession"/>, reads the media socket into the player, and runs the PC's
/// bitrate control. "Ask again" (screen or sound) works as often as the user presses it, at most
/// once per <see cref="MirrorSession.AskInterval"/>. Quality, encoder and sound changes go to a
/// live session as <c>streamConfig</c>, so the phone never asks its user for consent again.
/// All public members are called on the UI thread; <see cref="StatusChanged"/> is raised there.
/// </summary>
public sealed class MirrorController : IDisposable
{
    private readonly IPeerSessionRegistry _sessions;
    private readonly ISettingsStore _settings;
    private readonly Func<PeerMessage.StreamCaps?> _caps;
    private readonly string _pcId;
    private readonly DispatcherQueue _dispatcher;
    private readonly MirrorSession _session;
    private readonly MediaPlayerElement _element;
    private readonly DispatcherQueueTimer _timer;

    private Vms1Listener? _listener;
    private Vms1Connection? _connection;
    private MediaReceiveQueue? _queue;
    private MirrorPlayer? _player;
    private BitrateController? _bitrate;
    private StreamPlan? _plan;
    private CancellationTokenSource? _run;
    private long _droppedSeen;
    private bool _mayAsk;
    private bool _mayAskAudio;
    private bool _disposed;

    public MirrorController(string peerId, string phoneName, string pcId, IPeerSessionRegistry sessions, ISettingsStore settings,
        Func<PeerMessage.StreamCaps?> caps, MediaPlayerElement element)
    {
        PeerId = peerId;
        _pcId = pcId;
        _sessions = sessions;
        _settings = settings;
        _caps = caps;
        _element = element;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _session = new MirrorSession(phoneName);
        _session.Changed += OnSessionChanged;
        _timer = _dispatcher.CreateTimer();
        _timer.Interval = TimeSpan.FromSeconds(1);
        _timer.Tick += (_, _) => OnTick();
        _timer.Start();
    }

    public string PeerId { get; }
    public MirrorStatus Status => _session.Status;
    public PeerMessage.StreamState? LastState => _session.LastState;

    /// <summary>"Ask again" / "Start again" may be pressed now.</summary>
    public bool MayAskAgain => _session.MayAskAgain();

    /// <summary>"Ask again" for the phone's sound may be pressed now.</summary>
    public bool MayAskAudioAgain => _session.MayAskAudioAgain();

    /// <summary>The phone has both a hardware and a software encoder, so the hardware switch means something.</summary>
    public bool PhoneHasEncoderChoice => _caps()?.Encoders is { } encoders && encoders.Any(e => e.Hw) && encoders.Any(e => !e.Hw);

    /// <summary>Raised on the UI thread after the status or one of the Ask again rate limits changed.</summary>
    public event Action<MirrorStatus>? StatusChanged;

    /// <summary>The player shows the phone's picture (after a start or a rebuild). UI thread.</summary>
    public event Action? PictureShown;

    public MirrorQuality Quality => MirrorQuality.FromSettings(_settings.Current.MirrorQualityPreset, _settings.Current.MirrorAudio, _settings.Current.MirrorHardwareEncode);

    /// <summary>Starts, or starts again after a stop. Throttled by the session's ask interval.</summary>
    public async Task StartAsync()
    {
        if (_disposed || _session.Status.IsLive) return;
        if (_session.Status.Phase != MirrorPhase.Idle && !_session.MayAskAgain()) return;
        await TearDownAsync();

        string phone = _session.PhoneName;
        string? host = _sessions.GetAdvertisableHost(PeerId);
        Func<string, byte[], int, byte[]>? export = _sessions.GetExportKey(PeerId);
        if (!_sessions.IsConnected(PeerId) || export is null)
        {
            _session.SetUnavailable($"{phone} is not connected. Open Veyro on the phone and check that both are on the same network.");
            return;
        }
        if (host is null)
        {
            _session.SetUnavailable("This PC has no local network address the phone can reach.");
            return;
        }

        PeerMessage.StreamCaps? caps = _caps();
        MirrorQuality quality = Quality;
        StreamPlan? plan = caps is null ? null : StreamPlanner.Plan(caps, PcVideoDecoders.Find(), quality);
        string sid = StreamRules.NewSid();
        if (MirrorSession.WhyUnavailable(phone, caps, plan) is null)
        {
            try
            {
                _listener = Vms1Listener.Start(_pcId, StreamRules.SidBytes(sid)!, new Vms1PendingSession(PeerId, export));
            }
            catch (IOException error)
            {
                VeyroTrace.Warn($"No media port: {error.Message}");
                _session.SetUnavailable("Every media port (5150-5169) is in use. Close other Veyro windows or apps using them and start again.");
                return;
            }
        }
        PeerMessage.StreamStart? start = _session.Begin(sid, caps, plan, quality.Audio, host, _listener?.Port ?? 0);
        if (start is null)
        {
            await TearDownAsync();
            return;
        }
        _plan = plan;
        _bitrate = new BitrateController(plan!.Bitrate);
        _run = new CancellationTokenSource();
        _ = ReadSocketAsync(_listener!, _run.Token);
        VeyroTrace.Info($"Mirroring requested: {plan.Codec} {(plan.HwEncode ? "hw" : "sw")} {plan.Width}x{plan.Height} {plan.Fps} fps");
        await SendAsync(start);
    }

    public Task AskAgainAsync() => StartAsync();

    public async Task AskAudioAgainAsync()
    {
        if (_session.AskAudioAgain() is { } ask) await SendAsync(ask);
    }

    public async Task StopAsync()
    {
        if (_session.UserStop() is { } stop) await SendAsync(stop);
        await TearDownAsync();
    }

    /// <summary>
    /// The user picked another quality or flipped the hardware switch. A live session gets the
    /// new plan as <c>streamConfig</c>; codec and encoder switch on the same capture.
    /// </summary>
    public async Task OnQualityChangedAsync()
    {
        if (!_session.Status.IsLive || _session.Sid is not { } sid || _plan is not { } old) return;
        if (_caps() is not { } caps || StreamPlanner.Plan(caps, PcVideoDecoders.Find(), Quality) is not { } plan) return;
        _plan = plan;
        _bitrate?.Retarget(plan.Bitrate);
        int bitrate = Math.Min(plan.Bitrate, _bitrate?.Current ?? plan.Bitrate);
        await SendAsync(new PeerMessage.StreamConfig(sid,
            Codec: plan.Codec != old.Codec ? plan.Codec : null,
            Hw: plan.HwEncode != old.HwEncode ? plan.HwEncode : null,
            MaxLong: plan.MaxLong, Fps: plan.Fps, Bitrate: bitrate));
    }

    /// <summary>The user switched the phone's sound on or off.</summary>
    public async Task OnAudioChangedAsync()
    {
        if (_session.SetAudioRequested(_settings.Current.MirrorAudio) is { } config) await SendAsync(config);
    }

    /// <summary>The window was minimised or restored: the phone stops encoding while nobody can see it.</summary>
    public async Task SetPausedAsync(bool paused)
    {
        if (_session.Status.IsLive && _session.Sid is { } sid) await SendAsync(new PeerMessage.StreamConfig(sid, Paused: paused));
    }

    /// <summary>A stream message from this controller's phone. Any thread.</summary>
    public void OnMessage(PeerMessage message) => _dispatcher.TryEnqueue(() =>
    {
        if (_disposed) return;
        switch (message)
        {
            case PeerMessage.StreamState state:
                _session.OnState(state);
                if (!_session.Status.IsLive) _ = TearDownAsync();
                break;
            case PeerMessage.StreamStop stop:
                _session.OnStop(stop);
                if (!_session.Status.IsLive) _ = TearDownAsync();
                break;
        }
    });

    /// <summary>The phone's control session closed. Any thread.</summary>
    public void OnSessionClosed() => _dispatcher.TryEnqueue(() =>
    {
        if (_disposed) return;
        _session.OnPhoneDisconnected();
        _ = TearDownAsync();
    });

    private void OnSessionChanged(MirrorStatus status)
    {
        _mayAsk = _session.MayAskAgain();
        _mayAskAudio = _session.MayAskAudioAgain();
        StatusChanged?.Invoke(status);
    }

    /// <summary>Background: waits for the phone's media socket, then feeds the player until the socket ends.</summary>
    private async Task ReadSocketAsync(Vms1Listener listener, CancellationToken ct)
    {
        Vms1Connection? connection = await listener.TakeAsync().ConfigureAwait(false);
        if (connection is null || ct.IsCancellationRequested)
        {
            connection?.Dispose();
            return;
        }
        var queue = new MediaReceiveQueue();
        var player = new MirrorPlayer(_element, _dispatcher, queue);
        player.KeyframeNeeded += () => _dispatcher.TryEnqueue(() => _ = RequestKeyframeAsync());
        player.Playing += () => _dispatcher.TryEnqueue(() => { if (!ct.IsCancellationRequested) PictureShown?.Invoke(); });
        bool owned = false;
        _dispatcher.TryEnqueue(() =>
        {
            if (ct.IsCancellationRequested || _disposed)
            {
                player.Dispose();
                queue.Close();
                connection.Dispose();
                return;
            }
            owned = true;
            _connection = connection;
            _queue = queue;
            _player = player;
            _droppedSeen = 0;
            _session.OnSocketConnected();
        });
        try
        {
            var reassembler = new AccessUnitReassembler();
            while (!ct.IsCancellationRequested)
            {
                Vms1Frame? frame = await connection.Channel.Reader.ReadAsync(ct).ConfigureAwait(false);
                switch (frame)
                {
                    case null:
                        throw new EndOfStreamException();
                    case Vms1Frame.VideoConfigFrame config:
                        player.ConfigureVideo(config.Config);
                        break;
                    case Vms1Frame.AudioConfigFrame audio:
                        player.ConfigureAudio(audio.Config);
                        break;
                    case Vms1Frame.Video fragment:
                        if (reassembler.Accept(fragment) is { } unit)
                        {
                            player.BeforeAccessUnit(unit.Key);
                            if (queue.Enqueue(unit)) _dispatcher.TryEnqueue(() => _ = RequestKeyframeAsync());
                        }
                        break;
                    case Vms1Frame.Audio audio:
                        player.PushAudio(audio.PtsUs, audio.Samples);
                        break;
                    case Vms1Frame.CloseFrame:
                        throw new EndOfStreamException();
                }
            }
        }
        catch (Exception error) when (error is IOException or InvalidDataException or EndOfStreamException or OperationCanceledException or ObjectDisposedException)
        {
            if (!ct.IsCancellationRequested)
            {
                VeyroTrace.Warn($"Media socket ended: {error.GetType().Name}");
                _dispatcher.TryEnqueue(() =>
                {
                    if (_disposed) return;
                    _session.OnSocketLost();
                    if (!_session.Status.IsLive) _ = TearDownAsync();
                });
            }
        }
        finally
        {
            // Not handed over (cancelled before the UI thread ran): close here.
            _dispatcher.TryEnqueue(() =>
            {
                if (owned) return;
                player.Dispose();
                queue.Close();
                connection.Dispose();
            });
        }
    }

    private async Task RequestKeyframeAsync()
    {
        if (_session.Sid is { } sid && _session.Status.IsLive) await SendAsync(new PeerMessage.StreamKeyframe(sid));
    }

    private void OnTick()
    {
        if (_disposed) return;
        if (_session.Tick() is { } timeout)
        {
            _ = SendAsync(timeout);
            _ = TearDownAsync();
            return;
        }
        // The Ask again buttons come back once the rate limit has passed.
        if (_session.MayAskAgain() != _mayAsk || _session.MayAskAudioAgain() != _mayAskAudio) OnSessionChanged(_session.Status);
        if (_queue is null || _bitrate is null || _session.Sid is not { } sid || _session.Status.Phase != MirrorPhase.Streaming) return;
        long dropped = _queue.Dropped;
        if (_bitrate.Tick(dropped - _droppedSeen) is { } bitrate) _ = SendAsync(new PeerMessage.StreamConfig(sid, Bitrate: bitrate));
        _droppedSeen = dropped;
    }

    private async Task SendAsync(PeerMessage message)
    {
        try
        {
            await _sessions.SendAsync(PeerId, message);
        }
        catch (Exception error) when (error is InvalidOperationException or IOException or OperationCanceledException or ObjectDisposedException)
        {
            VeyroTrace.Warn($"Mirror message not sent: {error.GetType().Name}");
            if (_session.Status.IsLive)
            {
                _session.OnPhoneDisconnected();
                await TearDownAsync();
            }
        }
    }

    private async Task TearDownAsync()
    {
        _run?.Cancel();
        _run = null;
        _plan = null;
        _bitrate = null;
        _player?.Dispose();
        _player = null;
        _queue?.Close();
        _queue = null;
        _connection?.Dispose();
        _connection = null;
        if (_listener is { } listener)
        {
            _listener = null;
            await listener.DisposeAsync();
        }
    }

    /// <summary>Stops a live session (telling the phone) and releases everything.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _timer.Stop();
        _ = StopAsync();
        _disposed = true;
    }
}
