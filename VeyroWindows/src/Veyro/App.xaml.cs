using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml.Media;
using Microsoft.Windows.AppLifecycle;
using System.ComponentModel;
using Veyro.Dialogs;
using Veyro.Core.Diagnostics;
using Veyro.Platform.Interop;
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
        // ADR-047: the tray icon is there while "keep running in the tray" is on (the default).
        container.SystemTrayService.Initialize(WindowHandle);
        container.SystemTrayService.SetEnabled(container.SettingsStore.Current.MinimizeToTrayOnClose);
        MainWindow.Closed += Window_Closed;
        MainWindow.AppWindow.Changed += (_, change) =>
        {
            if (change.DidVisibilityChange || change.DidPresenterChange) UpdateWindowOnScreen();
        };

        // ADR-030: first run shows onboarding. Installs from before onboarding existed that
        // already trust a phone count as onboarded.
        if (!container.SettingsStore.Current.OnboardingCompleted && container.TrustStore.GetTrustedPeers().Count > 0)
            _ = container.SettingsStore.UpdateAsync(s => s with { OnboardingCompleted = true });

        // The frame always exists (dialogs need its XamlRoot); its pages are created only while
        // the window is shown (ADR-047: no UI held in memory while Veyro waits in the tray).
        MainWindow.Content = new Frame();

        // ADR-047: started by Windows at sign-in, or by a toast button: stay in the tray. Anything
        // else (Start menu, a second launch) opens the window. Without a tray icon there would be
        // no way back to a hidden window, so then it opens minimised instead.
        ExtendedActivationKind kind = AppInstance.GetCurrent().GetActivatedEventArgs().Kind;
        bool background = kind is ExtendedActivationKind.StartupTask or ExtendedActivationKind.AppNotification;
        if (background && container.SystemTrayService.IsAvailable)
        {
            VeyroTrace.Info($"Started in the background ({kind}); the window opens from the tray");
            // Never activated, so nothing is drawn; a dialog later shows the window first
            // (ShowMainWindowForDialogAsync), which creates its XAML root then.
            MainWindow.AppWindow.Hide();
            ScheduleTrim();
        }
        else if (background)
        {
            EnsurePages();
            MainWindow.Activate();
            if (MainWindow.AppWindow.Presenter is OverlappedPresenter presenter) presenter.Minimize();
        }
        else
        {
            EnsurePages();
            MainWindow.Activate();
        }
        UpdateWindowOnScreen();
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
        _ = EnsureFirewallRuleAsync();
        _ = InitializeStartupAsync();
        _ = StartNetworkServicesAsync();
    }

    /// <summary>
    /// The firewall rule normally comes with the package (manifest), so nothing is asked. Only if
    /// it is really missing does Veyro ask for administrator permission to add it, and then once
    /// ever: the answer, yes or no, is remembered and Veyro never asks on its own again (the
    /// pairing screen's "Add rule" still can, when the user presses it). The check itself needs no
    /// elevation and shows nothing. If the rule cannot be read at all, nothing is asked.
    /// </summary>
    private static async Task EnsureFirewallRuleAsync()
    {
        try
        {
            VeyroContainer container = VeyroContainer.Instance;
            if (container.SettingsStore.Current.FirewallPromptAsked) return;
            NetworkCheckResult check = await container.NetworkDiagnostics.CheckAsync();
            if (check.Firewall != FirewallRuleState.Missing) return;
            await container.SettingsStore.UpdateAsync(s => s with { FirewallPromptAsked = true });
            await container.NetworkDiagnostics.RequestFirewallRuleAsync();
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Firewall check at startup failed", error);
        }
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
        // ADR-047: from here the sources poll only while a phone is connected.
        VeyroContainer.Instance.BackgroundPower.Start();
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
                XamlRoot root = await ShowMainWindowForDialogAsync()
                    ?? throw new InvalidOperationException("The window has no XamlRoot.");
                var dialog = new ConnectionRequestDialog(
                    request.PeerName, fingerprint, request.Code, request.ExpiresAt, request.Abandoned)
                {
                    XamlRoot = root
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

    // ── Start with Windows and background, from the tray (ADR-047) ─────────────────

    private static LoginStartupState _startupState = LoginStartupState.Unavailable;

    /// <summary>For the tray menu's tick: true/false, or null when Windows does not let Veyro change it.</summary>
    public static bool? StartupEnabledForMenu => _startupState switch
    {
        LoginStartupState.Enabled => true,
        LoginStartupState.Disabled or LoginStartupState.DisabledByUser => false,
        _ => null
    };

    /// <summary>
    /// Reads "Start with Windows" and, once per install, turns it on (the default). After that only
    /// the user changes it, so turning it off is never undone.
    /// </summary>
    private static async Task InitializeStartupAsync()
    {
        VeyroContainer container = VeyroContainer.Instance;
        try
        {
            _startupState = await container.LoginStartup.GetStateAsync();
            if (!container.SettingsStore.Current.AutoStartDefaultApplied)
            {
                if (_startupState == LoginStartupState.Disabled)
                    _startupState = await container.LoginStartup.SetEnabledAsync(true);
                await container.SettingsStore.UpdateAsync(s => s with
                {
                    AutoStartDefaultApplied = true,
                    AutoStartOnLogin = _startupState == LoginStartupState.Enabled
                });
                VeyroTrace.Info($"Start with Windows default applied: {_startupState}");
            }
            await container.GeneralViewModel.RefreshAsync();
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Start with Windows unavailable: {error.GetType().Name}");
        }
    }

    /// <summary>Tray "Start with Windows". Windows keeps a user's own "off" in its Startup apps page.</summary>
    public static async void ToggleStartupFromTray()
    {
        VeyroContainer container = VeyroContainer.Instance;
        try
        {
            if (_startupState == LoginStartupState.DisabledByUser)
            {
                // Turned off in Windows Settings: only Windows can turn it back on.
                await global::Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:startupapps"));
                return;
            }
            bool enable = _startupState != LoginStartupState.Enabled;
            _startupState = await container.LoginStartup.SetEnabledAsync(enable);
            await container.SettingsStore.UpdateAsync(s => s with { AutoStartOnLogin = _startupState == LoginStartupState.Enabled });
            await container.GeneralViewModel.RefreshAsync();
            VeyroTrace.Info($"Start with Windows from the tray: {_startupState}");
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Changing Start with Windows failed: {error.GetType().Name}");
        }
    }

    /// <summary>
    /// Tray "Keep running in the background", unticked: closing the window will quit Veyro. The
    /// window opens first, since the tray icon (the only way back to a hidden window) goes away.
    /// </summary>
    public static void StopBackgroundFromTray()
    {
        ShowMainWindow();
        // Through the view model, so Settings shows it and the icon is removed the usual way.
        VeyroContainer.Instance.GeneralViewModel.MinimizeToTrayOnClose = false;
        VeyroTrace.Info("Background mode turned off from the tray");
    }

    /// <summary>Settings changed "Start with Windows": keep the tray tick in step.</summary>
    public static void OnStartupStateChanged(LoginStartupState state) => _startupState = state;

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
            HideToTray();
            return;
        }

        // Really closing: take the notification-area icon away with the window, and the fetched
        // photo originals with the app (ADR-034).
        VeyroContainer.Instance.SystemTrayService.Dispose();
        VeyroContainer.Instance.Photos.Dispose();
    }

    public static void ShowMainWindow()
    {
        _trimGeneration++; // a pending "release the UI" from a recent hide no longer applies
        EnsurePages();
        if (MainWindow.AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized } presenter)
        {
            presenter.Restore();
        }

        MainWindow.AppWindow.Show();
        MainWindow.Activate();
        InteropHelpers.SetForegroundWindow(WindowHandle);
        UpdateWindowOnScreen();
    }

    // ── Background (ADR-047) ─────────────────────────────────────────────────────────

    private static volatile bool _windowOnScreen;
    private static int _trimGeneration;

    /// <summary>
    /// The window is shown and not minimised. Read from any thread (the discovery beacon and the
    /// power policy use it); written on the UI thread.
    /// </summary>
    public static bool WindowOnScreen => _windowOnScreen;

    private static void UpdateWindowOnScreen()
    {
        bool now = MainWindow is { } window && IsWindowOnScreen(window);
        if (now == _windowOnScreen) return;
        _windowOnScreen = now;
        VeyroContainer.Instance.BackgroundPower.WindowChanged();
    }

    /// <summary>Creates the main page (or onboarding) if the frame is empty. UI thread.</summary>
    private static void EnsurePages()
    {
        if (MainWindow.Content is not Frame frame || frame.Content is not null) return;
        var container = VeyroContainer.Instance;
        try
        {
            frame.Navigate(container.SettingsStore.Current.OnboardingCompleted ? typeof(MainPage) : typeof(OnboardingPage));
        }
        catch (Exception error)
        {
            // Never leave an empty black window: say what happened and where the log is.
            VeyroTrace.Crash("Opening the main page", error);
            frame.Content = new TextBlock
            {
                Margin = new Thickness(32),
                TextWrapping = TextWrapping.Wrap,
                Text = $"Veyro could not open its main page ({error.GetType().Name}). " +
                       $"Details are in {VeyroTrace.FilePath ?? "the debug output"}."
            };
        }
    }

    /// <summary>Close button with tray mode on: hide, then let go of the UI. UI thread.</summary>
    private static void HideToTray()
    {
        MainWindow.AppWindow.Hide();
        UpdateWindowOnScreen();
        VeyroContainer container = VeyroContainer.Instance;
        if (!container.SettingsStore.Current.TrayHintShown)
        {
            _ = container.SettingsStore.UpdateAsync(s => s with { TrayHintShown = true });
            container.NotificationToastService.ShowStatus(
                "Veyro is still running",
                "Your phone stays connected. Open Veyro from the tray icon; right-click it to quit or stop starting with Windows.");
        }
        ScheduleTrim();
    }

    /// <summary>
    /// A few seconds after the window is hidden (so a quick reopen costs nothing): drop the
    /// pages, then trim the heap and the working set once. Cancelled by showing the window.
    /// </summary>
    private static void ScheduleTrim()
    {
        int generation = ++_trimGeneration;
        DispatcherQueueTimer timer = MainWindow.DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromSeconds(5);
        timer.IsRepeating = false;
        timer.Tick += (t, _) =>
        {
            t.Stop();
            if (generation != _trimGeneration || _windowOnScreen) return;
            // A dialog (pairing, incoming files) needs the window; do not pull pages from under it.
            if (!TryEnterDialog()) return;
            try
            {
                if (MainWindow.Content is Frame frame)
                {
                    frame.Content = null;
                    frame.BackStack.Clear();
                }
            }
            finally
            {
                ExitDialog();
            }
            _ = Task.Run(ProcessPower.TrimMemory);
        };
        timer.Start();
    }

    /// <summary>
    /// Shows the window and waits until it can host a dialog (its XAML root exists). UI thread.
    /// </summary>
    public static async Task<XamlRoot?> ShowMainWindowForDialogAsync()
    {
        ShowMainWindow();
        var waited = Stopwatch.StartNew();
        while (MainWindow.Content?.XamlRoot is null && waited.Elapsed < TimeSpan.FromSeconds(5)) await Task.Delay(50);
        return MainWindow.Content?.XamlRoot;
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
