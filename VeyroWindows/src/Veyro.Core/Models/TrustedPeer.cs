using System.Text.Json.Serialization;

namespace Veyro.Core.Models;

public sealed record TrustedPeer(
    string DeviceId,
    string DisplayName,
    PlatformType Platform,
    string PublicKeyBase64,
    string PairingSecretBase64,
    bool AutoAcceptTransfers,
    DateTimeOffset PairedAt,
    DateTimeOffset? LastConnectedAt = null,
    // ADR-059: this PC's own name for the phone, or null. DisplayName is the name the phone
    // announces for itself and follows its renames; the nickname stays when it does.
    string? Nickname = null
)
{
    /// <summary>What the UI shows: the nickname when set, else the phone's own name.</summary>
    [JsonIgnore]
    public string ShownName => string.IsNullOrWhiteSpace(Nickname) ? DisplayName : Nickname;
}
