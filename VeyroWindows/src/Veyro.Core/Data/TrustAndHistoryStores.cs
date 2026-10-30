using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Veyro.Core.Diagnostics;
using Veyro.Core.Models;

namespace Veyro.Core.Data;

/// <summary>
/// HMAC-SHA256 authenticated trust store and atomic transfer history store.
/// Mirrors com.veyro.core.data.FileTrustStore and FileTransferHistoryStore.
/// </summary>
public sealed class FileTrustStore : ITrustStore
{
    /// <summary>How long a removed phone's secret is kept to tell it (ADR-057).</summary>
    public static readonly TimeSpan PendingUnpairKeep = TimeSpan.FromDays(90);

    /// <summary>At most this many owed notices; the oldest go first.</summary>
    public const int MaxPendingUnpairs = 32;

    private readonly string _filePath;
    private readonly string _removedPath;
    private readonly byte[] _hmacKey;
    private readonly TimeProvider _time;
    private readonly List<TrustedPeer> _peers = [];
    private readonly object _removedGate = new();
    private List<RemovedPeer> _removed = [];

    /// <summary>A phone removed here whose <c>unpair</c> is still owed (ADR-057).</summary>
    private sealed record RemovedPeer(string DeviceId, string PairingSecretBase64, DateTimeOffset RemovedAt);

    public FileTrustStore(string dataDirectory, byte[] hmacKey, TimeProvider? time = null)
    {
        Directory.CreateDirectory(dataDirectory);
        _filePath = Path.Combine(dataDirectory, "trust.json");
        // A file of its own, same key and tamper check: older builds never read it.
        _removedPath = Path.Combine(dataDirectory, "removed.json");
        _hmacKey = hmacKey;
        _time = time ?? TimeProvider.System;
        Load();
        _removed = LoadRemoved();
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
        // Paired again: the old removal no longer stands, and its secret is no longer the key.
        await ClearPendingUnpairAsync(peer.DeviceId).ConfigureAwait(false);
    }

    public async Task RemoveAsync(string deviceId)
    {
        _peers.RemoveAll(p => string.Equals(p.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
        await SaveAsync().ConfigureAwait(false);
    }

    public async Task RemoveAndNotifyAsync(string deviceId)
    {
        if (GetPeer(deviceId) is { } peer)
        {
            List<RemovedPeer> next;
            lock (_removedGate)
            {
                next = _removed.Where(r => !Same(r.DeviceId, deviceId)).ToList();
                next.Add(new RemovedPeer(peer.DeviceId, peer.PairingSecretBase64, _time.GetUtcNow()));
                if (next.Count > MaxPendingUnpairs) next.RemoveRange(0, next.Count - MaxPendingUnpairs);
                _removed = next;
            }
            await SaveRemovedAsync(next).ConfigureAwait(false);
        }
        await RemoveAsync(deviceId).ConfigureAwait(false);
    }

    public byte[]? GetPendingUnpairSecret(string deviceId)
    {
        RemovedPeer? owed;
        lock (_removedGate) owed = _removed.FirstOrDefault(r => Same(r.DeviceId, deviceId) && !IsExpired(r));
        if (owed is null) return null;
        try
        {
            byte[] secret = Convert.FromBase64String(owed.PairingSecretBase64);
            return secret.Length == 32 ? secret : null;
        }
        catch (FormatException) { return null; }
    }

    public async Task ClearPendingUnpairAsync(string deviceId)
    {
        List<RemovedPeer> next;
        lock (_removedGate)
        {
            if (!_removed.Any(r => Same(r.DeviceId, deviceId))) return;
            next = _removed.Where(r => !Same(r.DeviceId, deviceId)).ToList();
            _removed = next;
        }
        await SaveRemovedAsync(next).ConfigureAwait(false);
    }

    private static bool Same(string a, string b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    private bool IsExpired(RemovedPeer removed)
    {
        TimeSpan age = _time.GetUtcNow() - removed.RemovedAt;
        return age < TimeSpan.Zero || age > PendingUnpairKeep;
    }

    /// <summary>
    /// Owed notices, expired ones dropped. A file that fails its check counts as empty: the cost
    /// is only that a removed phone is not told (ADR-057), never a wrong trust decision.
    /// </summary>
    private List<RemovedPeer> LoadRemoved()
    {
        if (!File.Exists(_removedPath)) return [];
        try
        {
            var env = JsonSerializer.Deserialize<SignedEnvelope>(File.ReadAllText(_removedPath));
            if (env is null) return [];
            byte[] expectedMac = HMACSHA256.HashData(_hmacKey, Encoding.UTF8.GetBytes(env.PayloadJson));
            if (!CryptographicOperations.FixedTimeEquals(expectedMac, Convert.FromHexString(env.HmacHex)))
            {
                VeyroTrace.Warn("Removed-phone list failed verification; ignoring it");
                return [];
            }
            return (JsonSerializer.Deserialize<List<RemovedPeer>>(env.PayloadJson) ?? []).Where(r => !IsExpired(r)).ToList();
        }
        catch (Exception error) when (error is JsonException or FormatException or IOException or NotSupportedException)
        {
            VeyroTrace.Warn($"Removed-phone list unreadable; ignoring it: {error.GetType().Name}");
            return [];
        }
    }

    private async Task SaveRemovedAsync(List<RemovedPeer> removed)
    {
        if (removed.Count == 0)
        {
            File.Delete(_removedPath);
            return;
        }
        string payloadJson = JsonSerializer.Serialize(removed);
        string hmacHex = Convert.ToHexString(HMACSHA256.HashData(_hmacKey, Encoding.UTF8.GetBytes(payloadJson)));
        string temp = _removedPath + ".tmp";
        await File.WriteAllTextAsync(temp, JsonSerializer.Serialize(new SignedEnvelope(payloadJson, hmacHex))).ConfigureAwait(false);
        File.Move(temp, _removedPath, overwrite: true);
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

    /// <summary>Newest entries kept; older ones are dropped when a new one is added.</summary>
    public const int MaxRecords = 500;

    // One save at a time: a send and a receive that end together would otherwise both write
    // history.json.tmp. Edits to the list happen under the same lock.
    private readonly SemaphoreSlim _saveLock = new(1, 1);

    public async Task UpsertAsync(TransferRecord record)
    {
        await _saveLock.WaitAsync().ConfigureAwait(false);
        try
        {
            _records.RemoveAll(r => r.TransferId == record.TransferId);
            _records.Insert(0, record);
            if (_records.Count > MaxRecords)
            {
                var keep = _records.OrderByDescending(r => r.Timestamp).Take(MaxRecords).ToList();
                _records.Clear();
                _records.AddRange(keep);
            }
            await SaveAsync().ConfigureAwait(false);
        }
        finally
        {
            _saveLock.Release();
        }
    }

    public async Task ClearAsync()
    {
        await _saveLock.WaitAsync().ConfigureAwait(false);
        try
        {
            _records.Clear();
            await SaveAsync().ConfigureAwait(false);
        }
        finally
        {
            _saveLock.Release();
        }
    }

    private async Task SaveAsync()
    {
        string temp = _filePath + ".tmp";
        string json = JsonSerializer.Serialize(_records, new JsonSerializerOptions { WriteIndented = true });
        await File.WriteAllTextAsync(temp, json).ConfigureAwait(false);
        File.Move(temp, _filePath, overwrite: true);
    }
}
