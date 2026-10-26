using System.Text.Json;
using System.Text.Json.Nodes;
using Veyro.Core.Link;
using Veyro.Core.Photos;
using Xunit;

namespace Veyro.Tests;

/// <summary>Drives protocol/testvectors/link-photos.json (ADR-034).</summary>
public sealed class PhotoVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "link-photos.json")));
    }

    public static TheoryData<int> VectorIndices()
    {
        var data = new TheoryData<int>();
        using var doc = LoadVectors();
        int count = doc.RootElement.GetProperty("vectors").GetArrayLength();
        for (int i = 0; i < count; i++) data.Add(i);
        return data;
    }

    [Theory]
    [MemberData(nameof(VectorIndices))]
    public void Vector_DecodesToExpectedTypeAndReEncodesEquivalently(int index)
    {
        using var doc = LoadVectors();
        JsonElement vector = doc.RootElement.GetProperty("vectors")[index];
        string type = vector.GetProperty("type").GetString()!;
        string json = vector.GetProperty("json").GetString()!;

        Type expected = type switch
        {
            "photosReq" => typeof(LinkMessage.PhotosRequest),
            "photos" => typeof(LinkMessage.Photos),
            "photoThumbReq" => typeof(LinkMessage.PhotoThumbRequest),
            "photoThumb" => typeof(LinkMessage.PhotoThumb),
            "photoFetch" => typeof(LinkMessage.PhotoFetch),
            "photoNew" => typeof(LinkMessage.PhotoNew),
            "photoRemoved" => typeof(LinkMessage.PhotoRemoved),
            _ => throw new InvalidOperationException($"Unexpected vector type {type}")
        };
        LinkMessage? decoded = LinkJson.Decode<LinkMessage>(json);
        Assert.NotNull(decoded);
        Assert.IsType(expected, decoded);

        string reEncoded = LinkJson.Encode(decoded);
        Assert.True(
            JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(reEncoded)),
            $"Re-encoded JSON differs for vector {index}: {reEncoded}");
    }

    [Fact]
    public void OfferRequestId_RoundTripsAndTakesPartInEquality()
    {
        var offer = new LinkMessage.TransferOffer("t1", [new LinkItem(0, "a.jpg", 3, 0)], 3, "Phone", ["LAN_TCP"], RequestId: "r-1");
        string json = LinkJson.Encode<LinkMessage>(offer);
        Assert.Contains("\"requestId\":\"r-1\"", json);
        Assert.Equal(offer, LinkJson.Decode<LinkMessage>(json));
        Assert.NotEqual(offer, offer with { RequestId = "r-2" });
        Assert.DoesNotContain("requestId", LinkJson.Encode<LinkMessage>(offer with { RequestId = null }));
    }
}

public sealed class PhotoServiceTests : IDisposable
{
    private const string Peer = "PHONE";
    private const int Photos = LinkCapabilities.Photos;
    private static readonly string Jpeg = Convert.ToBase64String([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3]);

    private readonly string _root = Path.Combine(AppContext.BaseDirectory, "veyro-photos-" + Guid.NewGuid().ToString("N"));
    private readonly FakePeerSessions _sessions = new();
    private readonly ManualTimeProvider _time = new();

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch { }
    }

    private PhotoService NewService(int capacity = PhotoService.DefaultThumbnailCapacity)
    {
        var service = new PhotoService(_sessions, _root, _time, capacity);
        _sessions.Connect(Peer, Photos);
        return service;
    }

    private static PhotoInfo Info(long id, long ts) => new(id, $"IMG_{id}.jpg", ts);

    [Fact]
    public async Task Availability_FollowsConnectionAndCapability()
    {
        using var service = new PhotoService(_sessions, _root, _time);
        var changes = new List<PhotoChange>();
        service.Changed += changes.Add;
        Assert.False(service.IsAvailable(Peer));
        _sessions.Connect(Peer, LinkCapabilities.Messaging);
        Assert.False(service.IsAvailable(Peer));
        await Assert.ThrowsAsync<InvalidOperationException>(() => service.RequestListAsync(Peer));
        await service.RequestThumbnailsAsync(Peer, [1L]); // silently nothing
        Assert.Empty(_sessions.Sent);
        _sessions.Connect(Peer, Photos);
        Assert.True(service.IsAvailable(Peer));
        Assert.Contains(changes, c => c.Kind == PhotoChangeKind.Availability);
    }

    [Fact]
    public async Task RequestList_SendsRequestAndResolvesNewestFirstWithoutDuplicates()
    {
        using var service = NewService();
        Task<IReadOnlyList<PhotoInfo>> pending = service.RequestListAsync(Peer);
        Task<IReadOnlyList<PhotoInfo>> joined = service.RequestListAsync(Peer);
        Assert.Equal(PhotoService.ListLimit, Assert.Single(_sessions.SentOf<LinkMessage.PhotosRequest>(Peer)).Limit);

        _sessions.Receive(Peer, new LinkMessage.Photos([Info(1, 100), Info(3, 300), Info(2, 200), Info(3, 300)]));
        IReadOnlyList<PhotoInfo> list = await pending;
        Assert.Equal([3L, 2L, 1L], list.Select(p => p.Id));
        Assert.Same(list, await joined);
        Assert.True(service.HasList(Peer));
        Assert.Equal([3L, 2L, 1L], service.GetPhotos(Peer).Select(p => p.Id));
    }

    [Fact]
    public async Task RequestList_TimesOut()
    {
        using var service = NewService();
        Task<IReadOnlyList<PhotoInfo>> pending = service.RequestListAsync(Peer);
        _time.Advance(PhotoService.ListTimeout);
        await Assert.ThrowsAsync<TimeoutException>(() => pending);
    }

    [Fact]
    public async Task Thumbnails_AreBatchedDeduplicatedAndCached()
    {
        using var service = NewService();
        _sessions.Receive(Peer, new LinkMessage.Photos(Enumerable.Range(1, 120).Select(i => Info(i, i)).ToList()));

        await service.RequestThumbnailsAsync(Peer, Enumerable.Range(1, 120).Select(i => (long)i).Concat([5L, 999L]));
        var requests = _sessions.SentOf<LinkMessage.PhotoThumbRequest>(Peer);
        Assert.Equal([50, 50, 20], requests.Select(r => r.Ids.Count));
        Assert.All(requests, r => Assert.Equal(PhotoService.ThumbnailSize, r.Size));
        Assert.Equal(120, requests.SelectMany(r => r.Ids).Distinct().Count());
        Assert.DoesNotContain(999L, requests.SelectMany(r => r.Ids)); // not in the list

        // In flight: asking again sends nothing.
        await service.RequestThumbnailsAsync(Peer, [1L, 2L]);
        Assert.Equal(3, _sessions.SentOf<LinkMessage.PhotoThumbRequest>(Peer).Count);

        var changes = new List<PhotoChange>();
        service.Changed += changes.Add;
        _sessions.Receive(Peer, new LinkMessage.PhotoThumb(1, Jpeg));
        _sessions.Receive(Peer, new LinkMessage.PhotoThumb(2));
        Assert.Equal(Convert.FromBase64String(Jpeg), service.GetThumbnail(Peer, 1));
        Assert.Null(service.GetThumbnail(Peer, 2));
        Assert.True(service.HasNoThumbnail(Peer, 2));
        Assert.Equal([1L, 2L], changes.Where(c => c.Kind == PhotoChangeKind.Thumbnail).Select(c => c.PhotoId!.Value));

        // Cached, known-missing, and (after the retry delay) unanswered ids.
        _time.Advance(PhotoService.ThumbnailRetryAfter);
        await service.RequestThumbnailsAsync(Peer, [1L, 2L, 3L]);
        Assert.Equal([3L], _sessions.SentOf<LinkMessage.PhotoThumbRequest>(Peer)[^1].Ids);
    }

    [Fact]
    public void Thumbnails_RejectNonJpegAndUnsolicitedIds()
    {
        using var service = NewService();
        _sessions.Receive(Peer, new LinkMessage.Photos([Info(1, 1)]));
        _sessions.Receive(Peer, new LinkMessage.PhotoThumb(1, Convert.ToBase64String([1, 2, 3, 4])));
        Assert.Null(service.GetThumbnail(Peer, 1));
        Assert.True(service.HasNoThumbnail(Peer, 1));
        _sessions.Receive(Peer, new LinkMessage.PhotoThumb(77, Jpeg));
        Assert.Null(service.GetThumbnail(Peer, 77));
    }

    [Fact]
    public void ThumbnailCache_EvictsLeastRecentlyUsed()
    {
        using var service = NewService(capacity: 3);
        _sessions.Receive(Peer, new LinkMessage.Photos(Enumerable.Range(1, 4).Select(i => Info(i, i)).ToList()));
        foreach (long id in new long[] { 1, 2, 3 }) _sessions.Receive(Peer, new LinkMessage.PhotoThumb(id, Jpeg));
        Assert.NotNull(service.GetThumbnail(Peer, 1)); // 1 is now the most recent
        _sessions.Receive(Peer, new LinkMessage.PhotoThumb(4, Jpeg));
        Assert.Null(service.GetThumbnail(Peer, 2));
        Assert.NotNull(service.GetThumbnail(Peer, 1));
        Assert.NotNull(service.GetThumbnail(Peer, 3));
        Assert.NotNull(service.GetThumbnail(Peer, 4));
    }

    [Fact]
    public void PhotoNewAndRemoved_AreMergedInOrder()
    {
        using var service = NewService();
        _sessions.Receive(Peer, new LinkMessage.Photos([Info(1, 100), Info(3, 300)]));
        var changes = new List<PhotoChange>();
        service.Changed += changes.Add;

        _sessions.Receive(Peer, new LinkMessage.PhotoNew(Info(2, 200)));
        _sessions.Receive(Peer, new LinkMessage.PhotoNew(Info(4, 400)));
        _sessions.Receive(Peer, new LinkMessage.PhotoNew(Info(4, 400) with { Name = "renamed.jpg" }));
        Assert.Equal([4L, 3L, 2L, 1L], service.GetPhotos(Peer).Select(p => p.Id));
        Assert.Equal("renamed.jpg", service.GetPhoto(Peer, 4)!.Name);

        _sessions.Receive(Peer, new LinkMessage.PhotoRemoved(3));
        _sessions.Receive(Peer, new LinkMessage.PhotoRemoved(99)); // unknown: no change
        Assert.Equal([4L, 2L, 1L], service.GetPhotos(Peer).Select(p => p.Id));
        Assert.Equal(4, changes.Count(c => c.Kind == PhotoChangeKind.List));
    }

    [Fact]
    public async Task FetchOriginal_IssuesRequestIdAndCompletesWhenTheClaimedTransferFinishes()
    {
        using var service = NewService();
        Task<string> fetch = service.FetchOriginalAsync(Peer, 42);
        Task<string> joined = service.FetchOriginalAsync(Peer, 42);
        LinkMessage.PhotoFetch request = Assert.Single(_sessions.SentOf<LinkMessage.PhotoFetch>(Peer));
        Assert.Equal(42, request.Id);
        Assert.Matches("^[0-9a-f]{32}$", request.RequestId);

        LinkMessage.TransferOffer Offer(string? requestId) =>
            new("t1", [new LinkItem(0, "IMG_42.jpg", 3, 0)], 3, "Phone", ["LAN_TCP"], RequestId: requestId);

        // Only our own pending requestId is claimed, and only once.
        Assert.Null(service.TryClaim(Peer, Offer(null)));
        Assert.Null(service.TryClaim(Peer, Offer("r-unknown")));
        Assert.Null(service.TryClaim("OTHER", Offer(request.RequestId)));
        RequestedOfferClaim claim = Assert.IsType<RequestedOfferClaim>(service.TryClaim(Peer, Offer(request.RequestId)));
        Assert.Null(service.TryClaim(Peer, Offer(request.RequestId)));
        Assert.StartsWith(Path.GetFullPath(service.PhotosRoot) + Path.DirectorySeparatorChar, Path.GetFullPath(claim.DestinationDirectory));
        Assert.True(Directory.Exists(claim.DestinationDirectory));

        // The offer timer stops once claimed.
        _time.Advance(PhotoService.OfferTimeout);
        Assert.False(fetch.IsCompleted);

        string path = Path.Combine(claim.DestinationDirectory, "IMG_42.jpg");
        await File.WriteAllBytesAsync(path, [1, 2, 3]);
        service.OnCompleted(Peer, request.RequestId, [path]);
        Assert.Equal(path, await fetch);
        Assert.Equal(path, await joined);
        Assert.Equal(path, service.GetCachedOriginal(Peer, 42));

        // Cached: no second photoFetch.
        Assert.Equal(path, await service.FetchOriginalAsync(Peer, 42));
        Assert.Single(_sessions.SentOf<LinkMessage.PhotoFetch>(Peer));
    }

    [Fact]
    public async Task FetchOriginal_FailsWhenTheOfferNeverComesOrIsUnexpected()
    {
        using var service = NewService();
        Task<string> slow = service.FetchOriginalAsync(Peer, 1);
        _time.Advance(PhotoService.OfferTimeout);
        await Assert.ThrowsAsync<TimeoutException>(() => slow);

        Task<string> odd = service.FetchOriginalAsync(Peer, 2);
        string requestId = _sessions.SentOf<LinkMessage.PhotoFetch>(Peer)[^1].RequestId;
        var twoFiles = new LinkMessage.TransferOffer("t2",
            [new LinkItem(0, "a.jpg", 1, 0), new LinkItem(1, "b.jpg", 1, 0)], 2, "Phone", ["LAN_TCP"], RequestId: requestId);
        Assert.Null(service.TryClaim(Peer, twoFiles));
        await Assert.ThrowsAsync<InvalidDataException>(() => odd);

        Task<string> failed = service.FetchOriginalAsync(Peer, 3);
        string third = _sessions.SentOf<LinkMessage.PhotoFetch>(Peer)[^1].RequestId;
        Assert.NotNull(service.TryClaim(Peer, new LinkMessage.TransferOffer("t3", [new LinkItem(0, "c.jpg", 1, 0)], 1, "Phone", ["LAN_TCP"], RequestId: third)));
        service.OnFailed(Peer, third, new IOException("boom"));
        await Assert.ThrowsAsync<IOException>(() => failed);
    }

    [Fact]
    public async Task SessionClose_FailsPendingClearsStateAndDeletesTheSessionFolder()
    {
        using var service = NewService();
        _sessions.Receive(Peer, new LinkMessage.Photos([Info(1, 1)]));
        Task<string> fetch = service.FetchOriginalAsync(Peer, 1);
        string requestId = Assert.Single(_sessions.SentOf<LinkMessage.PhotoFetch>(Peer)).RequestId;
        RequestedOfferClaim claim = service.TryClaim(Peer,
            new LinkMessage.TransferOffer("t", [new LinkItem(0, "x.jpg", 1, 0)], 1, "Phone", ["LAN_TCP"], RequestId: requestId))!;
        await File.WriteAllBytesAsync(Path.Combine(claim.DestinationDirectory, "x.jpg"), [9]);

        _sessions.Disconnect(Peer);
        await Assert.ThrowsAsync<IOException>(() => fetch);
        Assert.False(service.HasList(Peer));
        Assert.Empty(service.GetPhotos(Peer));
        Assert.False(Directory.Exists(claim.DestinationDirectory));
        // A late claim for the dropped request is not honoured.
        Assert.Null(service.TryClaim(Peer,
            new LinkMessage.TransferOffer("t", [new LinkItem(0, "x.jpg", 1, 0)], 1, "Phone", ["LAN_TCP"], RequestId: requestId)));
    }

    [Fact]
    public async Task Dispose_DeletesTheCacheAndStartupRemovesLeftovers()
    {
        string leftover = Path.Combine(_root, "photos", "old-session");
        Directory.CreateDirectory(leftover);
        await File.WriteAllBytesAsync(Path.Combine(leftover, "x.jpg"), [1]);
        var service = NewService();
        Assert.False(Directory.Exists(leftover));

        Task<string> fetch = service.FetchOriginalAsync(Peer, 1);
        string requestId = Assert.Single(_sessions.SentOf<LinkMessage.PhotoFetch>(Peer)).RequestId;
        RequestedOfferClaim claim = service.TryClaim(Peer,
            new LinkMessage.TransferOffer("t", [new LinkItem(0, "x.jpg", 1, 0)], 1, "Phone", ["LAN_TCP"], RequestId: requestId))!;
        Assert.True(Directory.Exists(claim.DestinationDirectory));
        service.Dispose();
        await Assert.ThrowsAsync<ObjectDisposedException>(() => fetch);
        Assert.False(Directory.Exists(service.PhotosRoot));
    }
}
