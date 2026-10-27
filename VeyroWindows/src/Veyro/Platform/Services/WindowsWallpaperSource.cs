using Veyro.Core.Diagnostics;
using Veyro.Platform.Interop;
using Windows.Foundation;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

namespace Veyro.Platform.Services;

/// <summary>
/// The desktop wallpaper for the phone's PC card (ADR-038). The path comes from
/// SystemParametersInfo(SPI_GETDESKWALLPAPER), falling back to the TranscodedWallpaper copy
/// Windows keeps in %APPDATA%. Read only, nothing is written. A light poll (path, size, time)
/// detects changes, including slideshows. Paths and image bytes are user data: never logged.
/// </summary>
public sealed class WindowsWallpaperSource : IWindowsWallpaperSource
{
    private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(30);

    private readonly object _gate = new();
    private Timer? _timer;
    private string? _fingerprint;

    public event Action? Changed;

    private bool _active;

    public void Start()
    {
        lock (_gate)
        {
            if (_timer is not null) return;
            _fingerprint = Fingerprint();
            // Idle until a phone connects (SetActive): nobody needs to hear about a change before.
            _timer = new Timer(_ => Poll(), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
            if (_active) _timer.Change(PollInterval, PollInterval);
        }
    }

    public void SetActive(bool active)
    {
        lock (_gate)
        {
            if (active == _active) return;
            _active = active;
            if (active) _fingerprint = Fingerprint();
            _timer?.Change(active ? PollInterval : Timeout.InfiniteTimeSpan, active ? PollInterval : Timeout.InfiniteTimeSpan);
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _timer?.Dispose();
            _timer = null;
        }
    }

    public async Task<byte[]?> ReadJpegAsync(CancellationToken ct = default)
    {
        string? path = CurrentPath();
        if (path is null) return null;

        // Share flags: Windows may rewrite TranscodedWallpaper while it is open here.
        await using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using IRandomAccessStream source = file.AsRandomAccessStream();
        BitmapDecoder decoder = await BitmapDecoder.CreateAsync(source);
        (int width, int height) = WallpaperEncoding.FitWithin((int)decoder.PixelWidth, (int)decoder.PixelHeight, WallpaperEncoding.MaxSide);
        var transform = new BitmapTransform
        {
            ScaledWidth = (uint)width,
            ScaledHeight = (uint)height,
            InterpolationMode = BitmapInterpolationMode.Fant
        };
        using SoftwareBitmap bitmap = await decoder.GetSoftwareBitmapAsync(
            BitmapPixelFormat.Bgra8,
            BitmapAlphaMode.Ignore,
            transform,
            ExifOrientationMode.IgnoreExifOrientation,
            ColorManagementMode.DoNotColorManage);
        return await WallpaperEncoding.EncodeUnderCapAsync(width, height, (w, h, quality, token) => EncodeAsync(bitmap, w, h, quality, token), ct);
    }

    private static async Task<byte[]> EncodeAsync(SoftwareBitmap bitmap, int width, int height, int quality, CancellationToken ct)
    {
        using var output = new InMemoryRandomAccessStream();
        var options = new BitmapPropertySet
        {
            { "ImageQuality", new BitmapTypedValue(quality / 100f, PropertyType.Single) }
        };
        BitmapEncoder encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.JpegEncoderId, output, options);
        encoder.SetSoftwareBitmap(bitmap);
        if (width != bitmap.PixelWidth || height != bitmap.PixelHeight)
        {
            encoder.BitmapTransform.ScaledWidth = (uint)width;
            encoder.BitmapTransform.ScaledHeight = (uint)height;
            encoder.BitmapTransform.InterpolationMode = BitmapInterpolationMode.Fant;
        }
        await encoder.FlushAsync();
        ct.ThrowIfCancellationRequested();
        output.Seek(0);
        using var copy = new MemoryStream((int)output.Size);
        await output.AsStreamForRead().CopyToAsync(copy, ct);
        return copy.ToArray();
    }

    private void Poll()
    {
        string? now = Fingerprint();
        lock (_gate)
        {
            if (_timer is null || now == _fingerprint) return;
            _fingerprint = now;
        }
        try { Changed?.Invoke(); }
        catch (Exception error) { VeyroTrace.Error("Wallpaper change handler failed", error); }
    }

    private static string? CurrentPath()
    {
        try
        {
            string? path = InteropHelpers.GetDesktopWallpaperPath();
            if (path is not null && File.Exists(path)) return path;
            string transcoded = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "Microsoft", "Windows", "Themes", "TranscodedWallpaper");
            return File.Exists(transcoded) ? transcoded : null;
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Reading the wallpaper path failed: {error.GetType().Name}");
            return null;
        }
    }

    private static string? Fingerprint()
    {
        string? path = CurrentPath();
        if (path is null) return null;
        try
        {
            var info = new FileInfo(path);
            return $"{path}|{info.Length}|{info.LastWriteTimeUtc.Ticks}";
        }
        catch (Exception)
        {
            return path;
        }
    }
}

/// <summary>
/// This PC's Wi-Fi signal for the phone's PC card (ADR-038), from the internet connection
/// profile. Null when the PC is not on Wi-Fi. Polled, since signal changes raise no event.
/// </summary>
public sealed class WindowsNetworkSource : IWindowsNetworkSource
{
    private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(30);

    private readonly object _gate = new();
    private Timer? _timer;
    private int? _last;

    public event Action? Changed;

    public int? WifiLevel
    {
        get
        {
            try
            {
                var profile = global::Windows.Networking.Connectivity.NetworkInformation.GetInternetConnectionProfile();
                if (profile is null || !profile.IsWlanConnectionProfile) return null;
                byte? bars = profile.GetSignalBars();
                // Windows reports 0..5 bars; the protocol uses 0..4.
                return bars is { } b ? Math.Clamp((int)Math.Round(b * 4 / 5.0), 0, 4) : null;
            }
            catch (Exception error)
            {
                VeyroTrace.Warn($"Reading Wi-Fi signal failed: {error.GetType().Name}");
                return null;
            }
        }
    }

    private bool _active;

    public void SetActive(bool active)
    {
        lock (_gate)
        {
            if (active == _active) return;
            _active = active;
            _timer?.Change(active ? PollInterval : Timeout.InfiniteTimeSpan, active ? PollInterval : Timeout.InfiniteTimeSpan);
        }
    }

    public void Start()
    {
        lock (_gate)
        {
            if (_timer is not null) return;
            _last = WifiLevel;
            // Idle until a phone connects (SetActive); NetworkStatusChanged still reports changes.
            _timer = new Timer(_ => Poll(), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
            if (_active) _timer.Change(PollInterval, PollInterval);
        }
        try { global::Windows.Networking.Connectivity.NetworkInformation.NetworkStatusChanged += OnNetworkStatusChanged; }
        catch (Exception error) { VeyroTrace.Warn($"Watching network status failed: {error.GetType().Name}"); }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_timer is null) return;
            _timer.Dispose();
            _timer = null;
        }
        try { global::Windows.Networking.Connectivity.NetworkInformation.NetworkStatusChanged -= OnNetworkStatusChanged; }
        catch (Exception) { }
    }

    private void OnNetworkStatusChanged(object sender) => Poll();

    private void Poll()
    {
        int? now = WifiLevel;
        lock (_gate)
        {
            if (_timer is null || now == _last) return;
            _last = now;
        }
        try { Changed?.Invoke(); }
        catch (Exception error) { VeyroTrace.Error("Network change handler failed", error); }
    }
}
