using System.Net;
using System.Net.Sockets;
using Veyro.Core.Data;
using Veyro.Core.Diagnostics;
using Veyro.Core.Discovery;
using Veyro.Core.Models;

namespace Veyro.Core.Link;

/// <summary>Opens a persistent outbound session to a paired phone (ADR-040). Faked in tests.</summary>
public interface IPeerDialer
{
    /// <summary>
    /// True once a session to the peer is registered, false when the phone connected on its own
    /// meanwhile. Throws when the phone could not be reached or refused.
    /// </summary>
    Task<bool> DialAsync(TrustedPeer peer, PeerEndpoint endpoint, CancellationToken ct);
}

/// <summary>The PC's network changed (Windows: NetworkInformation.NetworkStatusChanged). Any thread.</summary>
public interface INetworkChangeSignal
{
    event Action? NetworkChanged;
}

/// <summary>
/// The production <see cref="IPeerDialer"/>: <see cref="LinkClient.OpenPersistentSessionAsync"/>
/// run by the listener's session loop. Discovery endpoints are untrusted, so only a plain unicast
/// IP address is dialled; the stored session secret is what authenticates the phone.
/// </summary>
public sealed class PersistentSessionDialer(
    string localDeviceId,
    Func<string> localName,
    ILinkSessionRunner runner,
    IPeerSessionRegistry sessions,
    bool allowLoopback = false) : IPeerDialer
{
    public Task<bool> DialAsync(TrustedPeer peer, PeerEndpoint endpoint, CancellationToken ct)
    {
        if (!IsDiallable(endpoint, allowLoopback))
            throw new InvalidOperationException("The discovered endpoint is not a dialable unicast address.");
        return new LinkClient(localDeviceId, localName()).OpenPersistentSessionAsync(endpoint, peer, runner, sessions, ct);
    }

    /// <summary>An IPv4/IPv6 unicast address and a valid port. Loopback only when allowed (tests).</summary>
    public static bool IsDiallable(PeerEndpoint endpoint, bool allowLoopback = false)
    {
        if (endpoint.Port is < 1 or > 65535 || !IPAddress.TryParse(endpoint.Host, out IPAddress? address)) return false;
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (address.AddressFamily is not (AddressFamily.InterNetwork or AddressFamily.InterNetworkV6)) return false;
        if (address.Equals(IPAddress.Any) || address.Equals(IPAddress.Broadcast) || address.Equals(IPAddress.IPv6Any)) return false;
        if (IPAddress.IsLoopback(address)) return allowLoopback;
        if (address.IsIPv6Multicast) return false;
        if (address.AddressFamily == AddressFamily.InterNetwork && address.GetAddressBytes()[0] is >= 224) return false;
        return true;
    }
}

/// <summary>
/// Restores the companion session when the phone cannot (ADR-040). The phone normally dials the
/// PC; if it does not (app killed, battery saver), this PC dials each paired phone that discovery
/// reports and that has no live session:
/// <list type="bullet">
/// <item>only after <see cref="PhoneReconnectGrace"/> since it was seen without a session, so the
/// phone's own reconnect has priority;</item>
/// <item>at most one dial in flight per phone; after a failure the next one waits
/// <see cref="Backoff"/> (5, 15, 30, 60, 120 s, then 300 s);</item>
/// <item>backoff resets on a network change or a new discovery sighting (the phone reappeared or
/// its address changed); nothing is dialled while the phone has a session;</item>
/// <item>automatic dials only while <c>isEnabled</c> holds ("Reconnect to my phones
/// automatically"); <see cref="ConnectNowAsync"/> (the refresh button) dials at once;</item>
/// <item>a phone that refuses the session (<see cref="SessionRefusedException"/>: it removed this
/// PC and would ask its user to pair) is not dialled again automatically until it connects by
/// itself or the user presses refresh.</item>
/// </list>
/// Discovery is untrusted: it only says where to dial. Only phones in the trust store are
/// dialled, and the stored session secret authenticates the session. UI-free.
/// </summary>
public sealed class SessionKeeper : IDisposable
{
    public static readonly TimeSpan PhoneReconnectGrace = TimeSpan.FromSeconds(10);

    public static readonly IReadOnlyList<TimeSpan> Backoff =
    [
        TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(15), TimeSpan.FromSeconds(30),
        TimeSpan.FromSeconds(60), TimeSpan.FromSeconds(120), TimeSpan.FromSeconds(300)
    ];

    private readonly IPeerSessionRegistry _sessions;
    private readonly IDiscoveryService _discovery;
    private readonly ITrustStore _trust;
    private readonly IPeerDialer _dialer;
    private readonly Func<bool> _isEnabled;
    private readonly INetworkChangeSignal? _network;
    private readonly TimeProvider _time;
    private readonly object _gate = new();
    private readonly Dictionary<string, PeerState> _peers = new(StringComparer.OrdinalIgnoreCase);
    private readonly CancellationTokenSource _stopping = new();
    private ITimer? _timer;
    private DateTimeOffset? _timerDue;
    private bool _disposed;

    /// <summary>A dial to the peer started or ended (argument: device id). Raised on any thread.</summary>
    public event Action<string>? DialingChanged;

    public SessionKeeper(
        IPeerSessionRegistry sessions,
        IDiscoveryService discovery,
        ITrustStore trust,
        IPeerDialer dialer,
        Func<bool> isEnabled,
        INetworkChangeSignal? network = null,
        TimeProvider? time = null)
    {
        _sessions = sessions;
        _discovery = discovery;
        _trust = trust;
        _dialer = dialer;
        _isEnabled = isEnabled;
        _network = network;
        _time = time ?? TimeProvider.System;
        _discovery.PeersUpdated += OnPeersUpdated;
        _sessions.SessionOpened += OnSessionOpened;
        _sessions.SessionClosed += OnSessionClosed;
        if (_network is not null) _network.NetworkChanged += OnNetworkChanged;
        OnPeersUpdated(_discovery.DiscoveredPeers);
    }

    /// <summary>True while a dial to the peer is in flight.</summary>
    public bool IsDialing(string peerId)
    {
        lock (_gate) return _peers.TryGetValue(peerId, out PeerState? state) && state.InFlight is not null;
    }

    /// <summary>
    /// The refresh button: dials the phone now, bypassing the grace period, the backoff and the
    /// automatic-reconnect setting. Joins a dial already in flight. True when the phone has a
    /// session afterwards; false when it is not visible on the network or the dial failed.
    /// </summary>
    public Task<bool> ConnectNowAsync(string peerId)
    {
        if (_sessions.IsConnected(peerId)) return Task.FromResult(true);
        if (_trust.GetPeer(peerId) is not { } peer) return Task.FromResult(false);
        PeerEndpoint? seen = Sighting(_discovery.DiscoveredPeers, peerId);
        TaskCompletionSource<bool> done;
        PeerEndpoint endpoint;
        lock (_gate)
        {
            if (_disposed) return Task.FromResult(false);
            PeerState state = StateOf(peerId);
            if (state.InFlight is { } running) return running.Task;
            PeerEndpoint? target = (state.Visible ? state.Endpoint : null) ?? seen;
            if (target is null) return Task.FromResult(false);
            endpoint = target;
            state.Failures = 0;
            state.NextAttempt = DateTimeOffset.MinValue;
            state.Refused = false;
            done = state.InFlight = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        }
        StartDial(peerId, peer, endpoint, done);
        return done.Task;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            _timer?.Dispose();
            _timer = null;
        }
        _stopping.Cancel();
        _discovery.PeersUpdated -= OnPeersUpdated;
        _sessions.SessionOpened -= OnSessionOpened;
        _sessions.SessionClosed -= OnSessionClosed;
        if (_network is not null) _network.NetworkChanged -= OnNetworkChanged;
    }

    private sealed class PeerState
    {
        /// <summary>In discovery's current list.</summary>
        public bool Visible;
        public PeerEndpoint? Endpoint;

        /// <summary>Start of the phone's own reconnect grace; null while it has a session.</summary>
        public DateTimeOffset? FirstSeen;

        public int Failures;
        public DateTimeOffset NextAttempt = DateTimeOffset.MinValue;
        public TaskCompletionSource<bool>? InFlight;

        /// <summary>
        /// The phone refused a session (it no longer trusts this PC). No automatic dial until it
        /// connects by itself or the user presses refresh.
        /// </summary>
        public bool Refused;
    }

    private PeerState StateOf(string peerId)
    {
        if (!_peers.TryGetValue(peerId, out PeerState? state))
        {
            state = new PeerState();
            _peers[peerId] = state;
        }
        return state;
    }

    private static PeerEndpoint? Sighting(IReadOnlyList<PeerDevice> peers, string peerId)
        => peers.FirstOrDefault(peer => string.Equals(peer.DeviceId, peerId, StringComparison.OrdinalIgnoreCase))?.Endpoints.FirstOrDefault();

    private void OnPeersUpdated(IReadOnlyList<PeerDevice> peers)
    {
        lock (_gate)
        {
            if (_disposed) return;
            DateTimeOffset now = _time.GetUtcNow();
            var visible = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (PeerDevice peer in peers)
            {
                if (peer.Endpoints.FirstOrDefault() is not { } endpoint || _trust.GetPeer(peer.DeviceId) is null) continue;
                if (!visible.Add(peer.DeviceId)) continue;
                PeerState state = StateOf(peer.DeviceId);
                bool reappeared = !state.Visible;
                bool newSighting = reappeared || state.Endpoint != endpoint;
                state.Visible = true;
                state.Endpoint = endpoint;
                if (newSighting)
                {
                    state.Failures = 0;
                    state.NextAttempt = DateTimeOffset.MinValue;
                }
                // Back on the network after an absence: the phone gets its grace period again.
                if (!_sessions.IsConnected(peer.DeviceId) && (reappeared || state.FirstSeen is null)) state.FirstSeen = now;
            }
            foreach ((string id, PeerState state) in _peers)
            {
                if (!visible.Contains(id)) state.Visible = false;
            }
        }
        Evaluate();
    }

    private void OnSessionOpened(string peerId)
    {
        lock (_gate)
        {
            if (!_peers.TryGetValue(peerId, out PeerState? state)) return;
            state.FirstSeen = null;
            state.Failures = 0;
            state.NextAttempt = DateTimeOffset.MinValue;
            state.Refused = false; // a session proves the phone trusts this PC again
        }
        Evaluate();
    }

    private void OnSessionClosed(string peerId)
    {
        // A replaced session (ADR-040) reports closed while its successor is live: nothing to do.
        if (_sessions.IsConnected(peerId)) return;
        lock (_gate)
        {
            if (_disposed || !_peers.TryGetValue(peerId, out PeerState? state)) return;
            // The phone usually reconnects by itself; it gets the grace period first.
            state.FirstSeen = _time.GetUtcNow();
            state.Failures = 0;
            state.NextAttempt = DateTimeOffset.MinValue;
        }
        Evaluate();
    }

    private void OnNetworkChanged()
    {
        lock (_gate)
        {
            if (_disposed) return;
            DateTimeOffset now = _time.GetUtcNow();
            foreach ((string id, PeerState state) in _peers)
            {
                state.Failures = 0;
                state.NextAttempt = DateTimeOffset.MinValue;
                // The phone sees the change too and may reconnect; it keeps its priority.
                if (!_sessions.IsConnected(id)) state.FirstSeen = now;
            }
        }
        VeyroTrace.Info("Network changed: reconnect backoff reset");
        Evaluate();
    }

    /// <summary>Starts every dial that is due and arms the timer for the next one.</summary>
    private void Evaluate()
    {
        var due = new List<(string Id, TrustedPeer Peer, PeerEndpoint Endpoint, TaskCompletionSource<bool> Done)>();
        lock (_gate)
        {
            if (_disposed) return;
            DateTimeOffset now = _time.GetUtcNow();
            DateTimeOffset? next = null;
            bool enabled = _isEnabled();
            foreach ((string id, PeerState state) in _peers)
            {
                if (!enabled || state.Refused || !state.Visible || state.Endpoint is null || state.FirstSeen is not { } firstSeen ||
                    state.InFlight is not null || _sessions.IsConnected(id))
                    continue;
                if (_trust.GetPeer(id) is not { } peer) continue;
                DateTimeOffset at = firstSeen + PhoneReconnectGrace > state.NextAttempt ? firstSeen + PhoneReconnectGrace : state.NextAttempt;
                if (at <= now)
                {
                    state.InFlight = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
                    due.Add((id, peer, state.Endpoint, state.InFlight));
                }
                else if (next is null || at < next)
                {
                    next = at;
                }
            }
            Arm(next, now);
        }
        foreach ((string id, TrustedPeer peer, PeerEndpoint endpoint, TaskCompletionSource<bool> done) in due)
            StartDial(id, peer, endpoint, done);
    }

    private void Arm(DateTimeOffset? at, DateTimeOffset now)
    {
        if (at == _timerDue && (_timer is not null || at is null)) return;
        _timer?.Dispose();
        _timer = null;
        _timerDue = at;
        if (at is not { } when) return;
        TimeSpan delay = when - now;
        _timer = _time.CreateTimer(_ => OnTimer(), null, delay < TimeSpan.Zero ? TimeSpan.Zero : delay, Timeout.InfiniteTimeSpan);
    }

    private void OnTimer()
    {
        lock (_gate)
        {
            _timer?.Dispose();
            _timer = null;
            _timerDue = null;
        }
        Evaluate();
    }

    private void StartDial(string peerId, TrustedPeer peer, PeerEndpoint endpoint, TaskCompletionSource<bool> done)
    {
        VeyroTrace.Info($"Dialling paired phone {peerId}");
        RaiseDialing(peerId);
        _ = RunDialAsync(peerId, peer, endpoint, done);
    }

    private async Task RunDialAsync(string peerId, TrustedPeer peer, PeerEndpoint endpoint, TaskCompletionSource<bool> done)
    {
        bool refused = false;
        try
        {
            await _dialer.DialAsync(peer, endpoint, _stopping.Token).ConfigureAwait(false);
        }
        catch (SessionRefusedException error) when (!error.IsBusy)
        {
            // The phone no longer trusts this PC (removed it, or would ask its user to pair).
            // Dialling again would only show that prompt again; wait for the phone or a refresh.
            refused = true;
            VeyroTrace.Info($"Phone {peerId} refused a session; automatic reconnect paused for it");
        }
        catch (Exception error)
        {
            // Type only: messages can carry addresses and the peer's refusal text.
            VeyroTrace.Info($"Dialling phone {peerId} failed: {error.GetType().Name}");
        }
        // Connected by any route (this dial or the phone's own) counts as success.
        bool connected = _sessions.IsConnected(peerId);
        lock (_gate)
        {
            if (_peers.TryGetValue(peerId, out PeerState? state) && ReferenceEquals(state.InFlight, done))
            {
                state.InFlight = null;
                if (connected)
                {
                    state.Failures = 0;
                    state.NextAttempt = DateTimeOffset.MinValue;
                }
                else
                {
                    state.NextAttempt = _time.GetUtcNow() + Backoff[Math.Min(state.Failures, Backoff.Count - 1)];
                    state.Failures++;
                    if (refused) state.Refused = true;
                }
            }
        }
        done.TrySetResult(connected);
        RaiseDialing(peerId);
        Evaluate();
    }

    private void RaiseDialing(string peerId)
    {
        try { DialingChanged?.Invoke(peerId); }
        catch (Exception error) { VeyroTrace.Error("DialingChanged handler failed", error); }
    }
}
