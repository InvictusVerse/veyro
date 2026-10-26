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

    public void ResetIdentity()
    {
        _seedStore.Clear();
        LoadOrCreate();
    }
}
