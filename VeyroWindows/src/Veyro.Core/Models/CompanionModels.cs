namespace Veyro.Core.Models;

/// <summary>
/// Companion-device models backing the Veyro Control Center sidebar
/// (battery status, ringer/audio streams, media playback session, and mirrored notifications).
/// </summary>
public enum RingerMode
{
    Silent = 0,
    Vibrate = 1,
    Normal = 2
}

public sealed record BatteryState(
    int LevelPercent,
    bool IsCharging
);

public sealed record AudioStreamModel(
    string StreamId,
    string Label,
    string IconGlyph,
    int Level,
    int MaxLevel = 100
);

public sealed record MediaSessionModel(
    string PackageName,
    string AppName,
    string TrackTitle,
    string Artist,
    bool IsPlaying,
    string? ThumbnailBase64 = null
)
{
    public string PlayPauseGlyph => IsPlaying ? "\uE769" : "\uE768";
}

public sealed record NotificationModel(
    string Key,
    string DeviceId,
    string AppPackage,
    string AppName,
    string Title,
    string Text,
    DateTimeOffset Timestamp,
    bool IsPinned = false
);
