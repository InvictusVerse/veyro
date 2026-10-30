using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Veyro.Core.Crypto;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Confirm-code pairing derivations against protocol/testvectors/pair-confirm.json
/// (04-PROTOCOL.md 4.3, ADR-031). The Android suite asserts the same file.
/// </summary>
public sealed class PairConfirmVectorTests
{
    private static JsonElement Vector()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "protocol", "testvectors", "pair-confirm.json")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors/pair-confirm.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(dir!.FullName, "protocol", "testvectors", "pair-confirm.json")));
        return doc.RootElement.Clone();
    }

    private static byte[] Hex(JsonElement e, string name) => Convert.FromHexString(e.GetProperty(name).GetString()!);
    private static string Str(JsonElement e, string name) => e.GetProperty(name).GetString()!;
    private static string ToHex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

    [Fact]
    public void AllExpectedValuesMatch()
    {
        JsonElement v = Vector();
        JsonElement input = v.GetProperty("input");
        JsonElement expected = v.GetProperty("expected");
        byte[] skA = Hex(input, "privateKeyAHex");
        byte[] skB = Hex(input, "privateKeyBHex");
        byte[] nA = Hex(input, "nonceAHex");
        byte[] nB = Hex(input, "nonceBHex");

        byte[] pkA = X25519Crypto.FromSeed(skA).PublicKey;
        byte[] pkB = X25519Crypto.FromSeed(skB).PublicKey;
        Assert.Equal(Str(expected, "publicKeyAHex"), ToHex(pkA));
        Assert.Equal(Str(expected, "publicKeyBHex"), ToHex(pkB));
        Assert.Equal(Str(expected, "publicKeyABase64"), Convert.ToBase64String(pkA));
        Assert.Equal(Str(expected, "publicKeyBBase64"), Convert.ToBase64String(pkB));

        string idA = DeviceId.FromPublicKey(pkA).Value;
        string idB = DeviceId.FromPublicKey(pkB).Value;
        Assert.Equal(Str(expected, "deviceIdA"), idA);
        Assert.Equal(Str(expected, "deviceIdB"), idB);

        byte[] shared = X25519Crypto.SharedSecret(skA, pkB);
        Assert.Equal(Str(expected, "sharedSecretHex"), ToHex(shared));
        Assert.Equal(shared, X25519Crypto.SharedSecret(skB, pkA));

        byte[] commit = PairConfirm.Commitment(pkB, pkA, nB);
        Assert.Equal(Str(expected, "commitHex"), ToHex(commit));
        Assert.Equal(Str(expected, "commitBase64"), Convert.ToBase64String(commit));
        Assert.True(PairConfirm.VerifyCommitment(commit, pkB, pkA, nB));

        string code = PairConfirm.Code(pkA, pkB, nA, nB);
        Assert.Equal(Str(expected, "code"), code);
        Assert.Equal(Str(expected, "codeDisplay"), PairConfirm.FormatCode(code));
        Assert.Equal(Str(expected, "codeDigestHex"),
            ToHex(SHA256.HashData([.. Encoding.ASCII.GetBytes("veyro-sas-v1"), .. pkA, .. pkB, .. nA, .. nB])));

        // ADR-058: bootstrapKeyHex is the retired v2 key. The v3 static key is pinned by
        // link/pairing-schedule-v3.json (confirm: transcriptHex, staticKeyHex).
        byte[] prk = Hkdf.Extract(Convert.FromHexString(V3TranscriptHex), shared);
        Assert.Equal(V3StaticKeyHex, ToHex(Hkdf.Expand(prk, ConnectionKeySchedule.StaticInfo(idA, idB), 32)));
    }

    private const string V3TranscriptHex = "96d9e04d4e6853917249a880f153f546ed1369d49887b2bd6d288401bea6b746";
    private const string V3StaticKeyHex = "e5a8fc1b998e73e89405ec843981059b50ba870a982fa854d773ee1bd812ad52";

    [Fact]
    public void IdentityManager_DerivesTheV3StaticKeyOnBothSides()
    {
        JsonElement v = Vector();
        JsonElement input = v.GetProperty("input");
        string expectedKey = V3StaticKeyHex;
        var phone = new DefaultIdentityManager(new MemorySeedStore(Hex(input, "privateKeyAHex")));
        var pc = new DefaultIdentityManager(new MemorySeedStore(Hex(input, "privateKeyBHex")));
        byte[] salt = Convert.FromHexString(V3TranscriptHex);
        byte[] info = ConnectionKeySchedule.StaticInfo(phone.DeviceId.Value, pc.DeviceId.Value);

        Assert.Equal(expectedKey, ToHex(phone.DeriveSharedKey(pc.PublicKey.Span, salt, info)));
        Assert.Equal(expectedKey, ToHex(pc.DeriveSharedKey(phone.PublicKey.Span, salt, info)));
        Assert.Throws<CryptographicException>(() => pc.DeriveSharedKey(new byte[32], salt, info));
    }

    [Fact]
    public void CommitmentMismatch_IsDetected()
    {
        JsonElement v = Vector();
        JsonElement input = v.GetProperty("input");
        JsonElement expected = v.GetProperty("expected");
        byte[] pkA = Hex(expected, "publicKeyAHex");
        byte[] pkB = Hex(expected, "publicKeyBHex");
        byte[] nB = Hex(input, "nonceBHex");
        byte[] commit = Hex(expected, "commitHex");

        byte[] otherNb = (byte[])nB.Clone();
        otherNb[0] ^= 1;
        Assert.False(PairConfirm.VerifyCommitment(commit, pkB, pkA, otherNb));
        Assert.False(PairConfirm.VerifyCommitment(commit, pkA, pkB, nB));
        Assert.False(PairConfirm.VerifyCommitment(commit[..31], pkB, pkA, nB));
        Assert.NotEqual(Str(expected, "code"), PairConfirm.Code(pkB, pkA, nB, Hex(input, "nonceAHex")));
    }
}

internal sealed class MemorySeedStore(byte[]? seed = null) : ISeedStore
{
    private byte[]? _seed = seed;
    public byte[]? LoadSeed() => _seed?.ToArray();
    public void SaveSeed(ReadOnlySpan<byte> seed) => _seed = seed.ToArray();
    public void Clear() => _seed = null;
}

/// <summary>
/// The PC as receiver of confirm pairing, driven by a test client that does exactly what the
/// Android initiator does (ConnectionManager.runOutboundConfirm).
/// </summary>
[Collection(LinkServerCollection.Name)]
public sealed class LinkServerConfirmPairingTests
{
    private sealed class TestPhone
    {
        public byte[] Seed { get; } = RandomNumberGenerator.GetBytes(32);
        public byte[] PublicKey { get; }
        public string Id { get; }

        public TestPhone()
        {
            PublicKey = X25519Crypto.FromSeed(Seed).PublicKey;
            Id = DeviceId.FromPublicKey(PublicKey).Value;
        }
    }

    private sealed class Fixture : IAsyncDisposable
    {
        public string Root { get; } = Path.Combine(AppContext.BaseDirectory, "veyro-confirm-" + Guid.NewGuid().ToString("N"));
        public DefaultIdentityManager Pc { get; } = new(new MemorySeedStore());
        public FileTrustStore TrustStore { get; }
        public LinkServer Server { get; }
        public List<InboundPairingRequest> Prompts { get; } = [];
        public TaskCompletionSource<bool> Answer { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Fixture()
        {
            Directory.CreateDirectory(Root);
            TrustStore = new FileTrustStore(Root, new byte[32]);
            Server = new LinkServer(
                Pc.DeviceId.Value, "Test PC", TrustStore, Path.Combine(Root, "received"), Pc.PublicKey.Span,
                _ => Task.FromResult(false),
                deriveSharedKey: (peerKey, salt, info) => Pc.DeriveSharedKey(peerKey, salt, info));
            Server.PairingAuthorizer = request =>
            {
                lock (Prompts) Prompts.Add(request);
                return Answer.Task;
            };
        }

        public async ValueTask DisposeAsync()
        {
            await Server.DisposeAsync();
            try { Directory.Delete(Root, recursive: true); } catch { }
        }
    }

    private sealed record PhoneSide(ClientWebSocket Socket, SecureLinkSession Secure, string Code, byte[] PcPublicKey);

    private static async Task SendTextAsync(WebSocket socket, string text, CancellationToken ct)
        => await socket.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, ct);

    private static async Task<string> ReceiveTextAsync(WebSocket socket, CancellationToken ct)
    {
        byte[] buffer = new byte[64 * 1024];
        WebSocketReceiveResult frame = await socket.ReceiveAsync(buffer, ct);
        return Encoding.UTF8.GetString(buffer, 0, frame.Count);
    }

    private static async Task<(ClientWebSocket Socket, LinkPreambleAck? Ack)> SendPreambleAsync(
        Fixture f, V3Dialer dialer, CancellationToken ct, string? pair = null, byte[]? pk = null, int protocolVersion = LinkProtocol.Version)
    {
        var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{f.Server.Port}/veyro"), ct);
        LinkPreambleAck ack = await dialer.SendPreambleAsync(socket, "Phone", ct, pair: pair, pk: pk, protocolVersion: protocolVersion);
        return (socket, ack);
    }

    /// <summary>The phone's side up to and including pairHello, with every check Android makes.</summary>
    private static async Task<PhoneSide> PairUntilPromptAsync(Fixture f, TestPhone phone, CancellationToken ct)
    {
        using var dialer = new V3Dialer(phone.Id);
        var (socket, ack) = await SendPreambleAsync(f, dialer, ct, LinkPreamble.PairConfirm, phone.PublicKey);
        Assert.Equal(LinkPreambleAck.ModePairingConfirm, ack?.Mode);
        byte[] pkB = Convert.FromBase64String(ack!.Pk!);
        byte[] commit = Convert.FromBase64String(ack.Commit!);
        Assert.True(DeviceId.Matches(f.Pc.DeviceId, pkB), "the PC key must hash to the dialled id");

        byte[] nA = PairConfirm.NewNonce();
        await SendTextAsync(socket, PeerMessageJson.Encode(LinkPairNonce.For(nA)), ct);
        LinkPairNonce reply = PeerMessageJson.Decode<LinkPairNonce>(await ReceiveTextAsync(socket, ct))!;
        Assert.Equal(LinkPairNonce.Type, reply.T);
        byte[] nB = Convert.FromBase64String(reply.N);
        Assert.True(PairConfirm.VerifyCommitment(commit, pkB, phone.PublicKey, nB), "nB must match the commitment");

        string code = PairConfirm.Code(phone.PublicKey, pkB, nA, nB);
        // The v3 confirm schedule (04-PROTOCOL.md 4.3): ikm = dh || staticKey, salt over transcript, nA, nB.
        string pcId = f.Pc.DeviceId.Value;
        byte[] transcript = dialer.Transcript;
        byte[] prk = Hkdf.Extract(transcript, X25519Crypto.SharedSecret(phone.Seed, pkB));
        byte[] staticKey = Hkdf.Expand(prk, ConnectionKeySchedule.StaticInfo(phone.Id, pcId), 32);
        LinkKeys keys = ConnectionKeySchedule.PairConfirm(LinkRole.Initiator, phone.Id, pcId, transcript, nA, nB, dialer.Dh(), staticKey);
        var secure = new SecureLinkSession(socket, keys);
        await secure.SendAsync(new PeerMessage.PairHello(phone.Id, "Phone", "android", "1.0",
            PublicKeyB64: Convert.ToBase64String(phone.PublicKey)), ct);
        return new PhoneSide(socket, secure, code, pkB);
    }

    private static async Task<InboundPairingRequest> WaitForPromptAsync(Fixture f, CancellationToken ct)
    {
        while (true)
        {
            lock (f.Prompts) if (f.Prompts.Count > 0) return f.Prompts[0];
            await Task.Delay(20, ct);
        }
    }

    [Fact]
    public async Task Accept_PairsWithTheSameCodeOnBothScreens()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var phone = new TestPhone();
        var completed = new TaskCompletionSource<TrustedPeer>(TaskCreationOptions.RunContinuationsAsynchronously);
        f.Server.PairingCompleted += peer => completed.TrySetResult(peer);
        await f.Server.StartAsync();

        PhoneSide side = await PairUntilPromptAsync(f, phone, ct);
        await using (side.Secure)
        {
            InboundPairingRequest prompt = await WaitForPromptAsync(f, ct);
            Assert.Equal(side.Code, prompt.Code);
            Assert.Equal(phone.Id, prompt.PeerDeviceId);
            Assert.Equal("Phone", prompt.PeerName);
            Assert.InRange(prompt.ExpiresAt - DateTimeOffset.UtcNow, TimeSpan.FromSeconds(100), TimeSpan.FromSeconds(121));
            Assert.DoesNotContain(side.Code, prompt.ToString());
            Assert.Null(f.TrustStore.GetPeer(phone.Id));

            // While the user decides, the PC still answers pings.
            await side.Secure.SendAsync(new PeerMessage.Ping("p1"), ct);
            var pong = Assert.IsType<PeerMessage.Pong>((await side.Secure.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal("p1", pong.Nonce);

            f.Answer.SetResult(true);
            var pairOk = Assert.IsType<PeerMessage.PairOk>((await side.Secure.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal(f.Pc.DeviceId.Value, pairOk.DeviceId);
            Assert.Equal(side.PcPublicKey, Convert.FromBase64String(pairOk.PublicKeyB64!));

            // The receive the PC held during the prompt now reads under the session key.
            await side.Secure.SwitchToSessionAsync(Convert.FromBase64String(pairOk.SessionSecretB64), ct);
            await side.Secure.SendAsync(new PeerMessage.Hello(phone.Id, "Phone", "android"), ct);
            Assert.IsType<PeerMessage.HelloAck>((await side.Secure.ReceiveAsync(ct))!.Envelope.Payload);

            TrustedPeer peer = await completed.Task.WaitAsync(ct);
            Assert.Equal(phone.Id, peer.DeviceId);
            TrustedPeer stored = f.TrustStore.GetPeer(phone.Id)!;
            Assert.Equal(Convert.ToBase64String(phone.PublicKey), stored.PublicKeyBase64);
            Assert.Equal(pairOk.SessionSecretB64, stored.PairingSecretBase64);
            Assert.False(prompt.Abandoned.IsCancellationRequested);
        }
    }

    [Fact]
    public async Task Decline_AnswersPairFailAndStoresNothing()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var phone = new TestPhone();
        await f.Server.StartAsync();

        PhoneSide side = await PairUntilPromptAsync(f, phone, ct);
        await using (side.Secure)
        {
            await WaitForPromptAsync(f, ct);
            f.Answer.SetResult(false);
            var fail = Assert.IsType<PeerMessage.PairFail>((await side.Secure.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal("declined", fail.Reason);
            Assert.Null(f.TrustStore.GetPeer(phone.Id));
        }
    }

    [Fact]
    public async Task NoAnswer_TimesOutAndClosesThePrompt()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        f.Server.ConfirmTimeout = TimeSpan.FromMilliseconds(400);
        var phone = new TestPhone();
        await f.Server.StartAsync();

        PhoneSide side = await PairUntilPromptAsync(f, phone, ct);
        await using (side.Secure)
        {
            InboundPairingRequest prompt = await WaitForPromptAsync(f, ct);
            var fail = Assert.IsType<PeerMessage.PairFail>((await side.Secure.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal("timeout", fail.Reason);
            Assert.True(prompt.Abandoned.IsCancellationRequested, "the dialog must be told to close");
            Assert.Null(f.TrustStore.GetPeer(phone.Id));
        }
    }

    [Fact]
    public async Task PhoneCancel_ClosesThePromptAndStoresNothing()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var phone = new TestPhone();
        await f.Server.StartAsync();

        PhoneSide side = await PairUntilPromptAsync(f, phone, ct);
        InboundPairingRequest prompt;
        await using (side.Secure)
        {
            prompt = await WaitForPromptAsync(f, ct);
            await side.Secure.SendAsync(new PeerMessage.PairFail("cancelled"), ct);
        }
        while (!prompt.Abandoned.IsCancellationRequested) await Task.Delay(20, ct);
        f.Answer.SetResult(true); // too late: the request is gone
        await Task.Delay(100, ct);
        Assert.Null(f.TrustStore.GetPeer(phone.Id));
    }

    [Fact]
    public async Task KeyThatDoesNotMatchTheId_IsRefusedWithoutPrompting()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var phone = new TestPhone();
        var other = new TestPhone();
        await f.Server.StartAsync();

        using var dialer = new V3Dialer(phone.Id);
        var (socket, ack) = await SendPreambleAsync(f, dialer, ct, LinkPreamble.PairConfirm, other.PublicKey);
        using (socket)
        {
            Assert.Equal(LinkPreambleAck.ModeRefused, ack?.Mode);
            Assert.Equal("identity-mismatch", ack?.Reason);
            Assert.Null(ack?.Commit);
            Assert.Empty(f.Prompts);
        }
    }

    [Fact]
    public async Task ProtocolVersion2_IsRefused()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var phone = new TestPhone();
        await f.Server.StartAsync();

        // ADR-058: v3 only, no fallback to the v2 handshake.
        using var dialer = new V3Dialer(phone.Id);
        var (socket, ack) = await SendPreambleAsync(f, dialer, ct, LinkPreamble.PairConfirm, phone.PublicKey, protocolVersion: 2);
        using (socket)
        {
            Assert.Equal(LinkPreambleAck.ModeRefused, ack?.Mode);
            Assert.Equal("unsupported-protocol-version", ack?.Reason);
            Assert.Empty(f.Prompts);
        }
    }

    [Fact]
    public async Task QrPairing_WithoutAnOpenWindow_IsRefused()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var phone = new TestPhone();
        await f.Server.StartAsync();

        using var dialer = new V3Dialer(phone.Id);
        var (socket, ack) = await SendPreambleAsync(f, dialer, ct, LinkPreamble.PairQr, phone.PublicKey);
        using (socket)
        {
            Assert.Equal(LinkPreambleAck.ModeRefused, ack?.Mode);
            Assert.Equal("no-pairing-window", ack?.Reason);
            Assert.Empty(f.Prompts);
        }
    }

    [Fact]
    public async Task UnknownPeerWithoutPairRequest_IsRefusedAsNotPaired()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var phone = new TestPhone();
        await f.Server.StartAsync();

        // ADR-058: the legacy PIN flow is gone; an unknown device must ask to pair.
        using var dialer = new V3Dialer(phone.Id);
        var (socket, ack) = await SendPreambleAsync(f, dialer, ct);
        using (socket)
        {
            Assert.Equal(LinkPreambleAck.ModeRefused, ack?.Mode);
            Assert.Equal("not-paired", ack?.Reason);
            Assert.Null(ack?.Epk);
            Assert.Empty(f.Prompts);
        }
    }

    [Fact]
    public async Task WithoutChaCha20Poly1305_TheServerRefusesToStart()
    {
        string root = Path.Combine(AppContext.BaseDirectory, "veyro-noaead-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var pc = new DefaultIdentityManager(new MemorySeedStore());
            await using var server = new LinkServer(
                pc.DeviceId.Value, "Test PC", new FileTrustStore(root, new byte[32]), Path.Combine(root, "received"), pc.PublicKey.Span,
                _ => Task.FromResult(false), aeadSupported: () => false);
            await Assert.ThrowsAsync<PlatformNotSupportedException>(server.StartAsync);
            Assert.False(server.IsRunning);
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }
}
