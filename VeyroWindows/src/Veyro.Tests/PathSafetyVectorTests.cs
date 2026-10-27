using System.Text.Json;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Drives protocol/testvectors/path-safety/relative-path.json.
///
/// This is the only vector file covering a security control rather than a wire format. The
/// expected reasons matter as much as accept/reject: the check order is normative, so two
/// implementations that both reject a path but disagree on why have diverged in a way that will
/// eventually show up as inconsistent diagnostics or an inconsistent audit trail.
/// </summary>
public sealed class PathSafetyVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");

        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "path-safety", "relative-path.json")));
    }

    /// <summary>Maps the canonical protocol reason names to the C# enum.</summary>
    private static PathRejectReason ParseReason(string wire) => wire switch
    {
        "EMPTY" => PathRejectReason.Empty,
        "TOO_LONG" => PathRejectReason.TooLong,
        "NOT_UTF8" => PathRejectReason.NotUtf8,
        "CONTAINS_NUL" => PathRejectReason.ContainsNul,
        "ABSOLUTE" => PathRejectReason.Absolute,
        "DRIVE_LETTER" => PathRejectReason.DriveLetter,
        "UNC_PATH" => PathRejectReason.UncPath,
        "BACKSLASH" => PathRejectReason.Backslash,
        "TRAVERSAL" => PathRejectReason.Traversal,
        "CURRENT_DIR_SEGMENT" => PathRejectReason.CurrentDirSegment,
        "EMPTY_SEGMENT" => PathRejectReason.EmptySegment,
        "RESERVED_NAME" => PathRejectReason.ReservedName,
        "TRAILING_DOT_OR_SPACE" => PathRejectReason.TrailingDotOrSpace,
        "FORBIDDEN_CHARACTER" => PathRejectReason.ForbiddenCharacter,
        "CONTROL_CHARACTER" => PathRejectReason.ControlCharacter,
        "ALTERNATE_DATA_STREAM" => PathRejectReason.AlternateDataStream,
        "TOO_MANY_SEGMENTS" => PathRejectReason.TooManySegments,
        _ => throw new ArgumentException($"vector uses unknown reason '{wire}'")
    };

    public static TheoryData<string> AcceptedPaths()
    {
        var data = new TheoryData<string>();
        using var doc = LoadVectors();
        foreach (var v in doc.RootElement.GetProperty("accept").EnumerateArray())
            data.Add(v.GetProperty("path").GetString()!);
        return data;
    }

    [Theory]
    [MemberData(nameof(AcceptedPaths))]
    public void AcceptedPaths_Validate(string path)
    {
        var result = RelativePath.Validate(path);
        var ok = Assert.IsType<PathValidationResult.Valid>(result);

        // Accepted paths must come back unchanged — no normalisation, no rewriting.
        Assert.Equal(path, ok.Normalized);
    }

    public static TheoryData<string?, string> RejectedPaths()
    {
        var data = new TheoryData<string?, string>();
        using var doc = LoadVectors();
        foreach (var v in doc.RootElement.GetProperty("reject").EnumerateArray())
        {
            var p = v.GetProperty("path");
            data.Add(p.ValueKind == JsonValueKind.Null ? null : p.GetString(),
                     v.GetProperty("reason").GetString()!);
        }
        return data;
    }

    [Theory]
    [MemberData(nameof(RejectedPaths))]
    public void RejectedPaths_FailWithTheExpectedReason(string? path, string expectedReason)
    {
        var result = RelativePath.Validate(path);
        var rejected = Assert.IsType<PathValidationResult.Rejected>(result);
        Assert.Equal(ParseReason(expectedReason), rejected.Reason);
    }

    public static TheoryData<string, string> OrderingCases()
    {
        var data = new TheoryData<string, string>();
        using var doc = LoadVectors();
        foreach (var v in doc.RootElement.GetProperty("orderingVectors").EnumerateArray())
            data.Add(v.GetProperty("path").GetString()!, v.GetProperty("reason").GetString()!);
        return data;
    }

    [Theory]
    [MemberData(nameof(OrderingCases))]
    public void CheckOrder_DeterminesTheReportedReason(string path, string expectedReason)
    {
        var rejected = Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate(path));
        Assert.Equal(ParseReason(expectedReason), rejected.Reason);
    }

    [Fact]
    public void GeneratedBoundaryCases()
    {
        using var doc = LoadVectors();

        var limits = doc.RootElement.GetProperty("limits");
        int maxBytes = limits.GetProperty("maxUtf8Bytes").GetInt32();
        int maxSegments = limits.GetProperty("maxSegments").GetInt32();

        Assert.Equal(ProtocolConstants.MaxRelativePathBytes, maxBytes);

        // Exactly at the limit is accepted; one past it is not.
        Assert.IsType<PathValidationResult.Valid>(RelativePath.Validate(new string('a', maxBytes)));
        var tooLong = Assert.IsType<PathValidationResult.Rejected>(
            RelativePath.Validate(new string('a', maxBytes + 1)));
        Assert.Equal(PathRejectReason.TooLong, tooLong.Reason);

        Assert.IsType<PathValidationResult.Valid>(
            RelativePath.Validate(string.Join("/", Enumerable.Repeat("a", maxSegments))));
        var tooMany = Assert.IsType<PathValidationResult.Rejected>(
            RelativePath.Validate(string.Join("/", Enumerable.Repeat("a", maxSegments + 1))));
        Assert.Equal(PathRejectReason.TooManySegments, tooMany.Reason);
    }

    [Fact]
    public void LoneSurrogates_AreRejected()
    {
        // Generated rather than read from the vector file: a lone surrogate cannot be carried in
        // a JSON string, which is itself why the rule exists.
        var high = Assert.IsType<PathValidationResult.Rejected>(
            RelativePath.Validate("lone\ud800surrogate.txt"));
        Assert.Equal(PathRejectReason.NotUtf8, high.Reason);

        var low = Assert.IsType<PathValidationResult.Rejected>(
            RelativePath.Validate("lone\udc00surrogate.txt"));
        Assert.Equal(PathRejectReason.NotUtf8, low.Reason);

        // A correctly paired surrogate (an astral-plane character) must still be accepted.
        Assert.IsType<PathValidationResult.Valid>(RelativePath.Validate("emoji\ud83d\ude00.txt"));
    }

    [Fact]
    public void LengthLimitIsBytesNotCharacters()
    {
        // A multi-byte character must count as its UTF-8 length. 'é' is 2 bytes, so 513 of them
        // exceed 1024 while being only 513 characters.
        var rejected = Assert.IsType<PathValidationResult.Rejected>(
            RelativePath.Validate(new string('é', 513)));
        Assert.Equal(PathRejectReason.TooLong, rejected.Reason);

        Assert.IsType<PathValidationResult.Valid>(RelativePath.Validate(new string('é', 512)));
    }

    public static TheoryData<string, string> SanitizeCases()
    {
        var data = new TheoryData<string, string>();
        using var doc = LoadVectors();
        foreach (var v in doc.RootElement.GetProperty("sanitizeOutgoingVectors").EnumerateArray())
            data.Add(v.GetProperty("input").GetString()!, v.GetProperty("expected").GetString()!);
        return data;
    }

    [Theory]
    [MemberData(nameof(SanitizeCases))]
    public void SanitizeOutgoingSegment(string input, string expected)
        => Assert.Equal(expected, RelativePath.SanitizeOutgoingSegment(input));

    [Fact]
    public void ValidationNeverThrows_EvenOnHostileInput()
    {
        // The failure mode is load-bearing: one bad path must fail one item, not the transfer.
        string[] hostile =
        [
            "\u0000", "\\", "//", "..", ".", "", "C:", "\ud800", "\udfff",
            new string('/', 100), new string('a', 5000), "con", "a/b/c:\u0000\\.."
        ];

        foreach (string h in hostile)
        {
            var result = RelativePath.Validate(h);
            Assert.True(result is PathValidationResult.Valid or PathValidationResult.Rejected);
        }
    }
}
