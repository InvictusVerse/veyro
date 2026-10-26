namespace Veyro.Data.Enums;

public enum StartupOptions
{
    Disabled = 0,
    InTray = 1,
    Minimized = 2,
    Visible = 3
}

/// <summary>What the button on a pairing-screen network check does.</summary>
public enum NetworkCheckAction
{
    None = 0,
    AddFirewallRule = 1,
    OpenNetworkSettings = 2
}

public enum AppThemeMode
{
    System = 0,
    Light = 1,
    Dark = 2
}
