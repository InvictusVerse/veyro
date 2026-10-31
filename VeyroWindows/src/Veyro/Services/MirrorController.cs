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

    /// <param name="camera">ADR-067: this session shows the phone's camera, not its screen.</param>
    public MirrorController(string peerId, string phoneName, string pcId, IPeerSessionRegistry sessions, ISettingsStore settings,
        Func<PeerMessage.StreamCaps?> caps, MediaPlayerElement element, bool camera = false)
    {
        PeerId = peerId;
        IsCamera = camera;
        _pcId = pcId;
        _sessions = sessions;
        _settings = settings;
        _caps = caps;
        _element = element;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _session = new MirrorSession(phoneName, camera: camera);
        _session.Changed += OnSessionChanged;
        _timer = _dispatcher.CreateTimer();
        _timer.Interval = TimeSpan.FromSeconds(1);
        _timer.Tick += (_, _) => OnTick();
        _timer.Start();
    }

    public string PeerId { get; }

    /// <summary>ADR-067: the phone's camera rather than its screen.</summary>
    public bool IsCamera { get; }

    /// <summary>ADR-067: another consumer (the virtual camera) needs frames while the window is minimised.</summary>
    public bool KeepStreamingWhenHidden { get; set; }

    /// <summary>The phone's latest cleaned caps.</summary>
    public PeerMessage.StreamCaps? Caps => _caps();

    /// <summary>The player while the media socket is open; null otherwise.</summary>
    public MirrorPlayer? Player => _player;

    /// <summary>ADR-067: every VIDEO_CONFIG from the phone, for the virtual camera's decoder. Background thread.</summary>
    public event Action<Vms1.VideoConfig>? VideoConfigReceived;

    /// <summary>ADR-067: every whole access unit from the phone. Background thread; do not change the data.</summary>
    public event Action<AccessUnit>? AccessUnitReceived;

    /// <summary>The latest VIDEO_CONFIG of this session, or null.</summary>
    public Vms1.VideoConfig? LastVideoConfig { get; private set; }

    /// <summary>Asks the phone for a keyframe (rate-limited on the phone).</summary>
    public Task RequestKeyframeAsync() => RequestKeyframeCoreAsync();

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
        Torch = false;

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
        StreamPlan? plan;
        string? cameraId = null;
        bool ready;
        if (IsCamera)
        {
            (plan, cameraId) = PlanCamera(caps);
            ready = MirrorSession.WhyCameraUnavailable(phone, caps, plan) is null && cameraId is not null;
        }
        else
        {
            plan = caps is null ? null : StreamPlanner.Plan(caps, PcVideoDecoders.Find(), quality);
            ready = MirrorSession.WhyUnavailable(phone, caps, plan) is null;
        }
        string sid = StreamRules.NewSid();
        if (ready)
        {
            try
            {
                _listener = Vms1Listener.Start(_pcId, StreamRules.SidBytes(sid)!, new Vms1PendingSession(PeerId, export),
                    source: IsCamera ? Vms1.SourceCamera : Vms1.SourceScreen);
            }
            catch (IOException error)
            {
                VeyroTrace.Warn($"No media port: {error.Message}");
                _session.SetUnavailable("Every media port (5150-5169) is in use. Close other Veyro windows or apps using them and start again.");
                return;
            }
        }
        PeerMessage.StreamStart? start = IsCamera
            ? _session.BeginCamera(sid, caps, _listener is null ? null : plan, cameraId, host, _listener?.Port ?? 0)
            : _session.Begin(sid, caps, plan, quality.Audio, host, _listener?.Port ?? 0);
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
    /// <summary>ADR-067: the camera, size and frame rate from this PC's settings, limited to what the phone offers.</summary>
    private (StreamPlan? Plan, string? CameraId) PlanCamera(PeerMessage.StreamCaps? caps)
    {
        AppSettings s = _settings.Current;
        if (caps is null || CameraPlanner.Choose(caps, s.WebcamCamera) is not { } camera) return (null, null);
        if (CameraPlanner.ChooseSize(camera, s.WebcamWidth, s.WebcamHeight) is not { } size) return (null, camera.Id);
        return (CameraPlanner.Plan(caps, size, s.WebcamFps, PcVideoDecoders.Find(), s.MirrorHardwareEncode), camera.Id);
    }

    /// <summary>ADR-067: the camera settings changed; a live session switches lens or size without a new consent.</summary>
    private async Task OnCameraChangedAsync(string sid, StreamPlan old)
    {
        var (plan, cameraId) = PlanCamera(_caps());
        if (plan is null || cameraId is null) return;
        _plan = plan;
        _bitrate?.Retarget(plan.Bitrate);
        await SendAsync(new PeerMessage.StreamConfig(sid,
            Codec: plan.Codec != old.Codec ? plan.Codec : null,
            Hw: plan.HwEncode != old.HwEncode ? plan.HwEncode : null,
            MaxLong: plan.MaxLong, Fps: plan.Fps, Bitrate: plan.Bitrate,
            Camera: cameraId, W: plan.Width, H: plan.Height));
    }

    public async Task OnQualityChangedAsync()
    {
        if (!_session.Status.IsLive || _session.Sid is not { } sid || _plan is not { } old) return;
        if (IsCamera)
        {
            await OnCameraChangedAsync(sid, old);
            return;
        }
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

    /// <summary>Camera sessions: the phone's flash as a steady light. Off at every new session.</summary>
    public bool Torch { get; private set; }

    /// <summary>The lens this PC's settings pick has a flash.</summary>
    public bool CameraHasFlash =>
        IsCamera && _caps() is { } caps && CameraPlanner.Choose(caps, _settings.Current.WebcamCamera)?.Flash == true;

    public async Task SetTorchAsync(bool on)
    {
        Torch = on;
        if (IsCamera && _session.Status.IsLive && _session.Sid is { } sid) await SendAsync(new PeerMessage.StreamConfig(sid, Torch: on));
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
                        LastVideoConfig = config.Config;
                        VideoConfigReceived?.Invoke(config.Config);
                        break;
                    case Vms1Frame.AudioConfigFrame audio:
                        player.ConfigureAudio(audio.Config);
                        break;
                    case Vms1Frame.Video fragment:
                        if (reassembler.Accept(fragment) is { } unit)
                        {
                            player.BeforeAccessUnit(unit.Key);
                            if (queue.Enqueue(unit)) _dispatcher.TryEnqueue(() => _ = RequestKeyframeAsync());
                            try { AccessUnitReceived?.Invoke(unit); }
                            catch (Exception error) { VeyroTrace.Warn($"Frame consumer failed: {error.GetType().Name}"); }
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

    private async Task RequestKeyframeCoreAsync()
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
