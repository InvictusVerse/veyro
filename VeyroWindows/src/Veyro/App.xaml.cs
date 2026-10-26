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

        var rootFrame = new Frame();
        MainWindow.Content = rootFrame;
        rootFrame.Navigate(typeof(MainPage));

        MainWindow.Activate();
        VeyroContainer.Instance.LinkServer.PairingAuthorizer = AuthorizePairingAsync;
        VeyroContainer.Instance.LinkServer.OfferAuthorizer = AuthorizeIncomingTransferAsync;
        _ = RequestFirewallRuleAsync();
        _ = StartNetworkServicesAsync();
    }

    private static async Task RequestFirewallRuleAsync()
    {
        const string ruleName = "Veyro LAN Transfer";
        const string script = "if (-not (Get-NetFirewallRule -DisplayName 'Veyro LAN Transfer' -ErrorAction SilentlyContinue)) { New-NetFirewallRule -DisplayName 'Veyro LAN Transfer' -Direction Inbound -Protocol TCP -LocalPort 5150-5169 -Action Allow -Profile Private }";
        try
        {
            VeyroTrace.Info("Requesting administrator permission to allow private-network TCP ports 5150-5169");
            var startInfo = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"{script}\"",
                Verb = "runas",
                UseShellExecute = true,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using Process? process = Process.Start(startInfo);
            if (process is null) { VeyroTrace.Warn($"Could not start firewall permission request for '{ruleName}'"); return; }
            await process.WaitForExitAsync().ConfigureAwait(false);
            VeyroTrace.Info($"Firewall permission request exited with code {process.ExitCode}");
        }
        catch (Win32Exception error) when (error.NativeErrorCode == 1223)
        {
            VeyroTrace.Warn("Firewall permission request was cancelled by the user");
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Firewall permission request failed", error);
        }
    }

    private static async Task StartNetworkServicesAsync()
    {
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
                var dialog = new ConnectionRequestDialog(request.PeerName, request.PeerDeviceId, request.Pin)
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
        if (!HandleClosedEvents)
            return;

        if (!VeyroContainer.Instance.SettingsStore.Current.MinimizeToTrayOnClose)
            return;

        if (!VeyroContainer.Instance.SystemTrayService.IsAvailable)
            return;

        args.Handled = true;
        MainWindow.AppWindow.Hide();
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
        VeyroContainer.Instance.SystemTrayService.Dispose();
        MainWindow?.Close();
        Current.Exit();
    }
}
