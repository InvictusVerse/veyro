using Veyro.Core.Link;
using Veyro.Core.Protocol.Media;

namespace Veyro.Core.Mirroring;

/// <summary>
/// The quality the user picks on the PC (ADR-066: every mirroring control lives on the PC; the
/// phone only allows capture). Hardware encoding and decoding are on by default and fall back to
/// software only where hardware is missing.
/// </summary>
public sealed record MirrorQuality(
    int MaxLong,
    int Fps,
    int MaxBitrate,
    bool HardwareEncode = true,
    bool HardwareDecode = true,
    bool PreferHevc = false,
    bool Audio = true)
{
    public static readonly MirrorQuality BatterySaver = new(1280, 30, 4_000_000);
    public static readonly MirrorQuality Balanced = new(1920, 60, 8_000_000);
    public static readonly MirrorQuality High = new(2560, 60, 16_000_000);
    public static readonly MirrorQuality UltraHd = new(3840, 60, 30_000_000);
    public static MirrorQuality Default => Balanced;
}

/// <summary>A decoder this PC has (from the Media Foundation probe).</summary>
public sealed record PcDecoder(string Codec, bool Hw, int MaxW, int MaxH);

/// <summary>What the PC will ask the phone for, and the size it expects back.</summary>
public sealed record StreamPlan(string Codec, bool HwEncode, bool HwDecode, int MaxLong, int Fps, int Bitrate, int Width, int Height);

/// <summary>
/// Picks codec, hardware, size, frame rate and bitrate from the phone's caps, this PC's decoders
/// and the user's quality (04-PROTOCOL.md 7.5.7). Pure, so it is unit-tested.
/// </summary>
public static class StreamPlanner
{
    /// <summary>H.264 level 5.1, the Windows decoder's limit: macroblocks per frame and per second.</summary>
    public const int H264MaxMacroblocks = 36_864;
    public const int H264MaxMacroblocksPerSecond = 983_040;
    public const int MinBitrate = 500_000;

    public static StreamPlan? Plan(PeerMessage.StreamCaps caps, IReadOnlyList<PcDecoder> decoders, MirrorQuality quality)
    {
        if (!caps.Allowed || caps.Screen is not { } screen) return null;
        int srcLong = Math.Max(screen.W, screen.H);
        int target = Math.Min(srcLong, Math.Clamp(quality.MaxLong, StreamRules.MinMaxLong, StreamRules.MaxMaxLong));
        // H.264 has lower latency and decodes everywhere; above 2048 px only H.265 keeps 60 fps.
        string[] order = quality.PreferHevc || target > 2048
            ? [StreamRules.CodecH265, StreamRules.CodecH264]
            : [StreamRules.CodecH264, StreamRules.CodecH265];

        foreach (string codec in order)
        {
            var encoders = (caps.Encoders ?? [])
                .Where(e => e.Codec == codec && (e.Hw || codec == StreamRules.CodecH264)) // software H.265 is too slow for a phone
                .OrderByDescending(e => e.Hw == quality.HardwareEncode)
                .ThenByDescending(e => Math.Max(e.MaxW, e.MaxH));
            var decoder = decoders
                .Where(d => d.Codec == codec)
                .OrderByDescending(d => d.Hw == quality.HardwareDecode)
                .ThenByDescending(d => Math.Max(d.MaxW, d.MaxH))
                .FirstOrDefault();
            if (decoder is null) continue;
            foreach (var encoder in encoders)
            {
                int limLong = Math.Min(Math.Max(encoder.MaxW, encoder.MaxH), Math.Max(decoder.MaxW, decoder.MaxH));
                int limShort = Math.Min(Math.Min(encoder.MaxW, encoder.MaxH), Math.Min(decoder.MaxW, decoder.MaxH));
                if (StreamGeometry.Compute(screen.W, screen.H, target, limLong, limShort, StreamGeometry.Fit.Stretch) is not { } size) continue;
                int fps = Math.Min(Math.Min(quality.Fps, screen.MaxFps), Math.Min(encoder.Fps, StreamRules.MaxFps));
                if (codec == StreamRules.CodecH264)
                {
                    int macroblocks = size.Width / 16 * (size.Height / 16);
                    if (macroblocks > H264MaxMacroblocks) continue;
                    fps = Math.Min(fps, H264MaxMacroblocksPerSecond / macroblocks);
                }
                fps = Math.Max(1, fps);
                double bitsPerPixel = codec == StreamRules.CodecH265 ? 0.05 : 0.07;
                long wanted = (long)(size.Width * (long)size.Height * fps * bitsPerPixel);
                int cap = Math.Clamp(quality.MaxBitrate, StreamRules.MinBitrate, StreamRules.MaxBitrate);
                int bitrate = (int)Math.Clamp(Math.Max(wanted, MinBitrate), StreamRules.MinBitrate, cap);
                return new StreamPlan(codec, encoder.Hw, decoder.Hw, Math.Max(size.Width, size.Height), fps, bitrate, size.Width, size.Height);
            }
        }
        return null;
    }
}
