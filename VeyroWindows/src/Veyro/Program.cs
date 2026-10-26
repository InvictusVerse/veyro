using Microsoft.Windows.AppLifecycle;
using Veyro.Platforms.Windows.Handoff;
using Veyro.Platforms.Windows.Interop;
using Veyro.Platforms.Windows.Shell;
using Windows.ApplicationModel.Activation;

namespace Veyro;

internal static class Program
{
    private static AppActivationArguments? _startupActivation;

    [STAThread]
    public static void Main(string[] args)
    {
        // ADR-039: COM started Veyro.exe to answer Explorer's "Send with Veyro" menu. No window and
        // no single-instance key, so the real Veyro can still start (and take over) later.
        if (ExplorerCommandServer.IsComServerLaunch(args))
        {
            ExplorerCommandServer.RunStandalone();
            return;
        }

        WinRT.ComWrappersSupport.InitializeComWrappers();

        bool isRedirect = DecideRedirection();
        if (!isRedirect)
        {
            Application.Start(p =>
            {
                DispatcherQueue queue = DispatcherQueue.GetForCurrentThread();
                var context = new DispatcherQueueSynchronizationContext(queue);
                SynchronizationContext.SetSynchronizationContext(context);
                _ = new App();
                // Low priority runs after OnLaunched has created the window: the hand-off pipe, the
                // Explorer menu, and a Share activation that started Veyro (ADR-039).
                queue.TryEnqueue(DispatcherQueuePriority.Low, () => HandoffHost.StartMainInstance(queue, _startupActivation));
            });
        }
    }

    private static bool DecideRedirection()
    {
        bool isRedirect = false;
        AppActivationArguments args = AppInstance.GetCurrent().GetActivatedEventArgs();
        AppInstance keyInstance = AppInstance.FindOrRegisterForKey("VeyroSingleInstanceMain");

        if (keyInstance.IsCurrent)
        {
            keyInstance.Activated += OnActivated;
            _startupActivation = args;
        }
        else if (args.Kind == ExtendedActivationKind.ShareTarget && args.Data is ShareTargetActivatedEventArgs share)
        {
            // ADR-039: the ShareOperation belongs to this process, so it is not redirected. This
            // process reads the items, hands the paths to the running Veyro, reports to Windows
            // and exits without a window.
            isRedirect = true;
            WaitPumping(() => ShareTargetActivation.RunInSecondaryProcessAsync(share), dispatchCalls: true);
        }
        else
        {
            isRedirect = true;
            RedirectActivationTo(keyInstance, args);
        }

        return isRedirect;
    }

    private static void RedirectActivationTo(AppInstance keyInstance, AppActivationArguments args)
        => WaitPumping(() => keyInstance.RedirectActivationToAsync(args).AsTask(), dispatchCalls: false);

    /// <summary>
    /// Runs <paramref name="work"/> on the thread pool and waits on this STA. The Share path also
    /// dispatches COM calls, in case a WinRT object created here is called back on this thread.
    /// </summary>
    private static void WaitPumping(Func<Task> work, bool dispatchCalls)
    {
        nint doneEvent = InteropHelpers.CreateEvent(nint.Zero, true, false, null);
        Task.Run(async () =>
        {
            try { await work(); }
            catch (Exception) { /* reported by the work itself; the process exits either way */ }
            finally { InteropHelpers.SetEvent(doneEvent); }
        });

        const uint CWMO_DEFAULT = 0;
        const uint CWMO_DISPATCH_CALLS = 0x1;
        const uint CWMO_DISPATCH_WINDOW_MESSAGES = 0x2;
        const uint INFINITE = 0xFFFFFFFF;
        uint flags = dispatchCalls ? CWMO_DISPATCH_CALLS | CWMO_DISPATCH_WINDOW_MESSAGES : CWMO_DEFAULT;
        _ = InteropHelpers.CoWaitForMultipleObjects(flags, INFINITE, 1, [doneEvent], out _);
    }

    private static void OnActivated(object? sender, AppActivationArguments args)
    {
        App.MainWindow?.DispatcherQueue.TryEnqueue(() =>
        {
            // A redirected Share activation (normally handled in its own process, see above).
            if (HandoffHost.HandleActivation(args)) return;
            App.ShowMainWindow();
        });
    }
}
