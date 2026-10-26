using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Veyro.Core.Models;

namespace Veyro.Core.Data;

/// <summary>
/// HMAC-SHA256 authenticated trust store and atomic transfer history store.
/// Mirrors com.veyro.core.data.FileTrustStore and FileTransferHistoryStore.
/// </summary>
public sealed class FileTrustStore : ITrustStore
{
    private readonly string _filePath;
    private readonly byte[] _hmacKey;
    private readonly List<TrustedPeer> _peers = [];

    public FileTrustStore(string dataDirectory, byte[] hmacKey)
    {
        Directory.CreateDirectory(dataDirectory);
        _filePath = Path.Combine(dataDirectory, "trust.json");
        _hmacKey = hmacKey;
        Load();
    }

    private sealed record SignedEnvelope(string PayloadJson, string HmacHex);

    private void Load()
    {
        _peers.Clear();
        if (!File.Exists(_filePath)) return;

        string raw = File.ReadAllText(_filePath);
        var env = JsonSerializer.Deserialize<SignedEnvelope>(raw)
            ?? throw new CryptographicException("Trust store envelope is malformed.");

        byte[] expectedMac = HMACSHA256.HashData(_hmacKey, Encoding.UTF8.GetBytes(env.PayloadJson));
        byte[] actualMac = Convert.FromHexString(env.HmacHex);
        if (!CryptographicOperations.FixedTimeEquals(expectedMac, actualMac))
            throw new CryptographicException("Trust store HMAC verification failed (fail-closed).");

        var loaded = JsonSerializer.Deserialize<List<TrustedPeer>>(env.PayloadJson) ?? [];
        _peers.AddRange(loaded);
    }

    private async Task SaveAsync()
    {
        string payloadJson = JsonSerializer.Serialize(_peers, new JsonSerializerOptions { WriteIndented = true });
        string hmacHex = Convert.ToHexString(HMACSHA256.HashData(_hmacKey, Encoding.UTF8.GetBytes(payloadJson)));
        string outer = JsonSerializer.Serialize(new SignedEnvelope(payloadJson, hmacHex), new JsonSerializerOptions { WriteIndented = true });

        string temp = _filePath + ".tmp";
        await File.WriteAllTextAsync(temp, outer).ConfigureAwait(false);
        File.Move(temp, _filePath, overwrite: true);
    }

    public IReadOnlyList<TrustedPeer> GetTrustedPeers() => _peers.ToList();

    public TrustedPeer? GetPeer(string deviceId)
        => _peers.FirstOrDefault(p => string.Equals(p.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));

    public async Task UpsertAsync(TrustedPeer peer)
    {
        _peers.RemoveAll(p => string.Equals(p.DeviceId, peer.DeviceId, StringComparison.OrdinalIgnoreCase));
        _peers.Add(peer);
        await SaveAsync().ConfigureAwait(false);
    }

    public async Task RemoveAsync(string deviceId)
    {
        _peers.RemoveAll(p => string.Equals(p.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
        await SaveAsync().ConfigureAwait(false);
    }
}

public sealed class FileTransferHistoryStore : ITransferHistoryStore
{
    private readonly string _filePath;
    private readonly List<TransferRecord> _records = [];

    public FileTransferHistoryStore(string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);
        _filePath = Path.Combine(dataDirectory, "history.json");
        Load();
    }

    private void Load()
    {
        _records.Clear();
        if (!File.Exists(_filePath)) return;
        try
        {
            var list = JsonSerializer.Deserialize<List<TransferRecord>>(File.ReadAllText(_filePath));
            if (list is not null) _records.AddRange(list);
        }
        catch
        {
            // Ignore corrupt history file
        }
    }

    public IReadOnlyList<TransferRecord> GetHistory()
        => _records.OrderByDescending(r => r.Timestamp).ToList();

    public async Task UpsertAsync(TransferRecord record)
    {
        _records.RemoveAll(r => r.TransferId == record.TransferId);
        _records.Insert(0, record);
        await SaveAsync().ConfigureAwait(false);
    }

    public async Task ClearAsync()
    {
        _records.Clear();
        await SaveAsync().ConfigureAwait(false);
    }

    private async Task SaveAsync()
    {
        string temp = _filePath + ".tmp";
        string json = JsonSerializer.Serialize(_records, new JsonSerializerOptions { WriteIndented = true });
        await File.WriteAllTextAsync(temp, json).ConfigureAwait(false);
        File.Move(temp, _filePath, overwrite: true);
    }
}
