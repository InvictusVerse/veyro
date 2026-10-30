using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Link;
using Veyro.Core.Protocol;

namespace Veyro.Core.Crypto;

/// <summary>
/// Session frame cryptography for link protocol v3 (04-PROTOCOL.md 5, ADR-058). Mirrors
/// com.veyro.core.link.FrameCrypto. Every frame after the ack is
/// <c>{"f": fromDeviceId, "c": base64(ciphertext || tag)}</c>, sealed with ChaCha20-Poly1305 under
/// the sender's direction key of this connection; nonce <c>u32be(0) || u64be(counter)</c>, AAD
/// <c>utf8(from) || 0x00 || dirByte || u64be(counter)</c>. The receiver builds both from its own
/// counter and the expected peer id. The stored pairing secret never seals anything.
/// </summary>
public static class FrameCrypto
{
    public const int KeyBytes = 32;
    public const byte DirI2R = 0x01;
    public const byte DirR2I = 0x02;
    public const uint SessionStreamId = 0;

    /// <summary>A fresh 32-byte pairing secret, minted by the device being paired *to*.</summary>
    public static byte[] NewSessionSecret() => RandomNumberGenerator.GetBytes(KeyBytes);

    public static string Sha256Hex(ReadOnlySpan<byte> data)
        => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();

    /// <summary><c>utf8(from) || 0x00 || dirByte || u64be(counter)</c>.</summary>
    public static byte[] Aad(string fromDeviceId, byte dir, long counter)
    {
        byte[] from = Encoding.UTF8.GetBytes(fromDeviceId);
        byte[] aad = new byte[from.Length + 2 + 8];
        from.CopyTo(aad, 0);
        aad[from.Length + 1] = dir;
        BinaryPrimitives.WriteInt64BigEndian(aad.AsSpan(from.Length + 2), counter);
        return aad;
    }

    /// <summary>
    /// Strict standard padded base64: length a multiple of 4, alphabet <c>[A-Za-z0-9+/]</c>, at
    /// most two trailing <c>=</c>, no whitespace, canonical. Null otherwise.
    /// </summary>
    public static byte[]? DecodeStrictBase64(string? value)
    {
        if (string.IsNullOrEmpty(value) || value.Length % 4 != 0) return null;
        int padding = 0;
        for (int i = 0; i < value.Length; i++)
        {
            char c = value[i];
            bool ok = c switch
            {
                >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '+' or '/' => padding == 0,
                '=' => ++padding <= 2 && i >= value.Length - 2,
                _ => false
            };
            if (!ok) return null;
        }
        byte[] bytes;
        try { bytes = Convert.FromBase64String(value); }
        catch (FormatException) { return null; }
        return Convert.ToBase64String(bytes) == value ? bytes : null;
    }

    /// <summary>A 32-byte key or nonce field: exactly 44 characters of canonical base64 (04-PROTOCOL.md 4.1).</summary>
    public static byte[]? DecodeKey32(string? value)
        => value is { Length: 44 } && DecodeStrictBase64(value) is { Length: 32 } bytes ? bytes : null;
}

/// <summary>Seals one direction's envelopes. Not thread-safe; the owner holds its send gate.</summary>
public sealed class SessionSealer : IDisposable
{
    private readonly AeadStreamFramer _framer;
    private readonly string _localDeviceId;
    private readonly byte _dir;

    public SessionSealer(byte[] key, string localDeviceId, byte dir)
    {
        if (localDeviceId.Any(c => c is '"' or '\\' || char.IsControl(c)))
            throw new ArgumentException("Device id must not need JSON escaping.", nameof(localDeviceId));
        _framer = new AeadStreamFramer(key, FrameCrypto.SessionStreamId, FramerRole.Seal);
        _localDeviceId = localDeviceId;
        _dir = dir;
    }

    public long NextCounter => _framer.NextCounter;

    /// <summary>Returns the wire text. Throws <see cref="InvalidDataException"/> when the counter is exhausted.</summary>
    public string Seal(string envelopeJson)
    {
        byte[] plain = Encoding.UTF8.GetBytes(envelopeJson);
        byte[] sealedBytes = new byte[plain.Length + AeadStreamFramer.TagSize];
        _framer.Seal(FrameCrypto.Aad(_localDeviceId, _dir, _framer.NextCounter), plain, sealedBytes);
        CryptographicOperations.ZeroMemory(plain);
        // Written directly, byte for byte as Android writes it: the default JSON encoder would
        // escape '+' in the base64 as \u002B. The id is a canonical device id and base64 has
        // nothing to escape, so no JSON escaping is needed.
        return "{\"f\":\"" + _localDeviceId + "\",\"c\":\"" + Convert.ToBase64String(sealedBytes) + "\"}";
    }

    public void Dispose() => _framer.Dispose();
}

/// <summary>Opens one direction's frames from one peer. Not thread-safe; the reader owns it.</summary>
public sealed class SessionOpener : IDisposable
{
    private readonly AeadStreamFramer _framer;
    private readonly string _peerDeviceId;
    private readonly byte _dir;

    public SessionOpener(byte[] key, string peerDeviceId, byte dir)
    {
        _framer = new AeadStreamFramer(key, FrameCrypto.SessionStreamId, FramerRole.Open);
        _peerDeviceId = peerDeviceId;
        _dir = dir;
    }

    /// <summary>The counter of the next frame.</summary>
    public long NextCounter => _framer.NextCounter;

    /// <summary>
    /// Returns the envelope JSON. Throws <see cref="UnauthorizedAccessException"/> for a tag
    /// failure and <see cref="InvalidDataException"/> for a malformed frame.
    /// </summary>
    public string Open(string wireText)
    {
        LinkWireFrame? wire;
        try { wire = PeerMessageJson.Decode<LinkWireFrame>(wireText); }
        catch (Exception error) when (error is System.Text.Json.JsonException or NotSupportedException)
        {
            throw new InvalidDataException("Malformed VLP control frame.", error);
        }
        if (wire is null || wire.From is null || wire.Ciphertext is null)
            throw new InvalidDataException("Malformed VLP control frame.");
        // Byte-exact, before any crypto: a frame naming another sender is not ours to open.
        if (!string.Equals(wire.From, _peerDeviceId, StringComparison.Ordinal))
            throw new InvalidDataException("VLP frame from an unexpected sender.");
        byte[] sealedBytes = FrameCrypto.DecodeStrictBase64(wire.Ciphertext)
            ?? throw new InvalidDataException("Malformed VLP control frame.");
        if (sealedBytes.Length < AeadStreamFramer.TagSize) throw new InvalidDataException("Malformed VLP control frame.");
        byte[] plain = new byte[sealedBytes.Length - AeadStreamFramer.TagSize];
        try
        {
            _framer.Open(FrameCrypto.Aad(_peerDeviceId, _dir, _framer.NextCounter), sealedBytes, plain);
        }
        catch (InvalidDataException error)
        {
            throw new UnauthorizedAccessException("VLP control frame authentication failed.", error);
        }
        string json = Encoding.UTF8.GetString(plain);
        CryptographicOperations.ZeroMemory(plain);
        return json;
    }

    public void Dispose() => _framer.Dispose();
}
