namespace Veyro.Core.Devices;

/// <summary>
/// The one rule for a device name from somewhere else (ADR-059): a phone's hello, helloAck or
/// deviceInfo, or a nickname. Mirrors Kotlin <c>com.veyro.core.model.DeviceNames</c>; the shared
/// cases are in protocol/testvectors/link-device-info-controls.json. Names are user data: never log them.
/// </summary>
public static class DeviceNames
{
    /// <summary>Longest accepted name in UTF-16 code units (C# and Kotlin length agree).</summary>
    public const int MaxChars = 64;

    /// <summary>
    /// The name trimmed, or null when it is empty, longer than <see cref="MaxChars"/>, or holds a
    /// control character: C0/C1 controls, line and paragraph separators, and the bidirectional
    /// embedding, override and isolate marks. A rejected name is dropped, never repaired.
    /// </summary>
    public static string? Clean(string? raw)
    {
        string? name = raw?.Trim();
        if (string.IsNullOrEmpty(name) || name.Length > MaxChars) return null;
        foreach (char c in name)
        {
            if (IsForbidden(c)) return null;
        }
        return name;
    }

    private static bool IsForbidden(char c)
        => char.IsControl(c) ||
           c is '\u2028' or '\u2029' ||
           c is >= '\u202A' and <= '\u202E' ||
           c is >= '\u2066' and <= '\u2069';
}
