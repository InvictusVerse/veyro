using Veyro.Core.Diagnostics;
using Veyro.Core.Link;

namespace Veyro.Core.Media;

/// <summary>
/// The PC's own media sessions and master volume, implemented per platform (Windows: GSMTC and
/// Core Audio). Declared here because <see cref="PcMediaPublisher"/> consumes it.
/// </summary>
public interface IPcMediaSource
{
    /// <summary>Raised on any thread when <see cref="Current"/> changed in a way peers must hear.</summary>
    event Action? Changed;

    /// <summary>Latest full snapshot. Cheap: implementations cache it.</summary>
    PeerMessage.MediaState Current { get; }

    /// <summary>
    /// Executes a command already validated by the publisher: session actions name an id from
    /// <see cref="Current"/>, <c>volume</c> is 0..100 and <c>mute</c> is 1/0.
    /// </summary>
    Task ExecuteAsync(PeerMessage.MediaCommand command, CancellationToken ct = default);
}

/// <summary>
/// Publishes the PC's media state to every connected peer that advertised
/// <see cref="PeerCapabilities.MediaControl"/> and executes the <c>mediaCmd</c> those peers send
/// (04-PROTOCOL.md 6.2). A full snapshot goes out when the capability is first seen on a session
/// and on every change, coalesced per peer to at most one per <see cref="MinInterval"/>; the
/// trailing send always carries the newest snapshot. Media metadata is never logged.
/// </summary>
public sealed class PcMediaPublisher : IDisposable
{
    public static readonly TimeSpan MinInterval = TimeSpan.FromMilliseconds(500);

    private readonly IPeerSessionRegistry _sessions;
    private readonly IPcMediaSource _source;
    private readonly TimeProvider _time;
    private readonly object _gate = new();
    private readonly Dictionary<string, PeerState> _peers = new(StringComparer.OrdinalIgnoreCase);
    private bool _disposed;

    public PcMediaPublisher(IPeerSessionRegistry sessions, IPcMediaSource source, TimeProvider? time = null)
    {
        _sessions = sessions;
        _source = source;
        _time = time ?? TimeProvider.System;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.MediaCommandReceived += OnCommand;
        _source.Changed += OnSourceChanged;
    }

    public void Dispose()
    {
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.MediaCommandReceived -= OnCommand;
        _source.Changed -= OnSourceChanged;
        lock (_gate)
        {
            _disposed = true;
            foreach (PeerState state in _peers.Values) state.Pending?.Dispose();
            _peers.Clear();
        }
    }

    private bool IsCapable(string peerId)
        => (_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.MediaControl) != 0;

    private void OnCapabilitiesChanged(string peerId)
    {
        if (!IsCapable(peerId)) return;
        bool known;
        lock (_gate) known = _peers.ContainsKey(peerId);
        // Only the first sighting is "connect"; later capability changes are not media changes.
        if (!known) Schedule(peerId);
    }

    private void OnSourceChanged()
    {
        foreach (string peerId in _sessions.GetConnectedPeers())
        {
            if (IsCapable(peerId)) Schedule(peerId);
        }
    }

    private void OnSessionClosed(string peerId)
    {
        lock (_gate)
        {
            if (_peers.Remove(peerId, out PeerState? state)) state.Pending?.Dispose();
        }
    }

    private void Schedule(string peerId)
    {
        bool sendNow = false;
        lock (_gate)
        {
            if (_disposed) return;
            if (!_peers.TryGetValue(peerId, out PeerState? state))
            {
                state = new PeerState();
                _peers[peerId] = state;
            }
            if (state.Pending is not null) return; // the trailing send will read the newest snapshot
            DateTimeOffset now = _time.GetUtcNow();
            DateTimeOffset due = state.LastSent is { } last ? last + MinInterval : now;
            if (now >= due)
            {
                state.LastSent = now;
                sendNow = true;
            }
            else
            {
                PeerState owner = state;
                state.Pending = _time.CreateTimer(_ => Flush(peerId, owner), null, due - now, Timeout.InfiniteTimeSpan);
            }
        }
        if (sendNow) _ = SendAsync(peerId);
    }

    private void Flush(string peerId, PeerState owner)
    {
        lock (_gate)
        {
            if (_disposed || !_peers.TryGetValue(peerId, out PeerState? state) || !ReferenceEquals(state, owner)) return;
            state.Pending?.Dispose();
            state.Pending = null;
            state.LastSent = _time.GetUtcNow();
        }
        _ = SendAsync(peerId);
    }

    private async Task SendAsync(string peerId)
    {
        try
        {
            if (!_sessions.IsConnected(peerId) || !IsCapable(peerId)) return;
            await _sessions.SendAsync(peerId, _source.Current).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Sending PC media state failed", error);
        }
    }

    private void OnCommand(string peerId, PeerMessage.MediaCommand command)
    {
        PeerMessage.MediaCommand? validated = Validate(command, _source.Current);
        if (validated is null) return;
        _ = ExecuteAsync(validated);
    }

    /// <summary>
    /// Untrusted input. Session actions must name a session of the latest local snapshot;
    /// anything unknown, unaddressed or missing a required value is ignored (null).
    /// </summary>
    public static PeerMessage.MediaCommand? Validate(PeerMessage.MediaCommand command, PeerMessage.MediaState current)
    {
        switch (command.Action)
        {
            case PeerMessage.MediaCommand.Volume:
                return command.Value is { } volume ? new(command.Action, null, Math.Clamp(volume, 0, 100)) : null;
            case PeerMessage.MediaCommand.Mute:
                return command.Value is { } mute ? new(command.Action, null, mute != 0 ? 1 : 0) : null;
            case PeerMessage.MediaCommand.Play:
            case PeerMessage.MediaCommand.Pause:
            case PeerMessage.MediaCommand.Toggle:
            case PeerMessage.MediaCommand.Next:
            case PeerMessage.MediaCommand.Prev:
            case PeerMessage.MediaCommand.Seek:
                if (string.IsNullOrEmpty(command.Id)) return null;
                MediaSessionInfo? session = current.Sessions.FirstOrDefault(candidate => candidate.Id == command.Id);
                if (session is null) return null;
                if (command.Action != PeerMessage.MediaCommand.Seek) return new(command.Action, session.Id);
                if (command.Value is not { } position || !session.CanSeek) return null;
                return new(command.Action, session.Id, Math.Clamp(position, 0, session.DurationMs ?? long.MaxValue));
            default:
                return null;
        }
    }

    private async Task ExecuteAsync(PeerMessage.MediaCommand command)
    {
        try { await _source.ExecuteAsync(command).ConfigureAwait(false); }
        catch (Exception error) { VeyroTrace.Error($"PC media command '{command.Action}' failed", error); }
    }

    private sealed class PeerState
    {
        public DateTimeOffset? LastSent;
        public ITimer? Pending;
    }
}
