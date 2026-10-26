using System.Globalization;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyro.Core.Diagnostics;
using Veyro.Core.Photos;

namespace Veyro.ViewModels;

/// <summary>
/// The Photos tab (ADR-034), laid out like Phone Link: the phone's recent camera photos and
/// screenshots, newest first, grouped by day. Talks only to <see cref="IPhotoService"/>.
/// Thumbnails are requested only for tiles on screen and released when a tile is recycled, so
/// at most the visible tiles hold decoded bitmaps. Names and images are never logged.
/// </summary>
public sealed class PhotosViewModel : BaseViewModel
{
    public const string EnableHint =
        "On the phone, open Veyro and turn on \"Photos on PC\", then allow access to photos.";

    private static readonly TimeSpan ThumbnailDebounce = TimeSpan.FromMilliseconds(80);

    private readonly IPhotoService _photos;
    private readonly IDeviceManager _deviceManager;
    private readonly DispatcherQueue? _dispatcher;
    private readonly Dictionary<long, PhotoItemViewModel> _items = [];
    private readonly HashSet<long> _wantedThumbnails = [];
    private List<PhotoItemViewModel> _ordered = [];
    private DispatcherQueueTimer? _thumbnailTimer;
    private string? _peerId;
    private bool _isActive;
    private bool _isLoading;
    private string _statusText = string.Empty;
    private PhotoItemViewModel? _viewerItem;

    public PhotosViewModel(IPhotoService photos, IDeviceManager deviceManager)
    {
        _photos = photos;
        _deviceManager = deviceManager;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _peerId = deviceManager.ActiveDevice?.DeviceId;
        _photos.Changed += change => Post(() => OnChanged(change));
        _deviceManager.ActiveDeviceChanged += device => Post(() =>
        {
            if (string.Equals(device?.DeviceId, _peerId, StringComparison.OrdinalIgnoreCase)) return;
            _peerId = device?.DeviceId;
            ViewerItem = null;
            StatusText = string.Empty;
            SyncAll();
            EnsureLoaded();
        });
        SyncAll();
    }

    /// <summary>Day groups, newest first. Bound through a grouped CollectionViewSource.</summary>
    public ObservableCollection<PhotoGroupViewModel> Groups { get; } = [];

    public bool IsAvailable => _peerId is not null && _photos.IsAvailable(_peerId);
    public bool IsUnavailable => !IsAvailable;

    public string UnavailableText => _peerId is null
        ? "Pair a phone to see its photos here."
        : _deviceManager.ActiveDevice is { IsConnected: false }
            ? "Connect the phone to see its recent photos. " + EnableHint
            : "This phone does not share photos yet. " + EnableHint;

    public bool IsLoading
    {
        get => _isLoading;
        private set
        {
            if (SetProperty(ref _isLoading, value))
            {
                OnPropertyChanged(nameof(HasNoPhotos));
                OnPropertyChanged(nameof(IsNotLoading));
            }
        }
    }

    public bool IsNotLoading => !_isLoading;

    /// <summary>The phone answered with an empty list: the switch is off or access was not granted.</summary>
    public bool HasNoPhotos => IsAvailable && !_isLoading && _peerId is { } peer && _photos.HasList(peer) && _ordered.Count == 0;

    public string EmptyText => "No photos to show. " + EnableHint;

    public string CountText => _ordered.Count switch
    {
        0 => string.Empty,
        1 => "1 photo",
        int n => $"{n:N0} photos"
    };

    public string SubtitleText => _deviceManager.ActiveDevice is { } device
        ? $"Recent photos and screenshots from {device.Name}"
        : string.Empty;

    public string StatusText
    {
        get => _statusText;
        set
        {
            if (SetProperty(ref _statusText, value ?? string.Empty)) OnPropertyChanged(nameof(HasStatus));
        }
    }

    public bool HasStatus => !string.IsNullOrEmpty(_statusText);

    /// <summary>The photo open in the viewer, or null.</summary>
    public PhotoItemViewModel? ViewerItem
    {
        get => _viewerItem;
        set
        {
            if (!SetProperty(ref _viewerItem, value)) return;
            OnPropertyChanged(nameof(IsViewerOpen));
            OnPropertyChanged(nameof(CanGoPrevious));
            OnPropertyChanged(nameof(CanGoNext));
        }
    }

    public bool IsViewerOpen => _viewerItem is not null;
    public bool CanGoPrevious => Neighbor(-1) is not null;
    public bool CanGoNext => Neighbor(1) is not null;

    public void Activate()
    {
        _isActive = true;
        SyncAll();
        EnsureLoaded();
    }

    public void Deactivate() => _isActive = false;

    public async Task RefreshAsync()
    {
        if (_peerId is not { } peer || !IsAvailable || IsLoading) return;
        IsLoading = true;
        StatusText = string.Empty;
        try { await _photos.RequestListAsync(peer); }
        catch (TimeoutException) { StatusText = "The phone did not answer. Try again."; }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Loading photos failed: {error.GetType().Name}");
            StatusText = "Could not load photos from the phone.";
        }
        finally
        {
            IsLoading = false;
            SyncList();
        }
    }

    /// <summary>A tile came on screen: show its thumbnail, asking the phone if needed.</summary>
    public void OnTileRealized(PhotoItemViewModel item)
    {
        item.IsRealized = true;
        if (_peerId is not { } peer) return;
        if (_photos.GetThumbnail(peer, item.Id) is { } bytes)
        {
            _ = item.SetThumbnailAsync(bytes);
            return;
        }
        if (_photos.HasNoThumbnail(peer, item.Id))
        {
            item.MarkNoThumbnail();
            return;
        }
        _wantedThumbnails.Add(item.Id);
        ScheduleThumbnailRequest();
    }

    /// <summary>A tile left the screen (container recycled): drop its decoded bitmap.</summary>
    public void OnTileRecycled(PhotoItemViewModel item)
    {
        item.IsRealized = false;
        item.ReleaseThumbnail();
        _wantedThumbnails.Remove(item.Id);
    }

    /// <summary>Fetches the original into the session cache (ADR-034), or returns the cached copy.</summary>
    public Task<string> FetchOriginalAsync(PhotoItemViewModel item, CancellationToken ct = default)
    {
        if (_peerId is not { } peer) throw new InvalidOperationException("No phone is selected.");
        return _photos.FetchOriginalAsync(peer, item.Id, ct);
    }

    /// <summary>The original if it is already on this PC; never contacts the phone.</summary>
    public string? CachedOriginal(PhotoItemViewModel item)
        => _peerId is { } peer ? _photos.GetCachedOriginal(peer, item.Id) : null;

    public void Open(PhotoItemViewModel item) => ViewerItem = item;

    public void CloseViewer() => ViewerItem = null;

    public void Step(int delta)
    {
        if (Neighbor(delta) is { } next) ViewerItem = next;
    }

    private PhotoItemViewModel? Neighbor(int delta)
    {
        if (_viewerItem is null) return null;
        int index = _ordered.IndexOf(_viewerItem);
        int target = index + delta;
        return index >= 0 && target >= 0 && target < _ordered.Count ? _ordered[target] : null;
    }

    private void OnChanged(PhotoChange change)
    {
        if (!string.Equals(change.PeerId, _peerId, StringComparison.OrdinalIgnoreCase)) return;
        switch (change.Kind)
        {
            case PhotoChangeKind.Availability:
                SyncAll();
                EnsureLoaded();
                break;
            case PhotoChangeKind.Cleared:
                ViewerItem = null;
                SyncAll();
                break;
            case PhotoChangeKind.List:
                SyncList();
                break;
            case PhotoChangeKind.Thumbnail when change.PhotoId is { } id && _items.TryGetValue(id, out var item):
                if (!item.IsRealized || _peerId is not { } peer) break;
                if (_photos.GetThumbnail(peer, id) is { } bytes) _ = item.SetThumbnailAsync(bytes);
                else if (_photos.HasNoThumbnail(peer, id)) item.MarkNoThumbnail();
                break;
        }
    }

    private void EnsureLoaded()
    {
        if (!_isActive || _peerId is not { } peer || !IsAvailable) return;
        if (!_photos.HasList(peer) && !IsLoading) _ = RefreshAsync();
    }

    private void ScheduleThumbnailRequest()
    {
        if (_dispatcher is null) return;
        if (_thumbnailTimer is null)
        {
            _thumbnailTimer = _dispatcher.CreateTimer();
            _thumbnailTimer.Interval = ThumbnailDebounce;
            _thumbnailTimer.IsRepeating = false;
            _thumbnailTimer.Tick += (_, _) => _ = RequestWantedThumbnailsAsync();
        }
        if (!_thumbnailTimer.IsRunning) _thumbnailTimer.Start();
    }

    private async Task RequestWantedThumbnailsAsync()
    {
        if (_peerId is not { } peer) return;
        // Only tiles still on screen; scrolling past quickly asks for nothing.
        List<long> ids = _wantedThumbnails.Where(id => _items.TryGetValue(id, out var item) && item.IsRealized).ToList();
        _wantedThumbnails.Clear();
        if (ids.Count == 0) return;
        try { await _photos.RequestThumbnailsAsync(peer, ids); }
        catch (Exception error) { VeyroTrace.Warn($"Requesting thumbnails failed: {error.GetType().Name}"); }
    }

    private void SyncAll()
    {
        SyncList();
        foreach (string name in new[]
                 {
                     nameof(IsAvailable), nameof(IsUnavailable), nameof(UnavailableText), nameof(HasNoPhotos),
                     nameof(SubtitleText)
                 })
            OnPropertyChanged(name);
    }

    /// <summary>
    /// Applies the service's list in place: items keep their view models (and thumbnails), groups
    /// are inserted, moved or removed, so a photoNew does not reset the scroll position.
    /// </summary>
    private void SyncList()
    {
        IReadOnlyList<PhotoInfo> photos = _peerId is { } peer && IsAvailable ? _photos.GetPhotos(peer) : [];
        var ordered = new List<PhotoItemViewModel>(photos.Count);
        var seen = new HashSet<long>();
        foreach (PhotoInfo info in photos)
        {
            if (!seen.Add(info.Id)) continue;
            if (_items.TryGetValue(info.Id, out var item)) item.Info = info;
            else _items[info.Id] = item = new PhotoItemViewModel(info);
            ordered.Add(item);
        }
        foreach (long gone in _items.Keys.Where(id => !seen.Contains(id)).ToList())
        {
            _items[gone].ReleaseThumbnail();
            _items.Remove(gone);
        }
        _ordered = ordered;

        DateTime today = DateTime.Now.Date;
        var desired = new List<(DateTime Day, List<PhotoItemViewModel> Items)>();
        foreach (PhotoItemViewModel item in ordered)
        {
            DateTime day = item.LocalTime.Date;
            if (desired.Count == 0 || desired[^1].Day != day) desired.Add((day, []));
            desired[^1].Items.Add(item);
        }

        var groups = desired.Select(entry =>
        {
            PhotoGroupViewModel group = Groups.FirstOrDefault(g => g.Day == entry.Day) ?? new PhotoGroupViewModel(entry.Day);
            group.Header = PhotoGroupViewModel.HeaderFor(entry.Day, today);
            SyncInPlace(group, entry.Items);
            return group;
        }).ToList();
        SyncInPlace(Groups, groups);

        if (_viewerItem is not null && !_items.ContainsKey(_viewerItem.Id)) ViewerItem = null;
        OnPropertyChanged(nameof(CountText));
        OnPropertyChanged(nameof(HasNoPhotos));
        OnPropertyChanged(nameof(CanGoPrevious));
        OnPropertyChanged(nameof(CanGoNext));
    }

    private static void SyncInPlace<T>(ObservableCollection<T> target, IReadOnlyList<T> desired) where T : class
    {
        for (int i = 0; i < desired.Count; i++)
        {
            if (i < target.Count && ReferenceEquals(target[i], desired[i])) continue;
            int existing = -1;
            for (int j = i + 1; j < target.Count; j++)
            {
                if (ReferenceEquals(target[j], desired[i])) { existing = j; break; }
            }
            if (existing >= 0) target.Move(existing, i);
            else target.Insert(i, desired[i]);
        }
        while (target.Count > desired.Count) target.RemoveAt(target.Count - 1);
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}

/// <summary>One day of photos. The collection itself is the group's items.</summary>
public sealed class PhotoGroupViewModel(DateTime day) : ObservableCollection<PhotoItemViewModel>
{
    private string _header = string.Empty;

    public DateTime Day { get; } = day;

    public string Header
    {
        get => _header;
        set
        {
            if (_header == value) return;
            _header = value;
            OnPropertyChanged(new PropertyChangedEventArgs(nameof(Header)));
        }
    }

    public static string HeaderFor(DateTime day, DateTime today)
    {
        if (day == today) return "Today";
        if (day == today.AddDays(-1)) return "Yesterday";
        string format = day.Year == today.Year ? "dddd, d MMMM" : "d MMMM yyyy";
        return day.ToString(format, CultureInfo.CurrentCulture);
    }
}

/// <summary>One photo tile. The bitmap exists only while the tile is on screen.</summary>
public sealed class PhotoItemViewModel : BaseViewModel
{
    /// <summary>Tiles are about 140 logical pixels; decode the thumbnail at that size, not 256.</summary>
    private const int TileDecodePixels = 160;

    private PhotoInfo _info;
    private ImageSource? _thumbnail;
    private bool _isMissing;
    private int _version;

    public PhotoItemViewModel(PhotoInfo info) => _info = info;

    public long Id => _info.Id;

    public PhotoInfo Info
    {
        get => _info;
        set
        {
            if (Equals(_info, value)) return;
            _info = value;
            foreach (string name in new[] { nameof(Name), nameof(DateText), nameof(DetailText), nameof(AccessibleName) })
                OnPropertyChanged(name);
        }
    }

    public DateTime LocalTime => DateTimeOffset.FromUnixTimeMilliseconds(_info.Ts).LocalDateTime;

    public string Name => string.IsNullOrWhiteSpace(_info.Name) ? "Photo" : _info.Name;

    public string DateText => LocalTime.ToString("f", CultureInfo.CurrentCulture);

    public string DetailText
    {
        get
        {
            var parts = new List<string> { DateText };
            if (_info.Width is { } w && _info.Height is { } h) parts.Add($"{w} x {h}");
            if (_info.Size is { } size) parts.Add(TransferProgressMath.FormatBytes(size));
            return string.Join("  ·  ", parts);
        }
    }

    public string AccessibleName => (_info.Bucket == "screenshots" ? "Screenshot, " : "Photo, ") + DateText;

    public ImageSource? Thumbnail
    {
        get => _thumbnail;
        private set
        {
            if (SetProperty(ref _thumbnail, value))
            {
                OnPropertyChanged(nameof(HasThumbnail));
                OnPropertyChanged(nameof(ShowPlaceholder));
            }
        }
    }

    public bool HasThumbnail => _thumbnail is not null;
    public bool ShowPlaceholder => _thumbnail is null;

    /// <summary>The phone has no thumbnail for this photo; the tile keeps its placeholder icon.</summary>
    public bool IsMissing
    {
        get => _isMissing;
        private set => SetProperty(ref _isMissing, value);
    }

    /// <summary>Set by the page while the tile's container is on screen.</summary>
    public bool IsRealized { get; set; }

    internal async Task SetThumbnailAsync(byte[] jpeg)
    {
        int version = ++_version;
        try
        {
            var bitmap = new BitmapImage { DecodePixelWidth = TileDecodePixels, DecodePixelType = DecodePixelType.Logical };
            using var stream = new MemoryStream(jpeg);
            await bitmap.SetSourceAsync(stream.AsRandomAccessStream());
            // Released or replaced while decoding: keep nothing.
            if (version != _version || !IsRealized) return;
            IsMissing = false;
            Thumbnail = bitmap;
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Thumbnail decode failed: {error.GetType().Name}");
            if (version == _version) IsMissing = true;
        }
    }

    internal void MarkNoThumbnail()
    {
        _version++;
        Thumbnail = null;
        IsMissing = true;
    }

    internal void ReleaseThumbnail()
    {
        _version++;
        Thumbnail = null;
    }
}
