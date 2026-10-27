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
    string? Group);

public enum NotificationChangeKind
{
    /// <summary>Newly posted on the phone: worth alerting the user.</summary>
    Posted,
    /// <summary>Already active when the session started, or a silent refresh.</summary>
    Updated,
    Removed,
    /// <summary>The peer's session closed; every notification of that peer is gone.</summary>
    Cleared
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

    Task InvokeActionAsync(string peerId, string key, int index, CancellationToken ct = default);
    Task ReplyAsync(string peerId, string key, string text, CancellationToken ct = default);
    Task DismissAsync(string peerId, string key, CancellationToken ct = default);
}

/// <summary>
/// UI-free notification mirror state (04-PROTOCOL.md 6.1). Keeps the current notification list
/// per peer from <c>notif</c> messages and sends PC-to-phone requests over that peer's session.
/// Notification contents are user data: nothing here logs them.
/// </summary>
public sealed class NotificationMirrorService : INotificationMirrorService, IDisposable
{
    public const int MaxReplyLength = 4096;
    public const int MaxKeyLength = 1024;
    public const int MaxNotificationsPerPeer = 500;
    /// <summary>16 KiB of PNG (the protocol limit) is at most 21848 base64 characters.</summary>
    public const int MaxIconBase64Length = 21848;
    private const int MaxBodyMessageLines = 3;

    private readonly IPeerSessionRegistry _sessions;
    private readonly object _gate = new();
    private readonly Dictionary<string, List<MirroredNotification>> _byPeer = new(StringComparer.OrdinalIgnoreCase);

    public event Action<NotificationChange>? Changed;

    public NotificationMirrorService(IPeerSessionRegistry sessions)
    {
        _sessions = sessions;
        _sessions.NotificationReceived += OnNotificationReceived;
        _sessions.SessionClosed += OnSessionClosed;
    }

    public IReadOnlyList<MirroredNotification> GetNotifications(string peerId)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var list) ? list.ToList() : [];
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

    private void OnSessionClosed(string peerId)
    {
        bool hadAny;
        lock (_gate) hadAny = _byPeer.Remove(peerId, out var list) && list.Count > 0;
        if (hadAny) Changed?.Invoke(new NotificationChange(peerId, NotificationChangeKind.Cleared, null, null));
    }

    private NotificationChange Upsert(string peerId, PeerMessage.Notification message, NotificationChangeKind kind)
    {
        MirroredNotification item = ToModel(peerId, message);
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var list))
            {
                list = [];
                _byPeer[peerId] = list;
            }
            list.RemoveAll(existing => existing.Key == item.Key);
            int position = list.FindIndex(existing => existing.Timestamp <= item.Timestamp);
            list.Insert(position < 0 ? list.Count : position, item);
            if (list.Count > MaxNotificationsPerPeer) list.RemoveRange(MaxNotificationsPerPeer, list.Count - MaxNotificationsPerPeer);
        }
        return new NotificationChange(peerId, kind, item.Key, item);
    }

    private NotificationChange? Remove(string peerId, string key)
    {
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var list) || list.RemoveAll(existing => existing.Key == key) == 0)
                return null;
        }
        return new NotificationChange(peerId, NotificationChangeKind.Removed, key, null);
    }

    private static MirroredNotification ToModel(string peerId, PeerMessage.Notification message)
    {
        DateTimeOffset timestamp = DateTimeOffset.UtcNow;
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
        return new MirroredNotification(
            peerId,
            message.Key,
            message.Pkg,
            string.IsNullOrWhiteSpace(message.App) ? message.Pkg ?? "Phone" : message.App,
            message.Title ?? string.Empty,
            body,
            timestamp,
            message.Actions?.Where(action => action is not null && action.Index >= 0 && !string.IsNullOrWhiteSpace(action.Label)).ToList() ?? [],
            message.CanReply == true,
            icon,
            message.Group);
    }
}
