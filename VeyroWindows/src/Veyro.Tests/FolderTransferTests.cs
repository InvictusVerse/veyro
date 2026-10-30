using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Transfer;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// ADR-064 end to end over a real LinkServer and a wire-level phone: a folder sent from the PC
/// keeps its structure and offers its empty directories, only to a phone that takes folders; a
/// folder from the phone lands in Downloads\Veyro\&lt;folder&gt; with its empty directories, and a
/// second copy is saved as "&lt;folder&gt; (2)".
/// </summary>
[Collection(LinkServerCollection.Name)]
public sealed class FolderTransferTests : IAsyncLifetime
{
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-folder-transfer-" + Guid.NewGuid().ToString("N"));
    private readonly CancellationTokenSource _timeout = new(TimeSpan.FromSeconds(60));
    private readonly byte[] _pcPublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _phonePublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _secret = RandomNumberGenerator.GetBytes(32);
    private string PcId => DeviceId.FromPublicKey(_pcPublicKey).Value;
    private string PhoneId => DeviceId.FromPublicKey(_phonePublicKey).Value;
    private CancellationToken Ct => _timeout.Token;
    private PeerSessionRegistry _registry = null!;
    private FileTrustStore _trustStore = null!;
    private LinkServer _server = null!;
    private string Received => Path.Combine(_root, "received");

    public async Task InitializeAsync()
    {
        Directory.CreateDirectory(_root);
        _trustStore = new FileTrustStore(Path.Combine(_root, "trust"), new byte[32]);
        await _trustStore.UpsertAsync(new TrustedPeer(
            PhoneId, "Phone", PlatformType.Android,
            Convert.ToBase64String(_phonePublicKey), Convert.ToBase64String(_secret),
            AutoAcceptTransfers: false, DateTimeOffset.UtcNow));
        _registry = new PeerSessionRegistry();
        _server = new LinkServer(PcId, "Test PC", _trustStore, Received, _pcPublicKey, _ => Task.FromResult(true), sessions: _registry);
        await _server.StartAsync();
    }

    public async Task DisposeAsync()
    {
        await _server.DisposeAsync();
        _timeout.Dispose();
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private async Task<SessionTransferTests.FakePhone> ConnectPhoneAsync(int capabilities)
    {
        var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
        await socket.SendAsync(Encoding.UTF8.GetBytes(PeerMessageJson.Encode(new LinkPreamble(PhoneId, "Phone", "android"))),
            WebSocketMessageType.Text, true, Ct);
        byte[] buffer = new byte[4096];
        WebSocketReceiveResult ack = await socket.ReceiveAsync(buffer, Ct);
        Assert.Equal(LinkPreambleAck.ModeSession, PeerMessageJson.Decode<LinkPreambleAck>(Encoding.UTF8.GetString(buffer, 0, ack.Count))?.Mode);
        var phone = new SessionTransferTests.FakePhone(socket, new SecureLinkSession(socket, PhoneId, _secret, encrypt: true, expectedPeerId: PcId));
        await phone.Session.SendAsync(new PeerMessage.Hello(PhoneId, "Phone", "android", capabilities), Ct);
        var helloAck = await phone.ReceiveAsync<PeerMessage.HelloAck>(Ct);
        // The PC says it takes folders (ADR-064).
        Assert.NotEqual(0, helloAck.Capabilities & PeerCapabilities.FolderTransfer);
        return phone;
    }

    /// <summary>"Trip" on disk: two files, one nested, and an empty directory.</summary>
    private string WriteTrip(out byte[] big)
    {
        string trip = Path.Combine(_root, "src-" + Guid.NewGuid().ToString("N"), "Trip");
        Directory.CreateDirectory(Path.Combine(trip, "Day 2"));
        Directory.CreateDirectory(Path.Combine(trip, "Empty"));
        File.WriteAllText(Path.Combine(trip, "a.txt"), "A");
        big = RandomNumberGenerator.GetBytes(5 * 1024 * 1024 + 7);
        File.WriteAllBytes(Path.Combine(trip, "Day 2", "b.bin"), big);
        return trip;
    }

    private TrustedPeer TrustedPhone => _trustStore.GetPeer(PhoneId)!;

    private LinkClient NewClient() => new(PcId, "Test PC") { AllowLoopbackDataPlane = true };

    [Fact]
    public async Task PcToPhone_FolderKeepsItsStructureAndOffersItsEmptyDirectories()
    {
        await using var phone = await ConnectPhoneAsync(PeerCapabilities.FileTransfer | PeerCapabilities.FolderTransfer);
        string trip = WriteTrip(out byte[] big);
        string phoneInbox = Path.Combine(_root, "phone");

        Task<BulkTransferResult> send = NewClient().SendFilesAsync(_registry, null, TrustedPhone, [trip], ct: Ct);
        var offer = await phone.ReceiveAsync<PeerMessage.TransferOffer>(Ct);
        Assert.Equal(["Trip/a.txt", "Trip/Day 2/b.bin", "Trip/Empty"], offer.Items.Select(i => i.RelPath));
        Assert.Equal([false, false, true], offer.Items.Select(i => i.IsDir));
        Assert.Equal(1 + big.LongLength, offer.TotalBytes);

        var tcp = new TcpListener(IPAddress.Loopback, 0);
        tcp.Start();
        string token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var files = offer.Items.Where(i => !i.IsDir).Select(i => new InboundTransferItem(i.Index, i.RelPath, i.Size)).ToList();
        Task<BulkTransferResult> receive = Task.Run(async () =>
        {
            try { return await BulkTransfer.ReceiveAsync(tcp, offer.TransferId, token, PcId, phoneInbox, files, ct: Ct); }
            finally { tcp.Stop(); }
        }, Ct);
        await phone.Session.SendAsync(new PeerMessage.TransferDecision(offer.TransferId, true, Route: "LAN_TCP",
            TcpHost: "127.0.0.1", TcpPort: ((IPEndPoint)tcp.LocalEndpoint).Port, FallbackHost: "127.0.0.1", AuthToken: token), Ct);

        await Task.WhenAll(send, receive).WaitAsync(Ct);
        Assert.Equal("A", await File.ReadAllTextAsync(Path.Combine(phoneInbox, "Trip", "a.txt"), Ct));
        Assert.Equal(big, await File.ReadAllBytesAsync(Path.Combine(phoneInbox, "Trip", "Day 2", "b.bin"), Ct));
    }

    [Fact]
    public async Task PcToPhone_FolderIsNotOfferedToAPhoneThatDoesNotTakeFolders()
    {
        await using var phone = await ConnectPhoneAsync(PeerCapabilities.FileTransfer);
        string trip = WriteTrip(out _);

        var error = await Assert.ThrowsAsync<NotSupportedException>(() => NewClient().SendFilesAsync(_registry, null, TrustedPhone, [trip], ct: Ct));
        Assert.Contains("cannot receive folders", error.Message);
    }

    /// <summary>Phone-to-PC: a folder offer with an empty directory, VTC1 for its files, then the PC's transferEnd.</summary>
    private async Task<PeerMessage.TransferEnd> PhoneSendsTripAsync(SessionTransferTests.FakePhone phone, string transferId, byte[] big)
    {
        string a = Path.Combine(_root, "a-" + transferId);
        string b = Path.Combine(_root, "b-" + transferId);
        await File.WriteAllTextAsync(a, "A", Ct);
        await File.WriteAllBytesAsync(b, big, Ct);
        LinkItem[] items =
        [
            new(0, "Trip/a.txt", 1, 0),
            new(1, "Trip/Day 2/b.bin", big.LongLength, 0),
            new(2, "Trip/Empty", 0, 0, IsDir: true),
        ];
        await phone.Session.SendAsync(new PeerMessage.TransferOffer(transferId, items, 1 + big.LongLength, "Phone", ["LAN_TCP"]), Ct);
        var decision = await phone.ReceiveAsync<PeerMessage.TransferDecision>(Ct);
        Assert.True(decision.Accept, decision.Reason);
        await BulkTransfer.SendAsync(decision.TcpHost!, decision.TcpPort!.Value, transferId, decision.AuthToken!, PhoneId,
            [new OutboundTransferItem(0, a, "Trip/a.txt"), new OutboundTransferItem(1, b, "Trip/Day 2/b.bin")],
            resume: decision.Resume, ct: Ct);
        return await phone.ReceiveAsync<PeerMessage.TransferEnd>(Ct);
    }

    [Fact]
    public async Task PhoneToPc_FolderLandsWithItsStructure_AndASecondCopyGetsItsOwnName()
    {
        await using var phone = await ConnectPhoneAsync(PeerCapabilities.FileTransfer | PeerCapabilities.FolderTransfer);
        byte[] big = RandomNumberGenerator.GetBytes(300_000);

        Assert.True((await PhoneSendsTripAsync(phone, "f1", big)).Completed);
        Assert.Equal("A", await File.ReadAllTextAsync(Path.Combine(Received, "Trip", "a.txt"), Ct));
        Assert.Equal(big, await File.ReadAllBytesAsync(Path.Combine(Received, "Trip", "Day 2", "b.bin"), Ct));
        Assert.True(Directory.Exists(Path.Combine(Received, "Trip", "Empty")));

        Assert.True((await PhoneSendsTripAsync(phone, "f2", big)).Completed);
        Assert.Equal("A", await File.ReadAllTextAsync(Path.Combine(Received, "Trip (2)", "a.txt"), Ct));
        Assert.True(Directory.Exists(Path.Combine(Received, "Trip (2)", "Empty")));
        Assert.Equal(["a.txt"], Directory.GetFiles(Path.Combine(Received, "Trip")).Select(path => Path.GetFileName(path)));
    }
}
