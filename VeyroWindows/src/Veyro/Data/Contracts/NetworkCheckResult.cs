namespace Veyro.Data.Contracts;

public sealed record NetworkCheckResult(FirewallRuleState Firewall, IReadOnlyList<NetworkProfileInfo> Profiles);
