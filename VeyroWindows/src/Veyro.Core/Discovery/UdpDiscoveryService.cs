using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using Veyro.Core.Diagnostics;
using Veyro.Core.Models;
using Veyro.Core.Protocol;

namespace Veyro.Core.Discovery;

/// <summary>
/// LAN discovery using the VLP UDP beacon. Records only locate candidates; callers must still
/// authenticate the device over the secure WebSocket session before trusting it.
/// </summary>
public sealed class UdpDiscoveryService : IDiscoveryService
{
    private const int BeaconPort = 47653;
    private static readonly IPAddress MulticastAddress = IPAddress.Parse("239.255.42.99");
    private static readonly TimeSpan PeerTtl = TimeSpan.FromSeconds(4);

    private readonly string _localDeviceId;
    // ADR-059: follows Settings; only advertised in open mode (pairing).
    private volatile string _localName;
    private readonly byte[] _tokenKey;
    private readonly Func<int> _controlPort;
    private readonly Func<bool> _privateMode;
    private readonly Func<bool> _announceFast;
    private readonly byte[] _publicKey;

    /// <summary>Beacon period while someone may be looking: window on screen or pairing open.</summary>
    public static readonly TimeSpan FastAnnounceInterval = TimeSpan.FromSeconds(1);

    /// <summary>
    /// Beacon period in the background. Paired phones keep their session and mostly reconnect to
    /// the last address; a beacon every 3 s still finds the PC within a few seconds while waking
    /// the Wi-Fi radio a third as often. Android drops a PC only after 30 s without one.
    /// </summary>
    public static readonly TimeSpan IdleAnnounceInterval = TimeSpan.FromSeconds(3);
    private readonly object _gate = new();
    private readonly Dictionary<string, (PeerDevice Peer, DateTimeOffset Seen)> _peers = new(StringComparer.OrdinalIgnoreCase);
    private UdpClient? _socket;
    private Task? _receiveTask;
    private Task? _announceTask;
    private CancellationTokenSource? _runCancellation;

    public UdpDiscoveryService(
        string localDeviceId,
        string localName,
        ReadOnlySpan<byte> tokenKey,
        ReadOnlySpan<byte> publicKey,
        Func<int> controlPort,
        Func<bool>? privateMode = null,
        Func<bool>? announceFast = null)
    {
        _localDeviceId = localDeviceId;
        _localName = localName;
        _tokenKey = tokenKey.ToArray();
        _publicKey = publicKey.ToArray();
        _controlPort = controlPort;
        _privateMode = privateMode ?? (() => false);
        _announceFast = announceFast ?? (() => true);
        if (_tokenKey.Length == 0) throw new ArgumentException("Discovery token key is required.", nameof(tokenKey));
        if (_publicKey.Length != 32) throw new ArgumentException("X25519 public key must be 32 bytes.", nameof(publicKey));
    }

    /// <summary>ADR-059: this PC was renamed in Settings; the next pairing beacon carries the new name.</summary>
    public void SetLocalName(string name)
    {
        if (Veyro.Core.Devices.DeviceNames.Clean(name) is { } clean) _localName = clean;
    }

    public IReadOnlyList<PeerDevice> DiscoveredPeers
    {
        get { lock (_gate) return _peers.Values.Select(x => x.Peer).ToList(); }
    }

    public event Action<IReadOnlyList<PeerDevice>>? PeersUpdated;

    public Task StartAsync(CancellationToken ct = default)
    {
        if (_socket is not null) return Task.CompletedTask;
        VeyroTrace.Info($"Starting UDP discovery on 239.255.42.99:47653; controlPort={_controlPort()}");
        _socket = new UdpClient(AddressFamily.InterNetwork);
        _socket.EnableBroadcast = true;
        _socket.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _socket.Client.Bind(new IPEndPoint(IPAddress.Any, BeaconPort));
        _socket.JoinMulticastGroup(MulticastAddress);
        _runCancellation = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _receiveTask = ReceiveLoopAsync(_runCancellation.Token);
        _announceTask = AnnounceLoopAsync(_runCancellation.Token);
        VeyroTrace.Info("UDP discovery listener started");
        return Task.CompletedTask;
    }

    public async Task StopAsync()
    {
        _runCancellation?.Cancel();
        _socket?.Close();
        if (_receiveTask is not null) await IgnoreCancellation(_receiveTask).ConfigureAwait(false);
        if (_announceTask is not null) await IgnoreCancellation(_announceTask).ConfigureAwait(false);
        _socket?.Dispose();
        _socket = null;
        _receiveTask = null;
        _announceTask = null;
        _runCancellation?.Dispose();
        _runCancellation = null;
        VeyroTrace.Info("UDP discovery stopped");
        lock (_gate) _peers.Clear();
        PeersUpdated?.Invoke([]);
    }

    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                UdpReceiveResult received = await _socket!.ReceiveAsync(ct).ConfigureAwait(false);
                BeaconCodec.BeaconResult decoded = BeaconCodec.Decode(received.Buffer, received.Buffer.Length);
                switch (decoded)
                {
                    case BeaconCodec.BeaconResult.Query:
                        await SendAnnouncementAsync(received.RemoteEndPoint.Address, ct).ConfigureAwait(false);
                        break;
                    case BeaconCodec.BeaconResult.Ok ok:
                        AddPeer(ok.Record, received.RemoteEndPoint.Address);
                        break;
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) when (ct.IsCancellationRequested) { break; }
            catch (SocketException) { }
            catch (Exception error) { VeyroTrace.Error("UDP discovery receive loop failed", error); }
        }
    }

    private async Task AnnounceLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                byte[]? beacon = TryEncodeAnnouncement();
                if (beacon is not null)
                {
                    await _socket!.SendAsync(beacon, beacon.Length, new IPEndPoint(MulticastAddress, BeaconPort)).ConfigureAwait(false);
                    await _socket.SendAsync(beacon, beacon.Length, new IPEndPoint(IPAddress.Broadcast, BeaconPort)).ConfigureAwait(false);
                }
                else VeyroTrace.Warn("UDP discovery is listening but control listener is not running; no announcement sent");
                RemoveExpired();
                await Task.Delay(_announceFast() ? FastAnnounceInterval : IdleAnnounceInterval, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (SocketException) when (ct.IsCancellationRequested) { break; }
            catch (SocketException) { await Task.Delay(1000, ct).ConfigureAwait(false); }
        }
    }

    private byte[]? TryEncodeAnnouncement()
    {
        int port = _controlPort();
        if (port is < 1 or > 65535) return null;
        byte[] token = PresenceToken();
        byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes("veyro-pk-v1").Concat(_publicKey).ToArray())[..8];
        var record = new PresenceRecord
        {
            Version = ProtocolConstants.VlpVersion,
            DeviceId = _localDeviceId,
            Platform = PlatformType.Windows,
            CapabilityBits = 1,
            PublicKeyHash = hash,
            PresenceToken = token,
            Name = _privateMode() ? null : _localName,
            Port = port,
            PublicKey = _privateMode() ? null : _publicKey
        };
        return BeaconCodec.Encode(BeaconCodec.BeaconType.Announce, record);
    }

    private async Task SendAnnouncementAsync(IPAddress address, CancellationToken ct)
    {
        byte[]? beacon = TryEncodeAnnouncement();
        if (beacon is null) return;
        await _socket!.SendAsync(beacon, beacon.Length, new IPEndPoint(address, BeaconPort)).WaitAsync(ct).ConfigureAwait(false);
    }

    private byte[] PresenceToken()
    {
        long bucket = DateTimeOffset.UtcNow.ToUnixTimeSeconds() / 30;
        byte[] input = Encoding.UTF8.GetBytes($"veyro-presence-v1:{bucket}");
        return HMACSHA256.HashData(_tokenKey, input)[..PresenceRecord.TokenLength];
    }

    private void AddPeer(PresenceRecord record, IPAddress address)
    {
        if (string.Equals(record.DeviceId, _localDeviceId, StringComparison.OrdinalIgnoreCase)) return;
        var peer = new PeerDevice(
            record.DeviceId,
            record.Name ?? "Veyro device",
            record.Platform,
            [new PeerEndpoint(address.ToString(), record.Port, "UDP")],
            IsTrusted: false,
            IsConnected: false,
            Fingerprint: record.PublicKey is null ? null : DeviceId.FormatFingerprint(record.PublicKey),
            LastSeen: DateTimeOffset.UtcNow);
        lock (_gate) _peers[record.DeviceId] = (peer, DateTimeOffset.UtcNow);
        PeersUpdated?.Invoke(DiscoveredPeers);
    }

    private void RemoveExpired()
    {
        bool changed;
        lock (_gate)
        {
            DateTimeOffset cutoff = DateTimeOffset.UtcNow - PeerTtl;
            changed = _peers.RemoveWhere(pair => pair.Value.Seen < cutoff) > 0;
        }
        if (changed) PeersUpdated?.Invoke(DiscoveredPeers);
    }

    private static async Task IgnoreCancellation(Task task)
    {
        try { await task.ConfigureAwait(false); } catch (OperationCanceledException) { }
    }
}

file static class DictionaryExtensions
{
    public static int RemoveWhere<TKey, TValue>(this Dictionary<TKey, TValue> dictionary, Func<KeyValuePair<TKey, TValue>, bool> predicate)
        where TKey : notnull
    {
        var keys = dictionary.Where(predicate).Select(pair => pair.Key).ToList();
        foreach (TKey key in keys) dictionary.Remove(key);
        return keys.Count;
    }
}