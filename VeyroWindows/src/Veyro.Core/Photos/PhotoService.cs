using Veyro.Core.Diagnostics;
using Veyro.Core.Link;
using Veyro.Core.Protocol;

namespace Veyro.Core.Photos;

public enum PhotoChangeKind
{
    /// <summary>Connection or Photos capability changed.</summary>
    Availability,
    /// <summary>The list changed (photos, photoNew, photoRemoved).</summary>
    List,
    /// <summary>The thumbnail of <see cref="PhotoChange.PhotoId"/> arrived (or the phone has none).</summary>
    Thumbnail,
    /// <summary>The session closed; everything for the peer is gone.</summary>
    Cleared
}

public sealed record PhotoChange(string PeerId, PhotoChangeKind Kind, long? PhotoId = null);

/// <summary>The phone's recent photos as Phone Link shows them (04-PROTOCOL.md 6.4, ADR-034).</summary>
public interface IPhotoService
{
    /// <summary>Raised on the session or timer thread; UI subscribers marshal to their dispatcher.</summary>
    event Action<PhotoChange>? Changed;

    /// <summary>Connected and advertising <see cref="LinkCapabilities.Photos"/>.</summary>
    bool IsAvailable(string peerId);

    /// <summary>True once a list arrived in this session.</summary>
    bool HasList(string peerId);

    /// <summary>Newest first.</summary>
    IReadOnlyList<PhotoInfo> GetPhotos(string peerId);

    PhotoInfo? GetPhoto(string peerId, long id);

    /// <summary>Sends <c>photosReq</c> and waits up to 15 s for <c>photos</c>.</summary>
    Task<IReadOnlyList<PhotoInfo>> RequestListAsync(string peerId, CancellationToken ct = default);

    /// <summary>
    /// Requests the thumbnails among <paramref name="ids"/> that are not cached, not known to be
    /// missing and not already requested, in batches of at most 50. Does nothing when unavailable.
    /// </summary>
    Task RequestThumbnailsAsync(string peerId, IEnumerable<long> ids, CancellationToken ct = default);

    /// <summary>Cached JPEG bytes, or null. Marks the entry recently used.</summary>
    byte[]? GetThumbnail(string peerId, long id);

    /// <summary>The phone answered without a thumbnail for this id.</summary>
    bool HasNoThumbnail(string peerId, long id);

    /// <summary>The original, if it was already fetched in this session.</summary>
    string? GetCachedOriginal(string peerId, long id);

    /// <summary>
    /// Sends <c>photoFetch</c> with a new requestId and completes with the path of the original
    /// once the phone's answering transfer finished, in this session's cache folder. Fails with
    /// <see cref="TimeoutException"/> if the offer does not start within 30 s or the whole fetch
    /// takes longer than 60 s.
    /// </summary>
    Task<string> FetchOriginalAsync(string peerId, long id, CancellationToken ct = default);
}

/// <summary>
/// UI-free photo state per peer (ADR-034). Metadata and thumbnails live in memory (thumbnails in
/// an LRU of about 300); originals are written to a per-session folder under the cache root and
/// deleted when the session closes or the service is disposed. File names and images are user
/// data: only counts and ids are logged.
/// </summary>
public sealed class PhotoService : IPhotoService, IRequestedOfferHandler, IDisposable
{
    public const int ListLimit = 2000;
    public const int MaxThumbnailBatch = 50;
    public const int ThumbnailSize = 256;
    public const int DefaultThumbnailCapacity = 300;
    /// <summary>The phone caps a thumbnail at 48 KiB; anything far above that is not one.</summary>
    public const int MaxThumbnailBytes = 64 * 1024;
    public static readonly TimeSpan ListTimeout = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan ThumbnailRetryAfter = TimeSpan.FromSeconds(20);
    /// <summary>How long the phone has to start the transfer answering a photoFetch.</summary>
    public static readonly TimeSpan OfferTimeout = TimeSpan.FromSeconds(30);
    /// <summary>
    /// Upper bound for the whole fetch, request to verified file, so the viewer always ends in a
    /// photo or an error (never an endless spinner). A photo is one file; 60 s is ample on a LAN.
    /// </summary>
    public static readonly TimeSpan FetchTimeout = TimeSpan.FromSeconds(60);
    /// <summary>Shown when the phone never sent the photo or did not finish in time.</summary>
    public const string NotSentMessage = "The phone did not send the photo.";

    private readonly IPeerSessionRegistry _sessions;
    private readonly TimeProvider _time;
    private readonly string _photosRoot;
    private readonly int _thumbnailCapacity;
    private readonly object _gate = new();
    private readonly Dictionary<string, PeerState> _byPeer = new(StringComparer.OrdinalIgnoreCase);
    private bool _disposed;

    public event Action<PhotoChange>? Changed;

    /// <param name="cacheRoot">The app's local cache folder; originals go to <c>photos\&lt;session&gt;</c> below it.</param>
    public PhotoService(IPeerSessionRegistry sessions, string cacheRoot, TimeProvider? time = null, int thumbnailCapacity = DefaultThumbnailCapacity)
    {
        _sessions = sessions;
        _time = time ?? TimeProvider.System;
        _thumbnailCapacity = Math.Max(1, thumbnailCapacity);
        _photosRoot = Path.Combine(cacheRoot, "photos");
        // Leftovers of a previous run that did not exit cleanly.
        TryDeleteDirectory(_photosRoot);
        _sessions.PhotosMessageReceived += OnPhotosMessage;
        _sessions.SessionOpened += OnSessionOpened;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
    }

    /// <summary>Root of the per-session folders, for tests and diagnostics.</summary>
    public string PhotosRoot => _photosRoot;

    public bool IsAvailable(string peerId)
        => _sessions.IsConnected(peerId) && (_sessions.GetPeerCapabilities(peerId) & LinkCapabilities.Photos) != 0;

    public bool HasList(string peerId)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) && state.HasList;
    }

    public IReadOnlyList<PhotoInfo> GetPhotos(string peerId)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) ? state.Photos.ToList() : [];
    }

    public PhotoInfo? GetPhoto(string peerId, long id)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) && state.ById.TryGetValue(id, out var info) ? info : null;
    }

    public async Task<IReadOnlyList<PhotoInfo>> RequestListAsync(string peerId, CancellationToken ct = default)
    {
        EnsureAvailable(peerId);
        TaskCompletionSource<IReadOnlyList<PhotoInfo>> waiter;
        bool send = false;
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            if (state.ListWaiter is { } existing) waiter = existing;
            else
            {
                waiter = new TaskCompletionSource<IReadOnlyList<PhotoInfo>>(TaskCreationOptions.RunContinuationsAsynchronously);
                state.ListWaiter = waiter;
                state.ListTimer = _time.CreateTimer(_ => TimeOutList(peerId, waiter), null, ListTimeout, Timeout.InfiniteTimeSpan);
                send = true;
            }
        }
        if (send)
        {
            try
            {
                await _sessions.SendAsync(peerId, new LinkMessage.PhotosRequest(ListLimit), ct).ConfigureAwait(false);
            }
            catch (Exception error)
            {
                lock (_gate)
                {
                    if (_byPeer.TryGetValue(peerId, out var state) && ReferenceEquals(state.ListWaiter, waiter)) ClearListWaiter(state);
                }
                waiter.TrySetException(error);
                throw;
            }
        }
        return await waiter.Task.WaitAsync(ct).ConfigureAwait(false);
    }

    public async Task RequestThumbnailsAsync(string peerId, IEnumerable<long> ids, CancellationToken ct = default)
    {
        if (!IsAvailable(peerId)) return;
        List<long> wanted = [];
        DateTimeOffset now = _time.GetUtcNow();
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            foreach (long id in ids.Distinct())
            {
                if (state.HasList && !state.ById.ContainsKey(id)) continue;
                if (state.Thumbnails.Contains(id) || state.NoThumbnail.Contains(id)) continue;
                if (state.ThumbnailsInFlight.TryGetValue(id, out var since) && now - since < ThumbnailRetryAfter) continue;
                state.ThumbnailsInFlight[id] = now;
                wanted.Add(id);
            }
        }
        for (int offset = 0; offset < wanted.Count; offset += MaxThumbnailBatch)
        {
            List<long> batch = wanted.GetRange(offset, Math.Min(MaxThumbnailBatch, wanted.Count - offset));
            try
            {
                await _sessions.SendAsync(peerId, new LinkMessage.PhotoThumbRequest(batch, ThumbnailSize), ct).ConfigureAwait(false);
            }
            catch
            {
                // Nothing was sent for this batch or the ones after it: allow asking again.
                lock (_gate)
                {
                    if (_byPeer.TryGetValue(peerId, out var state))
                        foreach (long id in wanted.Skip(offset)) state.ThumbnailsInFlight.Remove(id);
                }
                throw;
            }
        }
    }

    public byte[]? GetThumbnail(string peerId, long id)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) ? state.Thumbnails.Get(id) : null;
    }

    public bool HasNoThumbnail(string peerId, long id)
    {
        lock (_gate) return _byPeer.TryGetValue(peerId, out var state) && state.NoThumbnail.Contains(id);
    }

    public string? GetCachedOriginal(string peerId, long id)
    {
        lock (_gate)
            return _byPeer.TryGetValue(peerId, out var state) && state.Originals.TryGetValue(id, out var path) && File.Exists(path)
                ? path
                : null;
    }

    public async Task<string> FetchOriginalAsync(string peerId, long id, CancellationToken ct = default)
    {
        EnsureAvailable(peerId);
        Task<string> task;
        LinkMessage.PhotoFetch? request = null;
        lock (_gate)
        {
            PeerState state = StateOf(peerId);
            if (state.Originals.TryGetValue(id, out var cached) && File.Exists(cached)) return cached;
            if (state.FetchByPhoto.TryGetValue(id, out var running)) task = running.Completion.Task;
            else
            {
                string requestId = Guid.NewGuid().ToString("N");
                var pending = new PendingFetch(id, requestId, _time.GetUtcNow());
                state.Fetches[requestId] = pending;
                state.FetchByPhoto[id] = pending;
                pending.Timer = _time.CreateTimer(
                    _ => Fail(peerId, requestId, new TimeoutException(NotSentMessage)),
                    null, OfferTimeout, Timeout.InfiniteTimeSpan);
                task = pending.Completion.Task;
                request = new LinkMessage.PhotoFetch(id, requestId);
            }
        }
        if (request is not null)
        {
            VeyroTrace.Info($"Photo fetch requested: id={id}");
            try { await _sessions.SendAsync(peerId, request, ct).ConfigureAwait(false); }
            catch (Exception error)
            {
                Fail(peerId, request.RequestId, error);
                throw;
            }
        }
        return await task.WaitAsync(ct).ConfigureAwait(false);
    }

    // ── IRequestedOfferHandler (session thread) ─────────────────────────────────────────

    public RequestedOfferClaim? TryClaim(string peerId, LinkMessage.TransferOffer offer)
    {
        if (offer.RequestId is not { } requestId) return null;
        string directory;
        lock (_gate)
        {
            if (_disposed || !_byPeer.TryGetValue(peerId, out var state) ||
                !state.Fetches.TryGetValue(requestId, out var pending) || pending.Claimed)
                return null;
            // A photo is one file. Anything else is not what we asked for: it takes the normal
            // consent path and this request fails.
            bool expected = offer.Items is { Count: 1 } items && !items[0].IsDir && RelativePath.IsValid(items[0].RelPath);
            if (!expected)
            {
                directory = string.Empty;
            }
            else
            {
                pending.Claimed = true;
                pending.Timer?.Dispose();
                // The rest of the fetch budget, counted from the request.
                TimeSpan remaining = pending.RequestedAt + FetchTimeout - _time.GetUtcNow();
                pending.Timer = _time.CreateTimer(
                    _ => Fail(peerId, requestId, new TimeoutException(NotSentMessage)),
                    null, remaining > TimeSpan.Zero ? remaining : TimeSpan.Zero, Timeout.InfiniteTimeSpan);
                // requestId is ours (a hex GUID), so it is safe as a folder name.
                directory = Path.Combine(state.Folder, requestId);
            }
        }
        if (directory.Length == 0)
        {
            Fail(peerId, requestId, new InvalidDataException("The phone answered with an unexpected transfer."));
            return null;
        }
        Directory.CreateDirectory(directory);
        return new RequestedOfferClaim(directory);
    }

    public void OnCompleted(string peerId, string requestId, IReadOnlyList<string> filePaths)
    {
        if (filePaths.Count == 0)
        {
            Fail(peerId, requestId, new InvalidDataException("The transfer carried no file."));
            return;
        }
        PendingFetch? pending;
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var state) || !state.Fetches.Remove(requestId, out pending)) return;
            if (state.FetchByPhoto.TryGetValue(pending.PhotoId, out var current) && ReferenceEquals(current, pending))
                state.FetchByPhoto.Remove(pending.PhotoId);
            state.Originals[pending.PhotoId] = filePaths[0];
            pending.Timer?.Dispose();
        }
        VeyroTrace.Info($"Photo fetched: id={pending.PhotoId}");
        pending.Completion.TrySetResult(filePaths[0]);
    }

    public void OnFailed(string peerId, string requestId, Exception error) => Fail(peerId, requestId, error);

    public void Dispose()
    {
        List<PeerState> states;
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            states = _byPeer.Values.ToList();
            _byPeer.Clear();
        }
        _sessions.PhotosMessageReceived -= OnPhotosMessage;
        _sessions.SessionOpened -= OnSessionOpened;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
        foreach (PeerState state in states) Abandon(state, new ObjectDisposedException(nameof(PhotoService)));
        TryDeleteDirectory(_photosRoot);
    }

    // ── Inbound (session thread) ──────────────────────────────────────────────────────

    private void OnPhotosMessage(string peerId, LinkMessage message)
    {
        switch (message)
        {
            case LinkMessage.Photos photos: OnList(peerId, photos); break;
            case LinkMessage.PhotoThumb thumb: OnThumbnail(peerId, thumb); break;
            case LinkMessage.PhotoNew added: OnPhotoNew(peerId, added); break;
            case LinkMessage.PhotoRemoved removed: OnPhotoRemoved(peerId, removed); break;
        }
    }

    private void OnList(string peerId, LinkMessage.Photos message)
    {
        TaskCompletionSource<IReadOnlyList<PhotoInfo>>? waiter;
        IReadOnlyList<PhotoInfo> photos;
        lock (_gate)
        {
            if (_disposed) return;
            PeerState state = StateOf(peerId);
            // Untrusted input: drop malformed entries and duplicate ids, sort newest first.
            state.SetPhotos((message.Items ?? []).Select(Sanitize).OfType<PhotoInfo>());
            state.HasList = true;
            // A refresh may follow the user granting access on the phone: ask again for misses.
            state.NoThumbnail.Clear();
            photos = state.Photos.ToList();
            waiter = state.ListWaiter;
            ClearListWaiter(state);
        }
        VeyroTrace.Info($"Photo list received: count={photos.Count}");
        waiter?.TrySetResult(photos);
        Raise(new PhotoChange(peerId, PhotoChangeKind.List));
    }

    private void OnThumbnail(string peerId, LinkMessage.PhotoThumb message)
    {
        byte[]? jpeg = DecodeThumbnail(message.Jpeg);
        lock (_gate)
        {
            if (_disposed) return;
            PeerState state = StateOf(peerId);
            bool requested = state.ThumbnailsInFlight.Remove(message.Id);
            if (!requested && !state.ById.ContainsKey(message.Id)) return;
            if (jpeg is null) state.NoThumbnail.Add(message.Id);
            else
            {
                state.NoThumbnail.Remove(message.Id);
                state.Thumbnails.Put(message.Id, jpeg);
            }
        }
        Raise(new PhotoChange(peerId, PhotoChangeKind.Thumbnail, message.Id));
    }

    private void OnPhotoNew(string peerId, LinkMessage.PhotoNew message)
    {
        if (Sanitize(message.Item) is not { } item) return;
        lock (_gate)
        {
            if (_disposed) return;
            StateOf(peerId).Upsert(item);
        }
        Raise(new PhotoChange(peerId, PhotoChangeKind.List));
    }

    private void OnPhotoRemoved(string peerId, LinkMessage.PhotoRemoved message)
    {
        lock (_gate)
        {
            if (_disposed || !_byPeer.TryGetValue(peerId, out var state) || !state.Remove(message.Id)) return;
        }
        Raise(new PhotoChange(peerId, PhotoChangeKind.List));
    }

    private void OnSessionOpened(string peerId) => Raise(new PhotoChange(peerId, PhotoChangeKind.Availability));

    private void OnCapabilitiesChanged(string peerId) => Raise(new PhotoChange(peerId, PhotoChangeKind.Availability));

    private void OnSessionClosed(string peerId)
    {
        PeerState? state;
        lock (_gate)
        {
            if (!_byPeer.Remove(peerId, out state)) state = null;
        }
        if (state is not null) Abandon(state, new IOException("The phone disconnected."));
        Raise(new PhotoChange(peerId, PhotoChangeKind.Cleared));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    private void Abandon(PeerState state, Exception reason)
    {
        List<PendingFetch> pending;
        TaskCompletionSource<IReadOnlyList<PhotoInfo>>? listWaiter;
        lock (_gate)
        {
            pending = state.Fetches.Values.ToList();
            state.Fetches.Clear();
            state.FetchByPhoto.Clear();
            listWaiter = state.ListWaiter;
            ClearListWaiter(state);
        }
        foreach (PendingFetch fetch in pending)
        {
            fetch.Timer?.Dispose();
            fetch.Completion.TrySetException(reason);
        }
        listWaiter?.TrySetException(reason);
        TryDeleteDirectory(state.Folder);
    }

    private void Fail(string peerId, string requestId, Exception error)
    {
        PendingFetch? pending;
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var state) || !state.Fetches.Remove(requestId, out pending)) return;
            if (state.FetchByPhoto.TryGetValue(pending.PhotoId, out var current) && ReferenceEquals(current, pending))
                state.FetchByPhoto.Remove(pending.PhotoId);
            pending.Timer?.Dispose();
        }
        VeyroTrace.Warn($"Photo fetch failed: id={pending.PhotoId}, {error.GetType().Name}");
        pending.Completion.TrySetException(error);
    }

    private void TimeOutList(string peerId, TaskCompletionSource<IReadOnlyList<PhotoInfo>> waiter)
    {
        lock (_gate)
        {
            if (!_byPeer.TryGetValue(peerId, out var state) || !ReferenceEquals(state.ListWaiter, waiter)) return;
            ClearListWaiter(state);
        }
        waiter.TrySetException(new TimeoutException("The phone did not send its photo list."));
    }

    private static void ClearListWaiter(PeerState state)
    {
        state.ListTimer?.Dispose();
        state.ListTimer = null;
        state.ListWaiter = null;
    }

    private void EnsureAvailable(string peerId)
    {
        if (!_sessions.IsConnected(peerId)) throw new InvalidOperationException("The phone is not connected.");
        if ((_sessions.GetPeerCapabilities(peerId) & LinkCapabilities.Photos) == 0)
            throw new InvalidOperationException("The phone does not offer photos.");
    }

    private PeerState StateOf(string peerId)
    {
        if (!_byPeer.TryGetValue(peerId, out var state))
        {
            state = new PeerState(Path.Combine(_photosRoot, Guid.NewGuid().ToString("N")), _thumbnailCapacity);
            _byPeer[peerId] = state;
        }
        return state;
    }

    private static PhotoInfo? Sanitize(PhotoInfo? info)
    {
        if (info is null) return null;
        string name = info.Name ?? string.Empty;
        if (name.Length > 255) name = name[..255];
        return info with
        {
            Name = name,
            Width = info.Width is > 0 ? info.Width : null,
            Height = info.Height is > 0 ? info.Height : null,
            Size = info.Size is >= 0 ? info.Size : null
        };
    }

    private static byte[]? DecodeThumbnail(string? base64)
    {
        if (string.IsNullOrEmpty(base64) || base64.Length > MaxThumbnailBytes * 4 / 3 + 4) return null;
        try
        {
            byte[] bytes = Convert.FromBase64String(base64);
            // JPEG starts with SOI (FF D8).
            return bytes.Length is > 2 and <= MaxThumbnailBytes && bytes[0] == 0xFF && bytes[1] == 0xD8 ? bytes : null;
        }
        catch (FormatException) { return null; }
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            // A file may still be open in another app; the next start removes it.
            VeyroTrace.Warn($"Photo cache cleanup incomplete: {error.GetType().Name}");
        }
    }

    private void Raise(PhotoChange change)
    {
        Action<PhotoChange>? handlers = Changed;
        if (handlers is null) return;
        foreach (Action<PhotoChange> handler in handlers.GetInvocationList().Cast<Action<PhotoChange>>())
        {
            try { handler(change); }
            catch (Exception error) { VeyroTrace.Error("Photo change handler failed", error); }
        }
    }

    private sealed class PendingFetch(long photoId, string requestId, DateTimeOffset requestedAt)
    {
        public long PhotoId { get; } = photoId;
        public string RequestId { get; } = requestId;
        public DateTimeOffset RequestedAt { get; } = requestedAt;
        public TaskCompletionSource<string> Completion { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public ITimer? Timer { get; set; }
        public bool Claimed { get; set; }
    }

    private sealed class PeerState(string folder, int thumbnailCapacity)
    {
        public string Folder { get; } = folder;
        public List<PhotoInfo> Photos { get; private set; } = [];
        public Dictionary<long, PhotoInfo> ById { get; } = [];
        public bool HasList { get; set; }
        public TaskCompletionSource<IReadOnlyList<PhotoInfo>>? ListWaiter { get; set; }
        public ITimer? ListTimer { get; set; }
        public ThumbnailCache Thumbnails { get; } = new(thumbnailCapacity);
        public HashSet<long> NoThumbnail { get; } = [];
        public Dictionary<long, DateTimeOffset> ThumbnailsInFlight { get; } = [];
        public Dictionary<string, PendingFetch> Fetches { get; } = new(StringComparer.Ordinal);
        public Dictionary<long, PendingFetch> FetchByPhoto { get; } = [];
        public Dictionary<long, string> Originals { get; } = [];

        public void SetPhotos(IEnumerable<PhotoInfo> photos)
        {
            ById.Clear();
            foreach (PhotoInfo photo in photos) ById.TryAdd(photo.Id, photo);
            Photos = ById.Values.OrderByDescending(p => p.Ts).ThenByDescending(p => p.Id).Take(ListLimit).ToList();
            if (Photos.Count < ById.Count)
            {
                ById.Clear();
                foreach (PhotoInfo photo in Photos) ById[photo.Id] = photo;
            }
        }

        public void Upsert(PhotoInfo item)
        {
            if (ById.ContainsKey(item.Id)) Photos.RemoveAll(p => p.Id == item.Id);
            ById[item.Id] = item;
            int index = Photos.FindIndex(p => p.Ts < item.Ts || (p.Ts == item.Ts && p.Id < item.Id));
            Photos.Insert(index < 0 ? Photos.Count : index, item);
            while (Photos.Count > ListLimit)
            {
                ById.Remove(Photos[^1].Id);
                Photos.RemoveAt(Photos.Count - 1);
            }
        }

        public bool Remove(long id)
        {
            if (!ById.Remove(id)) return false;
            Photos.RemoveAll(p => p.Id == id);
            Thumbnails.Remove(id);
            NoThumbnail.Remove(id);
            ThumbnailsInFlight.Remove(id);
            Originals.Remove(id);
            return true;
        }
    }
}

/// <summary>Least-recently-used thumbnail bytes. Not thread-safe; the service locks around it.</summary>
internal sealed class ThumbnailCache(int capacity)
{
    private readonly Dictionary<long, LinkedListNode<(long Id, byte[] Jpeg)>> _nodes = [];
    private readonly LinkedList<(long Id, byte[] Jpeg)> _order = new();

    public int Count => _nodes.Count;

    public bool Contains(long id) => _nodes.ContainsKey(id);

    public byte[]? Get(long id)
    {
        if (!_nodes.TryGetValue(id, out var node)) return null;
        _order.Remove(node);
        _order.AddFirst(node);
        return node.Value.Jpeg;
    }

    public void Put(long id, byte[] jpeg)
    {
        if (_nodes.TryGetValue(id, out var existing)) _order.Remove(existing);
        var node = new LinkedListNode<(long, byte[])>((id, jpeg));
        _order.AddFirst(node);
        _nodes[id] = node;
        while (_nodes.Count > capacity && _order.Last is { } last)
        {
            _order.RemoveLast();
            _nodes.Remove(last.Value.Id);
        }
    }

    public void Remove(long id)
    {
        if (_nodes.Remove(id, out var node)) _order.Remove(node);
    }
}
