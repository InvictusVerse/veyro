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

    /// <summary>The phone's latest usable caps, or null when it has sent none on this connection.</summary>
    public PeerMessage.StreamCaps? CapsOf(string peerId)
    {
        lock (_gate) return _caps.GetValueOrDefault(peerId);
    }

    /// <summary>Opens (or brings forward) the mirror window for <paramref name="peerId"/> and starts mirroring. UI thread.</summary>
    public void Open(string peerId)
    {
        if (_windows.TryGetValue(peerId, out MirrorWindow? existing))
        {
            existing.Activate();
            if (!existing.Controller.Status.IsLive) _ = existing.Controller.StartAsync();
            return;
        }
        if (_trust.GetPeer(peerId) is not { } peer) return;
        try
        {
            var window = new MirrorWindow(peerId, peer.ShownName, _pcId, _sessions, _settings, () => CapsOf(peerId), _theme);
            window.Closed += (_, _) => _windows.Remove(peerId);
            _windows[peerId] = window;
            window.Activate();
            _ = window.Controller.StartAsync();
            VeyroTrace.Info("Mirror window opened");
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Opening the mirror window failed", error);
            _windows.Remove(peerId);
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
                Post(() =>
                {
                    if (_windows.TryGetValue(peerId, out MirrorWindow? window)) window.Controller.OnMessage(message);
                });
                break;
        }
    }

    private void OnSessionClosed(string peerId)
    {
        lock (_gate) _caps.Remove(peerId);
        Post(() =>
        {
            if (_windows.TryGetValue(peerId, out MirrorWindow? window)) window.Controller.OnSessionClosed();
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
