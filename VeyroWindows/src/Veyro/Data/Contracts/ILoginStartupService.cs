namespace Veyro.Data.Contracts;

/// <summary>Start Veyro at sign-in through the MSIX startup task (Package.appxmanifest VeyroStartupTask).</summary>
public interface ILoginStartupService
{
    Task<LoginStartupState> GetStateAsync();

    /// <summary>Requests the change and returns the state Windows actually applied.</summary>
    Task<LoginStartupState> SetEnabledAsync(bool enabled);
}
