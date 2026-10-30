namespace Veyro.Core.Mirroring;

/// <summary>What the presenter does with a decoded picture.</summary>
public enum AvDecision { Present, Wait, Drop }

/// <summary>
/// Keeps mirrored video in step with the phone's audio (ADR-066, 04-PROTOCOL.md 7.5.8). Video and
/// audio carry ptsUs from the phone's one monotonic clock, so they compare directly. Audio is the
/// master clock while it plays: a picture is shown when the audio reaches it, held while it is
/// early and dropped once it is too late to matter. Without audio every picture is shown as soon
/// as it is decoded (lowest latency). Pure.
/// </summary>
public static class AvSync
{
    /// <summary>A picture later than this behind the audio is dropped (at least two frames).</summary>
    public const long MinLateUs = 50_000;

    public static AvDecision Decide(long videoPtsUs, long? audioClockUs, long frameIntervalUs)
    {
        if (audioClockUs is not { } audio) return AvDecision.Present;
        long interval = Math.Max(1, frameIntervalUs);
        long delta = videoPtsUs - audio;
        if (delta > interval / 2) return AvDecision.Wait;
        if (delta < -Math.Max(2 * interval, MinLateUs)) return AvDecision.Drop;
        return AvDecision.Present;
    }

    /// <summary>
    /// The pts of the sample now leaving the speaker: the pts of the first sample handed to the
    /// audio device since <paramref name="anchorPtsUs"/>, plus what the device reports as played,
    /// minus its output latency. Null before anything played.
    /// </summary>
    public static long? AudioClock(long anchorPtsUs, long framesPlayed, int sampleRate, long outputLatencyUs)
    {
        if (framesPlayed <= 0 || sampleRate <= 0) return null;
        return anchorPtsUs + framesPlayed * 1_000_000L / sampleRate - Math.Max(0, outputLatencyUs);
    }
}
