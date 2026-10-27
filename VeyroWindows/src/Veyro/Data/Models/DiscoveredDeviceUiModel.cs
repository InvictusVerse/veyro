namespace Veyro.Data.Models;

/// <summary>A phone seen by discovery (untrusted: shown for pairing only, never as identity proof).</summary>
public sealed record DiscoveredDeviceUiModel(
    string DeviceId,
    string Name,
    string VerificationKey,
    string EndpointText,
    PlatformType Platform = PlatformType.Android
);
