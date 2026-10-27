namespace Veyro.Core.Models;

public sealed record PeerDevice(
    string DeviceId,
    string DisplayName,
    PlatformType Platform,
    IReadOnlyList<PeerEndpoint> Endpoints,
    bool IsTrusted,
    bool IsConnected,
    string? Fingerprint = null,
    DateTimeOffset LastSeen = default
);
