using Veyro.Core.Diagnostics;
using Veyro.Views;

namespace Veyro.Services;

/// <summary>
/// Screen mirroring on this PC (ADR-066): keeps each phone's latest <c>streamCaps</c> (checked
/// with <see cref="StreamRules.Clean"/>), opens one mirror window per phone, and routes the
/// phone's <c>streamState</c> / <c>streamStop</c> to that window's controller. Caps are dropped
/// when the phone's session closes. Created and used on the UI thread; events arrive on any thread.
/// </summary>
public sealed class MirrorService : IDisposable
{
    private readonly IPeerSessionRegistry _sessions;
    private readonly ITrustStore _trust;
    private readonly string _pcId;
    private readonly ISettingsStore _settings;
    private readonly IAppThemeModeService _theme;
    private readonly DispatcherQueue? _dispatcher;
    private readonly object _gate = new();
    private readonly Dictionary<string, PeerMessage.StreamCaps> _caps = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, MirrorWindow> _windows = new(StringComparer.OrdinalIgnoreCase);

    public MirrorService(IPeerSessionRegistry sessions, ITrustStore trust, string pcId, ISettingsStore settings, IAppThemeModeService theme)
    {
        _sessions = sessions;
        _trust = trust;
        _pcId = pcId;
        _settings = settings;
        _theme = theme;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _sessions.StreamMessageReceived += OnStreamMessage;
        _sessions.SessionClosed += OnSessionClosed;
    }

    private volatile bool _mirroring;

    /// <summary>A phone's screen is being shown now (for Do not disturb while mirroring). Any thread.</summary>
    public bool IsMirroring => _mirroring;

    private void UpdateMirroring() => _mirroring = _windows.Values.Any(w => !w.IsCamera && w.Controller.Status.IsLive);

    private static string KeyOf(string peerId, bool camera) => camera ? peerId + "|camera" : peerId;

    /// <summary>ADR-067: a camera window opened or closed (for the virtual camera). UI thread.</summary>
    public event Action<MirrorController?>? CameraChanged;

    /// <summary>The open camera window's controller, if any.</summary>
    public MirrorController? Camera => _windows.Values.FirstOrDefault(w => w.IsCamera)?.Controller;

    /// <summary>ADR-067: opens (or brings forward) the camera window for <paramref name="peerId"/>. UI thread.</summary>
    public void OpenCamera(string peerId) => OpenWindow(peerId, camera: true);

    /// <summary>The phone's latest usable caps, or null when it has sent none on this connection.</summary>
    public PeerMessage.StreamCaps? CapsOf(string peerId)
    {
        lock (_gate) return _caps.GetValueOrDefault(peerId);
    }

    /// <summary>Opens (or brings forward) the mirror window for <paramref name="peerId"/> and starts mirroring. UI thread.</summary>
    public void Open(string peerId) => OpenWindow(peerId, camera: false);

    private void OpenWindow(string peerId, bool camera)
    {
        string key = KeyOf(peerId, camera);
        if (_windows.TryGetValue(key, out MirrorWindow? existing))
        {
            existing.Activate();
            if (!existing.Controller.Status.IsLive) _ = existing.Controller.StartAsync();
            return;
        }
        if (_trust.GetPeer(peerId) is not { } peer) return;
        try
        {
            if (camera)
            {
                // One camera window at a time: the virtual camera shows one phone.
                foreach (MirrorWindow other in _windows.Values.Where(w => w.IsCamera).ToList()) other.Close();
            }
            var window = new MirrorWindow(peerId, peer.ShownName, _pcId, _sessions, _settings, () => CapsOf(peerId), _theme, camera);
            window.Closed += (_, _) =>
            {
                _windows.Remove(key);
                UpdateMirroring();
                if (camera) CameraChanged?.Invoke(Camera);
            };
            window.Controller.StatusChanged += _ => UpdateMirroring();
            _windows[key] = window;
            window.Activate();
            _ = window.Controller.StartAsync();
            if (camera) CameraChanged?.Invoke(window.Controller);
            VeyroTrace.Info(camera ? "Camera window opened" : "Mirror window opened");
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Opening the mirror window failed", error);
            _windows.Remove(key);
        }
    }

    private void OnStreamMessage(string peerId, PeerMessage message)
    {
        switch (message)
        {
            case PeerMessage.StreamCaps caps:
                if (StreamRules.Clean(caps) is { } clean)
                {
                    lock (_gate) _caps[peerId] = clean;
                }
                else
                {
                    VeyroTrace.Warn("Stream caps dropped: a field broke a rule");
                }
                break;
            case PeerMessage.StreamState or PeerMessage.StreamStop:
                // Each window's session ignores another sid, so both windows of a phone may hear it.
                Post(() =>
                {
                    foreach (MirrorWindow window in WindowsOf(peerId)) window.Controller.OnMessage(message);
                });
                break;
        }
    }

    private List<MirrorWindow> WindowsOf(string peerId) =>
        _windows.Values.Where(w => string.Equals(w.PeerId, peerId, StringComparison.OrdinalIgnoreCase)).ToList();

    private void OnSessionClosed(string peerId)
    {
        lock (_gate) _caps.Remove(peerId);
        Post(() =>
        {
            foreach (MirrorWindow window in WindowsOf(peerId)) window.Controller.OnSessionClosed();
        });
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }

    public void Dispose()
    {
        _sessions.StreamMessageReceived -= OnStreamMessage;
        _sessions.SessionClosed -= OnSessionClosed;
        foreach (MirrorWindow window in _windows.Values.ToList())
        {
            try { window.Close(); }
            catch (Exception error) { VeyroTrace.Error("Closing the mirror window failed", error); }
        }
        _windows.Clear();
    }
}
