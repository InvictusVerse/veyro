using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Security.Cryptography;
using Veyro.Core.Data;
using Veyro.Core.Link;
using Veyro.Core.Models;
using Veyro.Core.Photos;
using Veyro.Core.Protocol;
using Veyro.Core.Transfer;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Files the phone sends reach the Transfers history: a real LinkServer raises one
/// InboundTransferFinished per offer (completed, cancelled, declined, timed out), never for a photo
/// fetch the PC asked for; the report maps to an incoming TransferRecord.
/// </summary>
[Collection(LinkServerCollection.Name)]
public sealed class ReceivedHistoryTests : IAsyncLifetime
{
    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-received-history-" + Guid.NewGuid().ToString("N"));
    private readonly CancellationTokenSource _timeout = new(TimeSpan.FromSeconds(60));
    private readonly byte[] _pcPublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _phonePublicKey = RandomNumberGenerator.GetBytes(32);
    private readonly byte[] _secret = RandomNumberGenerator.GetBytes(32);
    private readonly BlockingCollection<InboundTransferReport> _reports = [];
    private string PcId => DeviceId.FromPublicKey(_pcPublicKey).Value;
    private string PhoneId => DeviceId.FromPublicKey(_phonePublicKey).Value;
    private CancellationToken Ct => _timeout.Token;
    private PeerSessionRegistry _registry = null!;
    private FileTrustStore _trustStore = null!;
    private LinkServer _server = null!;
    private Func<InboundOfferRequest, bool> _authorize = _ => true;
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
        _server = new LinkServer(PcId, "Test PC", _trustStore, Received, _pcPublicKey,
            request => Task.FromResult(_authorize(request)), sessions: _registry);
        _server.InboundTransferFinished += _reports.Add;
        await _server.StartAsync();
    }

    public async Task DisposeAsync()
    {
        await _server.DisposeAsync();
        _timeout.Dispose();
        _reports.Dispose();
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private InboundTransferReport NextReport() => _reports.Take(Ct);

    private async Task<SessionTransferTests.FakePhone> ConnectPhoneAsync()
    {
        var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{_server.Port}/veyro"), Ct);
        var dial = new V3Dialer(PhoneId);
        Assert.Equal(LinkPreambleAck.ModeSession, (await dial.SendPreambleAsync(socket, "Phone", Ct)).Mode);
        var phone = new SessionTransferTests.FakePhone(socket, new SecureLinkSession(socket, dial.SessionKeys(PcId, _secret)));
        await phone.Session.SendAsync(new PeerMessage.Hello(PhoneId, "Phone", "android",
            PeerCapabilities.FileTransfer | PeerCapabilities.FolderTransfer | PeerCapabilities.Photos), Ct);
        await phone.ReceiveAsync<PeerMessage.HelloAck>(Ct);
        return phone;
    }

    private string WriteSource(string name, int size)
    {
        string path = Path.Combine(_root, "src-" + Guid.NewGuid().ToString("N"), name);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, RandomNumberGenerator.GetBytes(size));
        return path;
    }

    [Fact]
    public async Task CompletedOffer_IsReportedWithItsSavedPath()
    {
        await using var phone = await ConnectPhoneAsync();
        string source = WriteSource("holiday.jpg", 70_000);
        Assert.True((await phone.SendFileAsync("t1", source, "holiday.jpg", PhoneId, Ct)).Completed);

        InboundTransferReport report = NextReport();
        Assert.Equal(InboundTransferOutcome.Completed, report.Outcome);
        Assert.Equal(PhoneId, report.PeerDeviceId);
        Assert.Equal("t1", report.TransferId);
        Assert.Equal(70_000, report.TotalBytes);
        Assert.Null(report.FolderName);
        InboundTransferReportItem item = Assert.Single(report.Items);
        Assert.Equal("holiday.jpg", item.RelativePath);
        Assert.Equal(70_000, item.SizeBytes);
        Assert.Equal(Path.GetFullPath(Path.Combine(Received, "holiday.jpg")), item.SavedPath);
        Assert.True(File.Exists(item.SavedPath));
    }

    [Fact]
    public async Task FolderOffer_IsReportedUnderItsSavedFolderName()
    {
        Directory.CreateDirectory(Path.Combine(Received, "Trip"));
        await using var phone = await ConnectPhoneAsync();
        string a = WriteSource("a.txt", 10);
        string b = WriteSource("b.bin", 20);
        LinkItem[] items = [new(0, "Trip/a.txt", 10, 0), new(1, "Trip/Day 2/b.bin", 20, 0), new(2, "Trip/Empty", 0, 0, IsDir: true)];
        await phone.Session.SendAsync(new PeerMessage.TransferOffer("f1", items, 30, "Phone", ["LAN_TCP"]), Ct);
        var decision = await phone.ReceiveAsync<PeerMessage.TransferDecision>(Ct);
        await BulkTransfer.SendAsync(decision.TcpHost!, decision.TcpPort!.Value, "f1", decision.AuthToken!, PhoneId,
            [new OutboundTransferItem(0, a, "Trip/a.txt"), new OutboundTransferItem(1, b, "Trip/Day 2/b.bin")],
            Vtc3.KeyDeriver(phone.Session.ExportKey, "f1"), resume: decision.Resume, ct: Ct);
        Assert.True((await phone.ReceiveAsync<PeerMessage.TransferEnd>(Ct)).Completed);

        InboundTransferReport report = NextReport();
        Assert.Equal(InboundTransferOutcome.Completed, report.Outcome);
        // "Trip" was taken, so the folder was saved as "Trip (2)" (ADR-064).
        Assert.Equal("Trip (2)", report.FolderName);
        Assert.Equal(["Trip (2)/a.txt", "Trip (2)/Day 2/b.bin"], report.Items.Select(i => i.RelativePath));
        Assert.All(report.Items, i => Assert.True(File.Exists(i.SavedPath)));

        TransferRecord record = ReceivedHistory.ToRecord(report, "Pixel", DateTimeOffset.UtcNow);
        Assert.Equal(Path.GetFullPath(Path.Combine(Received, "Trip (2)")), ReceivedHistory.SavedTarget(record));
    }

    [Fact]
    public async Task CancelledOffer_IsReportedWithoutSavedPaths()
    {
        await using var phone = await ConnectPhoneAsync();
        await phone.Session.SendAsync(new PeerMessage.TransferOffer("c1", [new LinkItem(0, "big.bin", 1_000_000, 0)], 1_000_000, "Phone", ["LAN_TCP"]), Ct);
        Assert.True((await phone.ReceiveAsync<PeerMessage.TransferDecision>(Ct)).Accept);
        await phone.Session.SendAsync(new PeerMessage.Cancel("c1", "user"), Ct);

        InboundTransferReport report = NextReport();
        Assert.Equal(InboundTransferOutcome.Cancelled, report.Outcome);
        Assert.Null(Assert.Single(report.Items).SavedPath);
    }

    [Fact]
    public async Task DeclinedAndTimedOutOffers_AreReported()
    {
        _authorize = _ => false;
        await using var phone = await ConnectPhoneAsync();
        await phone.Session.SendAsync(new PeerMessage.TransferOffer("d1", [new LinkItem(0, "a.txt", 5, 0)], 5, "Phone", ["LAN_TCP"]), Ct);
        Assert.False((await phone.ReceiveAsync<PeerMessage.TransferDecision>(Ct)).Accept);
        InboundTransferReport declined = NextReport();
        Assert.Equal(InboundTransferOutcome.Declined, declined.Outcome);
        Assert.Equal("a.txt", Assert.Single(declined.Items).RelativePath);

        _server.OfferConsent = _ => Task.FromResult(OfferVerdict.TimedOut);
        await phone.Session.SendAsync(new PeerMessage.TransferOffer("d2", [new LinkItem(0, "b.txt", 5, 0)], 5, "Phone", ["LAN_TCP"]), Ct);
        Assert.Equal("timeout", (await phone.ReceiveAsync<PeerMessage.TransferDecision>(Ct)).Reason);
        Assert.Equal(InboundTransferOutcome.TimedOut, NextReport().Outcome);
    }

    [Fact]
    public async Task PhotoFetch_IsNotReported()
    {
        using var photos = new PhotoService(_registry, Path.Combine(_root, "cache"));
        _server.RequestedOffers = photos;
        await using var phone = await ConnectPhoneAsync();

        Task<string> fetch = photos.FetchOriginalAsync(PhoneId, 7);
        var request = await phone.ReceiveAsync<PeerMessage.PhotoFetch>(Ct);
        string photo = WriteSource("IMG_1.jpg", 1000);
        Assert.True((await phone.SendFileAsync("p1", photo, "IMG_1.jpg", PhoneId, Ct, request.RequestId)).Completed);
        await fetch.WaitAsync(Ct);

        // The next report is the ordinary file sent after it, not the photo.
        string file = WriteSource("note.txt", 10);
        Assert.True((await phone.SendFileAsync("n1", file, "note.txt", PhoneId, Ct)).Completed);
        Assert.Equal("n1", NextReport().TransferId);
        Assert.Empty(_reports);
    }

    [Fact]
    public void Report_MapsToAnIncomingRecord()
    {
        string root = Path.Combine(_root, "dl");
        string saved = Path.Combine(root, "a.txt");
        var report = new InboundTransferReport(PhoneId, "t9",
            [new InboundTransferReportItem(0, "a.txt", 5, saved), new InboundTransferReportItem(1, "b.txt", 6, null)],
            11, null, InboundTransferOutcome.Completed, root);
        DateTimeOffset when = DateTimeOffset.UtcNow;

        TransferRecord record = ReceivedHistory.ToRecord(report, "Pixel", when);
        Assert.True(record.IsIncoming);
        Assert.Equal($"rx-{PhoneId}-t9", record.TransferId);
        Assert.Equal("Pixel", record.PeerDisplayName);
        Assert.Equal(when, record.Timestamp);
        Assert.Equal(11, record.TotalBytes);
        Assert.Equal(VerificationOutcome.Verified, record.Verification);
        Assert.Equal(root, record.DestinationFolder);
        Assert.Equal([saved, null], record.Items.Select(i => i.DestinationPath));
        Assert.Equal(saved, ReceivedHistory.SavedTarget(record));

        Assert.Equal(VerificationOutcome.Cancelled, ReceivedHistory.ToRecord(report with { Outcome = InboundTransferOutcome.Cancelled }, "P", when).Verification);
        Assert.Equal(VerificationOutcome.Declined, ReceivedHistory.ToRecord(report with { Outcome = InboundTransferOutcome.TimedOut }, "P", when).Verification);
        Assert.NotNull(ReceivedHistory.ToRecord(report with { Outcome = InboundTransferOutcome.Failed }, "P", when).ErrorMessage);

        // Open / Show in folder never point outside the received-files folder, nor at a send.
        Assert.Null(ReceivedHistory.SavedTarget(record with { Items = [record.Items[0] with { DestinationPath = Path.Combine(_root, "elsewhere.txt") }] }));
        // A folder name that is not one valid segment is ignored; the saved file is used instead.
        Assert.Equal(saved, ReceivedHistory.SavedTarget(record with { FolderName = ".." }));
        Assert.Null(ReceivedHistory.SavedTarget(record with { IsIncoming = false }));
    }

    [Fact]
    public async Task HistoryStore_KeepsLoadingOldRecordsAndStoresReceivedOnes()
    {
        string data = Path.Combine(_root, "data");
        Directory.CreateDirectory(data);
        // A record written before FolderName and the new outcomes existed.
        await File.WriteAllTextAsync(Path.Combine(data, "history.json"),
            """[{"TransferId":"old1","PeerDeviceId":"p","PeerDisplayName":"Phone","IsIncoming":false,"Timestamp":"2026-10-01T10:00:00+00:00","TotalBytes":3,"Verification":1,"Items":[{"Index":0,"RelativePath":"x.txt","SizeBytes":3,"Sha256Hex":""}],"DestinationFolder":null,"ErrorMessage":null}]""", Ct);
        var store = new FileTransferHistoryStore(data);
        TransferRecord old = Assert.Single(store.GetHistory());
        Assert.Null(old.FolderName);
        Assert.Equal(VerificationOutcome.Verified, old.Verification);

        var report = new InboundTransferReport(PhoneId, "t1", [new InboundTransferReportItem(0, "Trip/a.txt", 1, null)],
            1, "Trip", InboundTransferOutcome.Declined, Received);
        await store.UpsertAsync(ReceivedHistory.ToRecord(report, "Pixel", DateTimeOffset.UtcNow));
        TransferRecord stored = new FileTransferHistoryStore(data).GetHistory()[0];
        Assert.True(stored.IsIncoming);
        Assert.Equal("Trip", stored.FolderName);
        Assert.Equal(VerificationOutcome.Declined, stored.Verification);
    }

    /// <summary>Open never starts a received program, script or shortcut; it selects it in Explorer.</summary>
    [Theory]
    [InlineData("setup.exe", false)]
    [InlineData("SETUP.EXE", false)]
    [InlineData("run.cmd", false)]
    [InlineData("tool.ps1", false)]
    [InlineData("link.lnk", false)]
    [InlineData("site.url", false)]
    [InlineData("installer.msi", false)]
    [InlineData("app.msix", false)]
    [InlineData("evil.exe.", false)]
    [InlineData("evil.exe ", false)]
    [InlineData("photo.jpg", true)]
    [InlineData("report.pdf", true)]
    [InlineData("song.mp3", true)]
    [InlineData("notes.txt", true)]
    [InlineData("README", true)]
    public void Open_NeverStartsARunnableFile(string name, bool canOpen)
    {
        Assert.Equal(canOpen, ReceivedHistory.CanOpenDirectly(Path.Combine(_root, "dl", name)));
    }

    [Fact]
    public void Open_AFolderIsAlwaysShown()
    {
        string folder = Path.Combine(_root, "dl", "Trip.exe");
        Directory.CreateDirectory(folder);
        Assert.True(ReceivedHistory.CanOpenDirectly(folder));
    }

    [Fact]
    public async Task HistoryStore_KeepsTheNewestEntriesAndSavesOneAtATime()
    {
        string data = Path.Combine(_root, "cap");
        Directory.CreateDirectory(data);
        var store = new FileTransferHistoryStore(data);
        DateTimeOffset start = DateTimeOffset.UtcNow.AddDays(-1);
        // Many saves at once: none may fail on the shared temp file, and the cap holds.
        var saves = Enumerable.Range(0, FileTransferHistoryStore.MaxRecords + 20).Select(i =>
            Task.Run(() => store.UpsertAsync(new TransferRecord($"t{i}", "p", "Phone", false, start.AddSeconds(i), 1,
                VerificationOutcome.Verified, []))));
        await Task.WhenAll(saves);

        IReadOnlyList<TransferRecord> reloaded = new FileTransferHistoryStore(data).GetHistory();
        Assert.Equal(FileTransferHistoryStore.MaxRecords, reloaded.Count);
        Assert.Equal($"t{FileTransferHistoryStore.MaxRecords + 19}", reloaded[0].TransferId);
        Assert.DoesNotContain(reloaded, r => r.TransferId == "t0");
    }
}
