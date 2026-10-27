using Veyro.Core.Diagnostics;
using Windows.ApplicationModel;

namespace Veyro.Platform.Services;

/// <summary>Windows: <see cref="StartupTask"/> declared as VeyroStartupTask in Package.appxmanifest.</summary>
public sealed class LoginStartupService : ILoginStartupService
{
    private const string TaskId = "VeyroStartupTask";

    public async Task<LoginStartupState> GetStateAsync()
    {
        StartupTask? task = await GetTaskAsync();
        return task is null ? LoginStartupState.Unavailable : Map(task.State);
    }

    public async Task<LoginStartupState> SetEnabledAsync(bool enabled)
    {
        StartupTask? task = await GetTaskAsync();
        if (task is null) return LoginStartupState.Unavailable;
        try
        {
            if (enabled) return Map(await task.RequestEnableAsync());
            task.Disable();
            return Map(task.State);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Changing the startup task failed", error);
            return Map(task.State);
        }
    }

    private static async Task<StartupTask?> GetTaskAsync()
    {
        try
        {
            return await StartupTask.GetAsync(TaskId);
        }
        catch (Exception error)
        {
            // Unpackaged runs have no package identity and therefore no startup task.
            VeyroTrace.Error("Startup task unavailable", error);
            return null;
        }
    }

    private static LoginStartupState Map(StartupTaskState state) => state switch
    {
        StartupTaskState.Enabled or StartupTaskState.EnabledByPolicy => LoginStartupState.Enabled,
        StartupTaskState.DisabledByUser => LoginStartupState.DisabledByUser,
        StartupTaskState.DisabledByPolicy => LoginStartupState.DisabledByPolicy,
        _ => LoginStartupState.Disabled
    };
}
