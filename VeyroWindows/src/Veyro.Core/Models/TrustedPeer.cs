namespace Veyro.Core.Models;

public sealed record TrustedPeer(
    string DeviceId,
    string DisplayName,
    PlatformType Platform,
    string PublicKeyBase64,
    string PairingSecretBase64,
    bool AutoAcceptTransfers,
    DateTimeOffset PairedAt,
    DateTimeOffset? LastConnectedAt = null
);
