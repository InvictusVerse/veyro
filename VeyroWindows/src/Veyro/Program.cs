using Microsoft.Windows.AppLifecycle;
using Veyro.Platforms.Windows.Interop;

namespace Veyro;

internal static class Program
{
    [STAThread]
    public static void Main(string[] args)
    {
        WinRT.ComWrappersSupport.InitializeComWrappers();

        bool isRedirect = DecideRedirection();
        if (!isRedirect)
        {
            Application.Start(p =>
            {
                var context = new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread());
                SynchronizationContext.SetSynchronizationContext(context);
                _ = new App();
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
        }
        else
        {
            isRedirect = true;
            RedirectActivationTo(keyInstance, args);
        }

        return isRedirect;
    }

    private static void RedirectActivationTo(AppInstance keyInstance, AppActivationArguments args)
    {
        nint redirectEventHandle = InteropHelpers.CreateEvent(nint.Zero, true, false, null);
        Task.Run(() =>
        {
            keyInstance.RedirectActivationToAsync(args).AsTask().Wait();
            InteropHelpers.SetEvent(redirectEventHandle);
        });

        const uint CWMO_DEFAULT = 0;
        const uint INFINITE = 0xFFFFFFFF;
        _ = InteropHelpers.CoWaitForMultipleObjects(CWMO_DEFAULT, INFINITE, 1, [redirectEventHandle], out _);
    }

    private static void OnActivated(object? sender, AppActivationArguments args)
    {
        App.MainWindow?.DispatcherQueue.TryEnqueue(App.ShowMainWindow);
    }
}
