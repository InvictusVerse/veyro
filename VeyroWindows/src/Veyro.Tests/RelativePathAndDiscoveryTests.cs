using System.Text;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

public sealed class RelativePathTests
{
    [Theory]
    [InlineData("photo.jpg")]
    [InlineData("Documents/Reports/Q4-2026.pdf")]
    [InlineData("a/b/c/d/e/f.txt")]
    [InlineData("naïve-café.txt")]
    public void AcceptsWellFormedPaths(string path)
        => Assert.IsType<PathValidationResult.Valid>(RelativePath.Validate(path));

    [Theory]
    [InlineData(null, PathRejectReason.Empty)]
    [InlineData("", PathRejectReason.Empty)]
    [InlineData("../secret.txt", PathRejectReason.Traversal)]
    [InlineData("a/../../etc/passwd", PathRejectReason.Traversal)]
    [InlineData("./relative", PathRejectReason.CurrentDirSegment)]
    [InlineData("/etc/passwd", PathRejectReason.Absolute)]
    [InlineData("//server/share", PathRejectReason.UncPath)]
    [InlineData("C:/Windows/System32", PathRejectReason.DriveLetter)]
    [InlineData("dir\\file.txt", PathRejectReason.Backslash)]
    [InlineData("a//b.txt", PathRejectReason.EmptySegment)]
    [InlineData("dir/", PathRejectReason.EmptySegment)]
    [InlineData("CON.txt", PathRejectReason.ReservedName)]
    [InlineData("com1", PathRejectReason.ReservedName)]
    [InlineData("LPT9.dat", PathRejectReason.ReservedName)]
    [InlineData("trailing.", PathRejectReason.TrailingDotOrSpace)]
    [InlineData("trailing ", PathRejectReason.TrailingDotOrSpace)]
    [InlineData("file:stream", PathRejectReason.AlternateDataStream)]
    [InlineData("what?.txt", PathRejectReason.ForbiddenCharacter)]
    [InlineData("star*.txt", PathRejectReason.ForbiddenCharacter)]
    [InlineData("pipe|x", PathRejectReason.ForbiddenCharacter)]
    public void RejectsWithExpectedReason(string? path, PathRejectReason expected)
    {
        var result = Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate(path));
        Assert.Equal(expected, result.Reason);
    }

    [Fact]
    public void RejectsNulByte()
    {
        var r = Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate("safe.txt\u0000/../x"));
        Assert.Equal(PathRejectReason.ContainsNul, r.Reason);
    }

    [Fact]
    public void RejectsLoneSurrogate()
    {
        var r = Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate("bad\ud800.txt"));
        Assert.Equal(PathRejectReason.NotUtf8, r.Reason);
    }

    [Fact]
    public void LimitIs1024Utf8Bytes_MatchingVlpConstant()
    {
        Assert.Equal(1024, ProtocolConstants.MaxRelativePathBytes);

        Assert.IsType<PathValidationResult.Valid>(RelativePath.Validate(new string('a', 1024)));

        var over = Assert.IsType<PathValidationResult.Rejected>(
            RelativePath.Validate(new string('a', 1025)));
        Assert.Equal(PathRejectReason.TooLong, over.Reason);
    }

    [Fact]
    public void SegmentLimitIs64()
    {
        string ok = string.Join("/", Enumerable.Repeat("a", 64));
        Assert.IsType<PathValidationResult.Valid>(RelativePath.Validate(ok));

        string tooMany = string.Join("/", Enumerable.Repeat("a", 65));
        var r = Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate(tooMany));
        Assert.Equal(PathRejectReason.TooManySegments, r.Reason);
    }

    [Fact]
    public void DoesNotUnicodeNormalise_PreservingHashToFilenameCorrespondence()
    {
        // U+0065 U+0301 (e + combining acute) must NOT be folded to U+00E9.
        const string decomposed = "cafe\u0301.txt";
        var v = Assert.IsType<PathValidationResult.Valid>(RelativePath.Validate(decomposed));
        Assert.Equal(decomposed, v.Normalized);
    }

    [Theory]
    [InlineData("hello.txt", "hello.txt")]
    [InlineData("bad/name.txt", "bad_name.txt")]
    [InlineData("what?.txt", "what_.txt")]
    [InlineData("trailing. ", "trailing")]
    [InlineData("CON.txt", "_CON.txt")]
    [InlineData("", "unnamed")]
    public void SanitizeOutgoingSegment(string input, string expected)
        => Assert.Equal(expected, RelativePath.SanitizeOutgoingSegment(input));
}

public sealed class DiscoveryCodecTests
{
    private static PresenceRecord Sample(string? name = "Test PC", bool withKey = true) => new()
    {
        Version = 1,
        // Canonical 26-char Crockford id from identity/device-id.json. Note that an arbitrary
        // 26-character string is not necessarily canonical: 26 chars carry 130 bits but a
        // deviceId is 128, so decode drops the trailing 2 bits and re-encoding normalises the
        // final character. Both platforms re-encode on parse for exactly this reason.
        DeviceId = "6JE8415YZQQYQMBD34569HVYX4",
        Platform = PlatformType.Windows,
        CapabilityBits = 0x1F,
        PublicKeyHash = [1, 2, 3, 4, 5, 6, 7, 8],
        PresenceToken = [9, 10, 11, 12, 13, 14, 15, 16],
        Name = name,
        Port = 47650,
        PublicKey = withKey ? Enumerable.Range(0, 32).Select(i => (byte)i).ToArray() : null
    };

    [Fact]
    public void PresenceRecord_RoundTripsThroughKeyValueEncoding()
    {
        var original = Sample();
        byte[] encoded = PresenceCodec.EncodeToBytes(original);

        var ok = Assert.IsType<PresenceResult.Ok>(PresenceCodec.DecodeFromBytes(encoded));
        Assert.Equal(original.DeviceId, ok.Record.DeviceId);
        Assert.Equal(original.Port, ok.Record.Port);
        Assert.Equal(original.Name, ok.Record.Name);
        Assert.Equal(original.CapabilityBits, ok.Record.CapabilityBits);
        Assert.Equal(PlatformType.Windows, ok.Record.Platform);
        Assert.Equal(original.PublicKeyHash, ok.Record.PublicKeyHash);
        Assert.Equal(original.PresenceToken, ok.Record.PresenceToken);
        Assert.Equal(original.PublicKey, ok.Record.PublicKey);
    }

    [Fact]
    public void PresenceRecord_UsesExpectedWireKeys()
    {
        string text = Encoding.UTF8.GetString(PresenceCodec.EncodeToBytes(Sample()));
        string[] lines = text.Split('\n');

        Assert.Equal("v=1", lines[0]);
        Assert.StartsWith("id=", lines[1]);
        Assert.Equal("pf=windows", lines[2]);
        Assert.Equal("cap=1f", lines[3]);          // hex, lower-case
        Assert.Equal("pk8=0102030405060708", lines[4]);
        Assert.Equal("tok=090a0b0c0d0e0f10", lines[5]);
        Assert.Equal("p=47650", lines[6]);
        Assert.Equal("n=Test PC", lines[7]);
        Assert.StartsWith("pk=", lines[8]);
    }

    [Fact]
    public void PrivateMode_OmitsNameAndPublicKeyEntirely()
    {
        string text = Encoding.UTF8.GetString(
            PresenceCodec.EncodeToBytes(Sample(name: null, withKey: false)));

        // Omitted, not sent empty: an empty value still reveals that a name is being withheld.
        Assert.DoesNotContain("n=", text);
        Assert.DoesNotContain("pk=", text);
    }

    [Fact]
    public void DuplicateKey_IsRejected()
    {
        byte[] bytes = Encoding.UTF8.GetBytes("v=1\nv=1\nid=X\ncap=0\np=1");
        var bad = Assert.IsType<PresenceResult.Invalid>(PresenceCodec.DecodeFromBytes(bytes));
        Assert.Equal(PresenceRejectReason.DuplicateKey, bad.Reason);
    }

    [Fact]
    public void WrongVersion_IsRefusedNotGuessed()
    {
        byte[] bytes = Encoding.UTF8.GetBytes("v=2\nid=X\ncap=0\np=1");
        var bad = Assert.IsType<PresenceResult.Invalid>(PresenceCodec.DecodeFromBytes(bytes));
        Assert.Equal(PresenceRejectReason.BadVersion, bad.Reason);
    }

    [Fact]
    public void NameWithBidiOverride_IsRejectedAsSpoofing()
    {
        var r = Sample(name: "Trusted\u202EPC");
        var bad = Assert.IsType<PresenceResult.Invalid>(
            PresenceCodec.DecodeFromBytes(PresenceCodec.EncodeToBytes(r)));
        Assert.Equal(PresenceRejectReason.NameInvalid, bad.Reason);
    }

    [Fact]
    public void Beacon_BareQueryIsEightBytes_WithExactLayout()
    {
        byte[] q = BeaconCodec.EncodeBareQuery();
        Assert.Equal(8, q.Length);
        // "VEYR" | version=1 | type=QUERY | length=0
        Assert.Equal("56455952" + "01" + "01" + "0000", Convert.ToHexString(q).ToLowerInvariant());
    }

    [Fact]
    public void Beacon_AnnounceRoundTrips()
    {
        byte[] frame = BeaconCodec.Encode(BeaconCodec.BeaconType.Announce, Sample());
        var ok = Assert.IsType<BeaconCodec.BeaconResult.Ok>(BeaconCodec.Decode(frame));
        Assert.Equal(BeaconCodec.BeaconType.Announce, ok.Type);
        Assert.Equal(47650, ok.Record.Port);
    }

    [Fact]
    public void Beacon_DeclaredLengthIsNeverTrustedAgainstBuffer()
    {
        byte[] frame = BeaconCodec.Encode(BeaconCodec.BeaconType.Announce, Sample());
        // Claim a larger payload than the datagram actually carries.
        frame[6] = 0xFF;
        frame[7] = 0xFF;
        var bad = Assert.IsType<BeaconCodec.BeaconResult.Invalid>(BeaconCodec.Decode(frame));
        Assert.Equal(BeaconCodec.BeaconRejectReason.LengthMismatch, bad.Reason);
    }

    [Fact]
    public void Beacon_RespectsReceivedLengthNotBufferSize()
    {
        byte[] frame = BeaconCodec.Encode(BeaconCodec.BeaconType.Announce, Sample());
        byte[] oversizedBuffer = new byte[2048];
        frame.CopyTo(oversizedBuffer, 0);

        // Using buffer.Length would read uninitialised tail as payload.
        Assert.IsType<BeaconCodec.BeaconResult.Ok>(
            BeaconCodec.Decode(oversizedBuffer, frame.Length));
        Assert.IsType<BeaconCodec.BeaconResult.Invalid>(
            BeaconCodec.Decode(oversizedBuffer));
    }

    [Fact]
    public void Beacon_ForeignTrafficIsRejectedQuietlyOnMagic()
    {
        byte[] noise = Encoding.UTF8.GetBytes("GET / HTTP/1.1\r\n\r\n");
        var bad = Assert.IsType<BeaconCodec.BeaconResult.Invalid>(BeaconCodec.Decode(noise));
        Assert.Equal(BeaconCodec.BeaconRejectReason.BadMagic, bad.Reason);
    }

    [Fact]
    public void Beacon_AnnounceWithEmptyPayloadIsRejected()
    {
        byte[] q = BeaconCodec.EncodeBareQuery();
        q[5] = (byte)BeaconCodec.BeaconType.Announce;
        var bad = Assert.IsType<BeaconCodec.BeaconResult.Invalid>(BeaconCodec.Decode(q));
        Assert.Equal(BeaconCodec.BeaconRejectReason.BadPayload, bad.Reason);
    }
}
