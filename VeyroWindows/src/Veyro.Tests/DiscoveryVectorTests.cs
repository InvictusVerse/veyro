using System.Text;
using System.Text.Json;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Drives protocol/testvectors/discovery/presence-beacon.json.
///
/// Expected values were derived from 04-PROTOCOL.md section 3 by an independent script. The
/// public key is the all-zero key from identity/device-id.json, so the two vector files describe
/// the same hypothetical device and a mistake in the identity derivation would surface here too.
/// </summary>
public sealed class DiscoveryVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");

        return JsonDocument.Parse(File.ReadAllText(Path.Combine(
            dir!.FullName, "protocol", "testvectors", "discovery", "presence-beacon.json")));
    }

    private static PresenceRecord FromVectorInput(JsonElement input)
    {
        byte[] pk = Convert.FromHexString(input.GetProperty("staticPublicKeyHex").GetString()!);

        // pk8 = SHA-256(ascii("veyro-pk-v1") || staticPublicKey)[0..7]
        byte[] prefix = Encoding.ASCII.GetBytes("veyro-pk-v1");
        byte[] hashInput = new byte[prefix.Length + pk.Length];
        prefix.CopyTo(hashInput, 0);
        pk.CopyTo(hashInput, prefix.Length);
        byte[] pk8 = System.Security.Cryptography.SHA256.HashData(hashInput)[..8];

        return new PresenceRecord
        {
            Version = input.GetProperty("version").GetInt32(),
            DeviceId = input.GetProperty("deviceId").GetString()!,
            Platform = PlatformType.Windows,
            CapabilityBits = input.GetProperty("capabilityBits").GetInt32(),
            PublicKeyHash = pk8,
            PresenceToken = Convert.FromHexString(input.GetProperty("presenceTokenHex").GetString()!),
            Name = input.GetProperty("name").ValueKind == JsonValueKind.Null
                ? null
                : input.GetProperty("name").GetString(),
            Port = input.GetProperty("port").GetInt32(),
            PublicKey = input.GetProperty("name").ValueKind == JsonValueKind.Null ? null : pk
        };
    }

    [Fact]
    public void Presence_FullRecordMatchesVector()
    {
        using var doc = LoadVectors();
        var v = doc.RootElement.GetProperty("presenceVectors").EnumerateArray().First();

        var record = FromVectorInput(v.GetProperty("input"));
        var expected = v.GetProperty("expected");

        Assert.Equal(expected.GetProperty("pk8Hex").GetString(),
                     Convert.ToHexString(record.PublicKeyHash).ToLowerInvariant());
        Assert.Equal(expected.GetProperty("pkBase64Url").GetString(),
                     Base64Url.Encode(record.PublicKey!));

        byte[] payload = PresenceCodec.EncodeToBytes(record);
        Assert.Equal(expected.GetProperty("payloadLength").GetInt32(), payload.Length);
        Assert.Equal(expected.GetProperty("payloadText").GetString(), Encoding.UTF8.GetString(payload));

        // And it must decode back.
        var ok = Assert.IsType<PresenceResult.Ok>(PresenceCodec.DecodeFromBytes(payload));
        Assert.Equal(record.DeviceId, ok.Record.DeviceId);
        Assert.Equal(record.Port, ok.Record.Port);
        Assert.Equal(record.PublicKey, ok.Record.PublicKey);
    }

    [Fact]
    public void Presence_PrivateModeOmitsNameAndKey()
    {
        using var doc = LoadVectors();
        var v = doc.RootElement.GetProperty("presenceVectors").EnumerateArray().Skip(1).First();

        var record = FromVectorInput(v.GetProperty("input"));
        string text = Encoding.UTF8.GetString(PresenceCodec.EncodeToBytes(record));

        Assert.Equal(v.GetProperty("expected").GetProperty("payloadText").GetString(), text);

        foreach (var forbidden in v.GetProperty("expected").GetProperty("mustNotContain").EnumerateArray())
            Assert.DoesNotContain(forbidden.GetString()!, text);
    }

    [Fact]
    public void Beacon_BareQueryMatchesVector()
    {
        using var doc = LoadVectors();
        var v = doc.RootElement.GetProperty("beaconVectors").EnumerateArray().First();
        var expected = v.GetProperty("expected");

        byte[] frame = BeaconCodec.EncodeBareQuery();
        Assert.Equal(expected.GetProperty("frameLength").GetInt32(), frame.Length);
        Assert.Equal(expected.GetProperty("frameHex").GetString(),
                     Convert.ToHexString(frame).ToLowerInvariant());

        Assert.IsType<BeaconCodec.BeaconResult.Query>(BeaconCodec.Decode(frame));
    }

    [Fact]
    public void Beacon_AnnounceMatchesVector()
    {
        using var doc = LoadVectors();
        var presence = doc.RootElement.GetProperty("presenceVectors").EnumerateArray().First();
        var beacon = doc.RootElement.GetProperty("beaconVectors").EnumerateArray().Skip(1).First();

        var record = FromVectorInput(presence.GetProperty("input"));
        byte[] frame = BeaconCodec.Encode(BeaconCodec.BeaconType.Announce, record);

        var expected = beacon.GetProperty("expected");
        Assert.Equal(expected.GetProperty("frameLength").GetInt32(), frame.Length);
        Assert.Equal(expected.GetProperty("frameHex").GetString(),
                     Convert.ToHexString(frame).ToLowerInvariant());

        var ok = Assert.IsType<BeaconCodec.BeaconResult.Ok>(BeaconCodec.Decode(frame));
        Assert.Equal(BeaconCodec.BeaconType.Announce, ok.Type);
        Assert.Equal(record.DeviceId, ok.Record.DeviceId);
    }

    [Fact]
    public void Beacon_RejectionVectorsHold()
    {
        using var doc = LoadVectors();
        var record = FromVectorInput(
            doc.RootElement.GetProperty("presenceVectors").EnumerateArray().First().GetProperty("input"));

        byte[] good = BeaconCodec.Encode(BeaconCodec.BeaconType.Announce, record);

        // Declared length longer than the datagram.
        byte[] lying = (byte[])good.Clone();
        lying[6] = 0xFF; lying[7] = 0xFF;
        var m = Assert.IsType<BeaconCodec.BeaconResult.Invalid>(BeaconCodec.Decode(lying));
        Assert.Equal(BeaconCodec.BeaconRejectReason.LengthMismatch, m.Reason);

        // Received length is authoritative, not the buffer size.
        byte[] big = new byte[2048];
        good.CopyTo(big, 0);
        Assert.IsType<BeaconCodec.BeaconResult.Ok>(BeaconCodec.Decode(big, good.Length));
        Assert.IsType<BeaconCodec.BeaconResult.Invalid>(BeaconCodec.Decode(big));

        // Foreign traffic.
        var magic = Assert.IsType<BeaconCodec.BeaconResult.Invalid>(
            BeaconCodec.Decode(Encoding.ASCII.GetBytes("GET / HTTP/1.1")));
        Assert.Equal(BeaconCodec.BeaconRejectReason.BadMagic, magic.Reason);

        // Version refused rather than guessed.
        byte[] v2 = (byte[])good.Clone();
        v2[4] = 0x02;
        var badVer = Assert.IsType<BeaconCodec.BeaconResult.Invalid>(BeaconCodec.Decode(v2));
        Assert.Equal(BeaconCodec.BeaconRejectReason.BadVersion, badVer.Reason);

        // ANNOUNCE with no payload.
        byte[] emptyAnnounce = BeaconCodec.EncodeBareQuery();
        emptyAnnounce[5] = (byte)BeaconCodec.BeaconType.Announce;
        var bad = Assert.IsType<BeaconCodec.BeaconResult.Invalid>(BeaconCodec.Decode(emptyAnnounce));
        Assert.Equal(BeaconCodec.BeaconRejectReason.BadPayload, bad.Reason);
    }

    [Fact]
    public void Presence_RejectionVectorsHold()
    {
        const string validId = "6JE8415YZQQYQMBD34569HVYX4";
        const string head = "v=1\nid=" + validId + "\npf=windows\ncap=0\npk8=0102030405060708\ntok=0102030405060708\np=1";

        // Duplicate key.
        var dup = Assert.IsType<PresenceResult.Invalid>(
            PresenceCodec.DecodeFromBytes(Encoding.UTF8.GetBytes($"v=1\nv=1\nid={validId}\ncap=0\np=1")));
        Assert.Equal(PresenceRejectReason.DuplicateKey, dup.Reason);

        // A deviceId is 26 Crockford Base32 characters; "X" is not one. Accepting it on Windows
        // while Android rejects it is precisely the divergence these vectors exist to catch.
        var badId = Assert.IsType<PresenceResult.Invalid>(PresenceCodec.DecodeFromBytes(
            Encoding.UTF8.GetBytes("v=1\nid=X\npf=windows\ncap=0\npk8=0102030405060708\ntok=0102030405060708\np=1")));
        Assert.Equal(PresenceRejectReason.BadDeviceId, badId.Reason);

        // Bidi override in the name.
        var bidi = Assert.IsType<PresenceResult.Invalid>(
            PresenceCodec.DecodeFromBytes(Encoding.UTF8.GetBytes($"{head}\nn=Trusted\u202EPC")));
        Assert.Equal(PresenceRejectReason.NameInvalid, bidi.Reason);

        // Name longer than 32 UTF-8 bytes.
        var longName = Assert.IsType<PresenceResult.Invalid>(
            PresenceCodec.DecodeFromBytes(Encoding.UTF8.GetBytes($"{head}\nn={new string('x', 33)}")));
        Assert.Equal(PresenceRejectReason.NameTooLong, longName.Reason);

        // Port out of range.
        var badPort = Assert.IsType<PresenceResult.Invalid>(PresenceCodec.DecodeFromBytes(
            Encoding.UTF8.GetBytes($"v=1\nid={validId}\npf=windows\ncap=0\npk8=0102030405060708\ntok=0102030405060708\np=0")));
        Assert.Equal(PresenceRejectReason.BadPort, badPort.Reason);

        // Unknown keys are ignored, not rejected.
        var ok = Assert.IsType<PresenceResult.Ok>(
            PresenceCodec.DecodeFromBytes(Encoding.UTF8.GetBytes($"{head}\nfuture=whatever")));
        Assert.Equal(validId, ok.Record.DeviceId);
    }
}
