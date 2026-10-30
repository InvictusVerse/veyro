using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Veyro.Core.Crypto;

/// <summary>
/// Confirm-code pairing (04-PROTOCOL.md section 4.3, ADR-031): the derivations both sides compute.
/// Mirrors com.veyro.core.crypto.PairConfirm and is pinned by protocol/testvectors/pair-confirm.json.
///
/// A is the initiator (the phone), B the receiver (this PC). The commitment is what makes six
/// digits enough: B fixes nB before it sees nA, so a man in the middle cannot grind keys or nonces
/// until the two codes match. Nonces and codes are never logged.
/// </summary>
public static class PairConfirm
{
    public const string InfoCommit = "veyro-commit-v1";
    public const string InfoCode = "veyro-sas-v1";
    public const int NonceSize = 32;
    public const int KeySize = 32;
    public const int CommitSize = 32;

    /// <summary>A fresh 32-byte nonce from the OS CSPRNG.</summary>
    public static byte[] NewNonce() => RandomNumberGenerator.GetBytes(NonceSize);

    /// <summary><c>C = SHA-256("veyro-commit-v1" || pkB || pkA || nB)</c>.</summary>
    public static byte[] Commitment(ReadOnlySpan<byte> pkB, ReadOnlySpan<byte> pkA, ReadOnlySpan<byte> nB)
    {
        RequireSize(pkB, KeySize, nameof(pkB));
        RequireSize(pkA, KeySize, nameof(pkA));
        RequireSize(nB, NonceSize, nameof(nB));
        return Sha256(Encoding.ASCII.GetBytes(InfoCommit), pkB, pkA, nB);
    }

    /// <summary>Constant-time check of a received commitment. False for malformed input.</summary>
    public static bool VerifyCommitment(ReadOnlySpan<byte> commit, ReadOnlySpan<byte> pkB, ReadOnlySpan<byte> pkA, ReadOnlySpan<byte> nB)
    {
        if (commit.Length != CommitSize || pkB.Length != KeySize || pkA.Length != KeySize || nB.Length != NonceSize)
            return false;
        return CryptographicOperations.FixedTimeEquals(Commitment(pkB, pkA, nB), commit);
    }

    /// <summary>
    /// The 6 digits both screens show: <c>d = SHA-256("veyro-sas-v1" || pkA || pkB || nA || nB)</c>,
    /// <c>BE32(d[0..3]) mod 1000000</c>, zero-padded.
    /// </summary>
    public static string Code(ReadOnlySpan<byte> pkA, ReadOnlySpan<byte> pkB, ReadOnlySpan<byte> nA, ReadOnlySpan<byte> nB)
    {
        RequireSize(pkA, KeySize, nameof(pkA));
        RequireSize(pkB, KeySize, nameof(pkB));
        RequireSize(nA, NonceSize, nameof(nA));
        RequireSize(nB, NonceSize, nameof(nB));
        byte[] d = Sha256(Encoding.ASCII.GetBytes(InfoCode), pkA, pkB, nA, nB);
        uint value = BinaryPrimitives.ReadUInt32BigEndian(d);
        return (value % 1_000_000u).ToString("D6", System.Globalization.CultureInfo.InvariantCulture);
    }

    /// <summary><c>123 456</c>, for display only.</summary>
    public static string FormatCode(string code) => code.Length == 6 ? $"{code[..3]} {code[3..]}" : code;


    private static byte[] Hash(params byte[][] parts)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (byte[] part in parts) hash.AppendData(part);
        return hash.GetHashAndReset();
    }

    private static byte[] Sha256(byte[] domain, ReadOnlySpan<byte> a, ReadOnlySpan<byte> b, ReadOnlySpan<byte> c)
        => Hash(domain, a.ToArray(), b.ToArray(), c.ToArray());

    private static byte[] Sha256(byte[] domain, ReadOnlySpan<byte> a, ReadOnlySpan<byte> b, ReadOnlySpan<byte> c, ReadOnlySpan<byte> d)
        => Hash(domain, a.ToArray(), b.ToArray(), c.ToArray(), d.ToArray());

    private static void RequireSize(ReadOnlySpan<byte> value, int size, string name)
    {
        if (value.Length != size)
            throw new ArgumentException($"{name} must be {size} bytes, was {value.Length}.", name);
    }
}
