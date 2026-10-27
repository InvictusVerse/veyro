namespace Veyro.Data.Models;

/// <summary>A phone seen by discovery while the pairing screen is open (ADR-030). Discovery is untrusted.</summary>
public sealed record NearbyPhoneUiModel(string DeviceId, string Name, string Detail, bool IsPaired);
