using System.Net;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Crypto;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Xunit;

namespace Veyro.Tests;

/// <summary>QR pairing format 2 and PC pairing window (04-PROTOCOL.md 4.2, ADR-030, ADR-058).</summary>
public sealed class PairingQrTests
{
    private static readonly string Id = DeviceId.FromPublicKey(new byte[32]).Value;
    private static readonly byte[] Secret = Enumerable.Range(0, 16).Select(i => (byte)i).ToArray();
    private const string SecretText = "AAECAwQFBgcICQoLDA0ODw";

    private static PairingQrPayload Payload(string name = "Test PC", byte[]? secret = null) => new(
        Id, name,
        [new IPEndPoint(IPAddress.Parse("192.168.1.20"), 5150), new IPEndPoint(IPAddress.Parse("10.0.0.5"), 5151)],
        secret ?? Secret, 1_700_000_000);

    [Fact]
    public void Build_EmitsExactFormat()
    {
        Assert.Equal(
            $"veyro:pair?v=2&id={Id}&n=Test%20PC&a=192.168.1.20:5150,10.0.0.5:5151&s={SecretText}&e=1700000000",
            PairingQr.Build(Payload()));
    }

    [Fact]
    public void BuildThenParse_RoundTrips()
    {
        string name = "Bureau de L\u00e9a & co=1";
        PairingQrPayload parsed = Assert.IsType<PairingQrPayload>(PairingQr.TryParse(PairingQr.Build(Payload(name))));
        Assert.Equal(Id, parsed.DeviceId);
        Assert.Equal(name, parsed.Name);
        Assert.Equal(new[] { "192.168.1.20:5150", "10.0.0.5:5151" }, parsed.Endpoints.Select(e => e.ToString()));
        Assert.Equal(Secret, parsed.Secret);
        Assert.Equal(1_700_000_000, parsed.ExpiresAtUnixSeconds);
    }

    [Fact]
    public void Secret_IsUnpaddedBase64UrlAndRoundTrips()
    {
        Assert.Equal(SecretText, PairingQr.EncodeSecret(Secret));
        Assert.Equal(Secret, PairingQr.DecodeSecret(SecretText));
        byte[] high = Enumerable.Repeat((byte)0xFB, 16).ToArray();
        string encoded = PairingQr.EncodeSecret(high);
        Assert.Equal(22, encoded.Length);
        Assert.DoesNotContain('+', encoded);
        Assert.DoesNotContain('/', encoded);
        Assert.Equal(high, PairingQr.DecodeSecret(encoded));
        Assert.Throws<ArgumentException>(() => PairingQr.Build(Payload(secret: new byte[15])));
    }

    [Fact]
    public void Build_TakesAtMostFourEndpoints()
    {
        var payload = Payload() with
        {
            Endpoints = Enumerable.Range(1, 6).Select(i => new IPEndPoint(IPAddress.Parse($"192.168.0.{i}"), 5150)).ToList()
        };
        Assert.Equal(4, PairingQr.TryParse(PairingQr.Build(payload))!.Endpoints.Count);
    }

    [Fact]
    public void TruncateName_CutsAt64Utf8BytesOnACharacterBoundary()
    {
        Assert.Equal(new string('a', 64), PairingQr.TruncateName(new string('a', 70)));

        string accented = string.Concat(Enumerable.Repeat("\u00e9", 40)); // 2 bytes each
        Assert.Equal(32, PairingQr.TruncateName(accented).Length);

        string astral = char.ConvertFromUtf32(0x1F600); // 4 bytes, a surrogate pair
        string truncated = PairingQr.TruncateName("a" + string.Concat(Enumerable.Repeat(astral, 16)));
        Assert.Equal(61, Encoding.UTF8.GetByteCount(truncated));
        Assert.False(char.IsHighSurrogate(truncated[^1]));
        Assert.Equal("a" + string.Concat(Enumerable.Repeat(astral, 15)), truncated);

        Assert.Equal(string.Empty, PairingQr.TruncateName(null));
        Assert.Equal("short", PairingQr.TruncateName("short"));
    }

    [Fact]
    public void Build_DropsControlCharactersAndEncodesPlus()
    {
        string qr = PairingQr.Build(Payload("A\tB+C\u0007"));
        Assert.Contains("&n=AB%2BC&", qr);
        Assert.Equal("AB+C", PairingQr.TryParse(qr)!.Name);
        Assert.Equal("A B", PairingQr.TryParse(qr.Replace("&n=AB%2BC&", "&n=A+B&"))!.Name);
    }

    [Fact]
    public void Build_TruncatesLongNames()
    {
        PairingQrPayload parsed = PairingQr.TryParse(PairingQr.Build(Payload(new string('x', 100))))!;
        Assert.Equal(64, parsed.Name.Length);
    }

    [Theory]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2:5150&s={s}&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2:5150&c=123456&e=1")]
    [InlineData("veyro:pair?v=3&id={id}&n=x&a=192.168.1.2:5150&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id=NOTANID&n=x&a=192.168.1.2:5150&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&c=123456&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s=AAECAwQFBgcICQoLDA0OD&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s=AAECAwQFBgcICQoLDA0ODww&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s=AAECAwQFBgcICQoLDA0ODx&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s=AAECAwQFBgcICQoLDA0O+w&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s=AAECAwQFBgcICQoLDA0ODw==&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s={s}&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=8.8.8.8:5150&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=127.0.0.1:5150&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=10.1:5150&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:0&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=10.0.0.1:1,10.0.0.2:1,10.0.0.3:1,10.0.0.4:1,10.0.0.5:1&s={s}&e=1")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s={s}")]
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s={s}&e=-5")]
    [InlineData("veyro:pair?v=2&v=2&id={id}&n=x&a=192.168.1.2:5150&s={s}&e=1")]
    [InlineData("sefirah://pair?v=2&id={id}&n=x&a=192.168.1.2:5150&s={s}&e=1")]
    [InlineData("")]
    public void TryParse_RejectsRuleViolations(string template)
    {
        Assert.Null(PairingQr.TryParse(template.Replace("{id}", Id).Replace("{s}", SecretText)));
    }

    [Fact]
    public void TryParse_RejectsNameLongerThan64BytesDecoded()
    {
        string tooLong = Uri.EscapeDataString(new string('n', 65));
        Assert.Null(PairingQr.TryParse($"veyro:pair?v=2&id={Id}&n={tooLong}&a=192.168.1.2:5150&s={SecretText}&e=1"));
    }

    [Fact]
    public void TryParse_AcceptsLinkLocalAndPrivateRangesAndIgnoresUnknownKeys()
    {
        var parsed = PairingQr.TryParse(
            $"veyro:pair?v=2&id={Id}&a=169.254.3.4:5150,172.16.0.1:5151,172.31.255.255:5152&s={SecretText}&e=10&future=yes");
        Assert.NotNull(parsed);
        Assert.Equal(string.Empty, parsed.Name);
        Assert.Equal(3, parsed.Endpoints.Count);
        Assert.Null(PairingQr.TryParse($"veyro:pair?v=2&id={Id}&a=172.32.0.1:5150&s={SecretText}&e=10"));
    }

    [Fact]
    public void IsExpired_AllowsTenMinutesOfClockSkew()
    {
        var payload = Payload();
        Assert.False(payload.IsExpired(DateTimeOffset.FromUnixTimeSeconds(payload.ExpiresAtUnixSeconds + 600)));
        Assert.True(payload.IsExpired(DateTimeOffset.FromUnixTimeSeconds(payload.ExpiresAtUnixSeconds + 601)));
    }

    [Fact]
    public void ToString_DoesNotContainTheSecret()
    {
        Assert.DoesNotContain(SecretText, Payload().ToString());
        Assert.DoesNotContain(Convert.ToHexString(Secret), Payload().ToString(), StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(SecretText, new PairingAttempt(Secret, 3).ToString());
    }
}

public sealed class PairingWindowTests
{
    private static readonly string Id = DeviceId.FromPublicKey(new byte[32]).Value;
    private static readonly IReadOnlyList<IPEndPoint> Endpoints = [new IPEndPoint(IPAddress.Parse("192.168.1.20"), 5150)];

    private static PairingWindow NewWindow(ManualTimeProvider time, IReadOnlyList<IPEndPoint>? endpoints = null)
        => new(Id, () => "Test PC", () => endpoints ?? Endpoints, time);

    [Fact]
    public void Open_IssuesA128BitSecretForFiveMinutesAndAQr()
    {
        var time = new ManualTimeProvider();
        using var window = NewWindow(time);
        Assert.False(window.IsOpen);
        Assert.Null(window.QrContent);
        Assert.Null(window.TryBeginAttempt());

        window.Open();
        Assert.True(window.IsOpen);
        Assert.Equal(time.GetUtcNow() + TimeSpan.FromMinutes(5), window.ExpiresAt);
        Assert.Equal(TimeSpan.FromMinutes(5), window.Remaining);

        PairingQrPayload qr = PairingQr.TryParse(window.QrContent)!;
        Assert.Equal(Id, qr.DeviceId);
        Assert.Equal("Test PC", qr.Name);
        Assert.Equal(PairingQr.SecretSize, qr.Secret.Length);
        Assert.Equal(qr.Secret, window.TryBeginAttempt()!.Secret);
        Assert.Equal(window.ExpiresAt!.Value.ToUnixTimeSeconds(), qr.ExpiresAtUnixSeconds);
        Assert.Equal("192.168.1.20:5150", Assert.Single(qr.Endpoints).ToString());
    }

    [Fact]
    public void Attempt_IsACopyTheCallerMayZero()
    {
        using var window = NewWindow(new ManualTimeProvider());
        window.Open();
        PairingAttempt attempt = window.TryBeginAttempt()!;
        byte[] before = (byte[])attempt.Secret.Clone();
        CryptographicOperations.ZeroMemory(attempt.Secret);
        Assert.Equal(before, window.TryBeginAttempt()!.Secret);
    }

    [Fact]
    public void QrContent_IsNullWithoutAnEndpoint()
    {
        using var window = NewWindow(new ManualTimeProvider(), endpoints: []);
        window.Open();
        Assert.True(window.IsOpen);
        Assert.Null(window.QrContent);
    }

    [Fact]
    public void Window_ExpiresAfterFiveMinutes()
    {
        var time = new ManualTimeProvider();
        using var window = NewWindow(time);
        int changes = 0;
        window.Changed += () => changes++;
        window.Open();
        Assert.Equal(1, changes);

        time.Advance(TimeSpan.FromMinutes(4) + TimeSpan.FromSeconds(59));
        Assert.True(window.IsOpen);
        Assert.Equal(TimeSpan.FromSeconds(1), window.Remaining);

        time.Advance(TimeSpan.FromSeconds(1));
        Assert.Equal(2, changes); // raised by the expiry timer, not by a read
        Assert.False(window.IsOpen);
        Assert.Null(window.QrContent);
        Assert.Null(window.TryBeginAttempt());
    }

    [Fact]
    public void FiveFailures_ReplaceTheSecretAndRestartTheClock()
    {
        var time = new ManualTimeProvider();
        using var window = NewWindow(time);
        window.Open();
        PairingAttempt first = window.TryBeginAttempt()!;
        time.Advance(TimeSpan.FromMinutes(2));

        for (int i = 1; i < PairingWindow.MaxFailures; i++)
        {
            Assert.False(window.RecordFailure(first));
            Assert.Equal(i, window.Failures);
            Assert.Equal(first.Generation, window.TryBeginAttempt()!.Generation);
        }

        Assert.True(window.RecordFailure(first));
        PairingAttempt second = window.TryBeginAttempt()!;
        Assert.True(window.IsOpen);
        Assert.NotEqual(first.Generation, second.Generation);
        Assert.NotEqual(first.Secret, second.Secret);
        Assert.Equal(0, window.Failures);
        Assert.Equal(TimeSpan.FromMinutes(5), window.Remaining);

        // A late failure against the replaced secret does not count against the new one.
        Assert.False(window.RecordFailure(first));
        Assert.Equal(0, window.Failures);
        Assert.False(window.RecordFailure());
        Assert.Equal(1, window.Failures);
    }

    [Fact]
    public void Complete_ClosesAndReportsThePeer()
    {
        using var window = NewWindow(new ManualTimeProvider());
        TrustedPeer? completed = null;
        window.Completed += peer => completed = peer;
        window.Open();
        var peer = new TrustedPeer(Id, "Phone", PlatformType.Android, "pk", "secret", false, DateTimeOffset.UtcNow);

        window.Complete(peer);

        Assert.Same(peer, completed);
        Assert.False(window.IsOpen);
        Assert.False(window.RecordFailure());
    }

    [Fact]
    public void OpenAgain_IssuesANewSecretAndClose_EndsIt()
    {
        var time = new ManualTimeProvider();
        using var window = NewWindow(time);
        window.Open();
        PairingAttempt first = window.TryBeginAttempt()!;
        time.Advance(TimeSpan.FromMinutes(3));
        window.Open();
        PairingAttempt second = window.TryBeginAttempt()!;
        Assert.NotEqual(first.Generation, second.Generation);
        Assert.NotEqual(first.Secret, second.Secret);
        Assert.Equal(TimeSpan.FromMinutes(5), window.Remaining);

        window.Close();
        Assert.False(window.IsOpen);
        Assert.Null(window.TryBeginAttempt());
    }
}

/// <summary>
/// The server side of QR pairing (04-PROTOCOL.md 4.2, ADR-058): an open window pairs without a
/// prompt when the phone proves the QR secret; a closed window refuses.
/// </summary>
[Collection(LinkServerCollection.Name)]
public sealed class LinkServerPairingWindowTests
{
    private sealed class Fixture : IAsyncDisposable
    {
        public string Root { get; } = Path.Combine(AppContext.BaseDirectory, "veyro-pairwin-" + Guid.NewGuid().ToString("N"));
        public DefaultIdentityManager Pc { get; } = new(new MemorySeedStore());
        public byte[] PhoneSeed { get; } = RandomNumberGenerator.GetBytes(32);
        public byte[] PhonePublicKey { get; }
        public string PcId => Pc.DeviceId.Value;
        public string PhoneId => DeviceId.FromPublicKey(PhonePublicKey).Value;
        public FileTrustStore TrustStore { get; }
        public HookedTrustStore Hooks { get; }
        public LinkServer Server { get; }
        public List<InboundPairingRequest> Prompts { get; } = [];

        public Fixture()
        {
            PhonePublicKey = X25519Crypto.FromSeed(PhoneSeed).PublicKey;
            Directory.CreateDirectory(Root);
            TrustStore = new FileTrustStore(Root, new byte[32]);
            Hooks = new HookedTrustStore(TrustStore);
            Server = new LinkServer(PcId, "Test PC", Hooks, Path.Combine(Root, "received"), Pc.PublicKey.Span,
                _ => Task.FromResult(false),
                deriveSharedKey: (peerKey, salt, info) => Pc.DeriveSharedKey(peerKey, salt, info));
            Server.PairingAuthorizer = request =>
            {
                lock (Prompts) Prompts.Add(request);
                return Task.FromResult(false);
            };
        }

        public async Task<(ClientWebSocket Socket, V3Dialer Dialer, LinkPreambleAck Ack)> ConnectQrAsync(CancellationToken ct)
        {
            var socket = new ClientWebSocket();
            await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{Server.Port}/veyro"), ct);
            var dialer = new V3Dialer(PhoneId);
            LinkPreambleAck ack = await dialer.SendPreambleAsync(socket, "Phone", ct, pair: LinkPreamble.PairQr, pk: PhonePublicKey);
            return (socket, dialer, ack);
        }

        /// <summary>
        /// The phone side of the QR schedule as Android runs it: <c>ikm = dh || staticKey || qrMix</c>,
        /// and the pairHello proof over the transcript, both from <paramref name="qrSecret"/>.
        /// </summary>
        public (LinkKeys Keys, string Proof) PhoneQrKeys(V3Dialer dialer, byte[] qrSecret)
        {
            byte[] pcKey = Convert.FromBase64String(dialer.Ack!.Pk!);
            byte[] transcript = dialer.Transcript;
            byte[] prk = Hkdf.Extract(transcript, X25519Crypto.SharedSecret(PhoneSeed, pcKey));
            byte[] staticKey = Hkdf.Expand(prk, ConnectionKeySchedule.StaticInfo(PhoneId, PcId), 32);
            LinkKeys keys = ConnectionKeySchedule.PairQr(LinkRole.Initiator, PhoneId, PcId, transcript,
                dialer.Dh(), staticKey, ConnectionKeySchedule.QrMix(qrSecret));
            byte[] proof = ConnectionKeySchedule.QrProof(ConnectionKeySchedule.QrProofKey(qrSecret), PhoneId, PcId, transcript);
            return (keys, Convert.ToBase64String(proof));
        }

        public PeerMessage.PairHello Hello(string? proof) => new(PhoneId, "Phone", "android", "1.0",
            PublicKeyB64: Convert.ToBase64String(PhonePublicKey), QrProof: proof);

        public byte[] WindowSecret()
            => PairingQr.TryParse(Server.PairingWindow.QrContent)?.Secret ?? Server.PairingWindow.TryBeginAttempt()!.Secret;

        public async ValueTask DisposeAsync()
        {
            await Server.DisposeAsync();
            try { Directory.Delete(Root, recursive: true); } catch { }
        }
    }

    private static async Task WaitUntilAsync(Func<bool> condition, CancellationToken ct)
    {
        while (!condition()) await Task.Delay(20, ct);
    }

    [Fact]
    public async Task OpenWindow_PairsWithTheQrSecretWithoutPrompting()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var completed = new TaskCompletionSource<TrustedPeer>(TaskCreationOptions.RunContinuationsAsynchronously);
        f.Server.PairingCompleted += peer => completed.TrySetResult(peer);
        await f.Server.StartAsync();
        f.Server.PairingWindow.Open();
        byte[] qrSecret = f.WindowSecret();

        var (socket, dialer, ack) = await f.ConnectQrAsync(ct);
        using (socket)
        using (dialer)
        {
            Assert.Equal(LinkPreambleAck.ModePairing, ack.Mode);
            // Phone rule from 4.2: the answering key must be the one the QR's id commits to.
            Assert.True(DeviceId.Matches(new DeviceId(f.PcId), Convert.FromBase64String(ack.Pk!)));

            var (keys, proof) = f.PhoneQrKeys(dialer, qrSecret);
            await using var phone = new SecureLinkSession(socket, keys);
            await phone.SendAsync(f.Hello(proof), ct);
            var pairOk = Assert.IsType<PeerMessage.PairOk>((await phone.ReceiveAsync(ct))!.Envelope.Payload);
            Assert.Equal(f.PcId, pairOk.DeviceId);

            await phone.SwitchToSessionAsync(Convert.FromBase64String(pairOk.SessionSecretB64), ct);
            await phone.SendAsync(new PeerMessage.Hello(f.PhoneId, "Phone", "android"), ct);
            Assert.IsType<PeerMessage.HelloAck>((await phone.ReceiveAsync(ct))!.Envelope.Payload);

            TrustedPeer peer = await completed.Task.WaitAsync(ct);
            Assert.Equal(f.PhoneId, peer.DeviceId);
            Assert.Equal(pairOk.SessionSecretB64, f.TrustStore.GetPeer(f.PhoneId)!.PairingSecretBase64);
            Assert.False(f.Server.PairingWindow.IsOpen);
            Assert.Empty(f.Prompts);

            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", ct);
        }
    }

    /// <summary>
    /// Review issue 2 (ADR-058): when pairOk cannot be sent after the trust write (here the
    /// listener stops at that moment), the record is deleted again, nothing is reported paired,
    /// and the attempt counts as a window failure.
    /// </summary>
    [Fact]
    public async Task OpenWindow_PairOkThatCannotBeSent_LeavesNoTrustAndCountsAsAFailure()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        bool completed = false;
        f.Server.PairingCompleted += _ => completed = true;
        await f.Server.StartAsync();
        f.Server.PairingWindow.Open();
        byte[] qrSecret = f.WindowSecret();
        Task? stopping = null;
        f.Hooks.AfterUpsert = _ =>
        {
            stopping = f.Server.StopAsync();
            return Task.CompletedTask;
        };

        var (socket, dialer, ack) = await f.ConnectQrAsync(ct);
        using (socket)
        using (dialer)
        {
            Assert.Equal(LinkPreambleAck.ModePairing, ack.Mode);
            var (keys, proof) = f.PhoneQrKeys(dialer, qrSecret);
            await using var phone = new SecureLinkSession(socket, keys);
            await phone.SendAsync(f.Hello(proof), ct);
            await Assert.ThrowsAnyAsync<Exception>(() => phone.ReceiveAsync(ct));
            await WaitUntilAsync(() => stopping is not null, ct);
            await stopping!.WaitAsync(ct);
            Assert.Null(f.TrustStore.GetPeer(f.PhoneId));
            Assert.Equal(1, f.Server.PairingWindow.Failures);
            Assert.False(completed);
        }
    }

    [Fact]
    public async Task OpenWindow_WrongSecretCountsAsAFailure()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        await f.Server.StartAsync();
        f.Server.PairingWindow.Open();
        byte[] wrong = f.WindowSecret();
        wrong[0] ^= 0x01;

        var (socket, dialer, ack) = await f.ConnectQrAsync(ct);
        using (socket)
        using (dialer)
        {
            Assert.Equal(LinkPreambleAck.ModePairing, ack.Mode);
            var (keys, proof) = f.PhoneQrKeys(dialer, wrong);
            await using var phone = new SecureLinkSession(socket, keys);
            await phone.SendAsync(f.Hello(proof), ct);

            await WaitUntilAsync(() => f.Server.PairingWindow.Failures == 1, ct);
            Assert.True(f.Server.PairingWindow.IsOpen);
            Assert.Null(f.TrustStore.GetPeer(f.PhoneId));
            Assert.Empty(f.Prompts);
        }
    }

    [Fact]
    public async Task OpenWindow_RightKeysButBadProofCountsAsAFailure()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        await f.Server.StartAsync();
        f.Server.PairingWindow.Open();
        byte[] qrSecret = f.WindowSecret();

        var (socket, dialer, ack) = await f.ConnectQrAsync(ct);
        using (socket)
        using (dialer)
        {
            Assert.Equal(LinkPreambleAck.ModePairing, ack.Mode);
            var (keys, _) = f.PhoneQrKeys(dialer, qrSecret);
            await using var phone = new SecureLinkSession(socket, keys);
            await phone.SendAsync(f.Hello(Convert.ToBase64String(new byte[32])), ct);

            await WaitUntilAsync(() => f.Server.PairingWindow.Failures == 1, ct);
            Assert.Null(f.TrustStore.GetPeer(f.PhoneId));
        }
    }

    [Fact]
    public async Task OpenWindow_MissingPairHelloTimesOutAsAFailure()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        f.Server.FirstFrameTimeout = TimeSpan.FromMilliseconds(300);
        await f.Server.StartAsync();
        f.Server.PairingWindow.Open();

        var (socket, dialer, ack) = await f.ConnectQrAsync(ct);
        using (socket)
        using (dialer)
        {
            Assert.Equal(LinkPreambleAck.ModePairing, ack.Mode);
            await WaitUntilAsync(() => f.Server.PairingWindow.Failures == 1, ct);
            Assert.Null(f.TrustStore.GetPeer(f.PhoneId));
        }
    }

    [Fact]
    public async Task ClosedWindow_RefusesWithoutPrompting()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        await f.Server.StartAsync();

        // Opening and closing a window leaves nothing behind.
        f.Server.PairingWindow.Open();
        f.Server.PairingWindow.Close();
        var (socket, dialer, ack) = await f.ConnectQrAsync(ct);
        using (socket)
        using (dialer)
        {
            Assert.Equal(LinkPreambleAck.ModeRefused, ack.Mode);
            Assert.Equal("no-pairing-window", ack.Reason);
            Assert.Null(ack.Epk);
            Assert.Empty(f.Prompts);
        }
    }
}
