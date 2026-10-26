using System.Globalization;

namespace Veyro.Core.Transfer;

/// <summary>
/// Speed, remaining time and size text for the transfer banner and history, computed only from
/// bytes actually moved. Unknown stays unknown (null) instead of showing an invented figure.
/// </summary>
public static class TransferProgressMath
{
    /// <summary>Below this much elapsed time a rate is noise, so none is reported.</summary>
    public static readonly TimeSpan MinimumSampleTime = TimeSpan.FromMilliseconds(500);

    /// <summary>Average bytes per second since the transfer started moving data, or null when not measurable yet.</summary>
    public static long? AverageBytesPerSecond(long bytesTransferred, TimeSpan elapsed)
    {
        if (bytesTransferred <= 0 || elapsed < MinimumSampleTime) return null;
        return (long)(bytesTransferred / elapsed.TotalSeconds);
    }

    /// <summary>Remaining time at <paramref name="bytesPerSecond"/>, or null when the rate or total is unknown.</summary>
    public static TimeSpan? EstimateRemaining(long bytesTransferred, long totalBytes, long? bytesPerSecond)
    {
        if (bytesPerSecond is not > 0 || totalBytes <= 0) return null;
        long remaining = Math.Max(0, totalBytes - bytesTransferred);
        return TimeSpan.FromSeconds(Math.Ceiling(remaining / (double)bytesPerSecond.Value));
    }

    /// <summary>"0 B", "512 B", "1.5 KB", "12.3 MB", "2.01 GB" (1024-based, invariant culture).</summary>
    public static string FormatBytes(long bytes)
    {
        if (bytes < 0) bytes = 0;
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }
        if (unit == 0) return string.Create(CultureInfo.InvariantCulture, $"{bytes} B");
        string format = value >= 100 ? "0" : value >= 10 ? "0.0" : "0.##";
        return value.ToString(format, CultureInfo.InvariantCulture) + " " + units[unit];
    }

    /// <summary>"45 s", "3 min 05 s", "1 h 02 min".</summary>
    public static string FormatDuration(TimeSpan duration)
    {
        if (duration < TimeSpan.Zero) duration = TimeSpan.Zero;
        if (duration.TotalHours >= 1)
            return string.Create(CultureInfo.InvariantCulture, $"{(int)duration.TotalHours} h {duration.Minutes:00} min");
        if (duration.TotalMinutes >= 1)
            return string.Create(CultureInfo.InvariantCulture, $"{(int)duration.TotalMinutes} min {duration.Seconds:00} s");
        return string.Create(CultureInfo.InvariantCulture, $"{(int)Math.Ceiling(duration.TotalSeconds)} s");
    }
}
