using System.Security.Cryptography;

namespace Veyro.Platforms.Windows.Services;

/// <summary>
/// Seals the 32-byte X25519 identity seed at rest using Windows DPAPI (CurrentUser scope)
/// with atomic temp-then-rename write. Mirrors SealedFileSeedStore on Android (ADR-012, REQ-I03).
/// </summary>
public sealed class DpapiSeedStore : ISeedStore
{
    private static readonly byte[] Entropy = "veyro-dpapi-seed-v1"u8.ToArray();
    private readonly string _filePath;

    public DpapiSeedStore(string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);
        _filePath = Path.Combine(dataDirectory, "identity.seed.dpapi");
    }

    public byte[]? LoadSeed()
    {
        if (!File.Exists(_filePath)) return null;
        byte[] encrypted = File.ReadAllBytes(_filePath);
        byte[] plain = ProtectedData.Unprotect(encrypted, Entropy, DataProtectionScope.CurrentUser);
        if (plain.Length != 32)
            throw new CryptographicException("Unprotected identity seed has invalid length.");
        return plain;
    }

    public void SaveSeed(ReadOnlySpan<byte> seed)
    {
        if (seed.Length != 32)
            throw new ArgumentException("Seed must be 32 bytes.", nameof(seed));

        byte[] encrypted = ProtectedData.Protect(seed.ToArray(), Entropy, DataProtectionScope.CurrentUser);
        string tempPath = _filePath + ".tmp";
        File.WriteAllBytes(tempPath, encrypted);
        File.Move(tempPath, _filePath, overwrite: true);
    }

    public void Clear()
    {
        if (File.Exists(_filePath))
            File.Delete(_filePath);
    }
}

public sealed class SystemTrayService : ISystemTrayService
{
    public bool IsAvailable { get; private set; }

    public void Initialize(nint windowHandle)
    {
        IsAvailable = windowHandle != 0;
    }

    public void Dispose()
    {
        IsAvailable = false;
    }
}
