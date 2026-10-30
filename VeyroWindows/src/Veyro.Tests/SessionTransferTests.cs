using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Photos;
using Veyro.Core.Transfer;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// ADR-034: PC-to-phone transfers over the phone's live session (the hardware failure: a second
/// connection per transfer superseded the companion session on Android), the dial fallback, and
/// phone-to-PC offers on the same session, including photoFetch answers.
/// </summary>
[Collection(LinkServerCollection.Name)]
public sealed class SessionTransferTests : IAsyncLifetime
{
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-session-transfer-" + Guid.NewGuid().ToString("N"));
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
    private int _prompts;
    private Func<InboundOfferRequest, bool> _authorize = _ => true;

    public async Task InitializeAsync()
    {
        Directory.CreateDirectory(_root);
        _trustStore = new FileTrustStore(Path.Combine(_root, "trust"), new byte[32]);
        await _trustStore.UpsertAsync(new TrustedPeer(
            PhoneId, "Phone", PlatformType.Android,
            Convert.ToBase64String(_phonePublicKey), Convert.ToBase64String(_secret),
            AutoAcceptTransfers: false, DateTimeOffset.UtcNow));
        _registry = new PeerSessionRegistry();
        _server = new LinkServer(PcId, "Test PC", _trustStore, Received, _pcPublicKey,
            request =>
            {
                Interlocked.Increment(ref _prompts);
                return Task.FromResult(_authorize(request));
            },
            sessions: _registry);
        await _server.StartAsync();
    }

    public async Task DisposeAsync()
    {
        await _server.DisposeAsync();
        _timeout.Dispose();
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private TrustedPeer TrustedPhone => _trustStore.GetPeer(PhoneId)!;

    private LinkClient NewClient(TimeSpan? decisionTimeout = null) => new(PcId, "Test PC")
    {
        AllowLoopbackDataPlane = true,
        DecisionTimeout = decisionTimeout ?? TransferMailbox.DecisionTimeout
    };

    /// <summary>The phone's persistent session, as Android opens it: preamble, then its own Hello.</summary>
    private async Task<FakePhone> ConnectPhoneAsync(int capabilities = PeerCapabilities.FileTransfer)
    {
        var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
        var v3dial = new V3Dialer(PhoneId);
        LinkPreambleAck v3ack = await v3dial.SendPreambleAsync(socket, "Phone", Ct);
        Assert.Equal(LinkPreambleAck.ModeSession, v3ack.Mode);
        var phone = new FakePhone(socket, new SecureLinkSession(socket, v3dial.SessionKeys(PcId, _secret)));
        await phone.Session.SendAsync(new PeerMessage.Hello(PhoneId, "Phone", "android", capabilities), Ct);
        var helloAck = await phone.ReceiveAsync<PeerMessage.HelloAck>(Ct);
        Assert.NotEqual(0, helloAck.Capabilities & PeerCapabilities.Photos);
        Assert.True(_registry.IsConnected(PhoneId));
        return phone;
    }

    private string WriteSource(string name, int size, out byte[] payload)
    {
        payload = RandomNumberGenerator.GetBytes(size);
        string path = Path.Combine(_root, "src-" + Guid.NewGuid().ToString("N"), name);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, payload);
        return path;
    }

    /// <summary>The phone's receiving half: a loopback VTC1 listener announced in an accepting decision.</summary>
    private static async Task<(string Path, PeerMessage.TransferDecision Decision, Task<BulkTransferResult> Receive)> AcceptOnPhoneAsync(
        FakePhone phone, PeerMessage.TransferOffer offer, string pcId, string destination, CancellationToken ct)
    {
        var tcp = new TcpListener(IPAddress.Loopback, 0);
        tcp.Start();
        string token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var items = offer.Items.Select(i => new InboundTransferItem(i.Index, i.RelPath, i.Size)).ToList();
        Task<BulkTransferResult> receive = Task.Run(async () =>
        {
            try { return await BulkTransfer.ReceiveAsync(tcp, offer.TransferId, token, pcId, destination, items,
                Veyro.Core.Protocol.Vtc3.KeyDeriver(phone.Session.ExportKey, offer.TransferId), ct: ct); }
            finally { tcp.Stop(); }
        }, ct);
        var decision = new PeerMessage.TransferDecision(offer.TransferId, true, Route: "LAN_TCP",
            TcpHost: "127.0.0.1", TcpPort: ((IPEndPoint)tcp.LocalEndpoint).Port, FallbackHost: "127.0.0.1", AuthToken: token);
        await phone.Session.SendAsync(decision, ct);
        return (Path.Combine(destination, offer.Items[0].RelPath), decision, receive);
    }

    [Fact]
    public async Task PcToPhone_UsesTheLiveSession_WithInterleavedMessages()
    {
        await using FakePhone phone = await ConnectPhoneAsync();
        string source = WriteSource("holiday.bin", 300_000, out byte[] payload);
        int sessionsOpened = 0;
        _registry.SessionOpened += _ => Interlocked.Increment(ref sessionsOpened);

        Task<BulkTransferResult> send = NewClient().SendFilesAsync(_registry, discovered: null, TrustedPhone, [source], ct: Ct);
        var offer = await phone.ReceiveAsync<PeerMessage.TransferOffer>(Ct);
        Assert.Equal("holiday.bin", Assert.Single(offer.Items).RelPath);
        Assert.Null(offer.RequestId);
        // Hardware test 3: "LANTCP" made Android answer without a VTC1 endpoint. Exactly the
        // Kotlin TransferRoute name, nothing else.
        Assert.Equal(["LAN_TCP"], offer.PreferredRoutes);

        // Everything a phone may send before deciding: its Hello again, companion data, a
        // heartbeat, and transfer messages for some other id. None of it may break the wait.
        await phone.Session.SendAsync(new PeerMessage.Hello(PhoneId, "Phone", "android", PeerCapabilities.FileTransfer), Ct);
        await phone.Session.SendAsync(new PeerMessage.Notification("k", PeerMessage.Notification.KindPosted, Title: "t"), Ct);
        await phone.Session.SendAsync(new PeerMessage.Ping("p1"), Ct);
        await phone.Session.SendAsync(new PeerMessage.TransferDecision("someone-else", false, "declined"), Ct);
        await phone.Session.SendAsync(new PeerMessage.TransferProgressNotice(offer.TransferId, 0, offer.TotalBytes), Ct);
        await phone.Session.SendAsync(new PeerMessage.ItemResult("someone-else", 0, true), Ct);
        Assert.Equal("p1", (await phone.ReceiveAsync<PeerMessage.Pong>(Ct)).Nonce);

        var accepted = await AcceptOnPhoneAsync(phone, offer, PcId, Path.Combine(_root, "phone"), Ct);
        BulkTransferResult sent = await send;
        await accepted.Receive;
        Assert.Equal(payload.Length, sent.BytesTransferred);
        Assert.Equal(payload, await File.ReadAllBytesAsync(accepted.Path, Ct));
        await phone.Session.SendAsync(new PeerMessage.TransferEnd(offer.TransferId, true), Ct);

        // Same session throughout: still connected, never replaced, still answering.
        Assert.True(_registry.IsConnected(PhoneId));
        Assert.Equal(0, sessionsOpened);
        await phone.Session.SendAsync(new PeerMessage.Ping("p2"), Ct);
        Assert.Equal("p2", (await phone.ReceiveAsync<PeerMessage.Pong>(Ct)).Nonce);

        // A second send on the same session works too.
        string second = WriteSource("second.bin", 1234, out byte[] secondPayload);
        Task<BulkTransferResult> sendAgain = NewClient().SendFilesAsync(_registry, null, TrustedPhone, [second], ct: Ct);
        var offer2 = await phone.ReceiveAsync<PeerMessage.TransferOffer>(Ct);
        var accepted2 = await AcceptOnPhoneAsync(phone, offer2, PcId, Path.Combine(_root, "phone"), Ct);
        await sendAgain;
        await accepted2.Receive;
        Assert.Equal(secondPayload, await File.ReadAllBytesAsync(accepted2.Path, Ct));
    }

    [Fact]
    public async Task PcToPhone_DeclineOnTheSession_FailsTheSend()
    {
        await using FakePhone phone = await ConnectPhoneAsync();
        string source = WriteSource("a.bin", 10, out _);
        Task<BulkTransferResult> send = NewClient().SendFilesAsync(_registry, null, TrustedPhone, [source], ct: Ct);
        var offer = await phone.ReceiveAsync<PeerMessage.TransferOffer>(Ct);
        await phone.Session.SendAsync(new PeerMessage.TransferDecision(offer.TransferId, false, "declined"), Ct);
        var error = await Assert.ThrowsAsync<UnauthorizedAccessException>(() => send);
        Assert.Contains("declined", error.Message);
        Assert.True(_registry.IsConnected(PhoneId));
    }

    [Fact]
    public async Task PcToPhone_CancelBeforeDecision_IsADecline()
    {
        await using FakePhone phone = await ConnectPhoneAsync();
        string source = WriteSource("a.bin", 10, out _);
        Task<BulkTransferResult> send = NewClient().SendFilesAsync(_registry, null, TrustedPhone, [source], ct: Ct);
        var offer = await phone.ReceiveAsync<PeerMessage.TransferOffer>(Ct);
        await phone.Session.SendAsync(new PeerMessage.Cancel(offer.TransferId, "user"), Ct);
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => send);
    }

    [Fact]
    public async Task PcToPhone_PhoneCancelArrivingAfterItsSocketCloses_SaysThePhoneStoppedIt()
    {
        // ADR-055: a phone that cancels closes the data socket and sends cancel together, and on a
        // busy phone the closed socket lands first. The PC still reports the phone's cancel.
        await using FakePhone phone = await ConnectPhoneAsync();
        string source = WriteSource("big.bin", 32 * 1024 * 1024, out _);
        Task<BulkTransferResult> send = NewClient().SendFilesAsync(_registry, null, TrustedPhone, [source], ct: Ct);
        var offer = await phone.ReceiveAsync<PeerMessage.TransferOffer>(Ct);

        var tcp = new TcpListener(System.Net.IPAddress.Loopback, 0);
        tcp.Start();
        await phone.Session.SendAsync(new PeerMessage.TransferDecision(offer.TransferId, true, Route: "LAN_TCP",
            TcpHost: "127.0.0.1", TcpPort: ((System.Net.IPEndPoint)tcp.LocalEndpoint).Port, FallbackHost: "127.0.0.1",
            AuthToken: "token"), Ct);
        using (TcpClient data = await tcp.AcceptTcpClientAsync(Ct))
        {
            NetworkStream stream = data.GetStream();
            using Veyro.Core.Protocol.Vtc3Channel vtc = await Veyro.Core.Protocol.Vtc3.OpenAsReceiverAsync(
                stream, offer.TransferId, Veyro.Core.Protocol.Vtc3.KeyDeriver(phone.Session.ExportKey, offer.TransferId), Ct);
            Assert.IsType<Veyro.Core.Protocol.TcpFrame.Auth>(await vtc.Reader.ReadOrNullAsync(Ct));
            await vtc.Writer.WriteAsync(new Veyro.Core.Protocol.TcpFrame.AuthAck(1, offer.TransferId, true), ct: Ct);
            await vtc.Reader.ReadOrNullAsync(Ct); // ItemBegin
            await vtc.Reader.ReadOrNullAsync(Ct); // the first chunk; then the user cancels
        }
        tcp.Stop();
        await Task.Delay(300, Ct);
        await phone.Session.SendAsync(new PeerMessage.Cancel(offer.TransferId, "User cancelled"), Ct);

        var error = await Assert.ThrowsAsync<IOException>(() => send);
        Assert.Equal("The phone stopped the transfer.", error.Message);
    }

    [Fact]
    public async Task PcToPhone_NoDecisionInTime_TimesOutAndCancels()
    {
        await using FakePhone phone = await ConnectPhoneAsync();
        string source = WriteSource("a.bin", 10, out _);
        Task<BulkTransferResult> send = NewClient(TimeSpan.FromMilliseconds(300))
            .SendFilesAsync(_registry, null, TrustedPhone, [source], ct: Ct);
        var offer = await phone.ReceiveAsync<PeerMessage.TransferOffer>(Ct);
        await Assert.ThrowsAsync<TimeoutException>(() => send);
        var cancel = await phone.ReceiveAsync<PeerMessage.Cancel>(Ct);
        Assert.Equal(offer.TransferId, cancel.TransferId);
        Assert.Equal("timeout", cancel.Reason);
        Assert.True(_registry.IsConnected(PhoneId));
    }

    [Fact]
    public async Task PcToPhone_SessionClosesWhileWaiting_FailsTheSend()
    {
        FakePhone phone = await ConnectPhoneAsync();
        string source = WriteSource("a.bin", 10, out _);
        Task<BulkTransferResult> send = NewClient().SendFilesAsync(_registry, null, TrustedPhone, [source], ct: Ct);
        await phone.ReceiveAsync<PeerMessage.TransferOffer>(Ct);
        await phone.DisposeAsync();
        await Assert.ThrowsAsync<IOException>(() => send);
    }

    [Fact]
    public async Task PcToPhone_WithoutLiveSession_DialsTheDiscoveredEndpoint()
    {
        // The "phone" here is a second listener that trusts the PC and auto-accepts.
        string phoneRoot = Path.Combine(_root, "dialled-phone");
        var phoneTrust = new FileTrustStore(Path.Combine(phoneRoot, "trust"), new byte[32]);
        await phoneTrust.UpsertAsync(new TrustedPeer(
            PcId, "Test PC", PlatformType.Windows,
            Convert.ToBase64String(_pcPublicKey), Convert.ToBase64String(_secret),
            AutoAcceptTransfers: true, DateTimeOffset.UtcNow));
        await using var phoneListener = new LinkServer(PhoneId, "Phone", phoneTrust, Path.Combine(phoneRoot, "in"), _phonePublicKey,
            _ => Task.FromResult(false));
        await phoneListener.StartAsync();

        Assert.False(_registry.IsConnected(PhoneId));
        string source = WriteSource("dial.bin", 70_000, out byte[] payload);
        var discovered = new PeerDevice(PhoneId, "Phone", PlatformType.Android,
            [new PeerEndpoint("127.0.0.1", phoneListener.Port)], IsTrusted: true, IsConnected: false);
        BulkTransferResult result = await NewClient().SendFilesAsync(_registry, discovered, TrustedPhone, [source], ct: Ct);
        Assert.Equal(payload.Length, result.BytesTransferred);
        Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(phoneRoot, "in", "dial.bin"), Ct));

        // Neither connected nor discovered: a clear error, nothing dialled.
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            NewClient().SendFilesAsync(_registry, null, TrustedPhone, [source], ct: Ct));
    }

    /// <summary>
    /// Phone-to-PC on the persistent session: any number of sequential offers each go through
    /// consent; a photoFetch answer is auto-accepted into the session cache, not Downloads; an
    /// unknown requestId still gets the consent prompt.
    /// </summary>
    [Fact]
    public async Task PhoneToPc_SequentialOffersAndRequestedPhoto()
    {
        using var photos = new PhotoService(_registry, Path.Combine(_root, "cache"));
        _server.RequestedOffers = photos;
        _authorize = request => request.Offer.RequestId is null;
        await using FakePhone phone = await ConnectPhoneAsync(PeerCapabilities.FileTransfer | PeerCapabilities.Photos);

        for (int i = 0; i < 3; i++)
        {
            string source = WriteSource($"file{i}.bin", 5_000 + i, out byte[] payload);
            var end = await phone.SendFileAsync($"t{i}", source, $"file{i}.bin", PhoneId, Ct);
            Assert.True(end.Completed);
            Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(Received, $"file{i}.bin"), Ct));
        }
        Assert.Equal(3, _prompts);

        // photoFetch -> the phone answers with an offer carrying our requestId -> no prompt.
        Task<string> fetch = photos.FetchOriginalAsync(PhoneId, 4021);
        var request = await phone.ReceiveAsync<PeerMessage.PhotoFetch>(Ct);
        Assert.Equal(4021, request.Id);
        string photo = WriteSource("IMG_20261026_101500.jpg", 200_000, out byte[] photoBytes);
        var photoEnd = await phone.SendFileAsync("tp", photo, "IMG_20261026_101500.jpg", PhoneId, Ct, request.RequestId);
        Assert.True(photoEnd.Completed);
        string cached = await fetch.WaitAsync(Ct);
        Assert.Equal(photoBytes, await File.ReadAllBytesAsync(cached, Ct));
        Assert.StartsWith(Path.GetFullPath(photos.PhotosRoot), Path.GetFullPath(cached));
        Assert.False(File.Exists(Path.Combine(Received, "IMG_20261026_101500.jpg")));
        Assert.Equal(3, _prompts);

        // An offer naming a requestId we never issued takes the consent path (declined here).
        string stray = WriteSource("stray.jpg", 10, out _);
        await phone.Session.SendAsync(new PeerMessage.TransferOffer("ts",
            [new LinkItem(0, "stray.jpg", 10, 0)], 10, "Phone", ["LAN_TCP"], RequestId: "r-not-ours"), Ct);
        var strayDecision = await phone.ReceiveAsync<PeerMessage.TransferDecision>(Ct);
        Assert.False(strayDecision.Accept);
        Assert.Equal(4, _prompts);

        // The cache goes with the session.
        string sessionFolder = Path.GetDirectoryName(Path.GetDirectoryName(cached)!)!;
        var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _registry.SessionClosed += _ => closed.TrySetResult();
        await phone.Socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", Ct);
        await closed.Task.WaitAsync(Ct);
        Assert.False(Directory.Exists(sessionFolder));
    }

    internal sealed class FakePhone(ClientWebSocket socket, SecureLinkSession session) : IAsyncDisposable
    {
        public ClientWebSocket Socket { get; } = socket;
        public SecureLinkSession Session { get; } = session;

        /// <summary>Next message of type T; skips everything else (helloAck, snapshots, pongs).</summary>
        public async Task<T> ReceiveAsync<T>(CancellationToken ct) where T : PeerMessage
        {
            while (true)
            {
                SecureLinkMessage message = await Session.ReceiveAsync(ct) ?? throw new IOException("closed");
                if (message.Envelope.Payload is T wanted) return wanted;
            }
        }

        /// <summary>Phone-to-PC: offer, wait for the decision, VTC1, then the PC's transferEnd.</summary>
        public async Task<PeerMessage.TransferEnd> SendFileAsync(
            string transferId, string source, string relPath, string phoneId, CancellationToken ct, string? requestId = null)
        {
            long size = new FileInfo(source).Length;
            await Session.SendAsync(new PeerMessage.TransferOffer(transferId,
                [new LinkItem(0, relPath, size, 0)], size, "Phone", ["LAN_TCP"], RequestId: requestId), ct);
            var decision = await ReceiveAsync<PeerMessage.TransferDecision>(ct);
            Assert.True(decision.Accept, decision.Reason);
            await BulkTransfer.SendAsync(decision.TcpHost!, decision.TcpPort!.Value, transferId, decision.AuthToken!, phoneId,
                [new OutboundTransferItem(0, source, relPath)], Veyro.Core.Protocol.Vtc3.KeyDeriver(Session.ExportKey, transferId),
                resume: decision.Resume, ct: ct);
            return await ReceiveAsync<PeerMessage.TransferEnd>(ct);
        }

        public async ValueTask DisposeAsync()
        {
            try
            {
                if (Socket.State == WebSocketState.Open)
                    await Socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
            }
            catch { }
            await Session.DisposeAsync();
        }
    }
}
