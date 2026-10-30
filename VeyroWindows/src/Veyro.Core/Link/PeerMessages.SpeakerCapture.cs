using System.Text.Json.Serialization;

namespace Veyro.Core.Link;

// Phone as speaker and photo capture for the PC (ADR-073, 04-PROTOCOL.md 6.10). Gated by PcAudio
// and PhotoCapture on both sides. Mirrors com.veyro.core.link.SpeakerCaptureMessages.

[JsonDerivedType(typeof(SpeakerStart), "speakerStart")]
[JsonDerivedType(typeof(SpeakerState), "speakerState")]
[JsonDerivedType(typeof(SpeakerStop), "speakerStop")]
[JsonDerivedType(typeof(CaptureRequest), "captureReq")]
[JsonDerivedType(typeof(CaptureResult), "captureResult")]
public abstract partial record PeerMessage
{
    /// <summary>PC to phone: play this PC's sound; the phone dials the VMS1 speaker socket at host:port.</summary>
    public sealed record SpeakerStart(
        [property: JsonPropertyName("sid")] string Sid,
        [property: JsonPropertyName("host")] string Host,
        [property: JsonPropertyName("port")] int Port) : PeerMessage;

    /// <summary>Phone to PC: playing, or stopped (final, with a reason).</summary>
    public sealed record SpeakerState(
        [property: JsonPropertyName("sid")] string Sid,
        [property: JsonPropertyName("state")] string State,
        [property: JsonPropertyName("reason")] string? Reason = null) : PeerMessage;

    /// <summary>Either direction: the speaker session is over.</summary>
    public sealed record SpeakerStop(
        [property: JsonPropertyName("sid")] string Sid,
        [property: JsonPropertyName("reason")] string Reason) : PeerMessage;

    /// <summary>PC to phone: take a photo ("photo") or scan a document ("scan"); the image comes as an offer with requestId = rid.</summary>
    public sealed record CaptureRequest(
        [property: JsonPropertyName("rid")] string Rid,
        [property: JsonPropertyName("mode")] string Mode) : PeerMessage;

    /// <summary>Phone to PC: the request ended without an image.</summary>
    public sealed record CaptureResult(
        [property: JsonPropertyName("rid")] string Rid,
        [property: JsonPropertyName("ok")] bool Ok,
        [property: JsonPropertyName("reason")] string? Reason = null) : PeerMessage;
}

/// <summary>Field rules of 04-PROTOCOL.md 6.10 for what the PC receives.</summary>
public static class SpeakerCaptureRules
{
    public const string StatePlaying = "playing";
    public const string StateStopped = "stopped";
    public const string ModePhoto = "photo";
    public const string ModeScan = "scan";

    public static readonly IReadOnlySet<string> SpeakerReasons = new HashSet<string>(StringComparer.Ordinal)
        { "user", "busy", "disabled", "network", "session-closed", "error" };

    public static readonly IReadOnlySet<string> CaptureReasons = new HashSet<string>(StringComparer.Ordinal)
        { "declined", "cancelled", "expired", "permission", "busy", "error" };

    /// <summary>How long the PC waits for a photo, counted from the request (the phone forgets it after 2 minutes).</summary>
    public static readonly TimeSpan CaptureTimeout = TimeSpan.FromMinutes(3);

    public static bool IsValid(PeerMessage.SpeakerState m) =>
        StreamRules.SidBytes(m.Sid) is not null && m.State is StatePlaying or StateStopped &&
        (m.Reason is null || SpeakerReasons.Contains(m.Reason));

    public static bool IsValid(PeerMessage.SpeakerStop m) => StreamRules.SidBytes(m.Sid) is not null && SpeakerReasons.Contains(m.Reason);

    public static bool IsValid(PeerMessage.CaptureResult m) =>
        StreamRules.SidBytes(m.Rid) is not null && (m.Reason is null || CaptureReasons.Contains(m.Reason));
}
