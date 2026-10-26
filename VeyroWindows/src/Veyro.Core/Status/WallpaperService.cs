using Veyro.Core.Diagnostics;
using Veyro.Core.Link;

namespace Veyro.Core.Status;

/// <summary>
/// Size rules for <c>wallpaper</c> (04-PROTOCOL.md 6.2.1, ADR-038): JPEG, longest side at most
/// <see cref="MaxSide"/> px and at most <see cref="MaxBytes"/> before base64. Pure logic; the
/// platform supplies the actual JPEG encoder.
/// </summary>
public static class WallpaperEncoding
{
    public const int MaxSide = 720;
    public const int MaxBytes = 96 * 1024;

    /// <summary>The encoder never shrinks below this longest side; past it the wallpaper is not sent.</summary>
    public const int MinSide = 120;

    /// <summary>JPEG qualities tried in order at each size.</summary>
    public static readonly IReadOnlyList<int> Qualities = [85, 75, 65, 55, 45, 35];

    /// <summary>Scales (width, height) down, keeping the aspect ratio, so the longest side fits.</summary>
    public static (int Width, int Height) FitWithin(int width, int height, int maxSide)
    {
        if (width <= 0 || height <= 0) throw new ArgumentOutOfRangeException(nameof(width), "Image size must be positive.");
        if (maxSide <= 0) throw new ArgumentOutOfRangeException(nameof(maxSide));
        int longest = Math.Max(width, height);
        if (longest <= maxSide) return (width, height);
        double scale = (double)maxSide / longest;
        return (Math.Clamp((int)Math.Round(width * scale), 1, maxSide), Math.Clamp((int)Math.Round(height * scale), 1, maxSide));
    }

    /// <summary>
    /// Encodes at <see cref="MaxSide"/> with falling quality, then at smaller sizes, until a result
    /// fits <see cref="MaxBytes"/>. Returns null when nothing down to <see cref="MinSide"/> fits.
    /// <paramref name="encode"/> receives (width, height, quality 1..100).
    /// </summary>
    public static async Task<byte[]?> EncodeUnderCapAsync(
        int sourceWidth,
        int sourceHeight,
        Func<int, int, int, CancellationToken, Task<byte[]>> encode,
        CancellationToken ct = default)
    {
        int side = MaxSide;
        while (true)
        {
            (int width, int height) = FitWithin(sourceWidth, sourceHeight, side);
            foreach (int quality in Qualities)
            {
                ct.ThrowIfCancellationRequested();
                byte[] jpeg = await encode(width, height, quality, ct).ConfigureAwait(false);
                if (jpeg.Length > 0 && jpeg.Length <= MaxBytes) return jpeg;
            }
            int longest = Math.Max(width, height);
            if (longest <= MinSide) return null;
            side = Math.Max(MinSide, longest * 3 / 4);
        }
    }

    public static bool LooksLikeJpeg(ReadOnlySpan<byte> bytes)
        => bytes.Length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF;

    /// <summary>
    /// An incoming <c>wallpaper.jpeg</c> (untrusted). Null when absent, not base64, over the size
    /// cap or not a JPEG: the UI then shows its fallback.
    /// </summary>
    public static byte[]? DecodeIncoming(string? base64)
    {
        if (string.IsNullOrEmpty(base64) || base64.Length > (MaxBytes + 2) / 3 * 4) return null;
        byte[] bytes;
        try { bytes = Convert.FromBase64String(base64); }
        catch (FormatException) { return null; }
        return bytes.Length <= MaxBytes && LooksLikeJpeg(bytes) ? bytes : null;
    }
}

/// <summary>This PC's desktop wallpaper, implemented per platform (ADR-038).</summary>
public interface IPcWallpaperSource
{
    /// <summary>Raised on any thread when the wallpaper may have changed.</summary>
    event Action? Changed;

    /// <summary>The wallpaper as a JPEG within <see cref="WallpaperEncoding"/>'s caps, or null when there is none.</summary>
    Task<byte[]?> ReadJpegAsync(CancellationToken ct = default);
}

public interface IWallpaperService
{
    /// <summary>The peer's wallpaper changed or was cleared. Raised on the session thread.</summary>
    event Action<string>? Changed;

    /// <summary>The phone's latest wallpaper JPEG, or null when none since its session opened.</summary>
    byte[]? GetPhoneWallpaper(string peerId);
}

/// <summary>
/// Wallpapers in both directions (04-PROTOCOL.md 6.2.1, ADR-038): keeps each phone's latest
/// wallpaper (cleared when its session closes) and sends the PC's to peers advertising
/// <see cref="LinkCapabilities.Wallpaper"/> on connect and on change. Image bytes are user data:
/// never logged.
/// </summary>
public sealed class WallpaperService : IWallpaperService, IDisposable
{
    private readonly IPeerSessionRegistry _sessions;
    private readonly IPcWallpaperSource? _source;
    private readonly object _gate = new();
    private readonly Dictionary<string, byte[]> _phone = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, long> _sentVersion = new(StringComparer.OrdinalIgnoreCase);
    private long _version = 1;
    private Task<string?>? _encoded;

    public event Action<string>? Changed;

    public WallpaperService(IPeerSessionRegistry sessions, IPcWallpaperSource? source)
    {
        _sessions = sessions;
        _source = source;
        _sessions.DeviceMessageReceived += OnDeviceMessage;
        _sessions.SessionClosed += OnSessionClosed;
        _sessions.CapabilitiesChanged += OnCapabilitiesChanged;
        if (_source is not null) _source.Changed += OnSourceChanged;
    }

    public byte[]? GetPhoneWallpaper(string peerId)
    {
        lock (_gate) return _phone.GetValueOrDefault(peerId);
    }

    public void Dispose()
    {
        _sessions.DeviceMessageReceived -= OnDeviceMessage;
        _sessions.SessionClosed -= OnSessionClosed;
        _sessions.CapabilitiesChanged -= OnCapabilitiesChanged;
        if (_source is not null) _source.Changed -= OnSourceChanged;
    }

    private bool IsCapable(string peerId)
        => (_sessions.GetPeerCapabilities(peerId) & LinkCapabilities.Wallpaper) != 0;

    private void OnDeviceMessage(string peerId, LinkMessage message)
    {
        if (message is not LinkMessage.Wallpaper wallpaper) return;
        byte[]? bytes = WallpaperEncoding.DecodeIncoming(wallpaper.Jpeg);
        lock (_gate)
        {
            if (bytes is null) _phone.Remove(peerId);
            else _phone[peerId] = bytes;
        }
        Changed?.Invoke(peerId);
    }

    private void OnSessionClosed(string peerId)
    {
        bool had;
        lock (_gate)
        {
            had = _phone.Remove(peerId);
            _sentVersion.Remove(peerId);
        }
        if (had) Changed?.Invoke(peerId);
    }

    private void OnCapabilitiesChanged(string peerId)
    {
        if (IsCapable(peerId)) _ = PublishAsync(peerId);
    }

    private void OnSourceChanged()
    {
        lock (_gate)
        {
            _version++;
            _encoded = null;
        }
        foreach (string peerId in _sessions.GetConnectedPeers())
        {
            if (IsCapable(peerId)) _ = PublishAsync(peerId);
        }
    }

    private async Task PublishAsync(string peerId)
    {
        if (_source is null) return;
        long version;
        Task<string?> encoded;
        lock (_gate)
        {
            version = _version;
            if (_sentVersion.TryGetValue(peerId, out long sent) && sent == version) return;
            _sentVersion[peerId] = version;
            encoded = _encoded ??= EncodeAsync(_source);
        }
        try
        {
            string? jpeg = await encoded.ConfigureAwait(false);
            lock (_gate)
            {
                // A newer wallpaper is on its way; this one would only be overwritten.
                if (_version != version) return;
            }
            if (!_sessions.IsConnected(peerId)) return;
            await _sessions.SendAsync(peerId, new LinkMessage.Wallpaper(jpeg)).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            lock (_gate)
            {
                if (_sentVersion.TryGetValue(peerId, out long sent) && sent == version) _sentVersion.Remove(peerId);
                if (ReferenceEquals(_encoded, encoded) && encoded.IsFaulted) _encoded = null;
            }
            VeyroTrace.Warn($"Sending the PC wallpaper failed: {error.GetType().Name}");
        }
    }

    private static async Task<string?> EncodeAsync(IPcWallpaperSource source)
    {
        byte[]? jpeg = await source.ReadJpegAsync().ConfigureAwait(false);
        if (jpeg is null || jpeg.Length > WallpaperEncoding.MaxBytes || !WallpaperEncoding.LooksLikeJpeg(jpeg)) return null;
        return Convert.ToBase64String(jpeg);
    }
}
