using Veyro.Core.Link;

namespace Veyro.Core.Media;

/// <summary>The phone's latest media snapshot as received by the PC.</summary>
public sealed record PhoneMediaSnapshot(
    string PeerId,
    PeerMessage.MediaState State,
    /// <summary>Local clock (epoch ms) at receipt, the fallback for position extrapolation.</summary>
    long ReceivedAtMs);

public interface IPhoneMediaService
{
    /// <summary>
    /// The peer's snapshot or controllability changed. Raised on the session thread; UI
    /// subscribers marshal to their dispatcher.
    /// </summary>
    event Action<string>? Changed;

    PhoneMediaSnapshot? GetState(string peerId);

    /// <summary>Connected and advertising <see cref="PeerCapabilities.MediaControl"/>.</summary>
    bool CanControl(string peerId);

    /// <summary>
    /// Sends <c>mediaCmd</c> to the phone. Session actions need an <paramref name="id"/> from the
    /// phone's latest snapshot; <c>volume</c> is clamped to 0..100; <c>mute</c> is sent as 1/0.
    /// </summary>
    Task SendCommandAsync(string peerId, string action, string? id = null, long? value = null, CancellationToken ct = default);
}

/// <summary>
/// UI-free mirror of the phone's media sessions (04-PROTOCOL.md 6.2, ADR-026). Keeps the latest
/// <c>mediaState</c> per peer, cleared when that peer's session closes. Media metadata is user
/// data: nothing here logs it.
/// </summary>
public sealed class PhoneMediaService : IPhoneMediaService, IDisposable
{
    private readonly IPeerSessionRegistry _sessions;
    private readonly TimeProvider _time;
    private readonly object _gate = new();
    private readonly Dictionary<string, PhoneMediaSnapshot> _byPeer = new(StringComparer.OrdinalIgnoreCase);

    public event Action<string>? Changed;

    public PhoneMediaService(IPeerSessionRegistry sessions, TimeProvider? time = null)
    {
        _sessions = sessions;
        _time = time ?? TimeProvider.System;
        _sessions.MediaStateReceived += OnMediaState;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
    }

    public PhoneMediaSnapshot? GetState(string peerId)
    {
        lock (_gate) return _byPeer.GetValueOrDefault(peerId);
    }

    public bool CanControl(string peerId)
        => _sessions.IsConnected(peerId) && (_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.MediaControl) != 0;

    public async Task SendCommandAsync(string peerId, string action, string? id = null, long? value = null, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        ArgumentException.ThrowIfNullOrWhiteSpace(action);
        PeerMessage.MediaCommand command;
        switch (action)
        {
            case PeerMessage.MediaCommand.Volume:
                if (value is null) throw new ArgumentException("Volume needs a value.", nameof(value));
                command = new(action, null, Math.Clamp(value.Value, 0, 100));
                break;
            case PeerMessage.MediaCommand.Mute:
                if (value is null) throw new ArgumentException("Mute needs a value.", nameof(value));
                command = new(action, null, value.Value != 0 ? 1 : 0);
                break;
            case PeerMessage.MediaCommand.Play:
            case PeerMessage.MediaCommand.Pause:
            case PeerMessage.MediaCommand.Toggle:
            case PeerMessage.MediaCommand.Next:
            case PeerMessage.MediaCommand.Prev:
            case PeerMessage.MediaCommand.Seek:
                MediaSessionInfo session = FindSession(peerId, id)
                    ?? throw new ArgumentException("No such media session on the phone.", nameof(id));
                long? sessionValue = null;
                if (action == PeerMessage.MediaCommand.Seek)
                {
                    if (value is null) throw new ArgumentException("Seek needs a position.", nameof(value));
                    sessionValue = Math.Clamp(value.Value, 0, session.DurationMs ?? long.MaxValue);
                }
                command = new(action, session.Id, sessionValue);
                break;
            default:
                throw new ArgumentException("Unknown media action.", nameof(action));
        }

        if (!_sessions.IsConnected(peerId))
            throw new InvalidOperationException("The phone is not connected.");
        if ((_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.MediaControl) == 0)
            throw new InvalidOperationException("The phone does not support media control.");
        await _sessions.SendAsync(peerId, command, ct).ConfigureAwait(false);
    }

    public void Dispose()
    {
        _sessions.MediaStateReceived -= OnMediaState;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
    }

    private MediaSessionInfo? FindSession(string peerId, string? id)
    {
        if (string.IsNullOrEmpty(id)) return null;
        lock (_gate)
        {
            return _byPeer.TryGetValue(peerId, out var snapshot)
                ? snapshot.State.Sessions.FirstOrDefault(session => session.Id == id)
                : null;
        }
    }

    private void OnMediaState(string peerId, PeerMessage.MediaState state)
    {
        var snapshot = new PhoneMediaSnapshot(peerId, MediaSnapshots.Sanitize(state), _time.GetUtcNow().ToUnixTimeMilliseconds());
        lock (_gate) _byPeer[peerId] = snapshot;
        Changed?.Invoke(peerId);
    }

    private void OnSessionClosed(string peerId)
    {
        lock (_gate) _byPeer.Remove(peerId);
        Changed?.Invoke(peerId);
    }

    private void OnCapabilitiesChanged(string peerId) => Changed?.Invoke(peerId);
}
