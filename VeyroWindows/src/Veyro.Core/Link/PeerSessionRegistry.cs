using Veyro.Core.Diagnostics;

namespace Veyro.Core.Link;

/// <summary>
/// The live, authenticated sessions held by <see cref="LinkServer"/>, one per paired peer
/// (ADR-025). Lets UI-side services receive companion messages and send PC-to-phone requests
/// without touching sockets.
///
/// Events are raised on the session's receive thread; subscribers marshal to their own context.
/// </summary>
public interface IPeerSessionRegistry
{
    event Action<string>? SessionOpened;
    event Action<string>? SessionClosed;

    /// <summary>
    /// The peer's capability bits changed: its Hello/HelloAck arrived, or a companion message
    /// implied a capability (04-PROTOCOL.md 6.2). Raised after HelloAck has been sent, so a
    /// handler that reacts with a snapshot never overtakes it.
    /// </summary>
    event Action<string>? CapabilitiesChanged;

    event Action<string, PeerMessage.Notification>? NotificationReceived;
    event Action<string, PeerMessage.MediaState>? MediaStateReceived;
    event Action<string, PeerMessage.MediaCommand>? MediaCommandReceived;
    event Action<string, PeerMessage.DeviceStatus>? DeviceStatusReceived;

    // ADR-027 phone-to-PC messages. Bodies, numbers, names and SIM labels are user data.
    event Action<string, PeerMessage.SimList>? SimListReceived;
    event Action<string, PeerMessage.SmsThreads>? SmsThreadsReceived;
    event Action<string, PeerMessage.SmsMessages>? SmsMessagesReceived;
    event Action<string, PeerMessage.SmsNew>? SmsNewReceived;
    event Action<string, PeerMessage.SmsSendResult>? SmsSendResultReceived;
    event Action<string, PeerMessage.CallState>? CallStateReceived;
    event Action<string, PeerMessage.CallLog>? CallLogReceived;

    /// <summary>ADR-034 phone-to-PC photo messages: photos, photoThumb, photoNew, photoRemoved. User data.</summary>
    event Action<string, PeerMessage>? PhotosMessageReceived;

    /// <summary>
    /// ADR-038 phone-to-PC messages: <c>pcLock</c> (only from a peer still in the trust store) and
    /// <c>wallpaper</c>, <c>clip</c>, and ADR-059 <c>deviceInfo</c> (unvalidated). Wallpaper bytes,
    /// clips and names are user data.
    /// </summary>
    event Action<string, PeerMessage>? DeviceMessageReceived;

    bool IsConnected(string peerId);

    /// <summary>Peers with a live session right now.</summary>
    IReadOnlyList<string> GetConnectedPeers();

    /// <summary>Capability bits from the peer's Hello/HelloAck, or 0 when unknown or not connected.</summary>
    int GetPeerCapabilities(string peerId);

    /// <summary>
    /// ADR-059: the name in the peer's latest Hello/HelloAck on its live session, unvalidated, or
    /// null. Set before the capabilities, so a <see cref="CapabilitiesChanged"/> handler sees it.
    /// User data: never log it.
    /// </summary>
    string? GetPeerName(string peerId) => null;

    /// <summary>
    /// ADR-066 phone-to-PC screen mirroring messages: <c>streamCaps</c>, <c>streamState</c>,
    /// <c>streamStop</c> (unvalidated; check with <see cref="StreamRules"/>).
    /// </summary>
    event Action<string, PeerMessage>? StreamMessageReceived { add { } remove { } }

    /// <summary>The live session's key exporter (ADR-058), for a media socket's keys; null when not connected.</summary>
    Func<string, byte[], int, byte[]>? GetExportKey(string peerId) => null;

    /// <summary>This PC's IPv4 address the peer can reach (as for a transfer's tcpHost), or null.</summary>
    string? GetAdvertisableHost(string peerId) => null;

    /// <summary>Sends over the peer's live session. Throws if the peer has no session.</summary>
    Task SendAsync(string peerId, PeerMessage message, CancellationToken ct = default);

    /// <summary>
    /// Routes the transfer-scoped replies for <paramref name="transferId"/> on the peer's live
    /// session to the returned channel (ADR-034), so a PC-to-phone transfer does not need a second
    /// connection. Throws <see cref="InvalidOperationException"/> if the peer has no session.
    /// </summary>
    IOutboundTransferChannel OpenOutboundTransfer(string peerId, string transferId);
}

public sealed class PeerSessionRegistry : IPeerSessionRegistry
{
    private readonly object _gate = new();
    private readonly Dictionary<string, Registration> _sessions = new(StringComparer.OrdinalIgnoreCase);

    public event Action<string>? SessionOpened;
    public event Action<string>? SessionClosed;
    public event Action<string>? CapabilitiesChanged;
    public event Action<string, PeerMessage.Notification>? NotificationReceived;
    public event Action<string, PeerMessage.MediaState>? MediaStateReceived;
    public event Action<string, PeerMessage.MediaCommand>? MediaCommandReceived;
    public event Action<string, PeerMessage.DeviceStatus>? DeviceStatusReceived;
    public event Action<string, PeerMessage.SimList>? SimListReceived;
    public event Action<string, PeerMessage.SmsThreads>? SmsThreadsReceived;
    public event Action<string, PeerMessage.SmsMessages>? SmsMessagesReceived;
    public event Action<string, PeerMessage.SmsNew>? SmsNewReceived;
    public event Action<string, PeerMessage.SmsSendResult>? SmsSendResultReceived;
    public event Action<string, PeerMessage.CallState>? CallStateReceived;
    public event Action<string, PeerMessage.CallLog>? CallLogReceived;
    public event Action<string, PeerMessage>? PhotosMessageReceived;
    public event Action<string, PeerMessage>? DeviceMessageReceived;

    public bool IsConnected(string peerId)
    {
        lock (_gate) return _sessions.ContainsKey(peerId);
    }

    public IReadOnlyList<string> GetConnectedPeers()
    {
        lock (_gate) return _sessions.Keys.ToList();
    }

    public int GetPeerCapabilities(string peerId)
    {
        lock (_gate) return _sessions.TryGetValue(peerId, out var registration) ? registration.Capabilities : 0;
    }

    public string? GetPeerName(string peerId)
    {
        lock (_gate) return _sessions.TryGetValue(peerId, out var registration) ? registration.PeerName : null;
    }

    public event Action<string, PeerMessage>? StreamMessageReceived;

    public Func<string, byte[], int, byte[]>? GetExportKey(string peerId)
    {
        lock (_gate) return _sessions.TryGetValue(peerId, out var registration) ? registration.ExportKey : null;
    }

    public string? GetAdvertisableHost(string peerId)
    {
        SessionAddresses? addresses;
        lock (_gate) addresses = _sessions.TryGetValue(peerId, out var registration) ? registration.Addresses : null;
        if (addresses is null) return null;
        try { return LinkServer.FindAdvertisableAddress(addresses); }
        catch (InvalidOperationException) { return null; }
    }

    /// <summary>Routes an ADR-066 phone-to-PC stream message. Returns false for any other type.</summary>
    internal bool PublishStream(string peerId, PeerMessage message)
    {
        if (message is not (PeerMessage.StreamCaps or PeerMessage.StreamState or PeerMessage.StreamStop)) return false;
        Publish(StreamMessageReceived, peerId, message, nameof(StreamMessageReceived));
        return true;
    }

    public async Task SendAsync(string peerId, PeerMessage message, CancellationToken ct = default)
    {
        Registration? registration;
        lock (_gate) _sessions.TryGetValue(peerId, out registration);
        if (registration is null) throw new InvalidOperationException("The peer has no live session.");
        await registration.Send(message, ct).ConfigureAwait(false);
    }

    public IOutboundTransferChannel OpenOutboundTransfer(string peerId, string transferId)
    {
        Registration? registration;
        lock (_gate) _sessions.TryGetValue(peerId, out registration);
        if (registration is null) throw new InvalidOperationException("The peer has no live session.");
        return registration.Transfers.Register(transferId, registration.Send, registration.ExportKey);
    }

    /// <summary>
    /// Registers a new session, inbound or dialled by this PC (ADR-040). Exactly one session per
    /// peer is live: a newer one replaces the older (the phone reconnected before the old socket
    /// was noticed dead, or it dialled in while this PC's own dial was up). The replaced session
    /// is reported closed exactly once, so per-session companion state is reset once, and
    /// <paramref name="onReplaced"/> asks its loop to close the socket cleanly. Its later
    /// <see cref="Registration.Dispose"/> raises nothing.
    /// </summary>
    internal Registration Open(
        string peerId,
        Func<PeerMessage, CancellationToken, Task> send,
        Func<string, byte[], int, byte[]>? exportKey = null,
        Action? onReplaced = null,
        SessionAddresses? addresses = null)
    {
        var registration = new Registration(this, peerId, send, onReplaced, exportKey) { Addresses = addresses };
        Registration? replaced;
        lock (_gate)
        {
            _sessions.TryGetValue(peerId, out replaced);
            _sessions[peerId] = registration;
        }
        if (replaced is not null)
        {
            replaced.MarkReplaced();
            Raise(SessionClosed, peerId, nameof(SessionClosed));
        }
        Raise(SessionOpened, peerId, nameof(SessionOpened));
        return registration;
    }

    // Companion payloads are user data: handlers are isolated and nothing here logs them.
    internal void PublishNotification(string peerId, PeerMessage.Notification notification)
        => Publish(NotificationReceived, peerId, notification, nameof(NotificationReceived));

    internal void PublishMediaState(string peerId, PeerMessage.MediaState state)
        => Publish(MediaStateReceived, peerId, state, nameof(MediaStateReceived));

    internal void PublishMediaCommand(string peerId, PeerMessage.MediaCommand command)
        => Publish(MediaCommandReceived, peerId, command, nameof(MediaCommandReceived));

    internal void PublishDeviceStatus(string peerId, PeerMessage.DeviceStatus status)
        => Publish(DeviceStatusReceived, peerId, status, nameof(DeviceStatusReceived));

    /// <summary>Routes an ADR-027 phone-to-PC message. Returns false for any other type.</summary>
    internal bool PublishMessagingOrCall(string peerId, PeerMessage message)
    {
        switch (message)
        {
            case PeerMessage.SimList m: Publish(SimListReceived, peerId, m, nameof(SimListReceived)); return true;
            case PeerMessage.SmsThreads m: Publish(SmsThreadsReceived, peerId, m, nameof(SmsThreadsReceived)); return true;
            case PeerMessage.SmsMessages m: Publish(SmsMessagesReceived, peerId, m, nameof(SmsMessagesReceived)); return true;
            case PeerMessage.SmsNew m: Publish(SmsNewReceived, peerId, m, nameof(SmsNewReceived)); return true;
            case PeerMessage.SmsSendResult m: Publish(SmsSendResultReceived, peerId, m, nameof(SmsSendResultReceived)); return true;
            case PeerMessage.CallState m: Publish(CallStateReceived, peerId, m, nameof(CallStateReceived)); return true;
            case PeerMessage.CallLog m: Publish(CallLogReceived, peerId, m, nameof(CallLogReceived)); return true;
            default: return false;
        }
    }

    /// <summary>Routes an ADR-034 phone-to-PC photo message. Returns false for any other type.</summary>
    internal bool PublishPhotos(string peerId, PeerMessage message)
    {
        if (message is not (PeerMessage.Photos or PeerMessage.PhotoThumb or PeerMessage.PhotoNew or PeerMessage.PhotoRemoved))
            return false;
        Publish(PhotosMessageReceived, peerId, message, nameof(PhotosMessageReceived));
        return true;
    }

    /// <summary>Routes an ADR-038 pcLock or wallpaper, a clip, or an ADR-059 deviceInfo. Returns false for any other type.</summary>
    internal bool PublishDeviceMessage(string peerId, PeerMessage message)
    {
        if (message is not (PeerMessage.PcLock or PeerMessage.Wallpaper or PeerMessage.Clip or PeerMessage.DeviceInfo)) return false;
        Publish(DeviceMessageReceived, peerId, message, nameof(DeviceMessageReceived));
        return true;
    }

    private static void Publish<T>(Action<string, T>? handlers, string peerId, T message, string name)
    {
        if (handlers is null) return;
        foreach (Action<string, T> handler in handlers.GetInvocationList().Cast<Action<string, T>>())
        {
            // A faulty subscriber must not take the session down.
            try { handler(peerId, message); }
            catch (Exception error) { VeyroTrace.Error($"{name} handler failed", error); }
        }
    }

    private void OnCapabilitiesChanged(Registration registration)
    {
        bool current;
        lock (_gate) current = _sessions.TryGetValue(registration.PeerId, out var live) && ReferenceEquals(live, registration);
        if (current) Raise(CapabilitiesChanged, registration.PeerId, nameof(CapabilitiesChanged));
    }

    private void Close(Registration registration)
    {
        bool removed;
        lock (_gate)
        {
            removed = _sessions.TryGetValue(registration.PeerId, out var current) && ReferenceEquals(current, registration);
            if (removed) _sessions.Remove(registration.PeerId);
        }
        if (removed) Raise(SessionClosed, registration.PeerId, nameof(SessionClosed));
    }

    private static void Raise(Action<string>? handlers, string peerId, string name)
    {
        if (handlers is null) return;
        foreach (Action<string> handler in handlers.GetInvocationList().Cast<Action<string>>())
        {
            try { handler(peerId); }
            catch (Exception error) { VeyroTrace.Error($"{name} handler failed", error); }
        }
    }

    internal sealed class Registration : IDisposable
    {
        private readonly PeerSessionRegistry _owner;
        private readonly Action? _onReplaced;
        private int _capabilities;
        private int _disposed;
        private int _replaced;

        public Registration(
            PeerSessionRegistry owner,
            string peerId,
            Func<PeerMessage, CancellationToken, Task> send,
            Action? onReplaced = null,
            Func<string, byte[], int, byte[]>? exportKey = null)
        {
            _owner = owner;
            PeerId = peerId;
            Send = send;
            ExportKey = exportKey ?? ((_, _, _) => throw new InvalidOperationException("This session exports no keys."));
            _onReplaced = onReplaced;
        }

        public string PeerId { get; }
        public Func<PeerMessage, CancellationToken, Task> Send { get; }

        /// <summary>The session's key exporter (04-PROTOCOL.md 5.4); data sockets derive their keys from it.</summary>
        public Func<string, byte[], int, byte[]> ExportKey { get; }

        /// <summary>Both ends of the session's socket, for choosing the address a media socket is dialled on.</summary>
        public SessionAddresses? Addresses { get; init; }

        /// <summary>
        /// A newer session from the same peer took over. The loop stops publishing companion
        /// messages from this one (they would duplicate the new session's) and lets it close.
        /// </summary>
        public bool IsReplaced => Volatile.Read(ref _replaced) != 0;

        internal void MarkReplaced()
        {
            if (Interlocked.Exchange(ref _replaced, 1) != 0) return;
            try { _onReplaced?.Invoke(); }
            catch (Exception error) { VeyroTrace.Error("Closing a replaced session failed", error); }
        }

        /// <summary>Outbound transfers waiting on this session's replies (ADR-034).</summary>
        public TransferMailbox Transfers { get; } = new();

        private string? _peerName;

        /// <summary>ADR-059: the name in the peer's latest Hello/HelloAck. Written by the receive loop.</summary>
        public string? PeerName
        {
            get => Volatile.Read(ref _peerName);
            set => Volatile.Write(ref _peerName, value);
        }

        /// <summary>Written only by the session's receive loop; a change raises CapabilitiesChanged.</summary>
        public int Capabilities
        {
            get => Volatile.Read(ref _capabilities);
            set
            {
                if (Interlocked.Exchange(ref _capabilities, value) != value && Volatile.Read(ref _disposed) == 0)
                    _owner.OnCapabilitiesChanged(this);
            }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
            Transfers.Close();
            _owner.Close(this);
        }
    }
}
