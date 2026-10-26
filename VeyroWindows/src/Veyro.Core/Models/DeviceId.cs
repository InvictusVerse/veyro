using System.Security.Cryptography;
using System.Text;

namespace Veyro.Core.Models;

/// <summary>
/// Stable 128-bit device identifier derived from the X25519 static public key:
/// Crockford32(SHA-256("veyro-id-v1" || pk)[0..15]) (26 chars).
/// Mirrors com.veyro.core.model.DeviceId (REQ-I02).
/// </summary>
public readonly record struct DeviceId(string Value)
{
    private static readonly byte[] DomainPrefix = Encoding.ASCII.GetBytes("veyro-id-v1");

    /// <summary>26 Crockford Base32 characters encoding exactly 16 bytes, no padding.</summary>
    public const int Length = 26;

    public const int ByteLength = 16;

    public static DeviceId FromPublicKey(ReadOnlySpan<byte> publicKey)
    {
        if (publicKey.Length != 32)
            throw new ArgumentException("X25519 public key must be 32 bytes.", nameof(publicKey));

        Span<byte> input = stackalloc byte[DomainPrefix.Length + 32];
        DomainPrefix.CopyTo(input);
        publicKey.CopyTo(input[DomainPrefix.Length..]);

        Span<byte> digest = stackalloc byte[32];
        SHA256.HashData(input, digest);

        return new DeviceId(Crockford32.Encode(digest[..16]));
    }

    /// <summary>
    /// Parses a peer-supplied identifier, or returns null if it is not a well-formed deviceId.
    /// Mirrors com.veyro.core.model.DeviceId.parseOrNull.
    ///
    /// Everything arriving from the network is untrusted, so this validates rather than trusting
    /// the string: exact length, decodable as Crockford Base32, and exactly 16 decoded bytes. The
    /// result is re-encoded so the stored form is always canonical upper case.
    /// </summary>
    public static DeviceId? ParseOrNull(string? raw)
    {
        if (raw is null || raw.Length != Length) return null;

        byte[]? bytes = Crockford32.DecodeOrNull(raw);
        if (bytes is null || bytes.Length != ByteLength) return null;

        return new DeviceId(Crockford32.Encode(bytes));
    }

    /// <summary>Verifies that <paramref name="id"/> really is the commitment to the given key.</summary>
    public static bool Matches(DeviceId id, ReadOnlySpan<byte> staticPublicKey)
    {
        try { return FromPublicKey(staticPublicKey) == id; }
        catch { return false; }
    }

    public static string FormatFingerprint(ReadOnlySpan<byte> publicKey)
    {
        return FormatFingerprint(FromPublicKey(publicKey));
    }

    /// <summary>The same fingerprint, from an id already derived from (or bound to) the key.</summary>
    public static string FormatFingerprint(DeviceId deviceId)
    {
        var id = deviceId.Value;
        return $"{id[..4]}-{id[4..8]}-{id[8..12]}-{id[12..16]}";
    }

    public override string ToString() => Value;
}
