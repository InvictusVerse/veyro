using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml.Media;
using System.ComponentModel;
using Veyro.Dialogs;
using Veyro.Core.Diagnostics;
using Veyro.Platforms.Windows.Interop;
using Veyro.Views;
using WinRT.Interop;

namespace Veyro;

public partial class App : Application
{
    public static bool HandleClosedEvents { get; set; } = true;
    public static nint WindowHandle { get; private set; }
    public static Window MainWindow { get; private set; } = null!;

    public App()
    {
        InitializeComponent();
        // Hardware test: the window went black and pairing stalled with no visible error. An
        // unhandled UI-thread exception freezes the window under the debugger and kills it
        // otherwise. Log every one (type + stack, never messages) and keep the app alive.
        UnhandledException += (_, e) =>
        {
            VeyroTrace.Crash("UI thread", e.Exception);
            e.Handled = true;
        };
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            VeyroTrace.Crash("Unobserved task", e.Exception);
            e.SetObserved();
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception error) VeyroTrace.Crash("Background thread", error);
        };
        VeyroTrace.Info($"Veyro starting, log file {VeyroTrace.FilePath ?? "unavailable"}");
        VeyroContainer.Initialize();
        // Toast activation must be registered before any mirrored notification can arrive.
        VeyroContainer.Instance.NotificationToastService.Initialize();
    }

    protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
    {
        MainWindow = new Window
        {
            Title = Constants.AppDisplayName,
            SystemBackdrop = new MicaBackdrop { Kind = MicaKind.Base }
        };

        WindowHandle = WindowNative.GetWindowHandle(MainWindow);
        MainWindow.ExtendsContentIntoTitleBar = true;

        MainWindow.AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 760));

        var container = VeyroContainer.Instance;
        // ADR-036: the tray icon exists only while "keep running in the tray" is on (default off).
        container.SystemTrayService.Initialize(WindowHandle);
        container.SystemTrayService.SetEnabled(container.SettingsStore.Current.MinimizeToTrayOnClose);
        MainWindow.Closed += Window_Closed;

        // ADR-030: first run shows onboarding. Installs from before onboarding existed that
        // already trust a phone count as onboarded.
        if (!container.SettingsStore.Current.OnboardingCompleted && container.TrustStore.GetTrustedPeers().Count > 0)
            _ = container.SettingsStore.UpdateAsync(s => s with { OnboardingCompleted = true });

        var rootFrame = new Frame();
        MainWindow.Content = rootFrame;
        try
        {
            rootFrame.Navigate(container.SettingsStore.Current.OnboardingCompleted ? typeof(MainPage) : typeof(OnboardingPage));
        }
        catch (Exception error)
        {
            // Never leave an empty black window: say what happened and where the log is.
            VeyroTrace.Crash("Opening the main page", error);
            MainWindow.Content = new TextBlock
            {
                Margin = new Thickness(32),
                TextWrapping = TextWrapping.Wrap,
                Text = $"Veyro could not open its main page ({error.GetType().Name}). " +
                       $"Details are in {VeyroTrace.FilePath ?? "the debug output"}."
            };
        }

        MainWindow.Activate();
        // ADR-043: watch the Windows clipboard (UI thread).
        try { container.WindowsClipboard.Start(MainWindow.DispatcherQueue); }
        catch (Exception error) { VeyroTrace.Crash("Starting the clipboard watcher", error); }
        // Incoming files are asked about by the container's TransferConsentCoordinator (ADR-036).
        container.LinkServer.PairingAuthorizer = AuthorizePairingAsync;
        // A newly paired phone shows up in the device list and sidebar without a restart.
        // ADR-042: the phone removed this PC; drop it from the list right away.
        container.LinkServer.PeerUnpaired += _ =>
            MainWindow.DispatcherQueue.TryEnqueue(() =>
            {
                try { container.DeviceManager.ReloadPairedDevices(); }
                catch (Exception error) { VeyroTrace.Crash("Reloading devices after unpair", error); }
            });
        container.LinkServer.PairingCompleted += peer =>
            MainWindow.DispatcherQueue.TryEnqueue(() =>
            {
                VeyroTrace.Info("Pairing completed; reloading the device list");
                try { container.DeviceManager.ReloadPairedDevices(); }
                catch (Exception error) { VeyroTrace.Crash("Reloading devices after pairing", error); }
            });
        // ADR-029: on the UI thread, since reconnecting may show the Windows access prompt.
        _ = container.BluetoothCalling.InitializeAsync();
        _ = container.NetworkDiagnostics.RequestFirewallRuleAsync();
        _ = StartNetworkServicesAsync();
    }

    private static async Task StartNetworkServicesAsync()
    {
        // Companion sources first, so the first phone session already gets real snapshots.
        try
        {
            VeyroContainer.Instance.PcBatterySource.Start();
            VeyroContainer.Instance.PcNetworkSource.Start();
            VeyroContainer.Instance.PcWallpaperSource.Start();
            await VeyroContainer.Instance.PcMediaSource.StartAsync();
        }
        catch (Exception error)
        {
            VeyroTrace.Error("PC media/battery sources unavailable", error);
        }
        try
        {
            await VeyroContainer.Instance.LinkServer.StartAsync();
        }
        catch (Exception error)
        {
            VeyroContainer.Instance.StartupError = $"PC listener unavailable: {error.Message}";
            VeyroTrace.Error("PC listener startup failed", error);
        }
        await VeyroContainer.Instance.DeviceManager.RefreshDiscoveryAsync();
    }

    // ── Dialogs: WinUI allows one ContentDialog at a time per window (ADR-036) ──────────

    private static readonly SemaphoreSlim DialogGate = new(1, 1);

    /// <summary>Claims the single dialog slot without waiting. UI thread. Pair with <see cref="ExitDialog"/>.</summary>
    public static bool TryEnterDialog() => DialogGate.Wait(0);

    public static void ExitDialog() => DialogGate.Release();

    /// <summary>Shown and not minimised: a dialog in it would be seen. UI thread.</summary>
    public static bool IsWindowOnScreen(Window window)
        => window.AppWindow is { IsVisible: true } appWindow &&
           appWindow.Presenter is not OverlappedPresenter { State: OverlappedPresenterState.Minimized };

    private static Task<bool> AuthorizePairingAsync(InboundPairingRequest request)
    {
        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = MainWindow.DispatcherQueue.TryEnqueue(async () =>
        {
            bool entered = false;
            try
            {
                // ADR-031: the phone is showing the same code and waiting for Accept here. An
                // incoming-files dialog may be open; wait for it (it closes within 60 s).
                await DialogGate.WaitAsync(request.Abandoned);
                entered = true;
                string fingerprint = DeviceId.ParseOrNull(request.PeerDeviceId) is { } id
                    ? DeviceId.FormatFingerprint(id)
                    : request.PeerDeviceId;
                // The window may be hidden in the tray; the user has to see the prompt to accept.
                ShowMainWindow();
                var dialog = new ConnectionRequestDialog(
                    request.PeerName, fingerprint, request.Code, request.ExpiresAt, request.Abandoned)
                {
                    XamlRoot = MainWindow.Content.XamlRoot
                };
                bool accepted = await dialog.ShowAsync() == ContentDialogResult.Primary;
                completion.TrySetResult(accepted && !request.Abandoned.IsCancellationRequested);
            }
            catch (OperationCanceledException) when (request.Abandoned.IsCancellationRequested)
            {
                completion.TrySetResult(false);
            }
            catch (Exception error)
            {
                VeyroTrace.Warn($"Pairing prompt could not be shown: {error.GetType().Name}");
                completion.TrySetResult(false);
            }
            finally
            {
                if (entered) ExitDialog();
            }
        });
        return completion.Task;
    }

    // ── Tray actions (ADR-036) ───────────────────────────────────────────────────────

    private static bool _syncBusy;

    /// <summary>True while "Pause sync" holds the listener and discovery stopped.</summary>
    public static bool SyncPaused { get; private set; }

    /// <summary>
    /// Pause disconnects the phones and stops listening and discovery, so nothing syncs until
    /// Resume (or the next start; the pause is not saved). UI thread.
    /// </summary>
    public static async void ToggleSync()
    {
        if (_syncBusy) return;
        _syncBusy = true;
        var container = VeyroContainer.Instance;
        try
        {
            if (SyncPaused)
            {
                await container.LinkServer.StartAsync();
                await container.DeviceManager.RefreshDiscoveryAsync();
                SyncPaused = false;
                VeyroTrace.Info("Sync resumed from the tray");
            }
            else
            {
                await container.DiscoveryService.StopAsync();
                await container.LinkServer.StopAsync();
                SyncPaused = true;
                VeyroTrace.Info("Sync paused from the tray");
            }
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Changing sync state failed: {error.GetType().Name}");
        }
        finally
        {
            _syncBusy = false;
        }
    }

    /// <summary>
    /// Tray "Send files...": brings the window forward (the picker needs an owner on screen),
    /// then opens the picker directly.
    /// </summary>
    public static async void SendFilesFromTray()
    {
        ShowMainWindow();
        await PickAndSendFilesAsync();
    }

    /// <summary>
    /// Every "Send files" entry point (header, Transfers tab, tray) opens the system file picker
    /// directly and sends what is chosen to the selected phone (ADR-038). UI thread.
    /// </summary>
    public static async Task PickAndSendFilesAsync()
    {
        try
        {
            var picker = new Windows.Storage.Pickers.FileOpenPicker();
            InitializeWithWindow.Initialize(picker, WindowHandle);
            picker.FileTypeFilter.Add("*");
            var files = await picker.PickMultipleFilesAsync();
            if (files is not { Count: > 0 }) return;
            var paths = files.Select(file => file.Path).Where(path => !string.IsNullOrEmpty(path)).ToList();
            await VeyroContainer.Instance.TransfersPageViewModel.SendFilesToActiveDeviceAsync(paths);
        }
        catch (Exception error)
        {
            // Type only: exception messages can name the user's files.
            VeyroTrace.Warn($"Sending files failed: {error.GetType().Name}");
        }
    }

    private void Window_Closed(object sender, WindowEventArgs args)
    {
        if (HandleClosedEvents &&
            VeyroContainer.Instance.SettingsStore.Current.MinimizeToTrayOnClose &&
            VeyroContainer.Instance.SystemTrayService.IsAvailable)
        {
            args.Handled = true;
            MainWindow.AppWindow.Hide();
            return;
        }

        // Really closing: take the notification-area icon away with the window, and the fetched
        // photo originals with the app (ADR-034).
        VeyroContainer.Instance.SystemTrayService.Dispose();
        VeyroContainer.Instance.Photos.Dispose();
    }

    public static void ShowMainWindow()
    {
        if (MainWindow.AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized } presenter)
        {
            presenter.Restore();
        }

        MainWindow.AppWindow.Show();
        MainWindow.Activate();
        InteropHelpers.SetForegroundWindow(WindowHandle);
    }

    public static void ExitApplication()
    {
        HandleClosedEvents = false;
        VeyroContainer.Instance.LinkServer.StopAsync().GetAwaiter().GetResult();
        VeyroContainer.Instance.Photos.Dispose();
        VeyroContainer.Instance.NotificationToastService.Dispose();
        VeyroContainer.Instance.CallWindowService.Dispose();
        VeyroContainer.Instance.BluetoothCalling.Dispose();
        VeyroContainer.Instance.PcMediaSource.Dispose();
        VeyroContainer.Instance.PcBatterySource.Dispose();
        VeyroContainer.Instance.PcNetworkSource.Dispose();
        VeyroContainer.Instance.PcWallpaperSource.Dispose();
        VeyroContainer.Instance.SystemTrayService.Dispose();
        MainWindow?.Close();
        Current.Exit();
    }
}
