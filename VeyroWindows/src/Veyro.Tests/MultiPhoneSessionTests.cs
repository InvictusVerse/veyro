using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Calls;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Media;
using Veyro.Core.Messaging;
using Veyro.Core.Models;
using Veyro.Core.Notifications;
using Veyro.Core.Photos;
using Veyro.Core.Status;
using Veyro.Core.Transfer;
using Xunit;
using FakePhone = Veyro.Tests.SessionTransferTests.FakePhone;

namespace Veyro.Tests;

/// <summary>
/// ADR-040 against a real listener: sessions this PC dials run the same loop as inbound ones,
/// exactly one session per phone survives a duplicate (the replaced one is closed cleanly and
/// companion state is reset once), and two phones connected at once never share data.
/// </summary>
[Collection(LinkServerCollection.Name)]
public sealed class MultiPhoneSessionTests : IAsyncLifetime
{
    private const int AllCompanion =
        PeerCapabilities.FileTransfer | PeerCapabilities.NotificationMirror | PeerCapabilities.MediaControl |
        PeerCapabilities.DeviceStatus | PeerCapabilities.Messaging | PeerCapabilities.Calls |
        PeerCapabilities.Photos | PeerCapabilities.Wallpaper;

    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-multi-" + Guid.NewGuid().ToString("N"));
    private readonly CancellationTokenSource _timeout = new(TimeSpan.FromSeconds(60));
    private readonly byte[] _pcPublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly Phone _one = new("One");
    private readonly Phone _two = new("Two");
    private string PcId => DeviceId.FromPublicKey(_pcPublicKey).Value;
    private CancellationToken Ct => _timeout.Token;

    private PeerSessionRegistry _registry = null!;
    private FileTrustStore _trustStore = null!;
    private LinkServer _server = null!;

    private sealed class Phone(string name)
    {
        public string Name { get; } = name;
        public byte[] PublicKey { get; } = RandomNumberGenerator.GetBytes(32);
        public byte[] Secret { get; } = RandomNumberGenerator.GetBytes(32);
        public string Id => DeviceId.FromPublicKey(PublicKey).Value;
    }

    public async Task InitializeAsync()
    {
        Directory.CreateDirectory(_root);
        _trustStore = new FileTrustStore(Path.Combine(_root, "trust"), new byte[32]);
        foreach (Phone phone in new[] { _one, _two })
        {
            await _trustStore.UpsertAsync(new TrustedPeer(
                phone.Id, phone.Name, PlatformType.Android,
                Convert.ToBase64String(phone.PublicKey), Convert.ToBase64String(phone.Secret),
                AutoAcceptTransfers: true, DateTimeOffset.UtcNow));
        }
        _registry = new PeerSessionRegistry();
        _server = new LinkServer(PcId, "Test PC", _trustStore, Path.Combine(_root, "received"), _pcPublicKey,
            _ => Task.FromResult(true), sessions: _registry);
        await _server.StartAsync();
    }

    public async Task DisposeAsync()
    {
        await _server.DisposeAsync();
        _timeout.Dispose();
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    /// <summary>The phone dials in, as Android does: preamble, then its own Hello.</summary>
    private async Task<FakePhone> ConnectAsync(Phone phone, int capabilities = AllCompanion)
    {
        var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
        await socket.SendAsync(Encoding.UTF8.GetBytes(PeerMessageJson.Encode(new LinkPreamble(phone.Id, phone.Name, "android"))),
            WebSocketMessageType.Text, true, Ct);
        byte[] buffer = new byte[4096];
        WebSocketReceiveResult ack = await socket.ReceiveAsync(buffer, Ct);
        Assert.Equal(LinkPreambleAck.ModeSession, PeerMessageJson.Decode<LinkPreambleAck>(Encoding.UTF8.GetString(buffer, 0, ack.Count))?.Mode);
        var fake = new FakePhone(socket, new SecureLinkSession(socket, phone.Id, phone.Secret, encrypt: true, expectedPeerId: PcId));
        await fake.Session.SendAsync(new PeerMessage.Hello(phone.Id, phone.Name, "android", capabilities), Ct);
        await fake.ReceiveAsync<PeerMessage.HelloAck>(Ct);
        return fake;
    }

    /// <summary>The session loop handles frames in order: a pong means everything before the ping was handled.</summary>
    private async Task BarrierAsync(FakePhone phone)
    {
        string nonce = Guid.NewGuid().ToString("N");
        await phone.Session.SendAsync(new PeerMessage.Ping(nonce), Ct);
        while ((await phone.ReceiveAsync<PeerMessage.Pong>(Ct)).Nonce != nonce) { }
    }

    /// <summary>A second listener standing in for the phone, trusting this PC with the phone's secret.</summary>
    private async Task<(LinkServer Listener, PeerSessionRegistry Registry)> StartPhoneListenerAsync(Phone phone)
    {
        string root = Path.Combine(_root, "phone-" + phone.Name);
        var trust = new FileTrustStore(Path.Combine(root, "trust"), new byte[32]);
        await trust.UpsertAsync(new TrustedPeer(
            PcId, "Test PC", PlatformType.Windows,
            Convert.ToBase64String(_pcPublicKey), Convert.ToBase64String(phone.Secret),
            AutoAcceptTransfers: true, DateTimeOffset.UtcNow));
        var registry = new PeerSessionRegistry();
        var listener = new LinkServer(phone.Id, phone.Name, trust, Path.Combine(root, "in"), phone.PublicKey,
            _ => Task.FromResult(true), sessions: registry);
        await listener.StartAsync();
        return (listener, registry);
    }

    private PersistentSessionDialer Dialer() => new(PcId, () => "Test PC", _server, _registry, allowLoopback: true);

    private static async Task UntilAsync(Func<bool> condition, CancellationToken ct)
    {
        while (!condition())
        {
            ct.ThrowIfCancellationRequested();
            await Task.Delay(20, ct);
        }
    }

    [Fact]
    public async Task DialledSession_IsRegisteredLikeAnInboundOne_AndCarriesCompanionAndTransfers()
    {
        (LinkServer listener, PeerSessionRegistry phoneSide) = await StartPhoneListenerAsync(_one);
        await using (listener)
        {
            using var mirror = new NotificationMirrorService(_registry);
            var notified = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            mirror.Changed += change => { if (change.PeerId == _one.Id) notified.TrySetResult(); };
            var capable = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            _registry.CapabilitiesChanged += peer => { if (peer == _one.Id) capable.TrySetResult(); };

            TrustedPeer trusted = _trustStore.GetPeer(_one.Id)!;
            Assert.True(await Dialer().DialAsync(trusted, new PeerEndpoint("127.0.0.1", listener.Port), Ct));
            Assert.True(_registry.IsConnected(_one.Id));

            // The phone answered the PC's Hello: full capabilities, as on an inbound session.
            await capable.Task.WaitAsync(Ct);
            Assert.Equal(LinkServer.LocalCapabilities, _registry.GetPeerCapabilities(_one.Id) & LinkServer.LocalCapabilities);
            await UntilAsync(() => phoneSide.IsConnected(PcId), Ct);

            // Phone to PC companion message on the dialled session.
            await phoneSide.SendAsync(PcId, new PeerMessage.Notification("k1", PeerMessage.Notification.KindPosted, Title: "hi"), Ct);
            await notified.Task.WaitAsync(Ct);
            Assert.Equal("hi", Assert.Single(mirror.GetNotifications(_one.Id)).Title);

            // PC to phone transfer over the same session (TransferMailbox), not a second connection.
            string source = Path.Combine(_root, "out.bin");
            byte[] payload = RandomNumberGenerator.GetBytes(80_000);
            await File.WriteAllBytesAsync(source, payload, Ct);
            var client = new LinkClient(PcId, "Test PC") { AllowLoopbackDataPlane = true };
            BulkTransferResult result = await client.SendFilesAsync(_registry, null, trusted, [source], ct: Ct);
            Assert.Equal(payload.Length, result.BytesTransferred);
            Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(_root, "phone-One", "in", "out.bin"), Ct));

            // Already connected: nothing more is dialled or replaced.
            int opened = 0;
            _registry.SessionOpened += _ => Interlocked.Increment(ref opened);
            Assert.False(await Dialer().DialAsync(trusted, new PeerEndpoint("127.0.0.1", listener.Port), Ct));
            Assert.Equal(0, opened);

            // Pausing sync stops the listener and ends the dialled session too.
            await _server.StopAsync();
            await UntilAsync(() => !_registry.IsConnected(_one.Id), Ct);
        }
    }

    /// <summary>
    /// A phone that removed this PC answers a dial with a refusal, or holds the answer while it
    /// asks its user to pair. The dial gives up quickly either way and registers nothing.
    /// </summary>
    [Fact]
    public async Task DialToAPhoneThatNoLongerTrustsThePc_IsRefusedQuickly()
    {
        string root = Path.Combine(_root, "forgot-us");
        var forgetful = new FileTrustStore(Path.Combine(root, "trust"), new byte[32]);
        await using var listener = new LinkServer(_one.Id, _one.Name, forgetful, Path.Combine(root, "in"), _one.PublicKey,
            _ => Task.FromResult(false));
        await listener.StartAsync();
        TrustedPeer trusted = _trustStore.GetPeer(_one.Id)!;
        var endpoint = new PeerEndpoint("127.0.0.1", listener.Port);

        var declined = await Assert.ThrowsAsync<SessionRefusedException>(() =>
            new LinkClient(PcId, "Test PC").OpenPersistentSessionAsync(endpoint, trusted, _server, _registry, Ct));
        Assert.False(declined.IsBusy);

        var prompt = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        listener.PairingAuthorizer = _ => prompt.Task;
        var watch = System.Diagnostics.Stopwatch.StartNew();
        var unanswered = await Assert.ThrowsAsync<SessionRefusedException>(() =>
            new LinkClient(PcId, "Test PC") { PreambleAckTimeout = TimeSpan.FromMilliseconds(300) }
                .OpenPersistentSessionAsync(endpoint, trusted, _server, _registry, Ct));
        Assert.Equal("no-answer", unanswered.Reason);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(10));
        Assert.False(_registry.IsConnected(_one.Id));
        prompt.TrySetResult(false);
    }

    [Fact]
    public async Task SecondSessionFromTheSamePhone_ReplacesTheFirst_ClosesItCleanly_ResetsStateOnce()
    {
        using var mirror = new NotificationMirrorService(_registry);
        int opened = 0, closed = 0, cleared = 0;
        _registry.SessionOpened += peer => { if (peer == _one.Id) Interlocked.Increment(ref opened); };
        _registry.SessionClosed += peer => { if (peer == _one.Id) Interlocked.Increment(ref closed); };
        mirror.Changed += change => { if (change.Kind == NotificationChangeKind.Cleared) Interlocked.Increment(ref cleared); };

        FakePhone first = await ConnectAsync(_one);
        await first.Session.SendAsync(new PeerMessage.Notification("k1", PeerMessage.Notification.KindPosted, Title: "old"), Ct);
        await BarrierAsync(first);
        Assert.Single(mirror.GetNotifications(_one.Id));

        await using FakePhone second = await ConnectAsync(_one);
        Assert.True(_registry.IsConnected(_one.Id));
        Assert.Equal(2, opened);
        Assert.Equal(1, closed);
        Assert.Equal(1, cleared);
        Assert.Empty(mirror.GetNotifications(_one.Id));

        // The replaced socket gets a normal close frame naming the reason, and nothing it sends
        // after that is published.
        await Assert.ThrowsAsync<WebSocketException>(() => first.ReceiveAsync<PeerMessage.Pong>(Ct));
        Assert.Equal(WebSocketCloseStatus.NormalClosure, first.Socket.CloseStatus);
        Assert.Equal("replaced", first.Socket.CloseStatusDescription);
        await first.Socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "ok", Ct);
        await first.Session.DisposeAsync();
        await Task.Delay(300, Ct);

        // The old session ending raised nothing: state was reset once, the new session is live.
        Assert.True(_registry.IsConnected(_one.Id));
        Assert.Equal(1, closed);
        Assert.Equal(1, cleared);
        await second.Session.SendAsync(new PeerMessage.Notification("k2", PeerMessage.Notification.KindPosted, Title: "new"), Ct);
        await BarrierAsync(second);
        Assert.Equal("new", Assert.Single(mirror.GetNotifications(_one.Id)).Title);
    }

    [Fact]
    public async Task PhoneDiallingInWhileThePcsDialIsLive_KeepsExactlyOne_TheNewer()
    {
        (LinkServer listener, PeerSessionRegistry phoneSide) = await StartPhoneListenerAsync(_one);
        await using (listener)
        {
            int closed = 0;
            _registry.SessionClosed += peer => { if (peer == _one.Id) Interlocked.Increment(ref closed); };
            var outboundEnded = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            phoneSide.SessionClosed += peer => { if (peer == PcId) outboundEnded.TrySetResult(); };

            Assert.True(await Dialer().DialAsync(_trustStore.GetPeer(_one.Id)!, new PeerEndpoint("127.0.0.1", listener.Port), Ct));
            await UntilAsync(() => phoneSide.IsConnected(PcId), Ct);

            await using FakePhone inbound = await ConnectAsync(_one);
            // The PC closed its own dialled session cleanly; the phone saw it end.
            await outboundEnded.Task.WaitAsync(Ct);
            Assert.False(phoneSide.IsConnected(PcId));
            Assert.True(_registry.IsConnected(_one.Id));
            Assert.Equal(1, closed);

            // The surviving session is the phone's: the PC's requests reach it.
            await _registry.SendAsync(_one.Id, new PeerMessage.DeviceControl(Ring: true), Ct);
            Assert.True((await inbound.ReceiveAsync<PeerMessage.DeviceControl>(Ct)).Ring);
        }
    }

    [Fact]
    public async Task TwoPhonesAtOnce_NeverMixData()
    {
        using var notifications = new NotificationMirrorService(_registry);
        using var media = new PhoneMediaService(_registry);
        using var status = new DeviceStatusService(_registry, battery: null);
        using var wallpapers = new WallpaperService(_registry, source: null);
        using var messaging = new MessagingService(_registry);
        using var calls = new CallService(_registry);
        using var photos = new PhotoService(_registry, Path.Combine(_root, "photos"));

        await using FakePhone one = await ConnectAsync(_one);
        await using FakePhone two = await ConnectAsync(_two);
        Assert.Equal(2, _registry.GetConnectedPeers().Count);

        async Task ReportAsync(FakePhone phone, int n)
        {
            string tag = n == 1 ? "one" : "two";
            await phone.Session.SendAsync(new PeerMessage.Notification("k", PeerMessage.Notification.KindPosted, Title: tag), Ct);
            await phone.Session.SendAsync(new PeerMessage.MediaState([new MediaSessionInfo("s-" + tag, Title: tag, Playing: true)], Volume: n * 10), Ct);
            await phone.Session.SendAsync(new PeerMessage.DeviceStatus(Battery: n * 11, Ringer: n == 1 ? "silent" : "normal"), Ct);
            await phone.Session.SendAsync(new PeerMessage.Wallpaper(Convert.ToBase64String([0xFF, 0xD8, 0xFF, (byte)n, 0xD9])), Ct);
            await phone.Session.SendAsync(new PeerMessage.SmsThreads([new SmsThread(n, ["+1555010" + n], Snippet: tag, Ts: 1)]), Ct);
            await phone.Session.SendAsync(new PeerMessage.CallState("call-" + tag, "ringing", "in", "+1555020" + n), Ct);
            await phone.Session.SendAsync(new PeerMessage.Photos([new PhotoInfo(n, tag + ".jpg", 1)]), Ct);
            await BarrierAsync(phone);
        }

        // Interleaved, as two real phones would be.
        await Task.WhenAll(ReportAsync(one, 1), ReportAsync(two, 2));

        foreach ((Phone phone, int n, string tag) in new[] { (_one, 1, "one"), (_two, 2, "two") })
        {
            Assert.Equal(tag, Assert.Single(notifications.GetNotifications(phone.Id)).Title);
            Assert.Equal("s-" + tag, Assert.Single(media.GetState(phone.Id)!.State.Sessions).Id);
            Assert.Equal(n * 10, media.GetState(phone.Id)!.State.Volume);
            Assert.Equal(n * 11, status.GetPhoneStatus(phone.Id)!.Battery);
            Assert.Equal((byte)n, wallpapers.GetPhoneWallpaper(phone.Id)![3]);
            Assert.Equal(n, Assert.Single(messaging.GetThreads(phone.Id)).ThreadId);
            Assert.Equal("call-" + tag, calls.GetCurrentCall(phone.Id)!.CallId);
            Assert.Equal(n, Assert.Single(photos.GetPhotos(phone.Id)).Id);
        }

        // PC-to-phone requests go to the phone they name, and only to it.
        await status.SendControlAsync(_one.Id, ring: true, ct: Ct);
        await status.SendControlAsync(_two.Id, dnd: true, ct: Ct);
        PeerMessage.DeviceControl toOne = await one.ReceiveAsync<PeerMessage.DeviceControl>(Ct);
        PeerMessage.DeviceControl toTwo = await two.ReceiveAsync<PeerMessage.DeviceControl>(Ct);
        Assert.True(toOne.Ring);
        Assert.Null(toOne.Dnd);
        Assert.True(toTwo.Dnd);
        Assert.Null(toTwo.Ring);

        // A transfer reply from the other phone never reaches this phone's transfer.
        using (IOutboundTransferChannel channel = _registry.OpenOutboundTransfer(_one.Id, "t-1"))
        {
            await two.Session.SendAsync(new PeerMessage.TransferDecision("t-1", true), Ct);
            await BarrierAsync(two);
            await Assert.ThrowsAsync<TimeoutException>(() => channel.WaitForDecisionAsync(TimeSpan.FromMilliseconds(200), Ct));
            await one.Session.SendAsync(new PeerMessage.TransferDecision("t-1", false, "declined"), Ct);
            Assert.False((await channel.WaitForDecisionAsync(TimeSpan.FromSeconds(10), Ct)).Accept);
        }

        // One phone leaving clears only its own state.
        var gone = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _registry.SessionClosed += peer => { if (peer == _one.Id) gone.TrySetResult(); };
        await one.Socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", Ct);
        await gone.Task.WaitAsync(Ct);
        Assert.Empty(notifications.GetNotifications(_one.Id));
        Assert.Null(media.GetState(_one.Id));
        Assert.Null(status.GetPhoneStatus(_one.Id));
        Assert.Null(wallpapers.GetPhoneWallpaper(_one.Id));
        Assert.Null(calls.GetCurrentCall(_one.Id));
        Assert.Equal("two", Assert.Single(notifications.GetNotifications(_two.Id)).Title);
        Assert.Equal(22, status.GetPhoneStatus(_two.Id)!.Battery);
        Assert.NotNull(wallpapers.GetPhoneWallpaper(_two.Id));
        Assert.Equal("call-two", calls.GetCurrentCall(_two.Id)!.CallId);
        Assert.Single(photos.GetPhotos(_two.Id));
        Assert.Single(messaging.GetThreads(_two.Id));
    }
}
