using System.Text;

namespace Veyro.Core.Protocol;

/// <summary>
/// Why a peer-supplied path was rejected. Mirrors com.veyro.core.protocol.RelativePath.Reason.
/// </summary>
public enum PathRejectReason
{
    Empty,
    TooLong,
    NotUtf8,
    ContainsNul,
    Absolute,
    DriveLetter,
    UncPath,
    Backslash,
    Traversal,
    CurrentDirSegment,
    EmptySegment,
    ReservedName,
    TrailingDotOrSpace,
    ForbiddenCharacter,
    ControlCharacter,
    AlternateDataStream,
    TooManySegments
}

public abstract record PathValidationResult
{
    /// <summary><c>Normalized</c> is safe to resolve against the destination directory.</summary>
    public sealed record Valid(string Normalized, IReadOnlyList<string> Segments) : PathValidationResult;

    public sealed record Rejected(PathRejectReason Reason, string Detail) : PathValidationResult;
}

/// <summary>
/// Validation of a path received from a peer, before it is allowed anywhere near the filesystem.
///
/// This is a security control, not a convenience helper (REQ-T11, 04-PROTOCOL.md section 7).
/// A sender is untrusted even when paired: pairing proves *who* is sending, not that what they
/// send is well-formed.
///
/// Mirrors com.veyro.core.protocol.RelativePath byte-for-byte, including the order of checks,
/// which determines which reason a path that violates several rules is reported under.
///
/// Returns a result rather than throwing, deliberately: a single bad path must fail one item,
/// not the whole transfer.
/// </summary>
public static class RelativePath
{
    /// <summary>Windows device names, special in any directory, with or without an extension.</summary>
    private static readonly HashSet<string> WindowsReserved = BuildReserved();

    /// <summary>Characters Windows forbids, plus the separator VLP reserves for itself.</summary>
    private const string ForbiddenChars = "<>:\"\\|?*";

    private const int MaxSegments = 64;

    private static HashSet<string> BuildReserved()
    {
        var set = new HashSet<string>(StringComparer.Ordinal) { "con", "prn", "aux", "nul" };
        for (int i = 1; i <= 9; i++)
        {
            set.Add($"com{i}");
            set.Add($"lpt{i}");
        }
        return set;
    }

    public static PathValidationResult Validate(string? raw)
    {
        if (string.IsNullOrEmpty(raw))
            return new PathValidationResult.Rejected(PathRejectReason.Empty, "path is empty");

        if (Encoding.UTF8.GetByteCount(raw) > ProtocolConstants.MaxRelativePathBytes)
            return new PathValidationResult.Rejected(
                PathRejectReason.TooLong, $"exceeds {ProtocolConstants.MaxRelativePathBytes} bytes");

        // A NUL truncates the path in any C-based syscall layer, so "safe.txt\0/../../x" can pass
        // a naive string check and then open a different file entirely.
        if (raw.Contains('\u0000'))
            return new PathValidationResult.Rejected(PathRejectReason.ContainsNul, "contains NUL");

        // Unpaired surrogates survive a string but corrupt on encode, and some filesystems
        // normalise them differently, which breaks the hash-to-filename correspondence.
        if (ContainsSurrogate(raw) && !IsWellFormedUtf16(raw))
            return new PathValidationResult.Rejected(
                PathRejectReason.NotUtf8, "malformed UTF-16 surrogate pair");

        // The wire format uses '/' only. Accepting '\' would mean two spellings of the same path.
        if (raw.Contains('\\'))
            return new PathValidationResult.Rejected(
                PathRejectReason.Backslash, "backslash is not a separator in VLP");

        // Order matters: "//server/share" is both UNC-shaped and absolute-shaped, and the UNC
        // diagnosis is the more specific one.
        if (raw.StartsWith("//", StringComparison.Ordinal))
            return new PathValidationResult.Rejected(PathRejectReason.UncPath, "UNC-style path");

        if (raw.StartsWith('/'))
            return new PathValidationResult.Rejected(PathRejectReason.Absolute, "absolute path");

        if (raw.Length >= 2 && raw[1] == ':' && char.IsLetter(raw[0]))
            return new PathValidationResult.Rejected(
                PathRejectReason.DriveLetter, "drive-qualified path");

        string[] segments = raw.Split('/');

        if (segments.Length > MaxSegments)
            return new PathValidationResult.Rejected(
                PathRejectReason.TooManySegments, $"{segments.Length} segments");

        for (int index = 0; index < segments.Length; index++)
        {
            string segment = segments[index];
            bool isLast = index == segments.Length - 1;

            if (segment.Length == 0)
            {
                // A doubled separator normalises differently per platform; a trailing one is
                // reported distinctly so the caller can tell a directory entry from a typo.
                return new PathValidationResult.Rejected(
                    PathRejectReason.EmptySegment,
                    isLast ? "trailing separator" : $"empty segment at {index}");
            }

            if (segment == "..")
                return new PathValidationResult.Rejected(
                    PathRejectReason.Traversal, "parent-directory segment");

            if (segment == ".")
                return new PathValidationResult.Rejected(
                    PathRejectReason.CurrentDirSegment, "current-directory segment");

            foreach (char c in segment)
            {
                if (c < 0x20 || c == 0x7F)
                    return new PathValidationResult.Rejected(
                        PathRejectReason.ControlCharacter,
                        $"control character U+{(int)c:X4}");
            }

            foreach (char c in segment)
            {
                if (ForbiddenChars.Contains(c))
                {
                    // ':' is both Windows-forbidden and the NTFS alternate-data-stream marker;
                    // report the more specific reason.
                    return c == ':'
                        ? new PathValidationResult.Rejected(
                            PathRejectReason.AlternateDataStream, "NTFS stream separator")
                        : new PathValidationResult.Rejected(
                            PathRejectReason.ForbiddenCharacter, $"character '{c}'");
                }
            }

            // Windows silently strips these, so "evil. " and "evil" become the same file — a way
            // to overwrite a target whose name was supposed to be different.
            if (segment.EndsWith('.') || segment.EndsWith(' '))
                return new PathValidationResult.Rejected(
                    PathRejectReason.TrailingDotOrSpace, $"segment '{segment}'");

            int dot = segment.IndexOf('.');
            string stem = (dot >= 0 ? segment[..dot] : segment).ToLowerInvariant();
            if (WindowsReserved.Contains(stem))
                return new PathValidationResult.Rejected(
                    PathRejectReason.ReservedName, $"reserved device name '{stem}'");
        }

        return new PathValidationResult.Valid(string.Join("/", segments), segments);
    }

    /// <summary>Convenience for call sites that only need a yes/no.</summary>
    public static bool IsValid(string? raw) => Validate(raw) is PathValidationResult.Valid;

    /// <summary>
    /// Makes a name derived from an *outgoing* local file safe to put on the wire.
    ///
    /// Note the asymmetry: this sanitises data we produce, whereas <see cref="Validate"/>
    /// validates data we receive. Never use this on received input — silently rewriting a
    /// hostile path hides an attack instead of failing it.
    /// </summary>
    public static string SanitizeOutgoingSegment(string name)
    {
        var sb = new StringBuilder(name.Length);
        foreach (char c in name)
        {
            sb.Append(c < 0x20 || c == 0x7F || ForbiddenChars.Contains(c) || c == '/' ? '_' : c);
        }

        string cleaned = sb.ToString().TrimEnd('.', ' ');
        if (cleaned.Length == 0) cleaned = "unnamed";

        int dot = cleaned.IndexOf('.');
        string stem = (dot >= 0 ? cleaned[..dot] : cleaned).ToLowerInvariant();
        return WindowsReserved.Contains(stem) ? $"_{cleaned}" : cleaned;
    }

    private static bool ContainsSurrogate(string s)
    {
        foreach (char c in s)
        {
            if (char.IsSurrogate(c)) return true;
        }
        return false;
    }

    private static bool IsWellFormedUtf16(string s)
    {
        int i = 0;
        while (i < s.Length)
        {
            char c = s[i];
            if (char.IsHighSurrogate(c))
            {
                if (i + 1 >= s.Length || !char.IsLowSurrogate(s[i + 1])) return false;
                i += 2;
            }
            else if (char.IsLowSurrogate(c))
            {
                return false;
            }
            else
            {
                i++;
            }
        }
        return true;
    }
}
