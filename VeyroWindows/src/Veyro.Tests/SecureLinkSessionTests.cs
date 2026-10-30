using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Crypto;
using Veyro.Core.Link;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// The v3 session at the connection level (ADR-058, AC 1-3, 13, 20): fail closed, establishment,
/// the exporter, the pairing switch, replay across connections and the AEAD availability seam.
/// </summary>
public sealed class SecureLinkSessionTests
{
    private const string Phone = "PHONE";
    private const string Pc = "PC";
    private static readonly byte[] Secret = Enumerable.Repeat((byte)42, 32).ToArray();
    private static CancellationToken Ct => new CancellationTokenSource(TimeSpan.FromSeconds(20)).Token;

    /// <summary>Two WebSockets joined over loopback TCP: (client end, server end).</summary>
    internal static async Task<(WebSocket A, WebSocket B)> SocketPairAsync()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var client = new TcpClient();
        Task connect = client.ConnectAsync(IPAddress.Loopback, ((IPEndPoint)listener.LocalEndpoint).Port);
        TcpClient server = await listener.AcceptTcpClientAsync();
        await connect;
        listener.Stop();
        return (WebSocket.CreateFromStream(client.GetStream(), new WebSocketCreationOptions { IsServer = false }),
                WebSocket.CreateFromStream(server.GetStream(), new WebSocketCreationOptions { IsServer = true }));
    }

    private static async Task<(SecureLinkSession Phone, SecureLinkSession Pc)> EstablishedPairAsync()
    {
        var (a, b) = await SocketPairAsync();
        var (kPhone, kPc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        var phone = new SecureLinkSession(a, kPhone);
        var pc = new SecureLinkSession(b, kPc);
        await phone.SendAsync(new PeerMessage.Hello(Phone, "Phone", "android"), Ct);
        await pc.SendAsync(new PeerMessage.HelloAck(Pc, "PC", "windows"), Ct);
        Assert.IsType<PeerMessage.Hello>((await pc.ReceiveAsync(Ct))!.Envelope.Payload);
        Assert.IsType<PeerMessage.HelloAck>((await phone.ReceiveAsync(Ct))!.Envelope.Payload);
        return (phone, pc);
    }

    /// <summary>A raw sender for crafting frames: seals arbitrary envelope JSON with the next counter.</summary>
    private static async Task SendRawAsync(WebSocket socket, SessionSealer sealer, string envelopeJson)
        => await socket.SendAsync(Encoding.UTF8.GetBytes(sealer.Seal(envelopeJson)), WebSocketMessageType.Text, true, Ct);

    [Fact]
    public async Task EstablishedSessionsCarryFramesAndExportTheSameKeys()
    {
        var (phone, pc) = await EstablishedPairAsync();
        Assert.True(phone.IsEstablished && pc.IsEstablished);
        Assert.Equal(phone.ExportKey("veyro-vtc3", [1], 64), pc.ExportKey("veyro-vtc3", [1], 64));
        Assert.NotEqual(phone.ExportKey("veyro-vtc3", [1], 32), phone.ExportKey("veyro-screen-v3", [1], 32));
        Assert.Throws<ArgumentException>(() => phone.ExportKey("", [], 32));
        Assert.Throws<ArgumentException>(() => phone.ExportKey("veyro-vtc3", [], 0));
        await phone.DisposeAsync();
        Assert.Throws<InvalidOperationException>(() => phone.ExportKey("veyro-vtc3", [], 32));
        await pc.DisposeAsync();
    }

    [Fact]
    public async Task TheExporterRefusesBeforeEstablishment()
    {
        var (a, b) = await SocketPairAsync();
        var (kPhone, _) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        await using var phone = new SecureLinkSession(a, kPhone);
        Assert.False(phone.IsEstablished);
        Assert.Throws<InvalidOperationException>(() => phone.ExportKey("veyro-vtc3", [], 32));
        b.Dispose();
    }

    [Fact]
    public async Task ARecordedCommandReplayedOnANewConnectionIsRefused()
    {
        // Connection 1: the phone sends a lock request; the test records the sealed text.
        var (k1Phone, _) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        var recorder = new SessionSealer(k1Phone.SendKey, Phone, FrameCrypto.DirI2R);
        string hello = recorder.Seal(Envelope(1, "{\"t\":\"hello\",\"deviceId\":\"PHONE\",\"deviceName\":\"P\",\"platform\":\"android\"}"));
        string pcLock = recorder.Seal(Envelope(2, "{\"t\":\"pcLock\"}"));

        // Connection 2: same pairing secret, new ephemeral keys. The recording is replayed.
        var (a, b) = await SocketPairAsync();
        var (_, k2Pc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        await using var pc = new SecureLinkSession(b, k2Pc);
        await a.SendAsync(Encoding.UTF8.GetBytes(hello), WebSocketMessageType.Text, true, Ct);
        await a.SendAsync(Encoding.UTF8.GetBytes(pcLock), WebSocketMessageType.Text, true, Ct);
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => pc.ReceiveAsync(Ct));
        Assert.False(pc.IsEstablished);
        await Assert.ThrowsAsync<InvalidOperationException>(() => pc.ReceiveAsync(Ct));
        a.Dispose();
    }

    private static string Envelope(long seq, string payload, int v = 3, string from = Phone)
        => $"{{\"v\":{v},\"msgId\":\"m{seq}\",\"from\":\"{from}\",\"seq\":{seq},\"ts\":1,\"payload\":{payload}}}";

    [Fact]
    public async Task ReplayWithinTheConnectionFailsAndTheSessionStaysFailed()
    {
        var (a, b) = await SocketPairAsync();
        var (kPhone, kPc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        await using var pc = new SecureLinkSession(b, kPc);
        string ping = new SessionSealer(kPhone.SendKey, Phone, FrameCrypto.DirI2R).Seal(Envelope(1, "{\"t\":\"ping\",\"nonce\":\"n\"}"));
        await a.SendAsync(Encoding.UTF8.GetBytes(ping), WebSocketMessageType.Text, true, Ct);
        await a.SendAsync(Encoding.UTF8.GetBytes(ping), WebSocketMessageType.Text, true, Ct);
        Assert.IsType<PeerMessage.Ping>((await pc.ReceiveAsync(Ct))!.Envelope.Payload);
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => pc.ReceiveAsync(Ct));
        await Assert.ThrowsAsync<InvalidOperationException>(() => pc.SendAsync(new PeerMessage.Pong("n"), Ct));
        a.Dispose();
    }

    public static IEnumerable<object[]> MalformedEnvelopes() =>
    [
        [Envelope(1, "{\"t\":\"ping\",\"nonce\":\"n\"}", v: 2)],
        [Envelope(5, "{\"t\":\"ping\",\"nonce\":\"n\"}")],
        [Envelope(1, "{\"t\":\"ping\",\"nonce\":\"n\"}", from: "OTHER")],
        [Envelope(1, "{\"t\":\"pairOk\",\"deviceId\":\"PC\",\"deviceName\":\"x\",\"platform\":\"w\",\"sessionSecretB64\":\"AA==\"}")],
        [Envelope(1, "{\"t\":\"hello\"}")],
    ];

    [Theory]
    [MemberData(nameof(MalformedEnvelopes))]
    public async Task ABadEnvelopeOrADisallowedTypeClosesTheSession(string envelope)
    {
        var (a, b) = await SocketPairAsync();
        var (kPhone, kPc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        await using var pc = new SecureLinkSession(b, kPc);
        await SendRawAsync(a, new SessionSealer(kPhone.SendKey, Phone, FrameCrypto.DirI2R), envelope);
        await Assert.ThrowsAsync<InvalidDataException>(() => pc.ReceiveAsync(Ct));
        a.Dispose();
    }

    [Fact]
    public async Task ABinaryFrameOrAWrongSenderIsRefused()
    {
        var (a, b) = await SocketPairAsync();
        var (kPhone, kPc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        await using var pc = new SecureLinkSession(b, kPc);
        await a.SendAsync(new byte[] { 1, 2, 3 }, WebSocketMessageType.Binary, true, Ct);
        await Assert.ThrowsAsync<InvalidDataException>(() => pc.ReceiveAsync(Ct));

        var (c, d) = await SocketPairAsync();
        var (k2Phone, k2Pc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        await using var pc2 = new SecureLinkSession(d, k2Pc);
        string wire = new SessionSealer(k2Phone.SendKey, Phone, FrameCrypto.DirI2R).Seal(Envelope(1, "{\"t\":\"ping\",\"nonce\":\"n\"}"))
            .Replace("\"f\":\"PHONE\"", "\"f\":\"OTHER\"");
        await c.SendAsync(Encoding.UTF8.GetBytes(wire), WebSocketMessageType.Text, true, Ct);
        await Assert.ThrowsAsync<InvalidDataException>(() => pc2.ReceiveAsync(Ct));
        a.Dispose();
        c.Dispose();
    }

    [Fact]
    public async Task AnAuthenticatedUnknownTypeIsSkippedAndItsCounterCounts()
    {
        var (a, b) = await SocketPairAsync();
        var (kPhone, kPc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        await using var pc = new SecureLinkSession(b, kPc);
        var sealer = new SessionSealer(kPhone.SendKey, Phone, FrameCrypto.DirI2R);
        await SendRawAsync(a, sealer, Envelope(1, "{\"t\":\"futureThing\",\"x\":1}"));
        await SendRawAsync(a, sealer, Envelope(2, "{\"t\":\"ping\",\"nonce\":\"n\"}"));
        Assert.IsType<PeerMessage.Ping>((await pc.ReceiveAsync(Ct))!.Envelope.Payload);
        Assert.True(pc.IsEstablished);
        a.Dispose();
    }

    [Fact]
    public async Task TwoThousandFramesFromTwoConcurrentSendersAllOpenInOrder()
    {
        var (phone, pc) = await EstablishedPairAsync();
        int sent = 0;
        Task send = Task.WhenAll(Enumerable.Range(0, 2).Select(s => Task.Run(async () =>
        {
            for (int i = 0; i < 1000; i++) { await phone.SendAsync(new PeerMessage.Ping($"s{s}-{i}"), Ct); Interlocked.Increment(ref sent); }
        })));
        int received = 0;
        Task receive = Task.Run(async () =>
        {
            while (received < 2000)
            {
                if ((await pc.ReceiveAsync(Ct))!.Envelope.Payload is PeerMessage.Ping) received++;
            }
        });
        Task all = Task.WhenAll(send, receive);
        if (await Task.WhenAny(all, Task.Delay(TimeSpan.FromSeconds(30))) != all)
            Assert.Fail($"stalled: sent={Volatile.Read(ref sent)} received={received} send={send.Status} receive={receive.Status} {receive.Exception?.InnerException?.GetType().Name} {send.Exception?.InnerException?.GetType().Name}");
        await all;
        Assert.Equal(2000, received);
        // Both ends close together: each CloseAsync waits for the other side's close frame.
        Task closing = Task.WhenAll(phone.DisposeAsync().AsTask(), pc.DisposeAsync().AsTask());
        Assert.True(await Task.WhenAny(closing, Task.Delay(TimeSpan.FromSeconds(30))) == closing, "close handshake stalled");
    }

    [Fact]
    public async Task ThePairingSwitchIsRaceFreeAndBothEndsExportTheSameKeys()
    {
        var (a, b) = await SocketPairAsync();
        using EphemeralKey eI = EphemeralKey.Generate();
        using EphemeralKey eR = EphemeralKey.Generate();
        byte[] transcript = ConnectionKeySchedule.Transcript("p"u8, "a"u8);
        byte[] staticKey = Enumerable.Repeat((byte)3, 32).ToArray();
        byte[] nA = new byte[32], nB = Enumerable.Repeat((byte)1, 32).ToArray();
        await using var phone = new SecureLinkSession(a, ConnectionKeySchedule.PairConfirm(LinkRole.Initiator, Phone, Pc, transcript, nA, nB, eI.Agree(eR.PublicKey), staticKey));
        await using var pc = new SecureLinkSession(b, ConnectionKeySchedule.PairConfirm(LinkRole.Responder, Phone, Pc, transcript, nA, nB, eR.Agree(eI.PublicKey), staticKey));

        await phone.SendAsync(new PeerMessage.PairHello(Phone, "Phone", "android", "1"), Ct);
        Assert.IsType<PeerMessage.PairHello>((await pc.ReceiveAsync(Ct))!.Envelope.Payload);
        Task<SecureLinkMessage?> pending = pc.ReceiveAsync(Ct); // outstanding across the switch, as in RunConfirmPairingAsync
        byte[] secret = RandomNumberGenerator.GetBytes(32);
        await pc.SendPairOkAndSwitchAsync(new PeerMessage.PairOk(Pc, "PC", "windows", Convert.ToBase64String(secret)), (byte[])secret.Clone(), Ct);
        var ok = Assert.IsType<PeerMessage.PairOk>((await phone.ReceiveAsync(Ct))!.Envelope.Payload);
        await phone.SwitchToSessionAsync(Convert.FromBase64String(ok.SessionSecretB64), Ct);
        await phone.SendAsync(new PeerMessage.Hello(Phone, "Phone", "android"), Ct);
        Assert.IsType<PeerMessage.Hello>((await pending)!.Envelope.Payload);
        Assert.True(pc.IsEstablished);
        await pc.SendAsync(new PeerMessage.HelloAck(Pc, "PC", "windows"), Ct);
        Assert.IsType<PeerMessage.HelloAck>((await phone.ReceiveAsync(Ct))!.Envelope.Payload);
        Assert.Equal(phone.ExportKey("veyro-vtc3", [7], 32), pc.ExportKey("veyro-vtc3", [7], 32));
    }

    [Fact]
    public async Task ThePairingResponderAcceptsOnlyPairHelloFirst()
    {
        var (a, b) = await SocketPairAsync();
        using EphemeralKey eI = EphemeralKey.Generate();
        using EphemeralKey eR = EphemeralKey.Generate();
        byte[] transcript = ConnectionKeySchedule.Transcript("p"u8, "a"u8);
        LinkKeys bootI = ConnectionKeySchedule.PairConfirm(LinkRole.Initiator, Phone, Pc, transcript, new byte[32], new byte[32], eI.Agree(eR.PublicKey), new byte[32]);
        await using var pc = new SecureLinkSession(b, ConnectionKeySchedule.PairConfirm(LinkRole.Responder, Phone, Pc, transcript, new byte[32], new byte[32], eR.Agree(eI.PublicKey), new byte[32]));
        await SendRawAsync(a, new SessionSealer(bootI.SendKey, Phone, FrameCrypto.DirI2R), Envelope(1, "{\"t\":\"ping\",\"nonce\":\"n\"}"));
        await Assert.ThrowsAsync<InvalidDataException>(() => pc.ReceiveAsync(Ct));
        a.Dispose();
    }

    /// <summary>
    /// Review finding 2 (ADR-058): a control frame over the 24 MiB limit fails only that message.
    /// The size is known before sealing, so no counter is spent and the next frame still opens.
    /// </summary>
    [Fact]
    public async Task AnOversizedControlFrameFailsOnlyThatMessage()
    {
        var (phone, pc) = await EstablishedPairAsync();
        string huge = new('a', SecureLinkSession.MaxFrameSizeBytes);
        await Assert.ThrowsAsync<InvalidOperationException>(() => phone.SendAsync(new PeerMessage.Ping(huge), Ct));
        await phone.SendAsync(new PeerMessage.Ping("after"), Ct);
        var ping = Assert.IsType<PeerMessage.Ping>((await pc.ReceiveAsync(Ct))!.Envelope.Payload);
        Assert.Equal("after", ping.Nonce);
        await phone.DisposeAsync();
        await pc.DisposeAsync();
    }

    /// <summary>The size check before sealing (review issue 1) predicts the wire length exactly.</summary>
    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(1000)]
    [InlineData(65537)]
    public void TheWireLengthIsKnownBeforeSealing(int plainBytes)
    {
        var (k, _) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        using var sealer = new SessionSealer(k.SendKey, Phone, FrameCrypto.DirI2R);
        long predicted = sealer.WireLength(plainBytes);
        Assert.Equal(predicted, Encoding.UTF8.GetByteCount(sealer.Seal(new string('x', plainBytes))));
    }

    /// <summary>Delegates to a real socket; <see cref="FailNextSend"/> makes the next send fail without writing.</summary>
    private sealed class FlakySendSocket(WebSocket inner) : WebSocket
    {
        private readonly TaskCompletionSource _aborted = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public volatile bool FailNextSend;
        /// <summary>Sends hang until the socket is aborted, like a write to a peer that stopped reading.</summary>
        public volatile bool BlockSends;
        public int Writes;
        public bool WasAborted => _aborted.Task.IsCompleted;
        public override WebSocketCloseStatus? CloseStatus => inner.CloseStatus;
        public override string? CloseStatusDescription => inner.CloseStatusDescription;
        public override WebSocketState State => inner.State;
        public override string? SubProtocol => inner.SubProtocol;
        public override void Abort()
        {
            _aborted.TrySetResult();
            inner.Abort();
        }
        public override Task CloseAsync(WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken)
            => inner.CloseAsync(closeStatus, statusDescription, cancellationToken);
        public override Task CloseOutputAsync(WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken)
            => inner.CloseOutputAsync(closeStatus, statusDescription, cancellationToken);
        public override void Dispose() => inner.Dispose();
        public override Task<WebSocketReceiveResult> ReceiveAsync(ArraySegment<byte> buffer, CancellationToken cancellationToken)
            => inner.ReceiveAsync(buffer, cancellationToken);
        public override Task SendAsync(ArraySegment<byte> buffer, WebSocketMessageType messageType, bool endOfMessage, CancellationToken cancellationToken)
        {
            if (FailNextSend)
            {
                FailNextSend = false;
                throw new IOException("simulated socket failure after sealing");
            }
            if (BlockSends) return HangUntilAbortedAsync();
            Interlocked.Increment(ref Writes);
            return inner.SendAsync(buffer, messageType, endOfMessage, cancellationToken);
        }

        private async Task HangUntilAbortedAsync()
        {
            await _aborted.Task;
            throw new WebSocketException("aborted");
        }
    }

    /// <summary>
    /// Review issue 1 (ADR-058): once a frame is sealed its counter is spent. A write that then
    /// fails must fail the session, not leave it alive one counter ahead of the peer.
    /// </summary>
    [Fact]
    public async Task AWriteThatFailsAfterSealingFailsTheSession()
    {
        var (a, b) = await SocketPairAsync();
        var flaky = new FlakySendSocket(a);
        var (kPhone, kPc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        await using var phone = new SecureLinkSession(flaky, kPhone);
        await using var pc = new SecureLinkSession(b, kPc);
        await phone.SendAsync(new PeerMessage.Hello(Phone, "Phone", "android"), Ct);
        Assert.IsType<PeerMessage.Hello>((await pc.ReceiveAsync(Ct))!.Envelope.Payload);

        flaky.FailNextSend = true;
        await Assert.ThrowsAsync<IOException>(() => phone.SendAsync(new PeerMessage.Ping("lost"), Ct));
        int writes = flaky.Writes;
        await Assert.ThrowsAsync<InvalidOperationException>(() => phone.SendAsync(new PeerMessage.Ping("next"), Ct));
        Assert.Equal(writes, flaky.Writes);
    }

    /// <summary>
    /// Review issue 8: <see cref="SecureLinkSession.CloseOutputAsync"/> (the replaced-session
    /// close) is bounded even while a send holds the gate on a peer that stopped reading.
    /// </summary>
    [Fact]
    public async Task CloseOutputIsBoundedWhileASendHoldsTheGate()
    {
        var (a, b) = await SocketPairAsync();
        var socket = new FlakySendSocket(a) { BlockSends = true };
        var (kPhone, _) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        var phone = new SecureLinkSession(socket, kPhone);
        // The send hangs on the socket and holds the send gate, with no cancellation of its own.
        Task blocked = phone.SendAsync(new PeerMessage.Ping("stuck"), CancellationToken.None);
        await Task.Delay(200);
        Assert.False(blocked.IsCompleted);
        var clock = Stopwatch.StartNew();
        await phone.CloseOutputAsync("replaced").WaitAsync(TimeSpan.FromSeconds(15));
        clock.Stop();
        Assert.True(clock.Elapsed < SecureLinkSession.CloseHandshakeTimeout + TimeSpan.FromSeconds(3), $"close took {clock.Elapsed}");
        Assert.True(socket.WasAborted, "the socket is aborted when the close cannot be sent in time");
        await Assert.ThrowsAnyAsync<Exception>(() => blocked);
        await phone.DisposeAsync();
        b.Dispose();
    }

    /// <summary>A socket whose close handshake throws, as one with a receive still in flight may.</summary>
    private sealed class CloseThrowsSocket : WebSocket
    {
        public bool Disposed { get; private set; }
        public override WebSocketCloseStatus? CloseStatus => null;
        public override string? CloseStatusDescription => null;
        public override WebSocketState State => WebSocketState.Open;
        public override string? SubProtocol => null;
        public override void Abort() { }
        public override Task CloseAsync(WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken)
            => throw new InvalidOperationException("There is already one outstanding 'ReceiveAsync' call.");
        public override Task CloseOutputAsync(WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken)
            => throw new InvalidOperationException("There is already one outstanding 'ReceiveAsync' call.");
        public override void Dispose() => Disposed = true;
        public override Task<WebSocketReceiveResult> ReceiveAsync(ArraySegment<byte> buffer, CancellationToken cancellationToken)
            => throw new NotSupportedException();
        public override Task SendAsync(ArraySegment<byte> buffer, WebSocketMessageType messageType, bool endOfMessage, CancellationToken cancellationToken)
            => throw new NotSupportedException();
    }

    /// <summary>Review finding 5: a close handshake that throws still disposes the socket and wipes every key.</summary>
    [Fact]
    public async Task DisposeWipesEveryKeyEvenWhenTheCloseHandshakeThrows()
    {
        var (kPhone, kPc) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        var socket = new CloseThrowsSocket();
        var session = new SecureLinkSession(socket, kPhone);
        await session.DisposeAsync();
        Assert.True(socket.Disposed);
        foreach (byte[] key in new[] { kPhone.SendKey, kPhone.RecvKey, kPhone.ExpSecret! })
            Assert.All(key, b => Assert.Equal(0, b));
        kPc.Dispose();
    }

    /// <summary>
    /// Review findings 6 and 15: a peer that never answers the close frame holds
    /// <see cref="SecureLinkSession.DisposeAsync"/> for <see cref="SecureLinkSession.CloseHandshakeTimeout"/>,
    /// and the socket ends aborted.
    /// </summary>
    [Fact]
    public async Task ACloseThePeerNeverAnswersEndsAfterTheHandshakeTimeoutAndAbortsTheSocket()
    {
        var (a, b) = await SocketPairAsync();
        var (kPhone, _) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        var phone = new SecureLinkSession(a, kPhone);
        var clock = Stopwatch.StartNew();
        // b never reads, so it never answers the close frame.
        await phone.DisposeAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(15));
        clock.Stop();
        Assert.InRange(clock.Elapsed,
            SecureLinkSession.CloseHandshakeTimeout - TimeSpan.FromMilliseconds(250),
            SecureLinkSession.CloseHandshakeTimeout + TimeSpan.FromSeconds(3));
        Assert.Equal(WebSocketState.Aborted, a.State);
        b.Dispose();
    }

    [Fact]
    public async Task WithoutChaCha20Poly1305TheSessionRefusesToStart()
    {
        var (a, b) = await SocketPairAsync();
        var (kPhone, _) = TestLinkKeys.SessionPair(Phone, Pc, Secret);
        Assert.Throws<PlatformNotSupportedException>(() => new SecureLinkSession(a, kPhone, aeadSupported: () => false));
        a.Dispose();
        b.Dispose();
    }
}
