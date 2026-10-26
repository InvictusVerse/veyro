using System.Text;
using Veyro.Core.Crypto;
using Veyro.Core.Models;
using Veyro.Core.Protocol;
using Xunit;

namespace Veyro.Tests;

public sealed class ProtocolAndCryptoTests
{
    [Fact]
    public void Crockford32_RoundTrip_Works()
    {
        byte[] sample = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
        string encoded = Crockford32.Encode(sample);
        byte[] decoded = Crockford32.Decode(encoded.ToLowerInvariant().Replace('0', 'O').Replace('1', 'I'));
        Assert.Equal(sample, decoded);
    }

    [Fact]
    public void RelativePath_AcceptsValidPaths_And_RejectsTraversal()
    {
        // Full coverage lives in RelativePathTests; this keeps the smoke check alongside crypto.
        var ok = Assert.IsType<PathValidationResult.Valid>(
            RelativePath.Validate("Documents/Reports/Q4-2026.pdf"));
        Assert.Equal("Documents/Reports/Q4-2026.pdf", ok.Normalized);
        Assert.Equal(3, ok.Segments.Count);

        Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate("../secret.txt"));
        Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate("/etc/passwd"));
        Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate("C:\\Windows\\System32"));
        Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate("CON.txt"));
        Assert.IsType<PathValidationResult.Rejected>(RelativePath.Validate("trailing."));
    }

    [Fact]
    public void X25519_KeyAgreement_ProducesIdenticalSharedSecret()
    {
        var (alicePriv, alicePub) = X25519Crypto.GenerateKeyPair();
        var (bobPriv, bobPub) = X25519Crypto.GenerateKeyPair();

        byte[] s1 = X25519Crypto.SharedSecret(alicePriv, bobPub);
        byte[] s2 = X25519Crypto.SharedSecret(bobPriv, alicePub);

        Assert.Equal(s1, s2);
    }

    [Fact]
    public void Hkdf_Rfc5869_TestVector1_Passes()
    {
        byte[] ikm = Enumerable.Repeat((byte)0x0b, 22).ToArray();
        byte[] salt = Convert.FromHexString("000102030405060708090a0b0c");
        byte[] info = Convert.FromHexString("f0f1f2f3f4f5f6f7f8f9");

        byte[] prk = Hkdf.Extract(salt, ikm);
        Assert.Equal("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Convert.ToHexString(prk).ToLowerInvariant());

        byte[] okm = Hkdf.Expand(prk, info, 42);
        Assert.Equal(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Convert.ToHexString(okm).ToLowerInvariant());
    }
}
