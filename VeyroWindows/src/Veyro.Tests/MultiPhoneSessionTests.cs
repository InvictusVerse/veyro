using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Calls;
using Veyro.Core.Crypto;
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
        var v3dial = new V3Dialer(phone.Id);
        LinkPreambleAck v3ack = await v3dial.SendPreambleAsync(socket, phone.Name, Ct);
        Assert.Equal(LinkPreambleAck.ModeSession, v3ack.Mode);
        var fake = new FakePhone(socket, new SecureLinkSession(socket, v3dial.SessionKeys(PcId, phone.Secret)));
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
    /// A phone that removed this PC answers a dial with a refusal at once. Since ADR-058 there is
    /// no PIN prompt to hold the answer. The dial gives up and registers nothing.
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
        Assert.Equal("not-paired", declined.Reason);

        // ADR-058 removed the PIN prompt: nothing holds the answer and no prompt is shown.
        bool prompted = false;
        listener.PairingAuthorizer = _ => { prompted = true; return Task.FromResult(false); };
        var again = await Assert.ThrowsAsync<SessionRefusedException>(() =>
            new LinkClient(PcId, "Test PC").OpenPersistentSessionAsync(endpoint, trusted, _server, _registry, Ct));
        Assert.Equal("not-paired", again.Reason);
        Assert.False(prompted);
        Assert.False(_registry.IsConnected(_one.Id));
    }

    /// <summary>
    /// ADR-058 establishment: a dialler that repeats a live phone's preamble but cannot produce an
    /// authenticated first frame never replaces the live session, whether it sends a forged frame
    /// or nothing at all.
    /// </summary>
    [Fact]
    public async Task UnauthenticatedDialWithALivePhonesId_DoesNotReplaceItsSession()
    {
        _server.FirstFrameTimeout = TimeSpan.FromMilliseconds(400);
        int opened = 0, closed = 0;
        _registry.SessionOpened += peer => { if (peer == _one.Id) Interlocked.Increment(ref opened); };
        _registry.SessionClosed += peer => { if (peer == _one.Id) Interlocked.Increment(ref closed); };
        await using FakePhone live = await ConnectAsync(_one);
        Assert.Equal(1, opened);

        // A forged first frame under a key without the pairing secret.
        using (var forged = new ClientWebSocket())
        {
            await forged.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
            using var dialer = new V3Dialer(_one.Id);
            Assert.Equal(LinkPreambleAck.ModeSession, (await dialer.SendPreambleAsync(forged, _one.Name, Ct)).Mode);
            await using var forger = new SecureLinkSession(forged, dialer.SessionKeys(PcId, RandomNumberGenerator.GetBytes(32)));
            await forger.SendAsync(new PeerMessage.Hello(_one.Id, _one.Name, "android", AllCompanion), Ct);
            await Assert.ThrowsAnyAsync<Exception>(() => forger.ReceiveAsync(Ct));
        }

        // A preamble and then silence: the first-frame timer closes it (FR-C3).
        using (var silent = new ClientWebSocket())
        {
            await silent.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
            using var dialer = new V3Dialer(_one.Id);
            Assert.Equal(LinkPreambleAck.ModeSession, (await dialer.SendPreambleAsync(silent, _one.Name, Ct)).Mode);
            byte[] buffer = new byte[1024];
            await Assert.ThrowsAnyAsync<Exception>(async () =>
            {
                WebSocketReceiveResult result = await silent.ReceiveAsync(buffer, Ct);
                if (result.MessageType == WebSocketMessageType.Close) throw new WebSocketException("closed");
            });
        }

        await BarrierAsync(live);
        Assert.True(_registry.IsConnected(_one.Id));
        Assert.Equal(1, opened);
        Assert.Equal(0, closed);
    }

    /// <summary>
    /// A phone played on a raw socket (review issue 3): completes the WebSocket upgrade, answers
    /// the PC's preamble in session mode with this connection's real keys, then sends one sealed
    /// frame built by <paramref name="firstFrame"/> and holds the socket until the PC closes it.
    /// </summary>
    private static async Task RawPhoneAnswersDialAsync(TcpListener listener, Phone phone, Func<SessionSealer, string> firstFrame, CancellationToken ct)
    {
        using TcpClient client = await listener.AcceptTcpClientAsync(ct);
        NetworkStream stream = client.GetStream();
        var header = new List<byte>();
        while (!(header.Count >= 4 && header[^4] == '\r' && header[^3] == '\n' && header[^2] == '\r' && header[^1] == '\n'))
        {
            int next = stream.ReadByte();
            if (next < 0) return;
            header.Add((byte)next);
        }
        string key = Encoding.ASCII.GetString(header.ToArray()).Split("\r\n")
            .First(line => line.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase))["Sec-WebSocket-Key:".Length..].Trim();
        string accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
        await stream.WriteAsync(Encoding.ASCII.GetBytes(
            $"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n"), ct);
        using WebSocket ws = WebSocket.CreateFromStream(stream, new WebSocketCreationOptions { IsServer = true });

        byte[] buffer = new byte[64 * 1024];
        WebSocketReceiveResult result = await ws.ReceiveAsync(buffer, ct);
        byte[] preambleBytes = buffer.AsSpan(0, result.Count).ToArray();
        LinkPreamble preamble = PeerMessageJson.Decode<LinkPreamble>(Encoding.UTF8.GetString(preambleBytes))!;
        using EphemeralKey eph = EphemeralKey.Generate();
        byte[] ackBytes = Encoding.UTF8.GetBytes(PeerMessageJson.Encode(new LinkPreambleAck(LinkPreambleAck.ModeSession) with
        {
            Cn = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)),
            Epk = Convert.ToBase64String(eph.PublicKey)
        }));
        await ws.SendAsync(ackBytes, WebSocketMessageType.Text, true, ct);
        using LinkKeys keys = ConnectionKeySchedule.Session(LinkRole.Responder, preamble.Id, phone.Id,
            ConnectionKeySchedule.Transcript(preambleBytes, ackBytes), eph.Agree(FrameCrypto.DecodeKey32(preamble.Epk)!), phone.Secret);
        using var sealer = new SessionSealer(keys.SendKey, phone.Id, FrameCrypto.DirR2I);
        await ws.SendAsync(Encoding.UTF8.GetBytes(firstFrame(sealer)), WebSocketMessageType.Text, true, ct);
        try
        {
            while ((await ws.ReceiveAsync(buffer, ct)).MessageType != WebSocketMessageType.Close) { }
        }
        catch (Exception error) when (error is WebSocketException or IOException or OperationCanceledException) { }
    }

    /// <summary>
    /// Review issue 3 (ADR-058): a dialled phone whose first frame authenticates but is malformed
    /// (InvalidDataException on the PC) ends the dial with false; the dial does not hang.
    /// </summary>
    [Fact]
    public async Task DialledPhoneWhoseFirstFrameIsMalformed_EndsTheDialWithFalse()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            TrustedPeer trusted = _trustStore.GetPeer(_one.Id)!;
            Task phone = RawPhoneAnswersDialAsync(listener, _one, sealer => sealer.Seal(
                $"{{\"v\":2,\"msgId\":\"m\",\"from\":\"{_one.Id}\",\"seq\":1,\"ts\":1,\"payload\":{{\"t\":\"hello\"}}}}"), Ct);
            var endpoint = new PeerEndpoint("127.0.0.1", ((IPEndPoint)listener.LocalEndpoint).Port);
            bool registered = await new LinkClient(PcId, "Test PC")
                .OpenPersistentSessionAsync(endpoint, trusted, _server, _registry, Ct)
                .WaitAsync(TimeSpan.FromSeconds(15));
            Assert.False(registered);
            Assert.False(_registry.IsConnected(_one.Id));
            await phone.WaitAsync(TimeSpan.FromSeconds(15));
        }
        finally
        {
            listener.Stop();
        }
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
