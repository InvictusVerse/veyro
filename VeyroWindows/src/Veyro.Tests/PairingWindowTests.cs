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

/// <summary>QR pairing format and PC pairing window (04-PROTOCOL.md 4.2, ADR-030).</summary>
public sealed class PairingQrTests
{
    private static readonly string Id = DeviceId.FromPublicKey(new byte[32]).Value;

    private static PairingQrPayload Payload(string name = "Test PC", string code = "012345") => new(
        Id, name,
        [new IPEndPoint(IPAddress.Parse("192.168.1.20"), 5150), new IPEndPoint(IPAddress.Parse("10.0.0.5"), 5151)],
        code, 1_700_000_000);

    [Fact]
    public void Build_EmitsExactFormat()
    {
        Assert.Equal(
            $"veyro:pair?v=1&id={Id}&n=Test%20PC&a=192.168.1.20:5150,10.0.0.5:5151&c=012345&e=1700000000",
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
        Assert.Equal("012345", parsed.Code);
        Assert.Equal(1_700_000_000, parsed.ExpiresAtUnixSeconds);
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
    [InlineData("veyro:pair?v=2&id={id}&n=x&a=192.168.1.2:5150&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id=NOTANID&n=x&a=192.168.1.2:5150&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2:5150&c=12345&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2:5150&c=12345a&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2:5150&c=1234567&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=8.8.8.8:5150&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=127.0.0.1:5150&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=10.1:5150&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2:0&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=10.0.0.1:1,10.0.0.2:1,10.0.0.3:1,10.0.0.4:1,10.0.0.5:1&c=123456&e=1")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2:5150&c=123456")]
    [InlineData("veyro:pair?v=1&id={id}&n=x&a=192.168.1.2:5150&c=123456&e=-5")]
    [InlineData("veyro:pair?v=1&v=1&id={id}&n=x&a=192.168.1.2:5150&c=123456&e=1")]
    [InlineData("sefirah://pair?v=1&id={id}&n=x&a=192.168.1.2:5150&c=123456&e=1")]
    [InlineData("")]
    public void TryParse_RejectsRuleViolations(string template)
    {
        Assert.Null(PairingQr.TryParse(template.Replace("{id}", Id)));
    }

    [Fact]
    public void TryParse_RejectsNameLongerThan64BytesDecoded()
    {
        string tooLong = Uri.EscapeDataString(new string('n', 65));
        Assert.Null(PairingQr.TryParse($"veyro:pair?v=1&id={Id}&n={tooLong}&a=192.168.1.2:5150&c=123456&e=1"));
    }

    [Fact]
    public void TryParse_AcceptsLinkLocalAndPrivateRangesAndIgnoresUnknownKeys()
    {
        var parsed = PairingQr.TryParse(
            $"veyro:pair?v=1&id={Id}&a=169.254.3.4:5150,172.16.0.1:5151,172.31.255.255:5152&c=000000&e=10&future=yes");
        Assert.NotNull(parsed);
        Assert.Equal(string.Empty, parsed.Name);
        Assert.Equal(3, parsed.Endpoints.Count);
        Assert.Null(PairingQr.TryParse($"veyro:pair?v=1&id={Id}&a=172.32.0.1:5150&c=000000&e=10"));
    }

    [Fact]
    public void IsExpired_AllowsTenMinutesOfClockSkew()
    {
        var payload = Payload();
        Assert.False(payload.IsExpired(DateTimeOffset.FromUnixTimeSeconds(payload.ExpiresAtUnixSeconds + 600)));
        Assert.True(payload.IsExpired(DateTimeOffset.FromUnixTimeSeconds(payload.ExpiresAtUnixSeconds + 601)));
    }

    [Fact]
    public void Payload_ToStringDoesNotContainTheCode()
    {
        Assert.DoesNotContain("987654", Payload(code: "987654").ToString());
        Assert.DoesNotContain("987654", new PairingAttempt("987654", 3).ToString());
    }
}

public sealed class PairingWindowTests
{
    private static readonly string Id = DeviceId.FromPublicKey(new byte[32]).Value;
    private static readonly IReadOnlyList<IPEndPoint> Endpoints = [new IPEndPoint(IPAddress.Parse("192.168.1.20"), 5150)];

    private static PairingWindow NewWindow(ManualTimeProvider time, IReadOnlyList<IPEndPoint>? endpoints = null)
        => new(Id, () => "Test PC", () => endpoints ?? Endpoints, time);

    [Fact]
    public void Open_IssuesSixDigitCodeForFiveMinutesAndAQr()
    {
        var time = new ManualTimeProvider();
        using var window = NewWindow(time);
        Assert.False(window.IsOpen);
        Assert.Null(window.QrContent);
        Assert.Null(window.TryBeginAttempt());

        window.Open();
        Assert.True(window.IsOpen);
        Assert.True(PairingQr.IsValidCode(window.Code));
        Assert.Equal(time.GetUtcNow() + TimeSpan.FromMinutes(5), window.ExpiresAt);
        Assert.Equal(TimeSpan.FromMinutes(5), window.Remaining);

        PairingQrPayload qr = PairingQr.TryParse(window.QrContent)!;
        Assert.Equal(Id, qr.DeviceId);
        Assert.Equal("Test PC", qr.Name);
        Assert.Equal(window.Code, qr.Code);
        Assert.Equal(window.ExpiresAt!.Value.ToUnixTimeSeconds(), qr.ExpiresAtUnixSeconds);
        Assert.Equal("192.168.1.20:5150", Assert.Single(qr.Endpoints).ToString());
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
        Assert.Null(window.Code);
        Assert.Null(window.QrContent);
        Assert.Null(window.TryBeginAttempt());
    }

    [Fact]
    public void FiveFailures_ReplaceTheCodeAndRestartTheClock()
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
            Assert.Equal(first, window.TryBeginAttempt());
        }

        Assert.True(window.RecordFailure(first));
        PairingAttempt second = window.TryBeginAttempt()!;
        Assert.True(window.IsOpen);
        Assert.NotEqual(first.Generation, second.Generation);
        Assert.Equal(0, window.Failures);
        Assert.Equal(TimeSpan.FromMinutes(5), window.Remaining);

        // A late failure against the replaced code does not count against the new one.
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
    public void OpenAgain_IssuesANewCodeAndClose_EndsIt()
    {
        var time = new ManualTimeProvider();
        using var window = NewWindow(time);
        window.Open();
        long first = window.TryBeginAttempt()!.Generation;
        time.Advance(TimeSpan.FromMinutes(3));
        window.Open();
        Assert.NotEqual(first, window.TryBeginAttempt()!.Generation);
        Assert.Equal(TimeSpan.FromMinutes(5), window.Remaining);

        window.Close();
        Assert.False(window.IsOpen);
        Assert.Null(window.TryBeginAttempt());
    }
}

/// <summary>The server side: an open window pairs without the prompt; a closed one still prompts.</summary>
[Collection(LinkServerCollection.Name)]
public sealed class VeyroLinkServerPairingWindowTests
{
    private sealed class Fixture : IAsyncDisposable
    {
        public string Root { get; } = Path.Combine(AppContext.BaseDirectory, "veyro-pairwin-" + Guid.NewGuid().ToString("N"));
        public byte[] PcPublicKey { get; } = RandomNumberGenerator.GetBytes(32);
        public byte[] PhonePublicKey { get; } = RandomNumberGenerator.GetBytes(32);
        public string PcId => DeviceId.FromPublicKey(PcPublicKey).Value;
        public string PhoneId => DeviceId.FromPublicKey(PhonePublicKey).Value;
        public FileTrustStore TrustStore { get; }
        public VeyroLinkServer Server { get; }
        public List<InboundPairingRequest> Prompts { get; } = [];

        public Fixture()
        {
            Directory.CreateDirectory(Root);
            TrustStore = new FileTrustStore(Root, new byte[32]);
            Server = new VeyroLinkServer(PcId, "Test PC", TrustStore, Path.Combine(Root, "received"), PcPublicKey, _ => Task.FromResult(false));
            Server.PairingAuthorizer = request =>
            {
                lock (Prompts) Prompts.Add(request);
                return Task.FromResult(false);
            };
        }

        public async Task<(ClientWebSocket Socket, LinkPreambleAck? Ack)> ConnectAsync(CancellationToken ct)
        {
            var socket = new ClientWebSocket();
            await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{Server.Port}/veyro"), ct);
            await socket.SendAsync(Encoding.UTF8.GetBytes(LinkJson.Encode(new LinkPreamble(PhoneId, "Phone", "android"))),
                WebSocketMessageType.Text, true, ct);
            byte[] buffer = new byte[4096];
            WebSocketReceiveResult frame = await socket.ReceiveAsync(buffer, ct);
            return (socket, LinkJson.Decode<LinkPreambleAck>(Encoding.UTF8.GetString(buffer, 0, frame.Count)));
        }

        /// <summary>
        /// The phone's PairHello as Android frames it: pairing-mode body carried as the raw UTF-8
        /// JSON in <c>p</c>, authenticated by the bootstrap key.
        /// </summary>
        public async Task SendPairHelloAsync(ClientWebSocket socket, byte[] bootstrap, CancellationToken ct)
        {
            string envelope = LinkJson.Encode(new LinkEnvelope
            {
                MsgId = Guid.NewGuid().ToString(),
                From = PhoneId,
                Seq = 1,
                Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                Payload = new LinkMessage.PairHello(PhoneId, "Phone", "android", "1.0",
                    PublicKeyB64: Convert.ToBase64String(PhonePublicKey))
            });
            SealedFrame frame = FrameCrypto.Seal(envelope, bootstrap, encrypt: false, PhoneId);
            string wire = LinkJson.Encode(new LinkWireFrame
            {
                From = frame.FromDeviceId,
                Nonce = Convert.ToBase64String(frame.Nonce),
                Plaintext = Encoding.UTF8.GetString(frame.Body),
                Mac = Convert.ToBase64String(frame.Mac)
            });
            await socket.SendAsync(Encoding.UTF8.GetBytes(wire), WebSocketMessageType.Text, true, ct);
        }

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
    public async Task OpenWindow_PairsWithTheWindowCodeWithoutPrompting()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        var completed = new TaskCompletionSource<TrustedPeer>(TaskCreationOptions.RunContinuationsAsynchronously);
        f.Server.PairingCompleted += peer => completed.TrySetResult(peer);
        await f.Server.StartAsync();
        f.Server.PairingWindow.Open();
        string code = f.Server.PairingWindow.Code!;

        var (socket, ack) = await f.ConnectAsync(ct);
        using (socket)
        {
            Assert.Equal(LinkPreambleAck.ModePairing, ack?.Mode);

            // The phone side of the bootstrap, keyed by the code it scanned.
            byte[] bootstrap = FrameCrypto.DeriveBootstrapKey(code, f.PhoneId, f.PcId);
            await using var phone = new SecureLinkSession(socket, f.PhoneId, bootstrap, encrypt: false, expectedPeerId: f.PcId);
            await f.SendPairHelloAsync(socket, bootstrap, ct);
            var pairOk = Assert.IsType<LinkMessage.PairOk>((await phone.ReceiveAsync(ct))!.Envelope.Payload);

            // Phone rule from 4.2: the answering key must be the one the QR's id commits to.
            Assert.True(DeviceId.Matches(new DeviceId(f.PcId), Convert.FromBase64String(pairOk.PublicKeyB64!)));

            phone.Rekey(Convert.FromBase64String(pairOk.SessionSecretB64));
            await phone.SendAsync(new LinkMessage.Hello(f.PhoneId, "Phone", "android"), ct);
            Assert.IsType<LinkMessage.HelloAck>((await phone.ReceiveAsync(ct))!.Envelope.Payload);

            TrustedPeer peer = await completed.Task.WaitAsync(ct);
            Assert.Equal(f.PhoneId, peer.DeviceId);
            Assert.NotNull(f.TrustStore.GetPeer(f.PhoneId));
            Assert.False(f.Server.PairingWindow.IsOpen);
            Assert.Empty(f.Prompts);

            await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", ct);
        }
    }

    [Fact]
    public async Task OpenWindow_WrongCodeCountsAsAFailure()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        await f.Server.StartAsync();
        f.Server.PairingWindow.Open();
        string wrong = f.Server.PairingWindow.Code == "000000" ? "111111" : "000000";

        var (socket, ack) = await f.ConnectAsync(ct);
        using (socket)
        {
            Assert.Equal(LinkPreambleAck.ModePairing, ack?.Mode);
            await f.SendPairHelloAsync(socket, FrameCrypto.DeriveBootstrapKey(wrong, f.PhoneId, f.PcId), ct);

            await WaitUntilAsync(() => f.Server.PairingWindow.Failures == 1, ct);
            Assert.True(f.Server.PairingWindow.IsOpen);
            Assert.Null(f.TrustStore.GetPeer(f.PhoneId));
            Assert.Empty(f.Prompts);
        }
    }

    [Fact]
    public async Task OpenWindow_MissingPairHelloTimesOutAsAFailure()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        f.Server.WindowPairHelloTimeout = TimeSpan.FromMilliseconds(300);
        await f.Server.StartAsync();
        f.Server.PairingWindow.Open();

        var (socket, ack) = await f.ConnectAsync(ct);
        using (socket)
        {
            Assert.Equal(LinkPreambleAck.ModePairing, ack?.Mode);
            await WaitUntilAsync(() => f.Server.PairingWindow.Failures == 1, ct);
            Assert.Null(f.TrustStore.GetPeer(f.PhoneId));
        }
    }

    [Fact]
    public async Task ClosedWindow_KeepsTheLegacyPrompt()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        CancellationToken ct = timeout.Token;
        await using var f = new Fixture();
        await f.Server.StartAsync();

        var (socket, ack) = await f.ConnectAsync(ct);
        using (socket)
        {
            Assert.Equal(LinkPreambleAck.ModeRefused, ack?.Mode);
            Assert.Equal("declined", ack?.Reason);
            InboundPairingRequest prompt = Assert.Single(f.Prompts);
            Assert.Equal(f.PhoneId, prompt.PeerDeviceId);
            Assert.True(PairingQr.IsValidCode(prompt.Code));
        }

        // Opening and closing a window leaves the prompt path unchanged.
        f.Server.PairingWindow.Open();
        f.Server.PairingWindow.Close();
        var (again, againAck) = await f.ConnectAsync(ct);
        using (again)
        {
            Assert.Equal(LinkPreambleAck.ModeRefused, againAck?.Mode);
            Assert.Equal(2, f.Prompts.Count);
        }
    }
}
