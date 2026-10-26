using System.Security.Cryptography;
using System.Text;
using Org.BouncyCastle.Crypto.Agreement;
using Org.BouncyCastle.Crypto.Parameters;

namespace Veyro.Core.Crypto;

/// <summary>
/// RFC 5869 HKDF-SHA256 &amp; RFC 7748 X25519 key agreement.
/// Mirrors com.veyro.core.crypto.Hkdf and X25519 (REQ-I01, ADR-003).
/// </summary>
public static class Hkdf
{
    public static byte[] Extract(ReadOnlySpan<byte> salt, ReadOnlySpan<byte> ikm)
    {
        byte[] actualSalt = salt.IsEmpty ? new byte[32] : salt.ToArray();
        return HMACSHA256.HashData(actualSalt, ikm);
    }

    public static byte[] Expand(ReadOnlySpan<byte> prk, ReadOnlySpan<byte> info, int outputLength)
    {
        if (prk.Length < 32)
            throw new ArgumentException("PRK must be at least 32 bytes.", nameof(prk));
        if (outputLength is <= 0 or > 255 * 32)
            throw new ArgumentOutOfRangeException(nameof(outputLength));

        byte[] result = new byte[outputLength];
        byte[] prkBytes = prk.ToArray();
        byte[] t = [];
        int offset = 0;
        byte counter = 1;

        while (offset < outputLength)
        {
            using var hmac = new HMACSHA256(prkBytes);
            if (t.Length > 0) hmac.TransformBlock(t, 0, t.Length, null, 0);
            if (!info.IsEmpty)
            {
                byte[] infoBytes = info.ToArray();
                hmac.TransformBlock(infoBytes, 0, infoBytes.Length, null, 0);
            }
            hmac.TransformFinalBlock([counter], 0, 1);
            t = hmac.Hash!;

            int copyLen = Math.Min(t.Length, outputLength - offset);
            Buffer.BlockCopy(t, 0, result, offset, copyLen);
            offset += copyLen;
            counter++;
        }

        return result;
    }

    public static byte[] DeriveKey(ReadOnlySpan<byte> ikm, ReadOnlySpan<byte> salt, string info, int length = 32)
    {
        byte[] prk = Extract(salt, ikm);
        return Expand(prk, Encoding.UTF8.GetBytes(info), length);
    }
}

public static class X25519Crypto
{
    public static (byte[] PrivateKey, byte[] PublicKey) GenerateKeyPair()
    {
        byte[] seed = RandomNumberGenerator.GetBytes(32);
        return FromSeed(seed);
    }

    public static (byte[] PrivateKey, byte[] PublicKey) FromSeed(ReadOnlySpan<byte> seed)
    {
        if (seed.Length != 32)
            throw new ArgumentException("X25519 seed must be 32 bytes.", nameof(seed));

        var privParams = new X25519PrivateKeyParameters(seed.ToArray(), 0);
        var pubParams = privParams.GeneratePublicKey();
        return (privParams.GetEncoded(), pubParams.GetEncoded());
    }

    public static byte[] SharedSecret(ReadOnlySpan<byte> privateKey, ReadOnlySpan<byte> peerPublicKey)
    {
        var priv = new X25519PrivateKeyParameters(privateKey.ToArray(), 0);
        var pub = new X25519PublicKeyParameters(peerPublicKey.ToArray(), 0);
        var agreement = new X25519Agreement();
        agreement.Init(priv);

        byte[] shared = new byte[32];
        try
        {
            agreement.CalculateAgreement(pub, shared, 0);
        }
        catch (InvalidOperationException error)
        {
            // BouncyCastle raises this for an all-zero result (a low-order peer key) before the
            // check below can run; surface it as the documented CryptographicException.
            throw new CryptographicException("Rejected low-order X25519 public key.", error);
        }

        bool allZero = true;
        foreach (byte b in shared)
        {
            if (b != 0) { allZero = false; break; }
        }
        if (allZero)
            throw new CryptographicException("Rejected low-order X25519 public key.");

        return shared;
    }
}
