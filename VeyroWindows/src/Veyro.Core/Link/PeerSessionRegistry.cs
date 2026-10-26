using Veyro.Core.Diagnostics;

namespace Veyro.Core.Link;

/// <summary>
/// The live, authenticated sessions held by <see cref="VeyroLinkServer"/>, one per paired peer
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

    event Action<string, LinkMessage.Notification>? NotificationReceived;
    event Action<string, LinkMessage.MediaState>? MediaStateReceived;
    event Action<string, LinkMessage.MediaCommand>? MediaCommandReceived;
    event Action<string, LinkMessage.DeviceStatus>? DeviceStatusReceived;

    bool IsConnected(string peerId);

    /// <summary>Peers with a live session right now.</summary>
    IReadOnlyList<string> GetConnectedPeers();

    /// <summary>Capability bits from the peer's Hello/HelloAck, or 0 when unknown or not connected.</summary>
    int GetPeerCapabilities(string peerId);

    /// <summary>Sends over the peer's live session. Throws if the peer has no session.</summary>
    Task SendAsync(string peerId, LinkMessage message, CancellationToken ct = default);
}

public sealed class PeerSessionRegistry : IPeerSessionRegistry
{
    private readonly object _gate = new();
    private readonly Dictionary<string, Registration> _sessions = new(StringComparer.OrdinalIgnoreCase);

    public event Action<string>? SessionOpened;
    public event Action<string>? SessionClosed;
    public event Action<string>? CapabilitiesChanged;
    public event Action<string, LinkMessage.Notification>? NotificationReceived;
    public event Action<string, LinkMessage.MediaState>? MediaStateReceived;
    public event Action<string, LinkMessage.MediaCommand>? MediaCommandReceived;
    public event Action<string, LinkMessage.DeviceStatus>? DeviceStatusReceived;

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

    public async Task SendAsync(string peerId, LinkMessage message, CancellationToken ct = default)
    {
        Registration? registration;
        lock (_gate) _sessions.TryGetValue(peerId, out registration);
        if (registration is null) throw new InvalidOperationException("The peer has no live session.");
        await registration.Send(message, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// Registers a new session. A newer session from the same peer replaces the older one (the
    /// phone reconnected before the old socket was noticed dead); the replaced session is
    /// reported closed first so per-session state is reset.
    /// </summary>
    internal Registration Open(string peerId, Func<LinkMessage, CancellationToken, Task> send)
    {
        var registration = new Registration(this, peerId, send);
        Registration? replaced;
        lock (_gate)
        {
            _sessions.TryGetValue(peerId, out replaced);
            _sessions[peerId] = registration;
        }
        if (replaced is not null) Raise(SessionClosed, peerId, nameof(SessionClosed));
        Raise(SessionOpened, peerId, nameof(SessionOpened));
        return registration;
    }

    // Companion payloads are user data: handlers are isolated and nothing here logs them.
    internal void PublishNotification(string peerId, LinkMessage.Notification notification)
        => Publish(NotificationReceived, peerId, notification, nameof(NotificationReceived));

    internal void PublishMediaState(string peerId, LinkMessage.MediaState state)
        => Publish(MediaStateReceived, peerId, state, nameof(MediaStateReceived));

    internal void PublishMediaCommand(string peerId, LinkMessage.MediaCommand command)
        => Publish(MediaCommandReceived, peerId, command, nameof(MediaCommandReceived));

    internal void PublishDeviceStatus(string peerId, LinkMessage.DeviceStatus status)
        => Publish(DeviceStatusReceived, peerId, status, nameof(DeviceStatusReceived));

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
        private int _capabilities;
        private int _disposed;

        public Registration(PeerSessionRegistry owner, string peerId, Func<LinkMessage, CancellationToken, Task> send)
        {
            _owner = owner;
            PeerId = peerId;
            Send = send;
        }

        public string PeerId { get; }
        public Func<LinkMessage, CancellationToken, Task> Send { get; }

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
            if (Interlocked.Exchange(ref _disposed, 1) == 0) _owner.Close(this);
        }
    }
}
