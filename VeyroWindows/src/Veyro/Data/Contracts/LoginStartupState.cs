namespace Veyro.Data.Contracts;

public enum LoginStartupState
{
    Enabled,
    Disabled,
    /// <summary>Turned off in Task Manager or Settings; only the user can turn it back on there.</summary>
    DisabledByUser,
    DisabledByPolicy,
    /// <summary>Not running as a packaged app, or the startup task is missing.</summary>
    Unavailable
}
