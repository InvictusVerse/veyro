using System.Text;
using System.Text.Json;
using Veyro.Core.Crypto;
using Xunit;

namespace Veyro.Tests;

/// <summary>
/// Drives protocol/testvectors/link/framecrypto.json.
///
/// The expected values were produced by .NET BCL primitives in a throwaway program that
/// referenced neither implementation, and the harness was sanity-checked against the published
/// SHA-256 digest of "abc". The Kotlin suite pins the same constants.
/// </summary>
public sealed class FrameCryptoVectorTests
{
    private static JsonDocument LoadVectors()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !Directory.Exists(Path.Combine(dir.FullName, "protocol", "testvectors")))
            dir = dir.Parent;
        Assert.True(dir is not null, "could not locate protocol/testvectors");

        return JsonDocument.Parse(File.ReadAllText(
            Path.Combine(dir!.FullName, "protocol", "testvectors", "link", "framecrypto.json")));
    }

    private static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

    [Fact]
    public void BootstrapKey_MatchesVectors_AndIsRoleOrderIndependent()
    {
        using var doc = LoadVectors();
        string? firstKey = null;

        foreach (var v in doc.RootElement.GetProperty("bootstrapKeyVectors").EnumerateArray())
        {
            var input = v.GetProperty("input");
            byte[] key = FrameCrypto.DeriveBootstrapKey(
                input.GetProperty("pin").GetString()!,
                input.GetProperty("initiatorId").GetString()!,
                input.GetProperty("targetId").GetString()!);

            Assert.Equal(v.GetProperty("expected").GetProperty("keyHex").GetString(), Hex(key));

            firstKey ??= Hex(key);
            // Both vectors are the same pair with the roles swapped; they must agree.
            Assert.Equal(firstKey, Hex(key));
        }
    }

    [Fact]
    public void PairingFrame_MatchesVector()
    {
        using var doc = LoadVectors();
        var v = doc.RootElement.GetProperty("pairingFrameVectors").EnumerateArray().Single();
        var input = v.GetProperty("input");
        var expected = v.GetProperty("expected");

        byte[] key = Convert.FromHexString(input.GetProperty("keyHex").GetString()!);
        byte[] nonce = Convert.FromHexString(input.GetProperty("nonceHex").GetString()!);
        string from = input.GetProperty("fromDeviceId").GetString()!;
        string payload = input.GetProperty("payloadJson").GetString()!;

        var sealedFrame = FrameCrypto.Seal(payload, key, encrypt: false, from, nonce);

        Assert.Equal(expected.GetProperty("bodyHex").GetString(), Hex(sealedFrame.Body));
        Assert.Equal(expected.GetProperty("macHex").GetString(), Hex(sealedFrame.Mac));
        Assert.Equal(payload, FrameCrypto.Open(sealedFrame, key));
    }

    [Fact]
    public void SessionFrame_MatchesVector()
    {
        using var doc = LoadVectors();
        var v = doc.RootElement.GetProperty("sessionFrameVectors").EnumerateArray().Single();
        var input = v.GetProperty("input");
        var expected = v.GetProperty("expected");

        byte[] key = Convert.FromHexString(input.GetProperty("keyHex").GetString()!);
        byte[] nonce = Convert.FromHexString(input.GetProperty("nonceHex").GetString()!);
        string from = input.GetProperty("fromDeviceId").GetString()!;
        string payload = input.GetProperty("payloadJson").GetString()!;

        var sealedFrame = FrameCrypto.Seal(payload, key, encrypt: true, from, nonce);

        // body is ciphertext || tag, matching Java's single-array doFinal output.
        Assert.Equal(expected.GetProperty("bodyHex").GetString(), Hex(sealedFrame.Body));
        Assert.Equal(expected.GetProperty("macHex").GetString(), Hex(sealedFrame.Mac));
        Assert.Equal(payload, FrameCrypto.Open(sealedFrame, key));
    }

    [Fact]
    public void AlteredMac_DoesNotOpen()
    {
        byte[] key = FrameCrypto.NewSessionSecret();
        var f = FrameCrypto.Seal("{\"t\":\"ping\"}", key, true, "ALPHA");

        byte[] badMac = (byte[])f.Mac.Clone();
        badMac[^1] ^= 0xFF;

        Assert.Null(FrameCrypto.Open(f with { Mac = badMac }, key));
    }

    [Fact]
    public void ReattributedSender_DoesNotOpen()
    {
        // The whole reason fromDeviceId is inside the MAC.
        byte[] key = FrameCrypto.NewSessionSecret();
        var f = FrameCrypto.Seal("{\"t\":\"ping\"}", key, true, "ALPHA");

        Assert.Null(FrameCrypto.Open(f with { FromDeviceId = "CHARLIE" }, key));
    }

    [Fact]
    public void AlteredCiphertext_DoesNotOpen()
    {
        byte[] key = FrameCrypto.NewSessionSecret();
        var f = FrameCrypto.Seal("{\"t\":\"ping\"}", key, true, "ALPHA");

        byte[] badBody = (byte[])f.Body.Clone();
        badBody[0] ^= 0xFF;

        Assert.Null(FrameCrypto.Open(f with { Body = badBody }, key));
    }

    [Fact]
    public void WrongKey_DoesNotOpen()
    {
        var f = FrameCrypto.Seal("{\"t\":\"ping\"}", FrameCrypto.NewSessionSecret(), true, "ALPHA");
        Assert.Null(FrameCrypto.Open(f, FrameCrypto.NewSessionSecret()));
    }

    [Fact]
    public void Open_NeverThrowsOnHostileInput()
    {
        byte[] key = FrameCrypto.NewSessionSecret();

        // Truncated body, empty body, garbage — all must return null rather than throw.
        Assert.Null(FrameCrypto.Open(new SealedFrame(new byte[12], [], new byte[32], true, "X"), key));
        Assert.Null(FrameCrypto.Open(new SealedFrame(new byte[12], [1, 2, 3], new byte[32], true, "X"), key));
        Assert.Null(FrameCrypto.Open(new SealedFrame(new byte[12], new byte[64], [], true, "X"), key));
    }

    [Fact]
    public void NonceCache_RejectsReplayAndStaysBounded()
    {
        var cache = new NonceCache(capacity: 4);

        Assert.True(cache.CheckAndStore("a"));
        Assert.False(cache.CheckAndStore("a"));   // replay rejected

        Assert.True(cache.CheckAndStore("b"));
        Assert.True(cache.CheckAndStore("c"));
        Assert.True(cache.CheckAndStore("d"));    // size == capacity, no eviction yet

        Assert.True(cache.CheckAndStore("e"));    // size > capacity, evicts the eldest ("a")

        // "b".."e" are still held, so they are still rejected as replays.
        Assert.False(cache.CheckAndStore("b"));
        Assert.False(cache.CheckAndStore("e"));

        // "a" was evicted, so it is no longer recognised. This is the deliberate trade-off of a
        // bounded cache: an unbounded one would be a memory-exhaustion target for anything that
        // can send us frames. The bound is 4096 in production, far above the control-frame rate.
        Assert.True(cache.CheckAndStore("a"));
    }

    [Fact]
    public void NewPin_IsAlwaysSixDigits()
    {
        for (int i = 0; i < 200; i++)
        {
            string pin = FrameCrypto.NewPin();
            Assert.Equal(6, pin.Length);
            Assert.All(pin, c => Assert.True(char.IsAsciiDigit(c)));
        }
    }

    [Fact]
    public void Sha256Hex_MatchesPublishedDigest()
        => Assert.Equal(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FrameCrypto.Sha256Hex(Encoding.UTF8.GetBytes("abc")));
}
