using Veyro.Core.Link;

namespace Veyro.Core.Mirroring;

/// <summary>
/// The PC's choices for a phone camera (ADR-067): which camera, which size and frame rate (only
/// what that camera and the phone's encoders offer), codec and bitrate. Pure, so it is unit-tested.
/// </summary>
public static class CameraPlanner
{
    public const int DefaultWidth = 1280;
    public const int DefaultHeight = 720;
    public const int DefaultFps = 30;
    public const int MinBitrate = 1_000_000;
    public const int MaxBitrate = 50_000_000;

    /// <summary>The camera with <paramref name="id"/>, else the first one facing back, else the first.</summary>
    public static PeerMessage.StreamCamera? Choose(PeerMessage.StreamCaps? caps, string? id)
    {
        var cameras = caps?.Cameras ?? [];
        return cameras.FirstOrDefault(c => c.Id == id)
            ?? cameras.FirstOrDefault(c => c.Facing == "back")
            ?? cameras.FirstOrDefault();
    }

    /// <summary>
    /// The size <paramref name="w"/> x <paramref name="h"/> when the camera has it; otherwise the
    /// largest it has that is no bigger, else its smallest. Without a wish, 1280 x 720 the same way.
    /// </summary>
    public static PeerMessage.StreamSize? ChooseSize(PeerMessage.StreamCamera camera, int? w, int? h)
    {
        var sizes = camera.Sizes ?? [];
        if (sizes.Count == 0) return null;
        int wantW = w ?? DefaultWidth, wantH = h ?? DefaultHeight;
        return sizes.FirstOrDefault(s => s.W == wantW && s.H == wantH)
            ?? sizes.Where(s => (long)s.W * s.H <= (long)wantW * wantH).OrderByDescending(s => (long)s.W * s.H).ThenByDescending(s => s.Fps).FirstOrDefault()
            ?? sizes.OrderBy(s => (long)s.W * s.H).First();
    }

    /// <summary>The frame rates to offer for <paramref name="size"/>: 30, and 60 when the camera reaches it.</summary>
    public static IReadOnlyList<int> FrameRates(PeerMessage.StreamSize size) =>
        size.Fps >= 60 ? [30, 60] : [Math.Min(30, size.Fps)];

    /// <summary>A short label: "1920 × 1080 (1080p)".</summary>
    public static string Label(PeerMessage.StreamSize size)
    {
        string? name = (size.W, size.H) switch
        {
            (3840, 2160) => "4K",
            (2560, 1440) => "1440p",
            (1920, 1080) => "1080p",
            (1280, 720) => "720p",
            (960, 540) => "540p",
            (640, 480) => "480p",
            _ => null,
        };
        return name is null ? $"{size.W} × {size.H}" : $"{size.W} × {size.H} ({name})";
    }

    /// <summary>
    /// What to ask the phone for: H.265 above 2048 px when the phone has a hardware H.265 encoder
    /// and this PC decodes it, else H.264; the encoder must take the size; bitrate from the size.
    /// Null when no encoder can.
    /// </summary>
    public static StreamPlan? Plan(PeerMessage.StreamCaps caps, PeerMessage.StreamSize size, int fps,
        IReadOnlyList<PcDecoder> decoders, bool hardwareEncode)
    {
        bool Fits(PeerMessage.StreamEncoder e) =>
            (e.MaxW >= size.W && e.MaxH >= size.H) || (e.MaxW >= size.H && e.MaxH >= size.W);
        var encoders = (caps.Encoders ?? []).Where(Fits).ToList();
        bool hevc = Math.Max(size.W, size.H) > 2048
            && encoders.Any(e => e.Codec == StreamRules.CodecH265 && e.Hw)
            && decoders.Any(d => d.Codec == StreamRules.CodecH265);
        string codec = hevc ? StreamRules.CodecH265 : StreamRules.CodecH264;
        var encoder = encoders
            .Where(e => e.Codec == codec && (e.Hw || codec == StreamRules.CodecH264))
            .OrderByDescending(e => e.Hw == hardwareEncode)
            .FirstOrDefault();
        if (encoder is null) return null;
        var decoder = decoders.Where(d => d.Codec == codec).OrderByDescending(d => d.Hw).FirstOrDefault();
        int rate = Math.Clamp(Math.Min(fps, size.Fps), 1, StreamRules.MaxFps);
        double bitsPerPixel = hevc ? 0.07 : 0.10;
        int bitrate = (int)Math.Clamp((long)(size.W * (long)size.H * rate * bitsPerPixel), MinBitrate, MaxBitrate);
        int maxLong = Math.Clamp(Math.Max(size.W, size.H), StreamRules.MinMaxLong, StreamRules.MaxMaxLong);
        return new StreamPlan(codec, encoder.Hw, decoder?.Hw ?? false, maxLong, rate, bitrate, size.W, size.H);
    }
}
