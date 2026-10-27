using Veyro.Core.Data;
using Veyro.Core.Discovery;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// ADR-040: the PC dials a paired phone that is visible but did not reconnect by itself. Fake
/// clock, discovery, sessions and dialler; nothing touches the network.
/// </summary>
public sealed class SessionKeeperTests
{
    private const string Phone = "PHONE-A";
    private const string Other = "PHONE-B";

    private sealed class FakeDiscovery : IDiscoveryService
    {
        public IReadOnlyList<PeerDevice> DiscoveredPeers { get; private set; } = [];
        public event Action<IReadOnlyList<PeerDevice>>? PeersUpdated;
        public Task StartAsync(CancellationToken ct = default) => Task.CompletedTask;
        public Task StopAsync() => Task.CompletedTask;

        public void See(params (string Id, string Host)[] peers)
        {
            DiscoveredPeers = peers
                .Select(peer => new PeerDevice(peer.Id, peer.Id, PlatformType.Android, [new PeerEndpoint(peer.Host, 5170)], false, false))
                .ToList();
            PeersUpdated?.Invoke(DiscoveredPeers);
        }
    }

    private sealed class FakeTrust : ITrustStore
    {
        private readonly Dictionary<string, TrustedPeer> _peers = new(StringComparer.OrdinalIgnoreCase);
        public void Add(string id) => _peers[id] = new TrustedPeer(id, id, PlatformType.Android, "", "", false, DateTimeOffset.UnixEpoch);
        public IReadOnlyList<TrustedPeer> GetTrustedPeers() => _peers.Values.ToList();
        public TrustedPeer? GetPeer(string deviceId) => _peers.GetValueOrDefault(deviceId);
        public Task UpsertAsync(TrustedPeer peer) { _peers[peer.DeviceId] = peer; return Task.CompletedTask; }
        public Task RemoveAsync(string deviceId) { _peers.Remove(deviceId); return Task.CompletedTask; }
    }

    /// <summary>Each dial waits until the test completes it; continuations run inline.</summary>
    private sealed class FakeDialer : IPeerDialer
    {
        public List<(string Peer, PeerEndpoint Endpoint, TaskCompletionSource<bool> Result)> Calls { get; } = [];
        public Task<bool> DialAsync(TrustedPeer peer, PeerEndpoint endpoint, CancellationToken ct)
        {
            var result = new TaskCompletionSource<bool>();
            Calls.Add((peer.DeviceId, endpoint, result));
            return result.Task;
        }
        public int CountFor(string peer) => Calls.Count(call => call.Peer == peer);
        public void FailLast() => Fail(Calls[^1].Result);
        public void Fail(string peer) => Fail(Calls.Last(call => call.Peer == peer).Result);
        public void Succeed(string peer) => Resolve(() => Calls.Last(call => call.Peer == peer).Result.SetResult(true));
        public void Refuse(string peer, bool busy = false) => Resolve(() =>
            Calls.Last(call => call.Peer == peer).Result.SetException(new SessionRefusedException(busy ? "already-connected" : "declined", busy)));

        private static void Fail(TaskCompletionSource<bool> result) => Resolve(() => result.SetException(new IOException("unreachable")));

        /// <summary>
        /// Completes without xUnit's synchronization context, which would make the keeper's
        /// continuation run later on the pool instead of inline, so the test would race it.
        /// </summary>
        private static void Resolve(Action complete)
        {
            SynchronizationContext? context = SynchronizationContext.Current;
            SynchronizationContext.SetSynchronizationContext(null);
            try { complete(); }
            finally { SynchronizationContext.SetSynchronizationContext(context); }
        }
    }

    private sealed class FakeNetwork : INetworkChangeSignal
    {
        public event Action? NetworkChanged;
        public void Change() => NetworkChanged?.Invoke();
    }

    private sealed class Rig : IDisposable
    {
        public ManualTimeProvider Time { get; } = new();
        public FakePeerSessions Sessions { get; } = new();
        public FakeDiscovery Discovery { get; } = new();
        public FakeTrust Trust { get; } = new();
        public FakeDialer Dialer { get; } = new();
        public FakeNetwork Network { get; } = new();
        public bool Enabled { get; set; } = true;
        public SessionKeeper Keeper { get; }

        public Rig()
        {
            Trust.Add(Phone);
            Trust.Add(Other);
            Keeper = new SessionKeeper(Sessions, Discovery, Trust, Dialer, () => Enabled, Network, Time);
        }

        /// <summary>Advances one second at a time, so timers armed inside callbacks fire too.</summary>
        public void Wait(double seconds)
        {
            for (int i = 0; i < (int)Math.Round(seconds * 10); i++) Time.Advance(TimeSpan.FromMilliseconds(100));
        }

        public void Dispose() => Keeper.Dispose();
    }

    [Fact]
    public void Dials_OnlyAfterThePhonesOwnReconnectGrace()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(9.5);
        Assert.Empty(rig.Dialer.Calls);

        rig.Wait(0.6);
        var call = Assert.Single(rig.Dialer.Calls);
        Assert.Equal(Phone, call.Peer);
        Assert.Equal("192.168.1.20", call.Endpoint.Host);
        Assert.True(rig.Keeper.IsDialing(Phone));
    }

    [Fact]
    public void PhoneReconnectingByItself_IsNeverDialled()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(5);
        rig.Sessions.Connect(Phone, PeerCapabilities.FileTransfer);
        rig.Wait(60);
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(60);
        Assert.Empty(rig.Dialer.Calls);
    }

    [Fact]
    public void Failures_BackOff_5_15_30_60_120_ThenEvery300s_OneDialAtATime()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(10.1);
        Assert.Equal(1, rig.Dialer.CountFor(Phone));

        // While a dial is in flight nothing else is started, however long it takes.
        rig.Wait(400);
        rig.Discovery.See((Phone, "192.168.1.20"));
        Assert.Equal(1, rig.Dialer.CountFor(Phone));

        foreach (int wait in new[] { 5, 15, 30, 60, 120, 300, 300 })
        {
            int before = rig.Dialer.CountFor(Phone);
            rig.Dialer.FailLast();
            Assert.False(rig.Keeper.IsDialing(Phone));
            rig.Wait(wait - 0.5);
            Assert.Equal(before, rig.Dialer.CountFor(Phone));
            rig.Wait(0.6);
            Assert.Equal(before + 1, rig.Dialer.CountFor(Phone));
        }
    }

    [Fact]
    public void NetworkChange_ResetsTheBackoff()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(10.1);
        for (int i = 0; i < 4; i++)
        {
            rig.Dialer.FailLast();
            rig.Wait(new[] { 5, 15, 30, 60 }[i] + 0.1);
        }
        rig.Dialer.FailLast(); // next would wait 120 s
        int dials = rig.Dialer.CountFor(Phone);

        rig.Network.Change();
        // The phone sees the change too: it gets the grace period, then the first step again.
        rig.Wait(9.5);
        Assert.Equal(dials, rig.Dialer.CountFor(Phone));
        rig.Wait(0.6);
        Assert.Equal(dials + 1, rig.Dialer.CountFor(Phone));
        rig.Dialer.FailLast();
        rig.Wait(5.1);
        Assert.Equal(dials + 2, rig.Dialer.CountFor(Phone));
    }

    [Fact]
    public void NewSighting_ResetsTheBackoff()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(10.1);
        rig.Dialer.FailLast();
        rig.Wait(5.1);
        rig.Dialer.FailLast();
        rig.Wait(15.1);
        rig.Dialer.FailLast(); // next would wait 30 s
        int dials = rig.Dialer.CountFor(Phone);

        // Same phone, new address (it moved to another access point): dial at once.
        rig.Discovery.See((Phone, "192.168.1.77"));
        Assert.Equal(dials + 1, rig.Dialer.CountFor(Phone));
        Assert.Equal("192.168.1.77", rig.Dialer.Calls[^1].Endpoint.Host);
        rig.Dialer.FailLast();
        rig.Wait(5.1);
        Assert.Equal(dials + 2, rig.Dialer.CountFor(Phone));
    }

    [Fact]
    public void PhoneLeavingAndComingBack_GetsTheGraceAgain()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(4);
        rig.Discovery.See();
        rig.Wait(60);
        Assert.Empty(rig.Dialer.Calls); // not visible: nothing to dial

        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(9.5);
        Assert.Empty(rig.Dialer.Calls);
        rig.Wait(0.6);
        Assert.Single(rig.Dialer.Calls);
    }

    [Fact]
    public void SessionClosing_GivesThePhoneTheGraceBeforeAnyDial()
    {
        using var rig = new Rig();
        rig.Sessions.Connect(Phone, PeerCapabilities.FileTransfer);
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(30);
        Assert.Empty(rig.Dialer.Calls);

        rig.Sessions.Disconnect(Phone);
        rig.Wait(9.5);
        Assert.Empty(rig.Dialer.Calls);
        rig.Wait(0.6);
        Assert.Single(rig.Dialer.Calls);
    }

    [Fact]
    public void SuccessfulDial_StopsDialling()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(10.1);
        rig.Sessions.Connect(Phone, PeerCapabilities.FileTransfer);
        rig.Dialer.Succeed(Phone);
        Assert.False(rig.Keeper.IsDialing(Phone));
        rig.Wait(600);
        Assert.Single(rig.Dialer.Calls);
    }

    [Fact]
    public void SettingOff_DialsNothingAutomatically_ButRefreshDialsAtOnce()
    {
        using var rig = new Rig { Enabled = false };
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(60);
        Assert.Empty(rig.Dialer.Calls);

        Task<bool> refresh = rig.Keeper.ConnectNowAsync(Phone);
        Assert.Single(rig.Dialer.Calls);
        Assert.True(rig.Keeper.IsDialing(Phone));
        // A second press joins the dial in flight.
        Assert.Same(refresh, rig.Keeper.ConnectNowAsync(Phone));
        Assert.Single(rig.Dialer.Calls);

        rig.Sessions.Connect(Phone, PeerCapabilities.FileTransfer);
        rig.Dialer.Succeed(Phone);
        Assert.True(refresh.Wait(TimeSpan.FromSeconds(5)) && refresh.Result);
    }

    [Fact]
    public void Refresh_BypassesTheGrace_AndReportsAPhoneNotOnTheNetwork()
    {
        using var rig = new Rig();
        Task<bool> unseen = rig.Keeper.ConnectNowAsync(Phone);
        Assert.True(unseen.IsCompleted);
        Assert.False(unseen.Result);
        Assert.Empty(rig.Dialer.Calls);

        rig.Discovery.See((Phone, "192.168.1.20"));
        Task<bool> refresh = rig.Keeper.ConnectNowAsync(Phone);
        Assert.Single(rig.Dialer.Calls); // no 10 s wait
        rig.Dialer.FailLast();
        Assert.True(refresh.Wait(TimeSpan.FromSeconds(5)));
        Assert.False(refresh.Result);
    }

    [Fact]
    public void UnpairedPhones_AreNeverDialled_EachPhoneHasItsOwnState()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"), (Other, "192.168.1.30"), ("STRANGER", "192.168.1.40"));
        rig.Wait(10.1);
        Assert.Equal(1, rig.Dialer.CountFor(Phone));
        Assert.Equal(1, rig.Dialer.CountFor(Other));
        Assert.Equal(0, rig.Dialer.CountFor("STRANGER"));

        // Phone A connects; B keeps backing off on its own schedule.
        rig.Sessions.Connect(Phone, PeerCapabilities.FileTransfer);
        rig.Dialer.Succeed(Phone);
        rig.Dialer.Fail(Other);
        rig.Wait(5.1);
        Assert.Equal(1, rig.Dialer.CountFor(Phone));
        Assert.Equal(2, rig.Dialer.CountFor(Other));
    }

    [Fact]
    public void PhoneRefusingTheSession_IsNotDialledAgainUntilItConnectsOrTheUserRefreshes()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(10.1);
        rig.Dialer.Refuse(Phone);
        rig.Wait(900);
        rig.Network.Change();
        rig.Wait(60);
        Assert.Single(rig.Dialer.Calls);

        // The user asks explicitly: one dial.
        _ = rig.Keeper.ConnectNowAsync(Phone);
        Assert.Equal(2, rig.Dialer.Calls.Count);
        rig.Dialer.Refuse(Phone);

        // The phone connects by itself (it trusts this PC again): automatic dials resume later.
        rig.Sessions.Connect(Phone, PeerCapabilities.FileTransfer);
        rig.Sessions.Disconnect(Phone);
        rig.Wait(10.1);
        Assert.Equal(3, rig.Dialer.Calls.Count);
    }

    [Fact]
    public void BusyPhone_IsRetriedWithTheNormalBackoff()
    {
        using var rig = new Rig();
        rig.Discovery.See((Phone, "192.168.1.20"));
        rig.Wait(10.1);
        rig.Dialer.Refuse(Phone, busy: true);
        rig.Wait(5.1);
        Assert.Equal(2, rig.Dialer.Calls.Count);
    }

    [Theory]
    [InlineData("192.168.1.20", 5150, true)]
    [InlineData("10.0.0.5", 1, true)]
    [InlineData("127.0.0.1", 5150, false)]
    [InlineData("0.0.0.0", 5150, false)]
    [InlineData("255.255.255.255", 5150, false)]
    [InlineData("239.255.42.99", 5150, false)]
    [InlineData("phone.local", 5150, false)]
    [InlineData("192.168.1.20", 0, false)]
    [InlineData("192.168.1.20", 70000, false)]
    public void Dialer_OnlyDialsUnicastAddresses(string host, int port, bool expected)
        => Assert.Equal(expected, PersistentSessionDialer.IsDiallable(new PeerEndpoint(host, port)));
}
