using Veyro.Core.Data;
using Veyro.Core.Diagnostics;
using Veyro.Core.Link;

namespace Veyro.Core.Devices;

/// <summary>
/// Live device names (ADR-059, 04-PROTOCOL.md 6.7).
///  - Out: this PC's name from Settings goes as <c>deviceInfo</c> to every connected phone that
///    advertises <see cref="PeerCapabilities.DeviceInfo"/>, when its session starts and after a
///    rename (debounced, because the Settings box saves on every keystroke). Each phone is told a
///    name once.
///  - In: a phone's own name, from its hello or helloAck (<see cref="IPeerSessionRegistry.GetPeerName"/>,
///    read when its capabilities arrive) or a deviceInfo, is validated and stored in
///    the trust store whenever it differs; <see cref="PeerNameChanged"/> then lets the UI follow.
///    A phone renamed while away catches up from its next hello. A nickname is not touched.
/// Names are user data: never logged.
/// </summary>
public sealed class DeviceNameService : IDisposable
{
    /// <summary>Quiet time after the last keystroke before a rename goes out.</summary>
    public static readonly TimeSpan RenameDebounce = TimeSpan.FromSeconds(1);

    private readonly IPeerSessionRegistry _sessions;
    private readonly ITrustStore _trust;
    private readonly Func<string> _localName;
    private readonly TimeProvider _time;
    private readonly object _gate = new();
    private readonly Dictionary<string, string> _toldName = new(StringComparer.OrdinalIgnoreCase);
    private readonly SemaphoreSlim _storeLock = new(1, 1);
    private ITimer? _renameTimer;

    /// <summary>A paired phone's stored name changed (argument: device id). Raised on a background thread.</summary>
    public event Action<string>? PeerNameChanged;

    public DeviceNameService(IPeerSessionRegistry sessions, ITrustStore trust, Func<string> localName, TimeProvider? time = null)
    {
        _sessions = sessions;
        _trust = trust;
        _localName = localName;
        _time = time ?? TimeProvider.System;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.DeviceMessageReceived += OnDeviceMessage;
    }

    /// <summary>The phone's hello arrived: store the name in it (catch-up), then tell it ours.</summary>
    private void OnCapabilitiesChanged(string peerId)
    {
        if (_sessions.GetPeerName(peerId) is { } helloName) _ = StoreAsync(peerId, helloName);
        Announce(peerId);
    }

    /// <summary>This PC was renamed in Settings. Every connected phone hears the final name once.</summary>
    public void LocalNameChanged()
    {
        lock (_gate)
        {
            _renameTimer?.Dispose();
            _renameTimer = _time.CreateTimer(_ => AnnounceToAll(), null, RenameDebounce, Timeout.InfiniteTimeSpan);
        }
    }

    private void AnnounceToAll()
    {
        foreach (string peerId in _sessions.GetConnectedPeers()) Announce(peerId);
    }

    private void Announce(string peerId)
    {
        if ((_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.DeviceInfo) == 0) return;
        string? name = DeviceNames.Clean(_localName());
        if (name is null) return;
        lock (_gate)
        {
            if (_toldName.TryGetValue(peerId, out string? told) && told == name) return;
            _toldName[peerId] = name;
        }
        _ = SendAsync(peerId, name);
    }

    private async Task SendAsync(string peerId, string name)
    {
        try
        {
            await _sessions.SendAsync(peerId, new PeerMessage.DeviceInfo(name)).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            lock (_gate)
            {
                if (_toldName.TryGetValue(peerId, out string? told) && told == name) _toldName.Remove(peerId);
            }
            VeyroTrace.Warn($"Device name not sent: {error.GetType().Name}");
        }
    }

    private void OnSessionClosed(string peerId)
    {
        lock (_gate) _toldName.Remove(peerId);
    }

    private void OnDeviceMessage(string peerId, PeerMessage message)
    {
        if (message is PeerMessage.DeviceInfo { Name: { } name }) _ = StoreAsync(peerId, name);
    }

    /// <summary>Stores the phone's own name if it is valid and differs. Untrusted input.</summary>
    public async Task StoreAsync(string peerId, string announced)
    {
        string? name = DeviceNames.Clean(announced);
        if (name is null) return;
        bool changed;
        await _storeLock.WaitAsync().ConfigureAwait(false);
        try
        {
            changed = await _trust.SetAnnouncedNameAsync(peerId, name).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Phone name not stored: {error.GetType().Name}");
            return;
        }
        finally
        {
            _storeLock.Release();
        }
        if (!changed) return;
        try { PeerNameChanged?.Invoke(peerId); }
        catch (Exception error) { VeyroTrace.Error("PeerNameChanged handler failed", error); }
    }

    public void Dispose()
    {
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.DeviceMessageReceived -= OnDeviceMessage;
        lock (_gate) _renameTimer?.Dispose();
        _storeLock.Dispose();
    }
}
