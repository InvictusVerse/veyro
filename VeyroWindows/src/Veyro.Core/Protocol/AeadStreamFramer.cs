using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Veyro.Core.Protocol;

public enum FramerRole { Seal, Open }

/// <summary>
/// One direction of an AEAD stream (ADR-058, 04-PROTOCOL.md 5.5): one ChaCha20-Poly1305 key, one
/// implicit counter. Nonce = <c>u32be(streamId) || u64be(counter)</c>; the counter starts at 0
/// and advances after every successful seal or open, so the caller can never reuse a nonce.
/// After any failure the framer is poisoned and every later call throws
/// <see cref="InvalidDataException"/>. Not thread-safe by design; each owner holds its own lock.
/// Mirrors com.veyro.core.protocol.AeadStreamFramer.
/// </summary>
public sealed class AeadStreamFramer : IDisposable
{
    public const int KeySize = 32;
    public const int TagSize = 16;

    private readonly ChaCha20Poly1305 _aead;
    private readonly uint _streamId;
    private readonly FramerRole _role;
    private readonly byte[] _nonce = new byte[12];
    private long _counter;
    private bool _poisoned;
    private bool _disposed;

    public AeadStreamFramer(ReadOnlySpan<byte> key, uint streamId, FramerRole role)
    {
        if (key.Length != KeySize) throw new ArgumentException("AEAD key must be 32 bytes.", nameof(key));
        if (!ChaCha20Poly1305.IsSupported)
            throw new PlatformNotSupportedException("ChaCha20-Poly1305 is not available on this version of Windows.");
        _aead = new ChaCha20Poly1305(key);
        _streamId = streamId;
        _role = role;
    }

    /// <summary>The counter the next seal or open uses.</summary>
    public long NextCounter => _counter;

    /// <summary>Seals <paramref name="plaintext"/> into <paramref name="destination"/> as ciphertext || tag. Returns its length.</summary>
    public int Seal(ReadOnlySpan<byte> aad, ReadOnlySpan<byte> plaintext, Span<byte> destination)
    {
        if (_role != FramerRole.Seal) throw new InvalidOperationException("Seal on an Open framer.");
        EnsureUsable();
        if (_counter == long.MaxValue) Fail("AEAD stream counter exhausted.", null);
        int total = plaintext.Length + TagSize;
        if (destination.Length < total) Fail("AEAD destination too small.", null);
        try
        {
            _aead.Encrypt(NonceFor(_counter), plaintext, destination[..plaintext.Length],
                destination.Slice(plaintext.Length, TagSize), aad);
        }
        catch (CryptographicException error)
        {
            Fail("AEAD seal failed.", error);
        }
        _counter++;
        return total;
    }

    /// <summary>Opens <paramref name="sealedFrame"/> (ciphertext || tag) into <paramref name="destination"/>. Returns the plaintext length.</summary>
    public int Open(ReadOnlySpan<byte> aad, ReadOnlySpan<byte> sealedFrame, Span<byte> destination)
    {
        if (_role != FramerRole.Open) throw new InvalidOperationException("Open on a Seal framer.");
        EnsureUsable();
        if (sealedFrame.Length < TagSize) Fail("AEAD record too short.", null);
        int len = sealedFrame.Length - TagSize;
        if (destination.Length < len) Fail("AEAD destination too small.", null);
        if (_counter == long.MaxValue) Fail("AEAD stream counter exhausted.", null);
        try
        {
            _aead.Decrypt(NonceFor(_counter), sealedFrame[..len], sealedFrame.Slice(len, TagSize), destination[..len], aad);
        }
        catch (CryptographicException error)
        {
            Fail("AEAD record failed authentication.", error);
        }
        _counter++;
        return len;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _aead.Dispose();
    }

    private void EnsureUsable()
    {
        if (_disposed) throw new InvalidDataException("AEAD stream closed.");
        if (_poisoned) throw new InvalidDataException("AEAD stream failed earlier.");
    }

    private byte[] NonceFor(long counter)
    {
        BinaryPrimitives.WriteUInt32BigEndian(_nonce.AsSpan(0, 4), _streamId);
        BinaryPrimitives.WriteInt64BigEndian(_nonce.AsSpan(4, 8), counter);
        return _nonce;
    }

    [System.Diagnostics.CodeAnalysis.DoesNotReturn]
    private void Fail(string message, Exception? inner)
    {
        _poisoned = true;
        throw new InvalidDataException(message, inner);
    }
}
