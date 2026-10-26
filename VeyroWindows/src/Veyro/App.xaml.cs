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

        VeyroContainer.Instance.SystemTrayService.Initialize(WindowHandle);
        MainWindow.Closed += Window_Closed;

        var container = VeyroContainer.Instance;
        // ADR-030: first run shows onboarding. Installs from before onboarding existed that
        // already trust a phone count as onboarded.
        if (!container.SettingsStore.Current.OnboardingCompleted && container.TrustStore.GetTrustedPeers().Count > 0)
            _ = container.SettingsStore.UpdateAsync(s => s with { OnboardingCompleted = true });

        var rootFrame = new Frame();
        MainWindow.Content = rootFrame;
        rootFrame.Navigate(container.SettingsStore.Current.OnboardingCompleted ? typeof(MainPage) : typeof(OnboardingPage));

        MainWindow.Activate();
        container.LinkServer.PairingAuthorizer = AuthorizePairingAsync;
        container.LinkServer.OfferAuthorizer = AuthorizeIncomingTransferAsync;
        // A newly paired phone shows up in the device list and sidebar without a restart.
        container.LinkServer.PairingCompleted += _ =>
            MainWindow.DispatcherQueue.TryEnqueue(() => container.DeviceManager.ReloadPairedDevices());
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

    private static Task<bool> AuthorizePairingAsync(InboundPairingRequest request)
    {
        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = MainWindow.DispatcherQueue.TryEnqueue(async () =>
        {
            try
            {
                string fingerprint = DeviceId.ParseOrNull(request.PeerDeviceId) is { } id
                    ? DeviceId.FormatFingerprint(id)
                    : request.PeerDeviceId;
                var dialog = new ConnectionRequestDialog(request.PeerName, fingerprint, request.Pin)
                {
                    XamlRoot = MainWindow.Content.XamlRoot
                };
                completion.TrySetResult(await dialog.ShowAsync() == ContentDialogResult.Primary);
            }
            catch
            {
                completion.TrySetResult(false);
            }
        });
        return completion.Task;
    }

    private static Task<bool> AuthorizeIncomingTransferAsync(InboundOfferRequest request)
    {
        var completion = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _ = MainWindow.DispatcherQueue.TryEnqueue(async () =>
        {
            try
            {
                var dialog = new ContentDialog
                {
                    Title = "Incoming files",
                    Content = new TextBlock
                    {
                        Text = $"{request.Peer.DisplayName} wants to send {request.Offer.Items.Count} file(s) ({request.Offer.TotalBytes:N0} bytes).",
                        TextWrapping = TextWrapping.Wrap
                    },
                    PrimaryButtonText = "Allow",
                    CloseButtonText = "Decline",
                    DefaultButton = ContentDialogButton.Close,
                    XamlRoot = MainWindow.Content.XamlRoot
                };
                completion.TrySetResult(await dialog.ShowAsync() == ContentDialogResult.Primary);
            }
            catch
            {
                completion.TrySetResult(false);
            }
        });
        return completion.Task;
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

        // Really closing: take the notification-area icon away with the window.
        VeyroContainer.Instance.SystemTrayService.Dispose();
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
        VeyroContainer.Instance.NotificationToastService.Dispose();
        VeyroContainer.Instance.CallWindowService.Dispose();
        VeyroContainer.Instance.BluetoothCalling.Dispose();
        VeyroContainer.Instance.PcMediaSource.Dispose();
        VeyroContainer.Instance.PcBatterySource.Dispose();
        VeyroContainer.Instance.SystemTrayService.Dispose();
        MainWindow?.Close();
        Current.Exit();
    }
}
