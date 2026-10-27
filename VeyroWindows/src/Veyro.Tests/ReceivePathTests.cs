using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Photos;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Third hardware test (ADR-036): phone-to-PC said "declined", photo originals never arrived.
/// These play the Android side as TransferRunner.send really behaves: preferredRoutes
/// [WIFI_DIRECT, LAN_TCP], 8-character transfer ids, 512 KiB chunks, itemBegin/itemEnd mirrored on
/// the session, and after each item it waits for the session <c>itemResult</c> (it never reads
/// the VTC1 ItemResult). The C# sender used by the older tests read the VTC1 one, which is why
/// they passed while the phone stalled.
/// </summary>
[Collection(LinkServerCollection.Name)]
public sealed class ReceivePathTests : IAsyncLifetime
{
    private const int AndroidChunk = 512 * 1024;
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-receive-path-" + Guid.NewGuid().ToString("N"));
    private readonly CancellationTokenSource _timeout = new(TimeSpan.FromSeconds(60));
    private readonly byte[] _pcPublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _phonePublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _secret = RandomNumberGenerator.GetBytes(32);
    private string PcId => DeviceId.FromPublicKey(_pcPublicKey).Value;
    private string PhoneId => DeviceId.FromPublicKey(_phonePublicKey).Value;
    private CancellationToken Ct => _timeout.Token;
    private string Received => Path.Combine(_root, "received");

    private PeerSessionRegistry _registry = null!;
    private FileTrustStore _trustStore = null!;
    private LinkServer _server = null!;
    private int _legacyPrompts;

    public async Task InitializeAsync()
    {
        Directory.CreateDirectory(_root);
        _trustStore = new FileTrustStore(Path.Combine(_root, "trust"), new byte[32]);
        await TrustPhoneAsync(autoAccept: false);
        _registry = new PeerSessionRegistry();
        _server = new LinkServer(PcId, "Test PC", _trustStore, Received, _pcPublicKey,
            _ =>
            {
                Interlocked.Increment(ref _legacyPrompts);
                return Task.FromResult(true);
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

    private Task TrustPhoneAsync(bool autoAccept) => _trustStore.UpsertAsync(new TrustedPeer(
        PhoneId, "Pixel", PlatformType.Android,
        Convert.ToBase64String(_phonePublicKey), Convert.ToBase64String(_secret),
        AutoAcceptTransfers: autoAccept, DateTimeOffset.UtcNow));

    private async Task<SessionTransferTests.FakePhone> ConnectPhoneAsync()
    {
        var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
        await socket.SendAsync(Encoding.UTF8.GetBytes(PeerMessageJson.Encode(new LinkPreamble(PhoneId, "Pixel", "android"))),
            WebSocketMessageType.Text, true, Ct);
        byte[] buffer = new byte[4096];
        WebSocketReceiveResult ack = await socket.ReceiveAsync(buffer, Ct);
        Assert.Equal(LinkPreambleAck.ModeSession, PeerMessageJson.Decode<LinkPreambleAck>(Encoding.UTF8.GetString(buffer, 0, ack.Count))?.Mode);
        var phone = new SessionTransferTests.FakePhone(socket, new SecureLinkSession(socket, PhoneId, _secret, encrypt: true, expectedPeerId: PcId));
        await phone.Session.SendAsync(new PeerMessage.Hello(PhoneId, "Pixel", "android",
            PeerCapabilities.FileTransfer | PeerCapabilities.Photos), Ct);
        await phone.ReceiveAsync<PeerMessage.HelloAck>(Ct);
        return phone;
    }

    private static PeerMessage.TransferOffer AndroidOffer(string transferId, string relPath, long size, string? requestId = null)
        => new(transferId, [new LinkItem(0, relPath, size, 0)], size, "Pixel",
            [RouteWireNames.WifiDirect, RouteWireNames.LanTcp], RequestId: requestId);

    private sealed record AndroidSend(PeerMessage.TransferDecision Decision, PeerMessage.ItemResult? ItemResult, PeerMessage.TransferEnd? End);

    /// <summary>Android's TransferRunner.send, step for step, against the PC's live session.</summary>
    private async Task<AndroidSend> AndroidSendAsync(
        SessionTransferTests.FakePhone phone, string transferId, string relPath, byte[] payload, string? requestId = null)
    {
        await phone.Session.SendAsync(AndroidOffer(transferId, relPath, payload.Length, requestId), Ct);
        var decision = await phone.ReceiveAsync<PeerMessage.TransferDecision>(Ct);
        Assert.Equal(transferId, decision.TransferId);
        if (!decision.Accept) return new AndroidSend(decision, null, null);

        using var tcp = new TcpClient();
        await tcp.ConnectAsync(decision.TcpHost!, decision.TcpPort!.Value, Ct);
        NetworkStream stream = tcp.GetStream();
        long seq = 1;
        await TcpFrameCodec.WriteFrameAsync(stream, new TcpFrame.Auth(seq++, transferId, decision.AuthToken!, PhoneId, TransferRoute.LanTcp), ct: Ct);
        Assert.True(Assert.IsType<TcpFrame.AuthAck>(await TcpFrameCodec.ReadFrameOrNullAsync(stream, Ct)).Ok);

        int chunkCount = (payload.Length + AndroidChunk - 1) / AndroidChunk;
        await TcpFrameCodec.WriteFrameAsync(stream, new TcpFrame.ItemBegin(seq++, transferId, 0, payload.Length, chunkCount, AndroidChunk), ct: Ct);
        await phone.Session.SendAsync(new PeerMessage.ItemBegin(transferId, 0, payload.Length, chunkCount, AndroidChunk), Ct);
        for (int chunk = 0; chunk < chunkCount; chunk++)
        {
            int offset = chunk * AndroidChunk;
            byte[] data = payload.AsSpan(offset, Math.Min(AndroidChunk, payload.Length - offset)).ToArray();
            await TcpFrameCodec.WriteFrameAsync(stream, new TcpFrame.Chunk(seq++, transferId, 0, chunk, data),
                autoFlush: chunk % 4 == 0 || chunk == chunkCount - 1, ct: Ct);
        }
        string sha = Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();
        await TcpFrameCodec.WriteFrameAsync(stream, new TcpFrame.ItemEnd(seq++, transferId, 0, sha), ct: Ct);
        await phone.Session.SendAsync(new PeerMessage.ItemEnd(transferId, 0, sha), Ct);

        // mailbox.awaitItemResult: the session message, with Android's step timeout (shortened).
        PeerMessage.ItemResult itemResult;
        using (var step = CancellationTokenSource.CreateLinkedTokenSource(Ct))
        {
            step.CancelAfter(TimeSpan.FromSeconds(10));
            try
            {
                while (true)
                {
                    var candidate = await phone.ReceiveAsync<PeerMessage.ItemResult>(step.Token);
                    if (candidate.TransferId == transferId) { itemResult = candidate; break; }
                }
            }
            catch (OperationCanceledException) when (!Ct.IsCancellationRequested)
            {
                throw new Xunit.Sdk.XunitException("Receiver timed out verifying item: the PC sent no session itemResult");
            }
        }

        await TcpFrameCodec.WriteFrameAsync(stream, new TcpFrame.TransferEnd(seq, transferId, itemResult.Ok), ct: Ct);
        await phone.Session.SendAsync(new PeerMessage.TransferEnd(transferId, itemResult.Ok), Ct);
        tcp.Client.Shutdown(SocketShutdown.Send);
        var end = await phone.ReceiveAsync<PeerMessage.TransferEnd>(Ct);
        return new AndroidSend(decision, itemResult, end);
    }

    // ── Wire names ────────────────────────────────────────────────────────────────

    [Fact]
    public void RouteWireNames_MatchKotlinTransferRouteNames()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        string relative = Path.Combine("VeyroAndroid", "core", "model", "src", "main", "kotlin", "com", "veyro", "core", "model", "TransferRoute.kt");
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, relative))) dir = dir.Parent;
        Assert.True(dir is not null, "could not locate the Kotlin TransferRoute.kt");
        string source = File.ReadAllText(Path.Combine(dir!.FullName, relative));
        Match body = Regex.Match(source, @"enum class TransferRoute\s*\{(?<body>[^}]*)\}");
        Assert.True(body.Success);
        string[] kotlin = Regex.Matches(body.Groups["body"].Value, @"\b[A-Z][A-Z0-9_]*\b").Select(m => m.Value).ToArray();

        string[] windows = [RouteWireNames.WifiDirect, RouteWireNames.LanTcp];
        Assert.Equal(kotlin.Order(), windows.Order());
        // Every C# data-plane route has a wire name (WebSocket is the in-app fallback label, never
        // sent as a route); the old ToUpperInvariant() spelling is not one of them.
        Assert.Equal(Enum.GetValues<TransferRoute>().Count(route => route != TransferRoute.WebSocket), kotlin.Length);
        Assert.DoesNotContain(TransferRoute.LanTcp.ToString().ToUpperInvariant(), kotlin);
    }

    // ── Phone to PC ───────────────────────────────────────────────────────────────

    [Fact]
    public async Task AndroidSender_PhoneToPc_GetsAFullLanTcpDecisionAndASessionItemResult()
    {
        await using var phone = await ConnectPhoneAsync();
        byte[] payload = RandomNumberGenerator.GetBytes(3 * AndroidChunk + 1234);

        AndroidSend sent = await AndroidSendAsync(phone, "a1b2c3d4", "IMG_0001.jpg", payload);

        PeerMessage.TransferDecision decision = sent.Decision;
        Assert.True(decision.Accept, decision.Reason);
        Assert.Equal("LAN_TCP", decision.Route);
        Assert.Equal("127.0.0.1", decision.TcpHost); // loopback peer; a LAN peer gets the session's local address
        Assert.Equal(decision.TcpHost, decision.FallbackHost);
        Assert.InRange(decision.TcpPort!.Value, LinkServer.PortRangeStart, LinkServer.PortRangeEnd);
        Assert.Matches("^[0-9A-F]{64}$", decision.AuthToken);
        Assert.True(sent.ItemResult!.Ok);
        Assert.True(sent.End!.Completed);
        Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(Received, "IMG_0001.jpg"), Ct));

        // Not left busy: a second phone send on the same session is served too.
        byte[] second = RandomNumberGenerator.GetBytes(10);
        Assert.True((await AndroidSendAsync(phone, "e5f6a7b8", "note.txt", second)).End!.Completed);
        Assert.Equal(2, _legacyPrompts);
    }

    /// <summary>Opening a photo: photoFetch, Android answers with an offer carrying requestId, VTC1, done.</summary>
    [Fact]
    public async Task AndroidSender_PhotoFetchAnswer_CompletesFetchOriginalWithIdenticalBytes()
    {
        using var photos = new PhotoService(_registry, Path.Combine(_root, "cache"));
        _server.RequestedOffers = photos;
        await using var phone = await ConnectPhoneAsync();
        // The server records the phone's capabilities just after sending HelloAck.
        await WaitUntilAsync(() => photos.IsAvailable(PhoneId));

        Task<string> fetch = photos.FetchOriginalAsync(PhoneId, 4021);
        var request = await phone.ReceiveAsync<PeerMessage.PhotoFetch>(Ct);
        byte[] original = RandomNumberGenerator.GetBytes(2 * AndroidChunk + 77);

        AndroidSend sent = await AndroidSendAsync(phone, "9f8e7d6c", "IMG_20261026_101500.jpg", original, request.RequestId);

        Assert.True(sent.Decision.Accept, sent.Decision.Reason);
        Assert.Equal(0, _legacyPrompts); // requested: no prompt
        string path = await fetch.WaitAsync(Ct);
        Assert.Equal(original, await File.ReadAllBytesAsync(path, Ct));
        Assert.StartsWith(Path.GetFullPath(photos.PhotosRoot), Path.GetFullPath(path));
        Assert.False(File.Exists(Path.Combine(Received, "IMG_20261026_101500.jpg")));
    }

    /// <summary>
    /// Stale state: the phone accepted the decision but never opened the data socket. That used
    /// to hold the session's transfer slot forever, so every later offer was "busy" ("declined").
    /// </summary>
    [Fact]
    public async Task AcceptedSenderThatNeverConnects_DoesNotLeaveTheSessionBusy()
    {
        _server.DataConnectTimeout = TimeSpan.FromMilliseconds(300);
        await using var phone = await ConnectPhoneAsync();
        await phone.Session.SendAsync(AndroidOffer("dead0001", "lost.bin", 10), Ct);
        Assert.True((await phone.ReceiveAsync<PeerMessage.TransferDecision>(Ct)).Accept);
        Assert.False((await phone.ReceiveAsync<PeerMessage.TransferEnd>(Ct)).Completed);

        AndroidSend next = await AndroidSendAsync(phone, "live0002", "ok.bin", RandomNumberGenerator.GetBytes(100));
        Assert.True(next.Decision.Accept, next.Decision.Reason);
        Assert.True(next.End!.Completed);
    }

    [Fact]
    public async Task AutoAcceptPeer_SkipsThePrompt()
    {
        await TrustPhoneAsync(autoAccept: true);
        var ui = new FakeConsentUi();
        _server.OfferConsent = new TransferConsentCoordinator(ui).DecideAsync;
        await using var phone = await ConnectPhoneAsync();
        Assert.True((await AndroidSendAsync(phone, "auto0001", "a.bin", RandomNumberGenerator.GetBytes(5))).End!.Completed);
        Assert.Equal(0, ui.Dialogs + ui.Notifications);
        Assert.Equal(0, _legacyPrompts);
    }

    [Fact]
    public async Task AlwaysAcceptTicked_IsPersisted_AndTheNextOfferIsNotPrompted()
    {
        // ADR-038: the first file prompts with "Always accept from this phone" pre-ticked.
        var ui = new FakeConsentUi { Visible = true, AlwaysAccept = true };
        var coordinator = new TransferConsentCoordinator(ui, trustStore: _trustStore);
        string? enabledFor = null;
        coordinator.AutoAcceptEnabled += id => enabledFor = id;
        _server.OfferConsent = coordinator.DecideAsync;
        await using var phone = await ConnectPhoneAsync();

        Assert.True((await AndroidSendAsync(phone, "first001", "a.bin", [1, 2])).End!.Completed);
        Assert.Equal(1, ui.Dialogs);
        Assert.True(_trustStore.GetPeer(PhoneId)!.AutoAcceptTransfers);
        Assert.Equal(PhoneId, enabledFor);
        // Persisted, not just cached: a fresh store over the same file sees it.
        Assert.True(new FileTrustStore(Path.Combine(_root, "trust"), new byte[32]).GetPeer(PhoneId)!.AutoAcceptTransfers);

        Assert.True((await AndroidSendAsync(phone, "second01", "b.bin", [3])).End!.Completed);
        Assert.Equal(1, ui.Dialogs + ui.Notifications);
    }

    [Fact]
    public async Task PcLockAndWallpaper_FromTheLiveSession_ReachTheRegistry()
    {
        var received = new List<PeerMessage>();
        var got = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _registry.DeviceMessageReceived += (peer, message) =>
        {
            Assert.Equal(PhoneId, peer);
            lock (received)
            {
                received.Add(message);
                if (received.Count == 2) got.TrySetResult();
            }
        };
        await using var phone = await ConnectPhoneAsync();
        await phone.Session.SendAsync(new PeerMessage.PcLock(), Ct);
        await phone.Session.SendAsync(new PeerMessage.Wallpaper("/9j/4AAQSkZJRg=="), Ct);
        await got.Task.WaitAsync(Ct);

        Assert.IsType<PeerMessage.PcLock>(received[0]);
        Assert.IsType<PeerMessage.Wallpaper>(received[1]);
        Assert.NotEqual(0, _registry.GetPeerCapabilities(PhoneId) & PeerCapabilities.Wallpaper);
    }

    [Fact]
    public async Task AlwaysAcceptUnticked_OrDecline_LeavesAutoAcceptOff()
    {
        var ui = new FakeConsentUi { Visible = false, AlwaysAccept = false };
        var coordinator = new TransferConsentCoordinator(ui, trustStore: _trustStore);
        TrustedPeer peer = _trustStore.GetPeer(PhoneId)!;

        Assert.Equal(OfferVerdict.Accepted, await coordinator.DecideAsync(new InboundOfferRequest(peer, AndroidOffer("once0001", "a.bin", 1))));
        Assert.False(_trustStore.GetPeer(PhoneId)!.AutoAcceptTransfers);

        ui.AlwaysAccept = true;
        ui.Notification = (_, _) => Task.FromResult<bool?>(false);
        Assert.Equal(OfferVerdict.Declined, await coordinator.DecideAsync(new InboundOfferRequest(peer, AndroidOffer("decl0001", "a.bin", 1))));
        Assert.False(_trustStore.GetPeer(PhoneId)!.AutoAcceptTransfers);
    }

    // ── Consent prompt ────────────────────────────────────────────────────────────

    [Fact]
    public async Task HiddenWindow_AsksWithTheActionableNotification()
    {
        var ui = new FakeConsentUi { Visible = false };
        _server.OfferConsent = new TransferConsentCoordinator(ui).DecideAsync;
        await using var phone = await ConnectPhoneAsync();

        byte[] payload = RandomNumberGenerator.GetBytes(4000);
        AndroidSend sent = await AndroidSendAsync(phone, "hide0001", "doc.pdf", payload);

        Assert.True(sent.Decision.Accept, sent.Decision.Reason);
        Assert.Equal(0, ui.Dialogs);
        Assert.Equal(1, ui.Notifications);
        Assert.Equal("Pixel", ui.LastRequest!.Peer.DisplayName);
        Assert.Equal(payload, await File.ReadAllBytesAsync(Path.Combine(Received, "doc.pdf"), Ct));
    }

    [Fact]
    public async Task UnansweredPrompt_IsDeclinedWithReasonTimeout()
    {
        var ui = new FakeConsentUi { Visible = false, Notification = async (_, ct) => { await Task.Delay(Timeout.Infinite, ct); return true; } };
        _server.OfferConsent = new TransferConsentCoordinator(ui, TimeSpan.FromMilliseconds(300)).DecideAsync;
        await using var phone = await ConnectPhoneAsync();

        AndroidSend sent = await AndroidSendAsync(phone, "slow0001", "a.bin", [1, 2, 3]);
        Assert.False(sent.Decision.Accept);
        Assert.Equal(OfferDeclineReasons.Timeout, sent.Decision.Reason);
        Assert.True(ui.LastToken.IsCancellationRequested); // the toast was withdrawn
    }

    [Fact]
    public async Task DeclineAndPromptFailure_ReachThePhoneAsDeclinedAndError()
    {
        var ui = new FakeConsentUi { Visible = true, Dialog = (_, _) => Task.FromResult(false) };
        _server.OfferConsent = new TransferConsentCoordinator(ui).DecideAsync;
        await using var phone = await ConnectPhoneAsync();
        Assert.Equal(OfferDeclineReasons.Declined, (await AndroidSendAsync(phone, "no000001", "a.bin", [1])).Decision.Reason);

        _server.OfferConsent = _ => throw new InvalidOperationException("no UI");
        Assert.Equal(OfferDeclineReasons.Error, (await AndroidSendAsync(phone, "err00001", "a.bin", [1])).Decision.Reason);
    }

    [Fact]
    public async Task PhoneCancelWhilePromptIsOpen_ClosesThePromptAndFreesTheSession()
    {
        var opened = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var ui = new FakeConsentUi
        {
            Visible = false,
            Notification = async (_, ct) =>
            {
                opened.TrySetResult();
                await Task.Delay(Timeout.Infinite, ct);
                return true;
            }
        };
        _server.OfferConsent = new TransferConsentCoordinator(ui).DecideAsync;
        await using var phone = await ConnectPhoneAsync();
        await phone.Session.SendAsync(AndroidOffer("gone0001", "a.bin", 1), Ct);
        await opened.Task.WaitAsync(Ct);
        await phone.Session.SendAsync(new PeerMessage.Cancel("gone0001", "cancelled"), Ct);
        await WaitUntilAsync(() => ui.LastToken.IsCancellationRequested);

        ui.Notification = (_, _) => Task.FromResult<bool?>(true);
        // The cancelled offer unwinds asynchronously; it may answer "busy" for a moment, never for good.
        AndroidSend next;
        int attempt = 0;
        do next = await AndroidSendAsync(phone, $"next{attempt++:D4}", "b.bin", [7, 7]);
        while (next.Decision.Reason == OfferDeclineReasons.Busy && attempt < 20 && await Delay(50));
        Assert.True(next.Decision.Accept, next.Decision.Reason);
        Assert.True(next.End!.Completed);
    }

    [Fact]
    public async Task Coordinator_NeverShowsTwoPromptsAtOnce_AndFallsBackWhenTheDialogCannotOpen()
    {
        var ui = new FakeConsentUi
        {
            Visible = true,
            // e.g. the pairing dialog is open: ContentDialog.ShowAsync would throw.
            Dialog = (_, _) => throw new InvalidOperationException("Only a single ContentDialog can be open at any time."),
            Notification = async (_, ct) =>
            {
                await Task.Delay(100, ct);
                return true;
            }
        };
        var coordinator = new TransferConsentCoordinator(ui);
        TrustedPeer peer = _trustStore.GetPeer(PhoneId)!;
        Task<OfferVerdict>[] decisions = Enumerable.Range(0, 3)
            .Select(i => coordinator.DecideAsync(new InboundOfferRequest(peer, AndroidOffer($"q{i}", "a.bin", 1))))
            .ToArray();
        Assert.All(await Task.WhenAll(decisions), verdict => Assert.Equal(OfferVerdict.Accepted, verdict));
        Assert.Equal(1, ui.MaxOpen);
        Assert.Equal(3, ui.Dialogs);
        Assert.Equal(3, ui.Notifications);
    }

    [Fact]
    public async Task Coordinator_NotificationBodyClick_AsksAgainInTheWindow()
    {
        var ui = new FakeConsentUi { Visible = false };
        ui.Notification = (_, _) =>
        {
            ui.Visible = true; // the toast brought Veyro forward
            return Task.FromResult<bool?>(null);
        };
        ui.Dialog = (_, _) => Task.FromResult(true);
        var coordinator = new TransferConsentCoordinator(ui);
        TrustedPeer peer = _trustStore.GetPeer(PhoneId)!;
        Assert.Equal(OfferVerdict.Accepted, await coordinator.DecideAsync(new InboundOfferRequest(peer, AndroidOffer("b1", "a.bin", 1))));
        Assert.Equal(1, ui.Notifications);
        Assert.Equal(1, ui.Dialogs);
    }

    // ── tcpHost selection ─────────────────────────────────────────────────────────

    [Fact]
    public void AdvertisedHost_IsTheSessionsLocalAddressThenTheSameSubnet()
    {
        LocalIpv4[] local =
        [
            new(IPAddress.Parse("172.25.16.1"), 20),  // Hyper-V switch
            new(IPAddress.Parse("10.0.0.7"), 8),
            new(IPAddress.Parse("192.168.1.20"), 24),
            new(IPAddress.Parse("169.254.3.3"), 16)
        ];
        IPAddress phone = IPAddress.Parse("192.168.1.50");

        Assert.Equal(IPAddress.Parse("192.168.1.20"),
            LinkServer.ChooseAdvertisableAddress(new SessionAddresses(phone, IPAddress.Parse("192.168.1.20")), local));
        // Local address unknown: the phone's subnet, not the first adapter.
        Assert.Equal(IPAddress.Parse("192.168.1.20"),
            LinkServer.ChooseAdvertisableAddress(new SessionAddresses(phone, null), local));
        // A dual-stack socket reports IPv4-mapped IPv6; it must still match.
        Assert.Equal(IPAddress.Parse("192.168.1.20"),
            LinkServer.ChooseAdvertisableAddress(new SessionAddresses(phone.MapToIPv6(), IPAddress.IPv6Any), local));
        // Prefix length is honoured: 10.x/8 matches a phone at 10.200.1.1.
        Assert.Equal(IPAddress.Parse("10.0.0.7"),
            LinkServer.ChooseAdvertisableAddress(new SessionAddresses(IPAddress.Parse("10.200.1.1"), null), local));
        Assert.Equal(IPAddress.Loopback,
            LinkServer.ChooseAdvertisableAddress(new SessionAddresses(IPAddress.Loopback, IPAddress.Loopback), local));
        Assert.Null(LinkServer.ChooseAdvertisableAddress(new SessionAddresses(phone, null), [new(IPAddress.Parse("169.254.1.1"), 16)]));
    }

    // ── Settings ──────────────────────────────────────────────────────────────────

    [Fact]
    public async Task TrayMode_IsOnByDefaultAndOnlyAnExplicitChoiceTurnsItOff()
    {
        // ADR-047: tray mode is the default; an unchosen stored value takes the default.
        string dir = Path.Combine(_root, "settings");
        Directory.CreateDirectory(dir);
        string file = Path.Combine(dir, "settings.json");

        var fresh = new FileSettingsStore(dir).Current;
        Assert.True(fresh.MinimizeToTrayOnClose);
        Assert.False(fresh.AutoStartDefaultApplied);
        Assert.False(fresh.TrayHintShown);

        // Written by a build where off was the default, but never chosen by the user.
        await File.WriteAllTextAsync(file, """{"DeviceName":"PC","IsDiscoverable":true,"DownloadDirectory":"D:\\x","MinimizeToTrayOnClose":false}""", Ct);
        Assert.True(new FileSettingsStore(dir).Current.MinimizeToTrayOnClose);

        var store = new FileSettingsStore(dir);
        await store.UpdateAsync(s => s with { MinimizeToTrayOnClose = false, TrayModeChosen = true });
        Assert.False(new FileSettingsStore(dir).Current.MinimizeToTrayOnClose);

        await store.UpdateAsync(s => s with { MinimizeToTrayOnClose = true, TrayModeChosen = true });
        Assert.True(new FileSettingsStore(dir).Current.MinimizeToTrayOnClose);
    }

    private async Task WaitUntilAsync(Func<bool> condition)
    {
        while (!condition()) await Task.Delay(20, Ct);
    }

    private async Task<bool> Delay(int milliseconds)
    {
        await Task.Delay(milliseconds, Ct);
        return true;
    }

    private sealed class FakeConsentUi : ITransferConsentUi
    {
        private int _open;
        public volatile bool Visible;
        public Func<InboundOfferRequest, CancellationToken, Task<bool>> Dialog { get; set; } = (_, _) => Task.FromResult(true);
        public Func<InboundOfferRequest, CancellationToken, Task<bool?>> Notification { get; set; } = (_, _) => Task.FromResult<bool?>(true);
        /// <summary>The "Always accept from this phone" box as the user left it.</summary>
        public volatile bool AlwaysAccept;
        public int Dialogs;
        public int Notifications;
        public int MaxOpen;
        public InboundOfferRequest? LastRequest { get; private set; }
        public CancellationToken LastToken { get; private set; }

        public Task<bool> IsWindowVisibleAsync() => Task.FromResult(Visible);

        public async Task<ConsentAnswer> ShowDialogAsync(InboundOfferRequest request, CancellationToken ct)
        {
            Interlocked.Increment(ref Dialogs);
            Enter(request, ct);
            try { return new ConsentAnswer(await Dialog(request, ct), AlwaysAccept); }
            finally { Interlocked.Decrement(ref _open); }
        }

        public async Task<ConsentAnswer?> ShowNotificationAsync(InboundOfferRequest request, CancellationToken ct)
        {
            Interlocked.Increment(ref Notifications);
            Enter(request, ct);
            try { return await Notification(request, ct) is { } accepted ? new ConsentAnswer(accepted, AlwaysAccept) : null; }
            finally { Interlocked.Decrement(ref _open); }
        }

        private void Enter(InboundOfferRequest request, CancellationToken ct)
        {
            LastRequest = request;
            LastToken = ct;
            int open = Interlocked.Increment(ref _open);
            int max;
            while (open > (max = Volatile.Read(ref MaxOpen)) && Interlocked.CompareExchange(ref MaxOpen, open, max) != max) { }
        }
    }
}
