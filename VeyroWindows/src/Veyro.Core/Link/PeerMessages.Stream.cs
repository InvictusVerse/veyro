using System.Text.Json.Serialization;
using Veyro.Core.Protocol;

namespace Veyro.Core.Link;

// Screen mirroring control messages (ADR-066, 04-PROTOCOL.md 6.9). The PC decides every quality
// setting (codec, hardware encoding, size, frame rate, bitrate, audio); the phone reports what it
// can do and whether its user allows it, asks Android for capture consent and encodes what the PC
// asked for. Named stream* because mediaState is ADR-026's media-session message. Gated by
// ScreenSource on the phone and ScreenView on the PC. Mirrors com.veyro.core.link.StreamMessages.

[JsonDerivedType(typeof(StreamCaps), "streamCaps")]
[JsonDerivedType(typeof(StreamStart), "streamStart")]
[JsonDerivedType(typeof(StreamState), "streamState")]
[JsonDerivedType(typeof(StreamConfig), "streamConfig")]
[JsonDerivedType(typeof(StreamKeyframe), "streamKeyframe")]
[JsonDerivedType(typeof(StreamStop), "streamStop")]
public abstract partial record PeerMessage
{
    /// <summary>Phone to PC, after hello and whenever a value changes. Untrusted: check with <see cref="StreamRules"/>.</summary>
    public sealed record StreamCaps(
        [property: JsonPropertyName("allowed")] bool Allowed = false,
        [property: JsonPropertyName("screen")] StreamScreen? Screen = null,
        [property: JsonPropertyName("encoders")] IReadOnlyList<StreamEncoder>? Encoders = null,
        [property: JsonPropertyName("audio")] bool Audio = false) : PeerMessage;

    public sealed record StreamScreen(
        [property: JsonPropertyName("w")] int W,
        [property: JsonPropertyName("h")] int H,
        [property: JsonPropertyName("maxFps")] int MaxFps);

    public sealed record StreamEncoder(
        [property: JsonPropertyName("codec")] string Codec,
        [property: JsonPropertyName("hw")] bool Hw,
        [property: JsonPropertyName("maxW")] int MaxW,
        [property: JsonPropertyName("maxH")] int MaxH,
        [property: JsonPropertyName("fps")] int Fps);

    /// <summary>PC to phone: start mirroring with exactly these settings.</summary>
    public sealed record StreamStart(
        [property: JsonPropertyName("sid")] string Sid,
        [property: JsonPropertyName("source")] string Source,
        [property: JsonPropertyName("codec")] string Codec,
        [property: JsonPropertyName("hw")] bool Hw,
        [property: JsonPropertyName("maxLong")] int MaxLong,
        [property: JsonPropertyName("fps")] int Fps,
        [property: JsonPropertyName("bitrate")] int Bitrate,
        [property: JsonPropertyName("audio")] bool Audio,
        [property: JsonPropertyName("host")] string Host,
        [property: JsonPropertyName("port")] int Port) : PeerMessage;

    /// <summary>Phone to PC: what the session is doing and the values in use.</summary>
    public sealed record StreamState(
        [property: JsonPropertyName("sid")] string Sid,
        [property: JsonPropertyName("state")] string State,
        [property: JsonPropertyName("reason")] string? Reason = null,
        [property: JsonPropertyName("codec")] string? Codec = null,
        [property: JsonPropertyName("w")] int? W = null,
        [property: JsonPropertyName("h")] int? H = null,
        [property: JsonPropertyName("fps")] int? Fps = null,
        [property: JsonPropertyName("bitrate")] int? Bitrate = null,
        [property: JsonPropertyName("hw")] bool? Hw = null,
        [property: JsonPropertyName("audio")] bool? Audio = null,
        [property: JsonPropertyName("thermal")] int? Thermal = null) : PeerMessage;

    /// <summary>PC to phone: new limits mid-session (bitrate control, the user's changes, pause).</summary>
    public sealed record StreamConfig(
        [property: JsonPropertyName("sid")] string Sid,
        [property: JsonPropertyName("maxLong")] int? MaxLong = null,
        [property: JsonPropertyName("fps")] int? Fps = null,
        [property: JsonPropertyName("bitrate")] int? Bitrate = null,
        [property: JsonPropertyName("audio")] bool? Audio = null,
        [property: JsonPropertyName("paused")] bool? Paused = null) : PeerMessage;

    /// <summary>PC to phone: send a keyframe. Sent at most once per 500 ms.</summary>
    public sealed record StreamKeyframe([property: JsonPropertyName("sid")] string Sid) : PeerMessage;

    /// <summary>Either direction: the session is over.</summary>
    public sealed record StreamStop(
        [property: JsonPropertyName("sid")] string Sid,
        [property: JsonPropertyName("reason")] string Reason) : PeerMessage;
}

/// <summary>Values and field rules of 04-PROTOCOL.md 6.9 for what the PC receives; a message that breaks one is dropped.</summary>
public static class StreamRules
{
    public const string SourceScreen = "screen";
    public const string CodecH264 = "h264";
    public const string CodecH265 = "h265";

    public const string StateAsking = "asking";
    public const string StateStarting = "starting";
    public const string StateStreaming = "streaming";
    public const string StatePaused = "paused";
    public const string StateStopped = "stopped";

    public static readonly IReadOnlySet<string> States = new HashSet<string>(StringComparer.Ordinal)
        { StateAsking, StateStarting, StateStreaming, StatePaused, StateStopped };

    public static readonly IReadOnlySet<string> Reasons = new HashSet<string>(StringComparer.Ordinal)
    {
        "user", "declined", "timeout", "busy", "disabled", "permission", "unsupported",
        "projection-ended", "locked", "thermal", "encoder", "network", "session-closed", "error",
    };

    public const int MinMaxLong = 160, MaxMaxLong = 3840;
    public const int MinFps = 1, MaxFps = 120;
    public const int MinBitrate = 250_000, MaxBitrate = 80_000_000;

    /// <summary>A new session id: 16 random bytes as 22 characters of unpadded base64url.</summary>
    public static string NewSid() => Base64Url.Encode(System.Security.Cryptography.RandomNumberGenerator.GetBytes(16));

    /// <summary>The 16 sid bytes, or null unless <paramref name="sid"/> is 22 characters of canonical base64url.</summary>
    public static byte[]? SidBytes(string? sid)
    {
        if (sid is not { Length: 22 } || !sid.All(c => c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_')) return null;
        byte[]? bytes = Base64Url.DecodeOrNull(sid);
        // .NET ignores non-zero trailing bits; re-encoding refuses a non-canonical id.
        return bytes is { Length: 16 } && Base64Url.Encode(bytes) == sid ? bytes : null;
    }

    public static bool IsCodec(string? codec) => codec is CodecH264 or CodecH265;

    /// <summary>The usable part of a phone's caps, or null when it breaks a rule. Unknown codecs are dropped, not fatal.</summary>
    public static PeerMessage.StreamCaps? Clean(PeerMessage.StreamCaps caps)
    {
        if (caps.Screen is { } s && (s.W is < 16 or > 8192 || s.H is < 16 or > 8192 || s.MaxFps is < 1 or > 240)) return null;
        var encoders = (caps.Encoders ?? [])
            .Where(e => e is not null && IsCodec(e.Codec) && e.MaxW is >= 16 and <= 8192 && e.MaxH is >= 16 and <= 8192 && e.Fps is >= 1 and <= 240)
            .Take(16)
            .ToList();
        return caps with { Encoders = encoders };
    }

    public static bool IsValid(PeerMessage.StreamState m) =>
        SidBytes(m.Sid) is not null && States.Contains(m.State) &&
        (m.Reason is null || Reasons.Contains(m.Reason)) &&
        (m.Codec is null || IsCodec(m.Codec)) &&
        m.W is null or (>= 16 and <= 8192) && m.H is null or (>= 16 and <= 8192) &&
        m.Fps is null or (>= 1 and <= 240) && m.Bitrate is null or (>= 0 and <= MaxBitrate) &&
        m.Thermal is null or (>= 0 and <= 6);

    public static bool IsValid(PeerMessage.StreamStop m) => SidBytes(m.Sid) is not null && Reasons.Contains(m.Reason);
}
