using Veyro.Core.Diagnostics;
using Veyro.Core.Protocol.Media;
using Veyro.Platform.Media;

namespace Veyro.Services;

/// <summary>
/// Feeds "Veyro Phone Camera" (ADR-067) once the add-on is set up. Every half second it looks
/// whether an app (Zoom, Teams, OBS, the Camera app) has the camera open. When one does, it opens
/// the camera window for the shown phone if none is streaming (the phone then asks for its tap),
/// keeps it streaming while minimised, and copies its decoded frames, at the size the app chose,
/// into the camera. A window it opened itself closes 10 s after the last app let go. UI thread.
/// </summary>
public sealed class VirtualCameraService : IDisposable
{
    private static readonly TimeSpan Idle = TimeSpan.FromSeconds(10);

    private readonly MirrorService _mirror;
    private readonly VirtualCameraSetup _setup;
    private readonly ISettingsStore _settings;
    private readonly Func<string?> _phone;
    private readonly DispatcherQueue _dispatcher;
    private readonly DispatcherQueueTimer _timer;
    private readonly VirtualCameraFrames _frames = new();
    private MirrorController? _attached;
    private FrameTap? _tap;
    private bool _wasInDemand;
    private bool _autoOpened;
    private long _lastDemand;
    private bool _ready;

    public VirtualCameraService(MirrorService mirror, VirtualCameraSetup setup, ISettingsStore settings, Func<string?> connectedPhone)
    {
        _mirror = mirror;
        _setup = setup;
        _settings = settings;
        _phone = connectedPhone;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _ready = setup.State == VirtualCameraState.Ready;
        mirror.CameraChanged += OnCameraChanged;
        _timer = _dispatcher.CreateTimer();
        _timer.Interval = TimeSpan.FromMilliseconds(500);
        _timer.Tick += (_, _) => Tick();
        // Without the add-on nothing runs: no timer, no section.
        if (_ready) _timer.Start();
        setup.Changed += () => _dispatcher.TryEnqueue(() =>
        {
            _ready = setup.State == VirtualCameraState.Ready;
            if (_ready) _timer.Start();
            else
            {
                _timer.Stop();
                Detach();
                SetDemand(false);
            }
        });
    }

    public VirtualCameraSetup Setup => _setup;

    /// <summary>An app is using "Veyro Phone Camera" now.</summary>
    public bool InUse => _wasInDemand;

    /// <summary>Raised on the UI thread when an app starts or stops using the camera.</summary>
    public event Action<bool>? InUseChanged;

    private void Tick()
    {
        if (!_ready || !_frames.TryOpen())
        {
            SetDemand(false);
            return;
        }
        bool demand = _frames.InDemand;
        long now = Environment.TickCount64;
        if (demand)
        {
            _lastDemand = now;
            MirrorController? camera = _mirror.Camera;
            if (!_wasInDemand && (camera is null || !camera.Status.IsLive) && _phone() is { } phone)
            {
                // Once per use: a phone that declined is not asked again until the app reopens the camera.
                VeyroTrace.Info("Virtual camera in use: starting the phone's camera");
                _autoOpened = camera is null;
                if (camera is null) _mirror.OpenCamera(phone);
                else _ = camera.StartAsync();
            }
            camera = _mirror.Camera;
            if (camera is not null && !ReferenceEquals(camera, _attached)) Attach(camera);
            if (_attached is { } attached)
            {
                attached.KeepStreamingWhenHidden = true;
                (int w, int h) = _frames.Wanted;
                _tap?.SetTarget(w, h, _settings.Current.WebcamMirror);
            }
        }
        else if (_autoOpened && now - _lastDemand > Idle.TotalMilliseconds)
        {
            _autoOpened = false;
            VeyroTrace.Info("Virtual camera idle: closing the camera window it opened");
            _mirror.CloseCamera();
        }
        SetDemand(demand);
    }

    private void SetDemand(bool demand)
    {
        if (demand == _wasInDemand) return;
        _wasInDemand = demand;
        if (!demand && _attached is { } attached) attached.KeepStreamingWhenHidden = false;
        InUseChanged?.Invoke(demand);
    }

    private void OnCameraChanged(MirrorController? camera)
    {
        if (camera is null) Detach();
        else if (_ready) Attach(camera);
    }

    private void Attach(MirrorController camera)
    {
        Detach();
        _attached = camera;
        var tap = new FrameTap();
        _tap = tap;
        tap.FrameReady += (frame, w, h) => _frames.Write(frame, w, h);
        tap.KeyframeNeeded += () => _dispatcher.TryEnqueue(() => _ = camera.RequestKeyframeAsync());
        camera.VideoConfigReceived += tap.OnConfig;
        camera.AccessUnitReceived += tap.OnAccessUnit;
        if (camera.LastVideoConfig is { } config) tap.OnConfig(config);
        // The tap starts mid-stream: it needs a keyframe to decode from.
        _ = camera.RequestKeyframeAsync();
    }

    private void Detach()
    {
        if (_attached is { } camera && _tap is { } tap)
        {
            camera.VideoConfigReceived -= tap.OnConfig;
            camera.AccessUnitReceived -= tap.OnAccessUnit;
            camera.KeepStreamingWhenHidden = false;
        }
        _tap?.Dispose();
        _tap = null;
        _attached = null;
    }

    public void Dispose()
    {
        _timer.Stop();
        _mirror.CameraChanged -= OnCameraChanged;
        Detach();
        _frames.Dispose();
    }
}
