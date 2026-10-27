namespace Veyro.Data.Contracts;

/// <summary>
/// Windows Firewall and network-profile checks for the pairing screen, so problems are named
/// instead of failing silently. Windows: NetSecurity / NetConnection PowerShell cmdlets.
/// </summary>
public interface INetworkDiagnosticsService
{
    /// <summary>Reads the Veyro firewall rule and the network profiles. Never throws.</summary>
    Task<NetworkCheckResult> CheckAsync();

    /// <summary>Adds the inbound rule for TCP 5150-5169 on private networks after a UAC prompt. True on success.</summary>
    Task<bool> RequestFirewallRuleAsync();
}
