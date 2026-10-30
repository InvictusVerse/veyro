using Veyro.Core.Link;
using Veyro.Core.Photos;
using Veyro.Core.Protocol.Media;
using Xunit;

namespace Veyro.Tests;

/// <summary>ADR-073: photo and scan requests to the phone, and the speaker socket's direction rules.</summary>
public sealed class CaptureServiceTests : IDisposable
{
    private const string Phone = "PHONE";
    private readonly string _root = Path.Combine(Path.GetTempPath(), "veyro-capture-" + Guid.NewGuid().ToString("N"));
    private readonly FakePeerSessions _sessions = new();
    private readonly ManualTimeProvider _time = new();
    private readonly CaptureService _service;

    public CaptureServiceTests()
    {
        Directory.CreateDirectory(_root);
        _service = new CaptureService(_sessions, Path.Combine(_root, "cache"), () => Path.Combine(_root, "Downloads"), _time);
    }

    public void Dispose()
    {
        _service.Dispose();
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    private static PeerMessage.TransferOffer Offer(string? rid, params string[] names) =>
        new("t1", names.Select((n, i) => new LinkItem(i, n, 10, 0)).ToList(), 10L * names.Length, "Phone", ["lan"], RequestId: rid);

    private string SentRid() => Assert.Single(_sessions.SentOf<PeerMessage.CaptureRequest>(Phone)).Rid;

    [Fact]
    public async Task WithoutTheCapabilityNothingIsSent()
    {
        _sessions.Connect(Phone, PeerCapabilities.PcAudio);
        CaptureOutcome outcome = await _service.RequestAsync(Phone, SpeakerCaptureRules.ModePhoto);
        Assert.Equal("unsupported", outcome.Reason);
        Assert.Empty(_sessions.Sent);
    }

    [Fact]
    public async Task OnlyTheRequestedImageIsClaimedAndMovedToAFreeName()
    {
        _sessions.Connect(Phone, PeerCapabilities.PhotoCapture);
        Task<CaptureOutcome> request = _service.RequestAsync(Phone, SpeakerCaptureRules.ModeScan);
        PeerMessage.CaptureRequest sent = Assert.Single(_sessions.SentOf<PeerMessage.CaptureRequest>(Phone));
        Assert.Equal(SpeakerCaptureRules.ModeScan, sent.Mode);

        Assert.Null(_service.TryClaim(Phone, Offer(null, "Scan.jpg")));
        Assert.Null(_service.TryClaim(Phone, Offer("AAAAAAAAAAAAAAAAAAAAAA", "Scan.jpg")));
        Assert.Null(_service.TryClaim("OTHER", Offer(sent.Rid, "Scan.jpg")));
        RequestedOfferClaim claim = Assert.IsType<RequestedOfferClaim>(_service.TryClaim(Phone, Offer(sent.Rid, "Scan.jpg")));
        Assert.Null(_service.TryClaim(Phone, Offer(sent.Rid, "Scan.jpg")));

        string downloads = Path.Combine(_root, "Downloads");
        Directory.CreateDirectory(downloads);
        await File.WriteAllTextAsync(Path.Combine(downloads, "Scan.jpg"), "older");
        string received = Path.Combine(claim.DestinationDirectory, "Scan.jpg");
        await File.WriteAllTextAsync(received, "new");
        _service.OnCompleted(Phone, sent.Rid, [received]);

        CaptureOutcome outcome = await request;
        Assert.True(outcome.Ok);
        Assert.Equal(Path.Combine(downloads, "Scan (2).jpg"), outcome.FilePath);
        Assert.Equal("new", await File.ReadAllTextAsync(outcome.FilePath!));
        Assert.Equal("older", await File.ReadAllTextAsync(Path.Combine(downloads, "Scan.jpg")));
        Assert.False(File.Exists(received));
    }

    [Theory]
    [InlineData("notes.txt")]
    [InlineData("../evil.jpg")]
    public async Task AnOfferThatIsNotOneImageEndsTheRequest(string name)
    {
        _sessions.Connect(Phone, PeerCapabilities.PhotoCapture);
        Task<CaptureOutcome> request = _service.RequestAsync(Phone, SpeakerCaptureRules.ModePhoto);
        Assert.Null(_service.TryClaim(Phone, Offer(SentRid(), name)));
        Assert.Equal("error", (await request).Reason);
    }

    [Fact]
    public async Task TwoFilesAreRefused()
    {
        _sessions.Connect(Phone, PeerCapabilities.PhotoCapture);
        Task<CaptureOutcome> request = _service.RequestAsync(Phone, SpeakerCaptureRules.ModePhoto);
        Assert.Null(_service.TryClaim(Phone, Offer(SentRid(), "a.jpg", "b.jpg")));
        Assert.Equal("error", (await request).Reason);
    }

    [Fact]
    public async Task ThePhonesFailureReasonEndsTheRequest()
    {
        _sessions.Connect(Phone, PeerCapabilities.PhotoCapture);
        Task<CaptureOutcome> request = _service.RequestAsync(Phone, SpeakerCaptureRules.ModePhoto);
        string rid = SentRid();
        _sessions.Receive("OTHER", new PeerMessage.CaptureResult(rid, false, "declined"));
        _sessions.Receive(Phone, new PeerMessage.CaptureResult(rid, false, "bogus"));
        Assert.False(request.IsCompleted);
        _sessions.Receive(Phone, new PeerMessage.CaptureResult(rid, false, "declined"));
        Assert.Equal("declined", (await request).Reason);
    }

    [Fact]
    public async Task ARequestExpiresAndASessionCloseEndsIt()
    {
        _sessions.Connect(Phone, PeerCapabilities.PhotoCapture);
        Task<CaptureOutcome> first = _service.RequestAsync(Phone, SpeakerCaptureRules.ModePhoto);
        _time.Advance(SpeakerCaptureRules.CaptureTimeout - TimeSpan.FromSeconds(1));
        Assert.False(first.IsCompleted);
        _time.Advance(TimeSpan.FromSeconds(1));
        Assert.Equal("expired", (await first).Reason);

        Task<CaptureOutcome> second = _service.RequestAsync(Phone, SpeakerCaptureRules.ModePhoto);
        _sessions.Disconnect(Phone);
        Assert.Equal("network", (await second).Reason);
    }

    [Fact]
    public async Task ANewRequestReplacesTheWaitingOne()
    {
        _sessions.Connect(Phone, PeerCapabilities.PhotoCapture);
        Task<CaptureOutcome> first = _service.RequestAsync(Phone, SpeakerCaptureRules.ModePhoto);
        _ = _service.RequestAsync(Phone, SpeakerCaptureRules.ModeScan);
        Assert.Equal("cancelled", (await first).Reason);
    }

    private sealed class RecordingHandler(string rid) : IRequestedOfferHandler
    {
        public List<string> Completed { get; } = [];
        public RequestedOfferClaim? TryClaim(string peerId, PeerMessage.TransferOffer offer) =>
            offer.RequestId == rid ? new RequestedOfferClaim("x") : null;
        public void OnCompleted(string peerId, string requestId, IReadOnlyList<string> filePaths) => Completed.Add(requestId);
        public void OnFailed(string peerId, string requestId, Exception error) { }
    }

    [Fact]
    public void TheRouterSendsCompletionToTheHandlerThatClaimed()
    {
        var photos = new RecordingHandler("p");
        var capture = new RecordingHandler("c");
        var router = new RequestedOfferRouter(photos, capture);
        Assert.NotNull(router.TryClaim(Phone, Offer("c", "x.jpg")));
        Assert.Null(router.TryClaim(Phone, Offer("z", "x.jpg")));
        router.OnCompleted(Phone, "c", ["x"]);
        router.OnCompleted(Phone, "c", ["x"]);
        Assert.Equal(["c"], capture.Completed);
        Assert.Empty(photos.Completed);
    }

    [Fact]
    public void OnASpeakerSocketOnlyThePcSendsSoundAndNobodySendsVideo()
    {
        Assert.True(Vms1.AllowedFrom(Vms1.KindAudio, fromPhone: false, Vms1.SourceSpeaker));
        Assert.True(Vms1.AllowedFrom(Vms1.KindAudioConfig, fromPhone: false, Vms1.SourceSpeaker));
        Assert.False(Vms1.AllowedFrom(Vms1.KindAudio, fromPhone: true, Vms1.SourceSpeaker));
        Assert.False(Vms1.AllowedFrom(Vms1.KindVideo, fromPhone: true, Vms1.SourceSpeaker));
        Assert.False(Vms1.AllowedFrom(Vms1.KindVideo, fromPhone: false, Vms1.SourceSpeaker));
        Assert.True(Vms1.AllowedFrom(Vms1.KindAudio, fromPhone: true, Vms1.SourceScreen));
        Assert.False(Vms1.AllowedFrom(Vms1.KindAudio, fromPhone: false, Vms1.SourceScreen));
    }
}
