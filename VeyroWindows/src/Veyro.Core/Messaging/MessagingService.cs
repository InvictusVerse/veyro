using Veyro.Core.Diagnostics;
using Veyro.Core.Link;

namespace Veyro.Core.Messaging;

public enum SmsDeliveryState
{
    /// <summary>Incoming message.</summary>
    Received,
    Sent,
    /// <summary>Sent from the PC, waiting for <c>smsSendResult</c>.</summary>
    Pending,
    Failed
}

/// <summary>One message of a conversation as shown on the PC.</summary>
public sealed record ConversationMessage(
    /// <summary>Stable UI key: <c>c:clientId</c> for messages sent from this PC, else <c>m:id</c>.</summary>
    string Key,
    /// <summary>Phone message id; null until the phone reported the message.</summary>
    long? Id,
    /// <summary>Set for messages sent from this PC.</summary>
    string? ClientId,
    long ThreadId,
    string Address,
    string Body,
    long Ts,
    bool Outgoing,
    SmsDeliveryState State,
    /// <summary>Failure reason (<c>smsSendResult.error</c> or <c>timeout</c>) when Failed.</summary>
    string? Error = null,
    int? Sim = null);

/// <summary>Loaded part of a thread, oldest first.</summary>
public sealed record Conversation(
    long ThreadId,
    IReadOnlyList<ConversationMessage> Messages,
    /// <summary>The newest page was fetched, so older pages use <c>beforeTs</c>.</summary>
    bool HasFirstPage,
    /// <summary>The phone reported older messages exist.</summary>
    bool More);

public enum MessagingChangeKind
{
    /// <summary>Connection or Messaging capability changed.</summary>
    Availability,
    Sims,
    Threads,
    /// <summary>Messages of <see cref="MessagingChange.ThreadId"/> changed.</summary>
    Conversation,
    /// <summary>A new incoming message (smsNew, not sent by the phone). Toast-worthy.</summary>
    Incoming,
    /// <summary>The session closed; everything for the peer is gone.</summary>
    Cleared
}

public sealed record MessagingChange(
    string PeerId,
    MessagingChangeKind Kind,
    long? ThreadId = null,
    SmsMessage? Message = null,
    /// <summary>A local draft thread that was replaced by <see cref="ThreadId"/>.</summary>
    long? PreviousThreadId = null);

public interface IMessagingService
{
    /// <summary>Raised on the session or timer thread; UI subscribers marshal to their dispatcher.</summary>
    event Action<MessagingChange>? Changed;

    /// <summary>Connected and advertising <see cref="PeerCapabilities.Messaging"/>.</summary>
    bool IsAvailable(string peerId);

    IReadOnlyList<SimInfo> GetSims(string peerId);

    /// <summary>Newest first. Includes local draft threads (negative ids) for new conversations.</summary>
    IReadOnlyList<SmsThread> GetThreads(string peerId);

    /// <summary>True once a thread list arrived in this session.</summary>
    bool HasThreadList(string peerId);

    SmsThread? GetThread(string peerId, long threadId);

    Conversation? GetConversation(string peerId, long threadId);

    /// <summary>Sends <c>smsThreadsReq</c> and waits up to 15 s for <c>smsThreads</c>.</summary>
    Task<IReadOnlyList<SmsThread>> RequestThreadsAsync(string peerId, int limit = 50, CancellationToken ct = default);

    /// <summary>
    /// Loads the newest page of a thread, or the next older page (<c>beforeTs</c> = oldest loaded
    /// ts) once the newest is loaded. Does nothing when the phone said there is no more.
    /// </summary>
    Task<Conversation> LoadOlderAsync(string peerId, long threadId, CancellationToken ct = default);

    /// <summary>
    /// Validates like the phone, then shows an optimistic Pending message and sends
    /// <c>smsSend</c>. The message resolves on <c>smsSendResult</c> or fails after 75 s.
    /// </summary>
    Task<ConversationMessage> SendAsync(string peerId, IReadOnlyList<string> addresses, string body, int? sim = null, CancellationToken ct = default);

    /// <summary>Resends a Failed message from this PC under a new clientId.</summary>
    Task<ConversationMessage> RetryAsync(string peerId, string clientId, CancellationToken ct = default);

    /// <summary>Local only: the phone cannot mark threads read for a non-default SMS app.</summary>
    void MarkSeen(string peerId, long threadId);
}

/// <summary>
/// UI-free SMS state per peer (04-PROTOCOL.md 6.3, ADR-027). Everything lives in memory and is
/// dropped when the peer's session closes; nothing is persisted. Bodies, numbers, names and SIM
/// labels are user data: only counts and ids may be logged.
/// </summary>
public sealed class MessagingService : IMessagingService, IDisposable
{
    public static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan SendTimeout = TimeSpan.FromSeconds(75);
    public const int PageSize = 50;
    public const string ErrorTimeout = "timeout";
    public const string ErrorFailed = "failed";

    private readonly IPeerSessionRegistry _sessions;
    private readonly TimeProvider _time;
    private readonly object _gate = new();
    private readonly Dictionary<string, PeerState> _byPeer = new(StringComparer.OrdinalIgnoreCase);

    public event Action<MessagingChange>? Changed;

    public MessagingService(IPeerSessionRegistry sessions, TimeProvider? time = null)
    {
        _sessions = sessions;
        _time = time ?? TimeProvider.System;
        _sessions.SimListReceived += OnSimList;
        _sessions.SmsThreadsReceived += OnSmsThreads;
        _sessions.SmsMessagesReceived += OnSmsMessages;
        _sessions.SmsNewReceived += OnSmsNew;
        _sessions.SmsSendResultReceived += OnSmsSendResult;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
    }

    public bool IsAvailable(string peerId)
        => _sessions.IsConnected(peerId) && (_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.Messaging) != 0;

    public IReadOnlyList<SimInfo> GetSims(string peerId)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) ? state.Sims.ToList() : [];
    }

    public IReadOnlyList<SmsThread> GetThreads(string peerId)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) ? state.Threads.ToList() : [];
    }

    public bool HasThreadList(string peerId)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) && state.HasThreadList;
    }

    public SmsThread? GetThread(string peerId, long threadId)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) ? state.FindThread(threadId) : null;
    }

    public Conversation? GetConversation(string peerId, long threadId)
    {
        lock (_gate)
        {
            return _byPeer.TryGetValue(peerId, out var state) && state.Conversations.TryGetValue(threadId, out var conversation)
                ? conversation.Snapshot(threadId)
                : null;
        }
    }

    public async Task<IReadOnlyList<SmsThread>> RequestThreadsAsync(string peerId, int limit = 50, CancellationToken ct = default)
    {
        EnsureAvailable(peerId);
        ReplyWaiter<IReadOnlyList<SmsThread>> waiter;
        bool send = false;
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            if (state.ThreadsWaiter is { } existing) waiter = existing;
            else
            {
                waiter = new ReplyWaiter<IReadOnlyList<SmsThread>>();
                state.ThreadsWaiter = waiter;
                waiter.StartTimeout(_time, RequestTimeout, () => TimeOutThreads(peerId, waiter));
                send = true;
            }
        }
        if (send)
        {
            try
            {
                await _sessions.SendAsync(peerId, new PeerMessage.SmsThreadsRequest(Math.Clamp(limit, 1, 200)), ct).ConfigureAwait(false);
            }
            catch (Exception error)
            {
                lock (_gate)
                {
                    if (_byPeer.TryGetValue(peerId, out var state) && ReferenceEquals(state.ThreadsWaiter, waiter)) state.ThreadsWaiter = null;
                }
                waiter.Fail(error);
                throw;
            }
        }
        return await waiter.Task.WaitAsync(ct).ConfigureAwait(false);
    }

    public async Task<Conversation> LoadOlderAsync(string peerId, long threadId, CancellationToken ct = default)
    {
        EnsureAvailable(peerId);
        ReplyWaiter<PeerMessage.SmsMessages> waiter;
        PeerMessage.SmsMessagesRequest? request = null;
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            state.Conversations.TryGetValue(threadId, out var conversation);
            // Drafts have no history on the phone yet; a finished thread has nothing older.
            if (threadId <= 0 || conversation is { HasFirstPage: true, More: false })
                return conversation?.Snapshot(threadId) ?? new Conversation(threadId, [], true, false);
            if (state.PageWaiters.TryGetValue(threadId, out var existing)) waiter = existing;
            else
            {
                waiter = new ReplyWaiter<PeerMessage.SmsMessages>();
                state.PageWaiters[threadId] = waiter;
                waiter.StartTimeout(_time, RequestTimeout, () => TimeOutPage(peerId, threadId, waiter));
                long? beforeTs = conversation is { HasFirstPage: true } ? conversation.OldestPhoneTs() : null;
                request = new PeerMessage.SmsMessagesRequest(threadId, beforeTs, PageSize);
            }
        }
        if (request is not null)
        {
            try { await _sessions.SendAsync(peerId, request, ct).ConfigureAwait(false); }
            catch (Exception error)
            {
                lock (_gate)
                {
                    if (_byPeer.TryGetValue(peerId, out var state) &&
                        state.PageWaiters.TryGetValue(threadId, out var current) && ReferenceEquals(current, waiter))
                        state.PageWaiters.Remove(threadId);
                }
                waiter.Fail(error);
                throw;
            }
        }
        await waiter.Task.WaitAsync(ct).ConfigureAwait(false);
        return GetConversation(peerId, threadId) ?? new Conversation(threadId, [], true, false);
    }

    public async Task<ConversationMessage> SendAsync(
        string peerId, IReadOnlyList<string> addresses, string body, int? sim = null, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        if (!PhoneAddress.TryNormalizeAll(addresses, out IReadOnlyList<string> normalized))
            throw new ArgumentException($"Recipients must be 1 to {PhoneAddress.MaxAddresses} valid phone numbers.", nameof(addresses));
        if (!PhoneAddress.IsValidBody(body))
            throw new ArgumentException($"Message must be 1 to {PhoneAddress.MaxBodyLength} characters.", nameof(body));
        EnsureAvailable(peerId);

        string clientId = Guid.NewGuid().ToString("N");
        long now = _time.GetUtcNow().ToUnixTimeMilliseconds();
        ConversationMessage message;
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            if (sim is not null && state.Sims.Count > 0 && state.Sims.All(s => s.Id != sim))
                throw new ArgumentException("Unknown SIM.", nameof(sim));

            SmsThread thread = state.FindThreadByAddresses(normalized)
                               ?? new SmsThread(state.NextDraftId--, normalized, null, null, now, 0);
            state.PutOnTop(thread with { Snippet = body, Ts = Math.Max(thread.Ts, now) });
            ConversationState conversation = state.ConversationFor(thread.ThreadId, draft: thread.ThreadId < 0);
            message = new ConversationMessage("c:" + clientId, null, clientId, thread.ThreadId, normalized[0], body, now,
                Outgoing: true, SmsDeliveryState.Pending, null, sim);
            conversation.Messages.Add(message);
            state.Sends[clientId] = new SendRequest(normalized, body, sim);
            state.Pending[clientId] = _time.CreateTimer(_ => OnSendTimeout(peerId, clientId), null, SendTimeout, Timeout.InfiniteTimeSpan);
        }
        Raise(new MessagingChange(peerId, MessagingChangeKind.Threads));
        Raise(new MessagingChange(peerId, MessagingChangeKind.Conversation, message.ThreadId));
        VeyroTrace.Info($"SMS send queued: recipients={normalized.Count}");

        try
        {
            await _sessions.SendAsync(peerId, new PeerMessage.SmsSend(clientId, normalized, body, sim), ct).ConfigureAwait(false);
        }
        catch (Exception)
        {
            Resolve(peerId, clientId, ok: false, ErrorFailed);
            throw;
        }
        return message;
    }

    public Task<ConversationMessage> RetryAsync(string peerId, string clientId, CancellationToken ct = default)
    {
        EnsureAvailable(peerId);
        SendRequest request;
        long threadId;
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var state) || !state.Sends.TryGetValue(clientId, out var stored) ||
                state.FindByClientId(clientId) is not { } found || found.Message.State != SmsDeliveryState.Failed)
                throw new InvalidOperationException("No failed message to retry.");
            request = stored;
            found.Conversation.Messages.Remove(found.Message);
            state.Sends.Remove(clientId);
            threadId = found.Message.ThreadId;
        }
        Raise(new MessagingChange(peerId, MessagingChangeKind.Conversation, threadId));
        return SendAsync(peerId, request.Addresses, request.Body, request.Sim, ct);
    }

    public void MarkSeen(string peerId, long threadId)
    {
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var state) || state.FindThread(threadId) is not { Unread: > 0 } thread) return;
            state.Replace(thread with { Unread = 0 });
        }
        Raise(new MessagingChange(peerId, MessagingChangeKind.Threads));
    }

    public void Dispose()
    {
        _sessions.SimListReceived -= OnSimList;
        _sessions.SmsThreadsReceived -= OnSmsThreads;
        _sessions.SmsMessagesReceived -= OnSmsMessages;
        _sessions.SmsNewReceived -= OnSmsNew;
        _sessions.SmsSendResultReceived -= OnSmsSendResult;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
    }

    // ── Inbound (session thread) ──────────────────────────────────────────────────────

    private void OnSimList(string peerId, PeerMessage.SimList message)
    {
        // Untrusted input: SIM ids must be unique; labels are shown, never logged.
        var sims = (message.Sims ?? [])
            .Where(sim => sim is not null)
            .GroupBy(sim => sim.Id).Select(group => group.First())
            .Select(sim => sim with { Label = sim.Label ?? string.Empty })
            .ToList();
        lock (_gate) StateOf(peerId).Sims = sims;
        Raise(new MessagingChange(peerId, MessagingChangeKind.Sims));
    }

    private void OnSmsThreads(string peerId, PeerMessage.SmsThreads message)
    {
        ReplyWaiter<IReadOnlyList<SmsThread>>? waiter;
        IReadOnlyList<SmsThread> threads;
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            var received = (message.Threads ?? [])
                .Select(Sanitize).OfType<SmsThread>()
                .GroupBy(thread => thread.ThreadId).Select(group => group.First());
            // Drafts not yet matched by the phone stay; everything else is the phone's view.
            state.Threads = received.Concat(state.Threads.Where(thread => thread.ThreadId < 0))
                .OrderByDescending(thread => thread.Ts)
                .ToList();
            state.HasThreadList = true;
            threads = state.Threads.ToList();
            waiter = state.ThreadsWaiter;
            state.ThreadsWaiter = null;
        }
        VeyroTrace.Info($"SMS threads received: count={threads.Count}");
        waiter?.Complete(threads);
        Raise(new MessagingChange(peerId, MessagingChangeKind.Threads));
    }

    private void OnSmsMessages(string peerId, PeerMessage.SmsMessages message)
    {
        if (message.ThreadId <= 0) return;
        ReplyWaiter<PeerMessage.SmsMessages>? waiter;
        int count;
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            state.PageWaiters.Remove(message.ThreadId, out waiter);
            ConversationState conversation = state.ConversationFor(message.ThreadId, draft: false);
            var page = (message.Messages ?? []).Where(m => m is not null).ToList();
            foreach (SmsMessage item in page)
            {
                if (!item.Out) state.SeenIncomingIds.Add(item.Id);
                MergePhoneMessage(state, conversation, item with { ThreadId = message.ThreadId });
            }
            // A requested page, or the first unsolicited one, defines whether older pages exist.
            if (waiter is not null || !conversation.HasFirstPage) conversation.More = message.More;
            conversation.HasFirstPage = true;
            count = page.Count;
        }
        VeyroTrace.Info($"SMS page received: thread={message.ThreadId}, count={count}, more={message.More}");
        waiter?.Complete(message);
        Raise(new MessagingChange(peerId, MessagingChangeKind.Conversation, message.ThreadId));
    }

    private void OnSmsNew(string peerId, PeerMessage.SmsNew update)
    {
        if (update.Message is not { ThreadId: > 0 } raw) return;
        SmsMessage message = raw with { Address = raw.Address ?? string.Empty, Body = raw.Body ?? string.Empty };
        long threadId = message.ThreadId;
        long? previousThreadId = null;
        bool incoming;
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            incoming = !message.Out && state.SeenIncomingIds.Add(message.Id);
            if (!message.Out && !incoming && state.FindThread(threadId) is not null)
            {
                // Duplicate of a message already merged: refresh nothing, alert nothing.
                return;
            }

            SmsThread? existing = state.FindThread(threadId);
            SmsThread? reported = update.Thread is { } t && t.ThreadId == threadId ? Sanitize(t) : null;
            SmsThread merged = existing is not null
                ? existing with
                {
                    Addresses = reported?.Addresses is { Count: > 0 } a ? a : existing.Addresses,
                    Names = reported?.Names ?? existing.Names,
                    Snippet = message.Body,
                    Ts = Math.Max(existing.Ts, message.Ts),
                    Unread = existing.Unread + (incoming ? 1 : 0)
                }
                : (reported ?? new SmsThread(threadId, [message.Address])) with
                {
                    Snippet = message.Body,
                    Ts = Math.Max(reported?.Ts ?? 0, message.Ts),
                    Unread = Math.Max(reported?.Unread ?? 0, incoming ? 1 : 0)
                };

            // A message this PC sent to a brand-new recipient: the draft becomes the real thread.
            if (message.Out && state.FindDraftFor(merged, message.Body) is { } draft)
            {
                previousThreadId = draft.ThreadId;
                state.AdoptDraft(draft.ThreadId, threadId);
            }
            state.PutOnTop(merged);

            if (state.Conversations.TryGetValue(threadId, out var conversation))
                MergePhoneMessage(state, conversation, message);
        }
        Raise(new MessagingChange(peerId, MessagingChangeKind.Threads));
        Raise(new MessagingChange(peerId, MessagingChangeKind.Conversation, threadId, null, previousThreadId));
        if (incoming) Raise(new MessagingChange(peerId, MessagingChangeKind.Incoming, threadId, message));
    }

    private void OnSmsSendResult(string peerId, PeerMessage.SmsSendResult result)
    {
        if (string.IsNullOrEmpty(result.ClientId)) return;
        Resolve(peerId, result.ClientId, result.Ok, result.Ok ? null : NormalizeError(result.Error));
    }

    private void OnSendTimeout(string peerId, string clientId)
    {
        bool stillPending;
        lock (_gate) stillPending = _byPeer.TryGetValue(peerId, out var state) && state.Pending.ContainsKey(clientId);
        if (stillPending) Resolve(peerId, clientId, ok: false, ErrorTimeout);
    }

    private void OnSessionClosed(string peerId)
    {
        PeerState? state;
        lock (_gate) _byPeer.Remove(peerId, out state);
        if (state is not null)
        {
            foreach (ITimer timer in state.Pending.Values) timer.Dispose();
            var closed = new InvalidOperationException("The phone disconnected.");
            state.ThreadsWaiter?.Fail(closed);
            foreach (var waiter in state.PageWaiters.Values) waiter.Fail(closed);
        }
        Raise(new MessagingChange(peerId, MessagingChangeKind.Cleared));
    }

    private void OnCapabilitiesChanged(string peerId) => Raise(new MessagingChange(peerId, MessagingChangeKind.Availability));

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    /// <summary>Applies a send outcome. A late result after a timeout still corrects the state.</summary>
    private void Resolve(string peerId, string clientId, bool ok, string? error)
    {
        long threadId;
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var state)) return;
            if (state.Pending.Remove(clientId, out var timer)) timer.Dispose();
            if (state.FindByClientId(clientId) is not { } found) return;
            ConversationMessage updated = found.Message with
            {
                State = ok ? SmsDeliveryState.Sent : SmsDeliveryState.Failed,
                Error = ok ? null : error ?? ErrorFailed
            };
            found.Conversation.Replace(found.Message, updated);
            threadId = updated.ThreadId;
        }
        VeyroTrace.Info($"SMS send resolved: ok={ok}, reason={(ok ? "-" : error ?? ErrorFailed)}");
        Raise(new MessagingChange(peerId, MessagingChangeKind.Conversation, threadId));
    }

    /// <summary>Adds or updates a phone-reported message; an outgoing one adopts a matching local send.</summary>
    private static void MergePhoneMessage(PeerState state, ConversationState conversation, SmsMessage message)
    {
        SmsDeliveryState reported = !message.Out
            ? SmsDeliveryState.Received
            : message.Status switch
            {
                "pending" => SmsDeliveryState.Pending,
                "failed" => SmsDeliveryState.Failed,
                _ => SmsDeliveryState.Sent
            };

        int index = conversation.Messages.FindIndex(m => m.Id == message.Id);
        ConversationMessage? local = index >= 0
            ? conversation.Messages[index]
            : message.Out
                ? conversation.Messages.FirstOrDefault(m => m.Id is null && m.Outgoing && m.Body == message.Body)
                : null;

        if (local?.ClientId is { } clientId)
        {
            SmsDeliveryState adoptedState;
            if (state.Pending.Remove(clientId, out var timer))
            {
                // Still waiting for smsSendResult: a final phone status resolves it.
                if (reported is SmsDeliveryState.Sent or SmsDeliveryState.Failed) timer.Dispose();
                else state.Pending[clientId] = timer;
                adoptedState = reported;
            }
            else
            {
                // Already resolved by smsSendResult or the timeout: a "sent" report still wins.
                adoptedState = reported == SmsDeliveryState.Sent ? reported : local.State;
            }
            ConversationMessage adopted = local with
            {
                Id = message.Id,
                Address = message.Address,
                Ts = message.Ts,
                Sim = message.Sim ?? local.Sim,
                State = adoptedState,
                Error = adoptedState == SmsDeliveryState.Failed ? local.Error ?? ErrorFailed : null
            };
            conversation.Replace(local, adopted);
            return;
        }

        var model = new ConversationMessage("m:" + message.Id, message.Id, null, message.ThreadId,
            message.Address, message.Body, message.Ts, message.Out, reported, null, message.Sim);
        if (index >= 0) conversation.Messages[index] = model;
        else conversation.Insert(model);
    }

    private static SmsThread? Sanitize(SmsThread? thread)
    {
        if (thread is null || thread.ThreadId <= 0) return null;
        var addresses = (thread.Addresses ?? []).Where(a => !string.IsNullOrWhiteSpace(a)).ToList();
        IReadOnlyList<string?>? names = thread.Names is { } n && n.Count == (thread.Addresses?.Count ?? -1)
            ? thread.Addresses!.Select((a, i) => (a, i)).Where(p => !string.IsNullOrWhiteSpace(p.a)).Select(p => n[p.i]).ToList()
            : null;
        return thread with { Addresses = addresses, Names = names, Unread = Math.Max(0, thread.Unread) };
    }

    private static string NormalizeError(string? error)
        => error is "permission" or "disabled" or "invalid" or "radio" or "no-service" or "failed" ? error : ErrorFailed;

    private void EnsureAvailable(string peerId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        if (!_sessions.IsConnected(peerId)) throw new InvalidOperationException("The phone is not connected.");
        if ((_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.Messaging) == 0)
            throw new InvalidOperationException("The phone does not support messages.");
    }

    private PeerState StateOf(string peerId)
    {
        if (!_byPeer.TryGetValue(peerId, out var state))
        {
            state = new PeerState();
            _byPeer[peerId] = state;
        }
        return state;
    }

    private void TimeOutThreads(string peerId, ReplyWaiter<IReadOnlyList<SmsThread>> waiter)
    {
        lock (_gate)
        {
            if (_byPeer.TryGetValue(peerId, out var state) && ReferenceEquals(state.ThreadsWaiter, waiter)) state.ThreadsWaiter = null;
        }
        waiter.Fail(new TimeoutException("The phone did not answer the thread list request."));
    }

    private void TimeOutPage(string peerId, long threadId, ReplyWaiter<PeerMessage.SmsMessages> waiter)
    {
        lock (_gate)
        {
            if (_byPeer.TryGetValue(peerId, out var state) &&
                state.PageWaiters.TryGetValue(threadId, out var current) && ReferenceEquals(current, waiter))
                state.PageWaiters.Remove(threadId);
        }
        waiter.Fail(new TimeoutException("The phone did not answer the message page request."));
    }

    private void Raise(MessagingChange change)
    {
        if (Changed is not { } handlers) return;
        foreach (Action<MessagingChange> handler in handlers.GetInvocationList().Cast<Action<MessagingChange>>())
        {
            try { handler(change); }
            catch (Exception error) { VeyroTrace.Error("Messaging change handler failed", error); }
        }
    }

    private sealed record SendRequest(IReadOnlyList<string> Addresses, string Body, int? Sim);

    private sealed class ConversationState
    {
        public List<ConversationMessage> Messages { get; } = [];
        public bool HasFirstPage { get; set; }
        public bool More { get; set; }

        public Conversation Snapshot(long threadId) => new(threadId, Messages.ToList(), HasFirstPage, More);

        public long? OldestPhoneTs()
            => Messages.Where(m => m.Id is not null).Select(m => (long?)m.Ts).Min();

        public void Insert(ConversationMessage message)
        {
            int position = Messages.FindIndex(m => m.Ts > message.Ts || (m.Ts == message.Ts && (m.Id ?? long.MaxValue) > (message.Id ?? long.MaxValue)));
            Messages.Insert(position < 0 ? Messages.Count : position, message);
        }

        public void Replace(ConversationMessage old, ConversationMessage updated)
        {
            int index = Messages.IndexOf(old);
            if (index < 0) return;
            if (old.Ts == updated.Ts) Messages[index] = updated;
            else
            {
                Messages.RemoveAt(index);
                Insert(updated);
            }
        }
    }

    private sealed class PeerState
    {
        public List<SimInfo> Sims { get; set; } = [];
        public List<SmsThread> Threads { get; set; } = [];
        public bool HasThreadList { get; set; }
        public Dictionary<long, ConversationState> Conversations { get; } = [];
        public HashSet<long> SeenIncomingIds { get; } = [];
        public Dictionary<string, ITimer> Pending { get; } = new(StringComparer.Ordinal);
        public Dictionary<string, SendRequest> Sends { get; } = new(StringComparer.Ordinal);
        public ReplyWaiter<IReadOnlyList<SmsThread>>? ThreadsWaiter { get; set; }
        public Dictionary<long, ReplyWaiter<PeerMessage.SmsMessages>> PageWaiters { get; } = [];
        public long NextDraftId { get; set; } = -1;

        public SmsThread? FindThread(long threadId) => Threads.FirstOrDefault(t => t.ThreadId == threadId);

        public SmsThread? FindThreadByAddresses(IReadOnlyList<string> addresses)
            => Threads.FirstOrDefault(t => t.Addresses.Count == addresses.Count &&
                                           addresses.All(a => t.Addresses.Any(b => PhoneAddress.SameNumber(a, b))));

        /// <summary>A draft whose recipients match the phone thread and that holds an unconfirmed send of this body.</summary>
        public SmsThread? FindDraftFor(SmsThread real, string body)
            => Threads.FirstOrDefault(t => t.ThreadId < 0 &&
                                           t.Addresses.Count == real.Addresses.Count &&
                                           t.Addresses.All(a => real.Addresses.Any(b => PhoneAddress.SameNumber(a, b))) &&
                                           Conversations.TryGetValue(t.ThreadId, out var c) &&
                                           c.Messages.Any(m => m.Id is null && m.Body == body));

        /// <summary>Moves a draft's messages into the phone's thread; older history is then loadable.</summary>
        public void AdoptDraft(long draftId, long threadId)
        {
            Threads.RemoveAll(t => t.ThreadId == draftId);
            if (!Conversations.Remove(draftId, out var draft)) return;
            ConversationState target = ConversationFor(threadId, draft: false);
            foreach (ConversationMessage message in draft.Messages) target.Insert(message with { ThreadId = threadId });
            if (!target.HasFirstPage)
            {
                target.HasFirstPage = true;
                target.More = true;
            }
        }

        public ConversationState ConversationFor(long threadId, bool draft)
        {
            if (!Conversations.TryGetValue(threadId, out var conversation))
            {
                conversation = new ConversationState { HasFirstPage = draft, More = false };
                Conversations[threadId] = conversation;
            }
            return conversation;
        }

        public void PutOnTop(SmsThread thread)
        {
            Threads.RemoveAll(t => t.ThreadId == thread.ThreadId);
            Threads.Insert(0, thread);
        }

        public void Replace(SmsThread thread)
        {
            int index = Threads.FindIndex(t => t.ThreadId == thread.ThreadId);
            if (index >= 0) Threads[index] = thread;
        }

        public (ConversationState Conversation, ConversationMessage Message)? FindByClientId(string clientId)
        {
            foreach (ConversationState conversation in Conversations.Values)
            {
                ConversationMessage? message = conversation.Messages.FirstOrDefault(m => m.ClientId == clientId);
                if (message is not null) return (conversation, message);
            }
            return null;
        }
    }
}
