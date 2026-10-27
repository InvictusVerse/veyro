using Microsoft.Windows.AppLifecycle;
using Veyro.Core.Diagnostics;
using Veyro.Platform.Shell;
using Veyro.Services;
using Windows.ApplicationModel.Activation;

namespace Veyro.Platform.Handoff;

/// <summary>
/// Startup glue for the Share sheet and the Explorer menu in the main instance (ADR-039): the
/// hand-off pipe, the Explorer command class object, and a Share activation that started Veyro.
/// </summary>
internal static class HandoffHost
{
    private static HandoffPipeServer? _pipe;

    /// <summary>UI thread, once, after App.OnLaunched created the window.</summary>
    public static void StartMainInstance(DispatcherQueue ui, AppActivationArguments? startupActivation)
    {
        try
        {
            HandoffCoordinator.Initialize(ui);
            _pipe = new HandoffPipeServer(HandoffCoordinator.HandleFromAnyThreadAsync);
            _pipe.Start();
            ExplorerCommandServer.RegisterInMainInstance(new LiveExplorerMenuSource());
            _ = Task.Run(ShareTargetActivation.CleanStaleStaging);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Hand-off startup failed: {error.GetType().Name}");
        }

        if (startupActivation is not null) HandleActivation(startupActivation);
    }

    /// <summary>
    /// A Share activation that reached the main instance (it started Veyro, or was redirected).
    /// Returns false for every other kind, which the caller handles as before. UI thread.
    /// </summary>
    public static bool HandleActivation(AppActivationArguments activation)
    {
        if (activation.Kind != ExtendedActivationKind.ShareTarget || activation.Data is not ShareTargetActivatedEventArgs share)
            return false;
        _ = ShareTargetActivation.RunInMainInstanceAsync(share);
        return true;
    }
}
