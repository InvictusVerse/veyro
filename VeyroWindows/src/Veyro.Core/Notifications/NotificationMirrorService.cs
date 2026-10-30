using Veyro.Core.Diagnostics;
using Veyro.Core.Link;

namespace Veyro.Core.Notifications;

/// <summary>A phone notification as currently mirrored on the PC (ADR-025).</summary>
public sealed record MirroredNotification(
    string PeerId,
    string Key,
    string? Package,
    string AppName,
    string Title,
    string Body,
    DateTimeOffset Timestamp,
    IReadOnlyList<NotificationActionInfo> Actions,
    bool CanReply,
    string? IconPngBase64,
    string? Group,
    // ADR-063: the one-time code in the title or body, for "Copy code". User data: never logged.
    string? Code = null,
    // ADR-063: when it left the phone; set only on history entries.
    DateTimeOffset? RemovedAt = null);

public enum NotificationChangeKind
{
    /// <summary>Newly posted on the phone: worth alerting the user.</summary>
    Posted,
    /// <summary>Already active when the session started, or a silent refresh (also a post from a "Feed only" app).</summary>
    Updated,
    Removed,
    /// <summary>The peer's session closed; every notification of that peer is gone.</summary>
    Cleared,
    /// <summary>Only the peer's history changed: an entry expired or history was cleared (ADR-063).</summary>
    History
}

public sealed record NotificationChange(
    string PeerId,
    NotificationChangeKind Kind,
    string? Key,
    MirroredNotification? Notification);

public interface INotificationMirrorService
{
    /// <summary>Raised on the session thread; UI subscribers marshal to their dispatcher.</summary>
    event Action<NotificationChange>? Changed;

    /// <summary>Newest first.</summary>
    IReadOnlyList<MirroredNotification> GetNotifications(string peerId);

    /// <summary>
    /// Removed on the phone (or dismissed here) in the last 24 hours, most recently removed first
    /// (ADR-063). In memory only.
    /// </summary>
    IReadOnlyList<MirroredNotification> GetHistory(string peerId);

    void ClearHistory(string peerId);

    Task InvokeActionAsync(string peerId, string key, int index, CancellationToken ct = default);
    Task ReplyAsync(string peerId, string key, string text, CancellationToken ct = default);
    Task DismissAsync(string peerId, string key, CancellationToken ct = default);
}

/// <summary>
/// UI-free notification mirror state (04-PROTOCOL.md 6.1). Keeps the current notification list
/// per peer from <c>notif</c> messages and sends PC-to-phone requests over that peer's session.
/// ADR-063: applies the per-app rules before anything is shown (Hide drops, Feed only turns a
/// post into a silent update so no toast is raised), finds one-time codes, and keeps what the
/// phone removed for 24 hours in an in-memory history. A key that is live again leaves history.
/// Notification contents are user data: nothing here logs them.
/// </summary>
public sealed class NotificationMirrorService : INotificationMirrorService, IDisposable
{
    public const int MaxReplyLength = 4096;
    public const int MaxKeyLength = 1024;
    public const int MaxNotificationsPerPeer = 500;
    /// <summary>16 KiB of PNG (the protocol limit) is at most 21848 base64 characters.</summary>
    public const int MaxIconBase64Length = 21848;
    public const int MaxHistoryPerPeer = 300;
    public static readonly TimeSpan HistoryRetention = TimeSpan.FromHours(24);
    private const int MaxBodyMessageLines = 3;

    private readonly IPeerSessionRegistry _sessions;
    private readonly INotificationAppRules? _rules;
    private readonly TimeProvider _time;
    private readonly object _gate = new();
    private readonly Dictionary<string, List<MirroredNotification>> _byPeer = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, List<MirroredNotification>> _history = new(StringComparer.OrdinalIgnoreCase);
    private ITimer? _pruneTimer;
    private bool _disposed;

    public event Action<NotificationChange>? Changed;

    public NotificationMirrorService(IPeerSessionRegistry sessions, INotificationAppRules? rules = null, TimeProvider? time = null)
    {
        _sessions = sessions;
        _rules = rules;
        _time = time ?? TimeProvider.System;
        _sessions.NotificationReceived += OnNotificationReceived;
        _sessions.SessionClosed += OnSessionClosed;
        if (_rules is not null) _rules.Changed += OnRulesChanged;
    }

    public IReadOnlyList<MirroredNotification> GetNotifications(string peerId)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var list) ? list.ToList() : [];
    }

    public IReadOnlyList<MirroredNotification> GetHistory(string peerId)
    {
        DateTimeOffset cutoff = _time.GetUtcNow() - HistoryRetention;
        lock (_gate)
            return _history.TryGetValue(peerId, out var list) ? list.Where(item => item.RemovedAt > cutoff).ToList() : [];
    }

    public void ClearHistory(string peerId)
    {
        bool hadAny;
        lock (_gate) hadAny = _history.Remove(peerId, out var list) && list.Count > 0;
        if (hadAny) Changed?.Invoke(new NotificationChange(peerId, NotificationChangeKind.History, null, null));
    }

    public Task InvokeActionAsync(string peerId, string key, int index, CancellationToken ct = default)
    {
        ValidateKey(key);
        ArgumentOutOfRangeException.ThrowIfNegative(index);
        return SendAsync(peerId, new PeerMessage.NotificationActionRequest(key, index), ct);
    }

    public Task ReplyAsync(string peerId, string key, string text, CancellationToken ct = default)
    {
        ValidateKey(key);
        ArgumentNullException.ThrowIfNull(text);
        string trimmed = text.Trim();
        if (trimmed.Length == 0) throw new ArgumentException("Reply text is empty.", nameof(text));
        if (trimmed.Length > MaxReplyLength)
            throw new ArgumentException($"Reply text exceeds {MaxReplyLength} characters.", nameof(text));
        return SendAsync(peerId, new PeerMessage.NotificationReplyRequest(key, trimmed), ct);
    }

    public Task DismissAsync(string peerId, string key, CancellationToken ct = default)
    {
        ValidateKey(key);
        return SendAsync(peerId, new PeerMessage.NotificationDismissRequest(key), ct);
    }

    public void Dispose()
    {
        _sessions.NotificationReceived -= OnNotificationReceived;
        _sessions.SessionClosed -= OnSessionClosed;
        if (_rules is not null) _rules.Changed -= OnRulesChanged;
        lock (_gate)
        {
            _disposed = true;
            _pruneTimer?.Dispose();
            _pruneTimer = null;
        }
    }

    private async Task SendAsync(string peerId, PeerMessage request, CancellationToken ct)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(peerId);
        if (!_sessions.IsConnected(peerId))
            throw new InvalidOperationException("The phone is not connected.");
        // Companion messages are only sent to a peer that advertised the capability (ADR-025).
        if ((_sessions.GetPeerCapabilities(peerId) & PeerCapabilities.NotificationMirror) == 0)
            throw new InvalidOperationException("The phone does not support notification mirroring.");
        await _sessions.SendAsync(peerId, request, ct).ConfigureAwait(false);
        // The type only: keys and reply text are user data.
        VeyroTrace.Info($"Sent {request.GetType().Name} to the phone");
    }

    private static void ValidateKey(string key)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        if (key.Length > MaxKeyLength) throw new ArgumentException("Notification key is too long.", nameof(key));
    }

    private void OnNotificationReceived(string peerId, PeerMessage.Notification message)
    {
        // Untrusted input: ignore anything that does not fit the contract.
        if (string.IsNullOrWhiteSpace(message.Key) || message.Key.Length > MaxKeyLength) return;

        NotificationChange? change = message.Kind switch
        {
            PeerMessage.Notification.KindPosted => Upsert(peerId, message, NotificationChangeKind.Posted),
            PeerMessage.Notification.KindActive => Upsert(peerId, message, NotificationChangeKind.Updated),
            PeerMessage.Notification.KindRemoved => Remove(peerId, message.Key),
            _ => null
        };
        if (change is not null) Changed?.Invoke(change);
    }

    /// <summary>History stays: it is what the user may still want to read while the phone is away.</summary>
    private void OnSessionClosed(string peerId)
    {
        bool hadAny;
        lock (_gate) hadAny = _byPeer.Remove(peerId, out var list) && list.Count > 0;
        if (hadAny) Changed?.Invoke(new NotificationChange(peerId, NotificationChangeKind.Cleared, null, null));
    }

    private NotificationChange? Upsert(string peerId, PeerMessage.Notification message, NotificationChangeKind kind)
    {
        MirroredNotification item = ToModel(peerId, message);
        _rules?.NoteSeen(item.Package, message.App);
        NotificationAppMode mode = _rules?.ModeFor(item.Package) ?? NotificationAppMode.Show;
        lock (_gate)
        {
            // Live again on the phone: no longer history.
            bool historyChanged = _history.TryGetValue(peerId, out var history) && history.RemoveAll(old => old.Key == item.Key) > 0;
            if (!_byPeer.TryGetValue(peerId, out var list))
            {
                list = [];
                _byPeer[peerId] = list;
            }
            if (mode == NotificationAppMode.Hide)
            {
                // Hidden apps are not kept at all; a copy shown before the rule goes too.
                if (list.RemoveAll(existing => existing.Key == item.Key) > 0)
                    return new NotificationChange(peerId, NotificationChangeKind.Removed, item.Key, null);
                return historyChanged ? new NotificationChange(peerId, NotificationChangeKind.History, null, null) : null;
            }
            list.RemoveAll(existing => existing.Key == item.Key);
            int position = list.FindIndex(existing => existing.Timestamp <= item.Timestamp);
            list.Insert(position < 0 ? list.Count : position, item);
            if (list.Count > MaxNotificationsPerPeer) list.RemoveRange(MaxNotificationsPerPeer, list.Count - MaxNotificationsPerPeer);
        }
        // Feed only: a fresh post arrives as a silent update, so no toast is raised for it.
        if (kind == NotificationChangeKind.Posted && mode == NotificationAppMode.FeedOnly) kind = NotificationChangeKind.Updated;
        return new NotificationChange(peerId, kind, item.Key, item);
    }

    private NotificationChange? Remove(string peerId, string key)
    {
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var list)) return null;
            int index = list.FindIndex(existing => existing.Key == key);
            if (index < 0) return null;
            MirroredNotification removed = list[index];
            list.RemoveAll(existing => existing.Key == key);
            AddToHistory(peerId, removed with { RemovedAt = _time.GetUtcNow() });
            SchedulePrune();
        }
        return new NotificationChange(peerId, NotificationChangeKind.Removed, key, null);
    }

    /// <summary>Under the lock. Newest removal first, one entry per key, capped.</summary>
    private void AddToHistory(string peerId, MirroredNotification item)
    {
        if (!_history.TryGetValue(peerId, out var list))
        {
            list = [];
            _history[peerId] = list;
        }
        list.RemoveAll(old => old.Key == item.Key);
        list.Insert(0, item);
        if (list.Count > MaxHistoryPerPeer) list.RemoveRange(MaxHistoryPerPeer, list.Count - MaxHistoryPerPeer);
    }

    /// <summary>Under the lock. One timer at a time, due when the oldest entry turns 24 hours old.</summary>
    private void SchedulePrune()
    {
        if (_disposed || _pruneTimer is not null) return;
        DateTimeOffset? oldest = null;
        foreach (List<MirroredNotification> list in _history.Values)
        {
            foreach (MirroredNotification item in list)
            {
                if (item.RemovedAt is { } at && (oldest is null || at < oldest)) oldest = at;
            }
        }
        if (oldest is null) return;
        TimeSpan due = oldest.Value + HistoryRetention - _time.GetUtcNow();
        _pruneTimer = _time.CreateTimer(_ => Prune(), null, due > TimeSpan.Zero ? due : TimeSpan.Zero, Timeout.InfiniteTimeSpan);
    }

    private void Prune()
    {
        var expired = new List<string>();
        lock (_gate)
        {
            _pruneTimer?.Dispose();
            _pruneTimer = null;
            if (_disposed) return;
            DateTimeOffset cutoff = _time.GetUtcNow() - HistoryRetention;
            foreach ((string peerId, List<MirroredNotification> list) in _history)
            {
                if (list.RemoveAll(item => item.RemovedAt is not { } at || at <= cutoff) > 0) expired.Add(peerId);
            }
            foreach (string peerId in _history.Where(entry => entry.Value.Count == 0).Select(entry => entry.Key).ToList())
                _history.Remove(peerId);
            SchedulePrune();
        }
        foreach (string peerId in expired) Changed?.Invoke(new NotificationChange(peerId, NotificationChangeKind.History, null, null));
    }

    /// <summary>An app turned to Hide leaves the feed and history at once, and its toasts go.</summary>
    private void OnRulesChanged()
    {
        if (_rules is null) return;
        var removed = new List<(string PeerId, string Key)>();
        var historyPeers = new List<string>();
        lock (_gate)
        {
            foreach ((string peerId, List<MirroredNotification> list) in _byPeer)
            {
                foreach (MirroredNotification item in list.Where(IsHidden).ToList())
                {
                    list.Remove(item);
                    removed.Add((peerId, item.Key));
                }
            }
            foreach ((string peerId, List<MirroredNotification> list) in _history)
            {
                if (list.RemoveAll(IsHidden) > 0) historyPeers.Add(peerId);
            }
        }
        foreach ((string peerId, string key) in removed) Changed?.Invoke(new NotificationChange(peerId, NotificationChangeKind.Removed, key, null));
        foreach (string peerId in historyPeers) Changed?.Invoke(new NotificationChange(peerId, NotificationChangeKind.History, null, null));
    }

    private bool IsHidden(MirroredNotification item) => _rules?.ModeFor(item.Package) == NotificationAppMode.Hide;

    private MirroredNotification ToModel(string peerId, PeerMessage.Notification message)
    {
        DateTimeOffset timestamp = _time.GetUtcNow();
        if (message.Ts is long ts && ts > 0)
        {
            try { timestamp = DateTimeOffset.FromUnixTimeMilliseconds(ts); }
            catch (ArgumentOutOfRangeException) { }
        }

        string body = message.Text ?? string.Empty;
        if (message.Messages is { Count: > 0 } lines)
        {
            body = string.Join("\n", lines
                .Where(line => line is not null && !string.IsNullOrEmpty(line.Text))
                .TakeLast(MaxBodyMessageLines)
                .Select(line => string.IsNullOrWhiteSpace(line.Sender) ? line.Text : $"{line.Sender}: {line.Text}"));
        }

        string? icon = message.IconPng is { Length: > 0 and <= MaxIconBase64Length } png ? png : null;
        string title = message.Title ?? string.Empty;
        return new MirroredNotification(
            peerId,
            message.Key,
            message.Pkg,
            string.IsNullOrWhiteSpace(message.App) ? message.Pkg ?? "Phone" : message.App,
            title,
            body,
            timestamp,
            message.Actions?.Where(action => action is not null && action.Index >= 0 && !string.IsNullOrWhiteSpace(action.Label)).ToList() ?? [],
            message.CanReply == true,
            icon,
            message.Group,
            OneTimeCodeDetector.Find(title, body));
    }
}
