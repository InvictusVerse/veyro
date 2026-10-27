using Veyro.Core.Link;

namespace Veyro.Core.Media;

/// <summary>Pure helpers for <c>mediaState</c> snapshots (04-PROTOCOL.md 6.2, ADR-026).</summary>
public static class MediaSnapshots
{
    public const int MaxSessions = 16;
    public const int MaxIdLength = 256;
    public const int MaxTextLength = 512;
    /// <summary>24 KiB of image (the protocol limit) is at most 32768 base64 characters.</summary>
    public const int MaxArtBase64Length = 32768;
    /// <summary>Position drift that counts as a seek rather than normal playback.</summary>
    public const long PositionToleranceMs = 1500;

    public static readonly PeerMessage.MediaState Empty = new([]);

    /// <summary>
    /// Untrusted input from the peer: drop sessions without a usable id, de-duplicate ids, cap
    /// counts and lengths, strip oversize art, clamp numbers into range.
    /// </summary>
    public static PeerMessage.MediaState Sanitize(PeerMessage.MediaState state)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var sessions = new List<MediaSessionInfo>();
        foreach (MediaSessionInfo? session in state.Sessions ?? [])
        {
            if (session is null || string.IsNullOrWhiteSpace(session.Id) || session.Id.Length > MaxIdLength) continue;
            if (!seen.Add(session.Id)) continue;
            long? duration = session.DurationMs is > 0 ? session.DurationMs : null;
            long? position = session.PositionMs is { } p ? Math.Clamp(p, 0, duration ?? long.MaxValue) : null;
            sessions.Add(session with
            {
                App = Clip(session.App),
                Title = Clip(session.Title),
                Artist = Clip(session.Artist),
                Album = Clip(session.Album),
                PositionMs = position,
                DurationMs = duration,
                Ts = session.Ts is > 0 ? session.Ts : null,
                CanSeek = session.CanSeek && duration is not null,
                Art = session.Art is { Length: > 0 and <= MaxArtBase64Length } art ? art : null
            });
            if (sessions.Count == MaxSessions) break;
        }
        return new PeerMessage.MediaState(
            sessions,
            state.Volume is { } volume ? Math.Clamp(volume, 0, 100) : null,
            state.Muted);
    }

    /// <summary>
    /// Current position: <c>positionMs + (now - ts)</c> while playing (ADR-026). When the sample
    /// time is missing or implausible against our clock (skew), the receive time is used instead.
    /// </summary>
    public static long? ExtrapolatePositionMs(MediaSessionInfo session, long nowMs, long receivedAtMs)
    {
        if (session.PositionMs is not { } position) return null;
        if (!session.Playing) return position;
        long sampledAt = session.Ts ?? receivedAtMs;
        if (sampledAt > nowMs + 5_000 || sampledAt < receivedAtMs - 60_000) sampledAt = receivedAtMs;
        long current = position + Math.Max(0, nowMs - sampledAt);
        return session.DurationMs is { } duration ? Math.Min(current, duration) : current;
    }

    /// <summary>
    /// Whether <paramref name="next"/> differs from <paramref name="previous"/> in a way the peer
    /// must hear about. Position moving as playback predicts is not a change; a seek is.
    /// </summary>
    public static bool IsSignificantChange(PeerMessage.MediaState? previous, PeerMessage.MediaState next)
    {
        if (previous is null) return true;
        if (previous.Volume != next.Volume || previous.Muted != next.Muted) return true;
        if (previous.Sessions.Count != next.Sessions.Count) return true;
        for (int i = 0; i < next.Sessions.Count; i++)
        {
            MediaSessionInfo before = previous.Sessions[i];
            MediaSessionInfo after = next.Sessions[i];
            if (before with { PositionMs = null, Ts = null } != after with { PositionMs = null, Ts = null }) return true;
            if (before.PositionMs is null != after.PositionMs is null) return true;
            if (before.PositionMs is not { } oldPosition || after.PositionMs is not { } newPosition) continue;
            long elapsed = before.Playing && before.Ts is { } oldTs && after.Ts is { } newTs ? Math.Max(0, newTs - oldTs) : 0;
            if (Math.Abs(newPosition - (oldPosition + elapsed)) > PositionToleranceMs) return true;
        }
        return false;
    }

    private static string? Clip(string? value)
        => value is null || value.Length <= MaxTextLength ? value : value[..MaxTextLength];
}
