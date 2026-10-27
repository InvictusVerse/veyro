using Veyro.Core.Diagnostics;
using Veyro.Core.Link;
using Veyro.Core.Messaging;

namespace Veyro.Core.Calls;

/// <summary>A call on the phone as last reported by <c>callState</c>.</summary>
public sealed record PhoneCall(
    string PeerId,
    string CallId,
    /// <summary><c>ringing</c> | <c>active</c> | <c>ended</c></summary>
    string State,
    string? Direction,
    string? Number,
    string? Name)
{
    public bool IsRinging => State == CallService.StateRinging;
    public bool IsIncomingRinging => IsRinging && Direction != CallService.DirectionOut;
}

public enum CallChangeKind
{
    /// <summary>Connection or Calls capability changed.</summary>
    Availability,
    /// <summary><see cref="CallChange.Call"/> is the reported call, including an ended one.</summary>
    State,
    Log,
    /// <summary>The session closed. <see cref="CallChange.Call"/> is the call that was current, if any.</summary>
    Cleared
}

public sealed record CallChange(string PeerId, CallChangeKind Kind, PhoneCall? Call = null);

public interface ICallService
{
    /// <summary>Raised on the session or timer thread; UI subscribers marshal to their dispatcher.</summary>
    event Action<CallChange>? Changed;

    /// <summary>Connected and advertising <see cref="PeerCapabilities.Calls"/>.</summary>
    bool IsAvailable(string peerId);

    /// <summary>The ringing or active call, or null.</summary>
    PhoneCall? GetCurrentCall(string peerId);

    /// <summary>Latest call log of this session, newest first; empty before the first request.</summary>
    IReadOnlyList<CallLogEntry> GetCallLog(string peerId);

    /// <summary>Sends <c>callLogReq</c> and waits up to 15 s for <c>callLog</c>.</summary>
    Task<IReadOnlyList<CallLogEntry>> RequestCallLogAsync(string peerId, int limit = 100, CancellationToken ct = default);

    /// <summary>
    /// decline | silence | answer | end apply to the current call (its callId is attached);
    /// dial needs a number valid under the SMS address rules.
    /// </summary>
    Task SendActionAsync(string peerId, string action, string? number = null, CancellationToken ct = default);
}

/// <summary>
/// UI-free call state per peer (04-PROTOCOL.md 6.3, ADR-027). In memory only, cleared when the
/// session closes. Numbers and names are user data: only ids and counts may be logged.
/// </summary>
public sealed class CallService : ICallService, IDisposable
{
    public const string StateRinging = "ringing";
    public const string StateActive = "active";
    public const string StateEnded = "ended";
    public const string DirectionIn = "in";
    public const string DirectionOut = "out";
    public const string ActionDecline = "decline";
    public const string ActionSilence = "silence";
    public const string ActionAnswer = "answer";
    public const string ActionEnd = "end";
    public const string ActionDial = "dial";
    public const int MaxCallIdLength = 128;
    public static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(15);

    private readonly IPeerSessionRegistry _sessions;
    private readonly TimeProvider _time;
    private readonly object _gate = new();
    private readonly Dictionary<string, PhoneCall> _current = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, IReadOnlyList<CallLogEntry>> _log = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, ReplyWaiter<IReadOnlyList<CallLogEntry>>> _logWaiters = new(StringComparer.OrdinalIgnoreCase);

    public event Action<CallChange>? Changed;

    public CallService(IPeerSessionRegistry sessions, TimeProvider? time = null)
    {
        _sessions = sessions;
        _time = time ?? TimeProvider.System;
        _sessions.CallStateReceived += OnCallState;
        _sessions.CallLogReceived += OnCallLog;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
    }

    public bool IsAvailable(string peerId)
        => _sessions.IsConnected(peerId) && (_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.Calls) != 0;

    public PhoneCall? GetCurrentCall(string peerId)
    {
        lock (_gate) return _current.GetValueOrDefault(peerId);
    }

    public IReadOnlyList<CallLogEntry> GetCallLog(string peerId)
    {
        lock (_gate) return _log.GetValueOrDefault(peerId) ?? [];
    }

    public async Task<IReadOnlyList<CallLogEntry>> RequestCallLogAsync(string peerId, int limit = 100, CancellationToken ct = default)
    {
        EnsureAvailable(peerId);
        ReplyWaiter<IReadOnlyList<CallLogEntry>> waiter;
        bool send = false;
        lock (_gate)
        {
            if (_logWaiters.TryGetValue(peerId, out var existing)) waiter = existing;
            else
            {
                waiter = new ReplyWaiter<IReadOnlyList<CallLogEntry>>();
                _logWaiters[peerId] = waiter;
                waiter.StartTimeout(_time, RequestTimeout, () => RemoveWaiter(peerId, waiter,
                    new TimeoutException("The phone did not answer the call log request.")));
                send = true;
            }
        }
        if (send)
        {
            try
            {
                await _sessions.SendAsync(peerId, new PeerMessage.CallLogRequest(Math.Clamp(limit, 1, 500)), ct).ConfigureAwait(false);
            }
            catch (Exception error)
            {
                RemoveWaiter(peerId, waiter, error);
                throw;
            }
        }
        return await waiter.Task.WaitAsync(ct).ConfigureAwait(false);
    }

    public async Task SendActionAsync(string peerId, string action, string? number = null, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        PeerMessage.CallAction message;
        switch (action)
        {
            case ActionDial:
                string normalized = PhoneAddress.Normalize(number)
                    ?? throw new ArgumentException("Not a valid phone number.", nameof(number));
                message = new PeerMessage.CallAction(ActionDial, null, normalized);
                break;
            case ActionDecline:
            case ActionSilence:
            case ActionAnswer:
            case ActionEnd:
                PhoneCall call = GetCurrentCall(peerId)
                    ?? throw new InvalidOperationException("There is no call on the phone.");
                message = new PeerMessage.CallAction(action, call.CallId);
                break;
            default:
                throw new ArgumentException("Unknown call action.", nameof(action));
        }
        EnsureAvailable(peerId);
        await _sessions.SendAsync(peerId, message, ct).ConfigureAwait(false);
        VeyroTrace.Info($"Call action sent: {action}");
    }

    public void Dispose()
    {
        _sessions.CallStateReceived -= OnCallState;
        _sessions.CallLogReceived -= OnCallLog;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
    }

    private void OnCallState(string peerId, PeerMessage.CallState state)
    {
        // Untrusted input: an unknown state or a missing/oversized id is ignored.
        if (string.IsNullOrEmpty(state.CallId) || state.CallId.Length > MaxCallIdLength) return;
        if (state.State is not (StateRinging or StateActive or StateEnded)) return;
        string? direction = state.Direction is DirectionIn or DirectionOut ? state.Direction : null;
        var call = new PhoneCall(peerId, state.CallId, state.State, direction,
            string.IsNullOrWhiteSpace(state.Number) ? null : state.Number,
            string.IsNullOrWhiteSpace(state.Name) ? null : state.Name);
        lock (_gate)
        {
            if (call.State == StateEnded)
            {
                if (_current.TryGetValue(peerId, out var current) && current.CallId == call.CallId) _current.Remove(peerId);
            }
            else
            {
                // Keep what an earlier frame of the same call told us if this one omits it.
                if (_current.TryGetValue(peerId, out var current) && current.CallId == call.CallId)
                {
                    call = call with
                    {
                        Direction = call.Direction ?? current.Direction,
                        Number = call.Number ?? current.Number,
                        Name = call.Name ?? current.Name
                    };
                }
                _current[peerId] = call;
            }
        }
        VeyroTrace.Info($"Call state: {call.State}");
        Raise(new CallChange(peerId, CallChangeKind.State, call));
    }

    private void OnCallLog(string peerId, PeerMessage.CallLog log)
    {
        var entries = (log.Entries ?? [])
            .Where(entry => entry is not null)
            .Select(entry => entry with
            {
                Number = entry.Number ?? string.Empty,
                Type = entry.Type is "in" or "out" or "missed" or "rejected" or "blocked" ? entry.Type : "in",
                DurationSec = Math.Max(0, entry.DurationSec)
            })
            .OrderByDescending(entry => entry.Ts)
            .ToList();
        ReplyWaiter<IReadOnlyList<CallLogEntry>>? waiter;
        lock (_gate)
        {
            _log[peerId] = entries;
            _logWaiters.Remove(peerId, out waiter);
        }
        VeyroTrace.Info($"Call log received: count={entries.Count}");
        waiter?.Complete(entries);
        Raise(new CallChange(peerId, CallChangeKind.Log));
    }

    private void OnSessionClosed(string peerId)
    {
        PhoneCall? current;
        ReplyWaiter<IReadOnlyList<CallLogEntry>>? waiter;
        lock (_gate)
        {
            _current.Remove(peerId, out current);
            _log.Remove(peerId);
            _logWaiters.Remove(peerId, out waiter);
        }
        waiter?.Fail(new InvalidOperationException("The phone disconnected."));
        Raise(new CallChange(peerId, CallChangeKind.Cleared, current));
    }

    private void OnCapabilitiesChanged(string peerId) => Raise(new CallChange(peerId, CallChangeKind.Availability));

    private void RemoveWaiter(string peerId, ReplyWaiter<IReadOnlyList<CallLogEntry>> waiter, Exception error)
    {
        lock (_gate)
        {
            if (_logWaiters.TryGetValue(peerId, out var current) && ReferenceEquals(current, waiter)) _logWaiters.Remove(peerId);
        }
        waiter.Fail(error);
    }

    private void EnsureAvailable(string peerId)
    {
        if (!_sessions.IsConnected(peerId)) throw new InvalidOperationException("The phone is not connected.");
        if ((_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.Calls) == 0)
            throw new InvalidOperationException("The phone does not support calls.");
    }

    private void Raise(CallChange change)
    {
        if (Changed is not { } handlers) return;
        foreach (Action<CallChange> handler in handlers.GetInvocationList().Cast<Action<CallChange>>())
        {
            try { handler(change); }
            catch (Exception error) { VeyroTrace.Error("Call change handler failed", error); }
        }
    }
}
