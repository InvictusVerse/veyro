using System.Text;
using System.Text.Json;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Drives the shared interop vectors in <c>protocol/testvectors/</c>.
///
/// These are the only tests in this project whose expected values were produced independently of
/// this implementation. Everything else asserts that the C# agrees with itself or with one
/// person's reading of the Kotlin. Per <c>protocol/testvectors/README.md</c>: a vector that only
/// one platform passes is not a passing vector — it is a bug report about the other platform.
/// </summary>
public sealed class InteropVectorTests
{
    private static string VectorRoot
    {
        get
        {
            var dir = new DirectoryInfo(AppContext.BaseDirectory);
            while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
                dir = dir.Parent;

            Assert.True(dir is not null, "could not locate protocol/testvectors from the test output directory");
            return Path.Combine(dir!.FullName, "protocol", "testvectors");
        }
    }

    private static JsonDocument Load(params string[] relative)
        => JsonDocument.Parse(File.ReadAllText(Path.Combine(VectorRoot, Path.Combine(relative))));

    // ── VTC1 frames ────────────────────────────────────────────────────────────────────────

    private static TcpFrame BuildFrom(JsonElement input)
    {
        string type = input.GetProperty("type").GetString()!;
        long seq = input.GetProperty("seq").GetInt64();
        string tid = input.GetProperty("transferId").GetString()!;

        return type switch
        {
            "TransferEnd" => new TcpFrame.TransferEnd(seq, tid, input.GetProperty("completed").GetBoolean()),

            "Chunk" => new TcpFrame.Chunk(
                seq, tid,
                input.GetProperty("itemIndex").GetInt32(),
                input.GetProperty("chunkIndex").GetInt32(),
                Convert.FromHexString(input.GetProperty("dataHex").GetString()!)),

            "ItemBegin" => new TcpFrame.ItemBegin(
                seq, tid,
                input.GetProperty("itemIndex").GetInt32(),
                input.GetProperty("sizeBytes").GetInt64(),
                input.GetProperty("chunkCount").GetInt32(),
                input.GetProperty("chunkSize").GetInt32()),

            "Auth" => new TcpFrame.Auth(
                seq, tid,
                input.GetProperty("authToken").GetString()!,
                input.GetProperty("senderDeviceId").GetString()!,
                input.GetProperty("route").GetString() == "WIFI_DIRECT"
                    ? TransferRoute.WifiDirect
                    : TransferRoute.LanTcp),

            "AuthAck" => new TcpFrame.AuthAck(
                seq, tid,
                input.GetProperty("ok").GetBoolean(),
                input.GetProperty("reason").ValueKind == JsonValueKind.Null
                    ? null
                    : input.GetProperty("reason").GetString()),

            "ItemEnd" => new TcpFrame.ItemEnd(
                seq, tid,
                input.GetProperty("itemIndex").GetInt32(),
                input.GetProperty("sha256Hex").GetString()!),

            "ItemResult" => new TcpFrame.ItemResult(
                seq, tid,
                input.GetProperty("itemIndex").GetInt32(),
                input.GetProperty("ok").GetBoolean(),
                input.TryGetProperty("error", out var e) && e.ValueKind != JsonValueKind.Null
                    ? e.GetString()
                    : null),

            "Cancel" => new TcpFrame.Cancel(seq, tid, input.GetProperty("reason").GetString()!),

            _ => throw new NotSupportedException($"vector uses unknown frame type '{type}'")
        };
    }

    public static TheoryData<string> VectorNames()
    {
        var data = new TheoryData<string>();
        using var doc = Load("frames", "vtc1-frames.json");
        foreach (var v in doc.RootElement.GetProperty("vectors").EnumerateArray())
            data.Add(v.GetProperty("name").GetString()!);
        return data;
    }

    [Theory]
    [MemberData(nameof(VectorNames))]
    public async Task Vtc1_EncodesToExactVectorBytes(string vectorName)
    {
        using var doc = Load("frames", "vtc1-frames.json");
        var vector = doc.RootElement.GetProperty("vectors").EnumerateArray()
            .Single(v => v.GetProperty("name").GetString() == vectorName);

        var frame = BuildFrom(vector.GetProperty("input"));
        byte[] encoded = await TcpFrameCodec.EncodeAsync(frame);

        var expected = vector.GetProperty("expected");
        Assert.Equal(expected.GetProperty("frameHex").GetString(),
                     Convert.ToHexString(encoded).ToLowerInvariant());
        Assert.Equal(expected.GetProperty("frameLength").GetInt32(), encoded.Length);
    }

    [Theory]
    [MemberData(nameof(VectorNames))]
    public async Task Vtc1_DecodesVectorBytesBackToInput(string vectorName)
    {
        using var doc = Load("frames", "vtc1-frames.json");
        var vector = doc.RootElement.GetProperty("vectors").EnumerateArray()
            .Single(v => v.GetProperty("name").GetString() == vectorName);

        byte[] wire = Convert.FromHexString(vector.GetProperty("expected").GetProperty("frameHex").GetString()!);
        using var ms = new MemoryStream(wire);

        var decoded = await TcpFrameCodec.ReadFrameOrNullAsync(ms);
        Assert.NotNull(decoded);

        var expectedFrame = BuildFrom(vector.GetProperty("input"));
        Assert.Equal(expectedFrame, decoded);

        // The vector must describe a whole frame and nothing more.
        Assert.Null(await TcpFrameCodec.ReadFrameOrNullAsync(ms));
    }

    [Fact]
    public async Task Vtc1_BoundsVectorsHold()
    {
        await Assert.ThrowsAsync<ArgumentException>(
            async () => await TcpFrameCodec.EncodeAsync(new TcpFrame.TransferEnd(1, "", true)));

        await Assert.ThrowsAsync<ArgumentException>(
            async () => await TcpFrameCodec.EncodeAsync(
                new TcpFrame.TransferEnd(1, new string('a', 257), true)));

        byte[] good = await TcpFrameCodec.EncodeAsync(new TcpFrame.TransferEnd(1, "tx1", true));

        byte[] badMagic = (byte[])good.Clone();
        badMagic[0] = 0x00;
        using (var ms = new MemoryStream(badMagic))
            await Assert.ThrowsAsync<InvalidDataException>(async () => await TcpFrameCodec.ReadFrameOrNullAsync(ms));

        using (var empty = new MemoryStream())
            Assert.Null(await TcpFrameCodec.ReadFrameOrNullAsync(empty));

        using (var truncated = new MemoryStream(good[..(good.Length - 1)]))
            await Assert.ThrowsAsync<EndOfStreamException>(
                async () => await TcpFrameCodec.ReadFrameOrNullAsync(truncated));
    }

    // ── Identity ───────────────────────────────────────────────────────────────────────────

    [Fact]
    public void DeviceId_MatchesIdentityVectors()
    {
        using var doc = Load("identity", "device-id.json");

        foreach (var v in doc.RootElement.GetProperty("vectors").EnumerateArray())
        {
            byte[] pk = Convert.FromHexString(v.GetProperty("input").GetProperty("staticPublicKeyHex").GetString()!);
            var expected = v.GetProperty("expected");

            string actualId = DeviceId.FromPublicKey(pk).Value;
            Assert.Equal(expected.GetProperty("deviceId").GetString(), actualId);

            // The vector's fingerprint is space-separated upper-case hex of deviceIdBytes[0..7].
            byte[] idBytes = Convert.FromHexString(expected.GetProperty("deviceIdBytesHex").GetString()!);
            string fingerprint = string.Join(" ",
                idBytes.Take(8).Select(b => b.ToString("X2")));
            Assert.Equal(expected.GetProperty("fingerprint").GetString(), fingerprint);

            // And the id must be a 26-character Crockford Base32 rendering of those 16 bytes.
            Assert.Equal(26, actualId.Length);
            Assert.Equal(idBytes, Crockford32.Decode(actualId).Take(16).ToArray());
        }
    }
}
