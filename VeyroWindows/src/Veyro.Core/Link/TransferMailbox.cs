namespace Veyro.Core.Link;

/// <summary>
/// A PC-to-phone transfer running over the peer's live session (04-PROTOCOL.md 6.4, ADR-034).
/// The session's receive loop hands this channel every transfer-scoped message naming its
/// transferId (decision, cancel, transferEnd, itemResult, progressNotice). Disposing it stops
/// the routing; later messages for the id are ignored by the session as before.
/// </summary>
public interface IOutboundTransferChannel : IDisposable
{
    string TransferId { get; }

    /// <summary>Sends on the session this channel is bound to (not a newer one that replaced it).</summary>
    Task SendAsync(PeerMessage message, CancellationToken ct = default);

    /// <summary>
    /// Completes with the phone's decision. A <c>cancel</c> before the decision completes it as a
    /// decline. Throws <see cref="TimeoutException"/> after <paramref name="timeout"/> and
    /// <see cref="IOException"/> if the session closes first.
    /// </summary>
    Task<PeerMessage.TransferDecision> WaitForDecisionAsync(TimeSpan timeout, CancellationToken ct = default);

    /// <summary>
    /// Cancelled when the phone cancels the transfer after deciding, reports
    /// <c>transferEnd{completed:false}</c>, or the session closes.
    /// </summary>
    CancellationToken PeerAborted { get; }
}

/// <summary>
/// Per-session routing table for outbound transfers, keyed by transferId. Thread-safe: the
/// session loop delivers, the sender waits.
/// </summary>
public sealed class TransferMailbox
{
    /// <summary>How long the phone has to accept or decline (the user may be asked on the phone).</summary>
    public static readonly TimeSpan DecisionTimeout = TimeSpan.FromSeconds(60);

    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private bool _closed;

    /// <summary>Starts routing messages for <paramref name="transferId"/> to the returned entry.</summary>
    public Entry Register(string transferId, Func<PeerMessage, CancellationToken, Task> send)
    {
        ArgumentException.ThrowIfNullOrEmpty(transferId);
        var entry = new Entry(this, transferId, send);
        lock (_gate)
        {
            if (_closed) throw new InvalidOperationException("The peer has no live session.");
            if (!_entries.TryAdd(transferId, entry)) throw new InvalidOperationException("Transfer id already in use.");
        }
        return entry;
    }

    /// <summary>
    /// Routes a transfer-scoped message to its waiter. Returns false (the session handles it as
    /// before) for any other message or an id nobody waits for.
    /// </summary>
    public bool TryDeliver(PeerMessage message)
    {
        string? transferId = TransferIdOf(message);
        if (transferId is null) return false;
        Entry? entry;
        lock (_gate) _entries.TryGetValue(transferId, out entry);
        if (entry is null) return false;
        entry.Deliver(message);
        return true;
    }

    /// <summary>The session ended: every waiter fails and no new one can register.</summary>
    public void Close()
    {
        List<Entry> entries;
        lock (_gate)
        {
            _closed = true;
            entries = _entries.Values.ToList();
            _entries.Clear();
        }
        foreach (Entry entry in entries) entry.SessionClosed();
    }

    public static string? TransferIdOf(PeerMessage message) => message switch
    {
        PeerMessage.TransferDecision m => m.TransferId,
        PeerMessage.Cancel m => m.TransferId,
        PeerMessage.TransferEnd m => m.TransferId,
        PeerMessage.ItemResult m => m.TransferId,
        PeerMessage.TransferProgressNotice m => m.TransferId,
        _ => null
    };

    private void Remove(Entry entry)
    {
        lock (_gate)
        {
            if (_entries.TryGetValue(entry.TransferId, out var current) && ReferenceEquals(current, entry))
                _entries.Remove(entry.TransferId);
        }
    }

    public sealed class Entry : IOutboundTransferChannel
    {
        private readonly TransferMailbox _owner;
        private readonly Func<PeerMessage, CancellationToken, Task> _send;
        private readonly TaskCompletionSource<PeerMessage.TransferDecision> _decision =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly CancellationTokenSource _aborted = new();
        private int _disposed;

        internal Entry(TransferMailbox owner, string transferId, Func<PeerMessage, CancellationToken, Task> send)
        {
            _owner = owner;
            _send = send;
            TransferId = transferId;
        }

        public string TransferId { get; }
        public CancellationToken PeerAborted => _aborted.Token;

        /// <summary>The receiver's transferEnd, once it arrived.</summary>
        public PeerMessage.TransferEnd? End { get; private set; }

        public Task SendAsync(PeerMessage message, CancellationToken ct = default) => _send(message, ct);

        public async Task<PeerMessage.TransferDecision> WaitForDecisionAsync(TimeSpan timeout, CancellationToken ct = default)
            => await _decision.Task.WaitAsync(timeout, ct).ConfigureAwait(false);

        internal void Deliver(PeerMessage message)
        {
            switch (message)
            {
                case PeerMessage.TransferDecision decision:
                    _decision.TrySetResult(decision);
                    break;
                case PeerMessage.Cancel cancel:
                    // Before the decision the phone withdrew its answer: a decline. After it, an abort.
                    if (!_decision.TrySetResult(new PeerMessage.TransferDecision(TransferId, false, cancel.Reason))) Abort();
                    break;
                case PeerMessage.TransferEnd end:
                    End = end;
                    if (!end.Completed) Abort();
                    break;
                // itemResult and progressNotice are consumed: VTC1 reports per-item results and
                // progress on the data socket itself.
            }
        }

        internal void SessionClosed()
        {
            _decision.TrySetException(new IOException("The session closed before the phone answered."));
            Abort();
        }

        private void Abort()
        {
            if (Volatile.Read(ref _disposed) != 0) return;
            try { _aborted.Cancel(); }
            catch (ObjectDisposedException) { }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
            _owner.Remove(this);
            _decision.TrySetCanceled();
            _aborted.Dispose();
        }
    }
}
