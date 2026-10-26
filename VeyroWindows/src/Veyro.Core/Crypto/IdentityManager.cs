using Veyro.Core.Models;

namespace Veyro.Core.Crypto;

public interface ISeedStore
{
    byte[]? LoadSeed();
    void SaveSeed(ReadOnlySpan<byte> seed);
    void Clear();
}

public interface IIdentityManager
{
    DeviceId DeviceId { get; }
    string Fingerprint { get; }
    ReadOnlyMemory<byte> PublicKey { get; }
    byte[] DeriveLocalKey(string purpose, int length = 32);

    /// <summary>
    /// <c>HKDF-SHA256(ikm = X25519(identityPrivate, peerPublicKey), salt, info, length)</c>. The raw
    /// Diffie-Hellman output is wiped before returning, and the private key never leaves this
    /// class. Confirm-code pairing derives its bootstrap key this way (04-PROTOCOL.md 4.3).
    /// </summary>
    /// <exception cref="ArgumentException">The peer key is not 32 bytes.</exception>
    /// <exception cref="System.Security.Cryptography.CryptographicException">The peer key is a low-order point.</exception>
    byte[] DeriveSharedKey(ReadOnlySpan<byte> peerPublicKey, ReadOnlySpan<byte> salt, ReadOnlySpan<byte> info, int length = 32);

    void ResetIdentity();
}

/// <summary>
/// Manages the local X25519 device identity (generate-once, verify on load, deliberate reset).
/// Mirrors com.veyro.core.crypto.DefaultIdentityManager (REQ-I01..REQ-I04).
/// </summary>
public sealed class DefaultIdentityManager : IIdentityManager
{
    private readonly ISeedStore _seedStore;
    private byte[] _seed = [];
    private byte[] _publicKey = [];

    public DeviceId DeviceId { get; private set; }
    public string Fingerprint { get; private set; } = string.Empty;
    public ReadOnlyMemory<byte> PublicKey => _publicKey;

    public DefaultIdentityManager(ISeedStore seedStore)
    {
        _seedStore = seedStore;
        LoadOrCreate();
    }

    private void LoadOrCreate()
    {
        byte[]? existing = _seedStore.LoadSeed();
        if (existing is { Length: 32 })
        {
            _seed = existing;
        }
        else
        {
            var (priv, _) = X25519Crypto.GenerateKeyPair();
            _seed = priv;
            _seedStore.SaveSeed(_seed);
        }

        var (_, pub) = X25519Crypto.FromSeed(_seed);
        _publicKey = pub;
        DeviceId = DeviceId.FromPublicKey(_publicKey);
        Fingerprint = DeviceId.FormatFingerprint(_publicKey);
    }

    public byte[] DeriveLocalKey(string purpose, int length = 32)
        => Hkdf.DeriveKey(_seed, _publicKey, $"veyro-local-v1:{purpose}", length);

    public byte[] DeriveSharedKey(ReadOnlySpan<byte> peerPublicKey, ReadOnlySpan<byte> salt, ReadOnlySpan<byte> info, int length = 32)
    {
        if (peerPublicKey.Length != 32)
            throw new ArgumentException("Peer public key must be 32 bytes.", nameof(peerPublicKey));
        if (length is < 16 or > 64)
            throw new ArgumentOutOfRangeException(nameof(length));
        byte[] shared = X25519Crypto.SharedSecret(_seed, peerPublicKey);
        try
        {
            return Hkdf.Expand(Hkdf.Extract(salt, shared), info, length);
        }
        finally
        {
            System.Security.Cryptography.CryptographicOperations.ZeroMemory(shared);
        }
    }

    public void ResetIdentity()
    {
        _seedStore.Clear();
        LoadOrCreate();
    }
}
