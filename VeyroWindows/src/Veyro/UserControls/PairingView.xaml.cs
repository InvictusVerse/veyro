// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Views/Settings/DeviceDiscoveryPage.xaml.cs, GPL-3.0.
// Modified for Veyro.
using Veyro.Core.Diagnostics;
using Veyro.Services;

namespace Veyro.UserControls;

/// <summary>
/// The pairing screen body (ADR-030), shared by onboarding and Settings > Devices. The host calls
/// <see cref="PairingViewModel.Start"/> and <see cref="PairingViewModel.Stop"/> so the pairing
/// window is open only while the screen is visible.
/// </summary>
public sealed partial class PairingView : UserControl
{
    private int _renderVersion;

    public PairingViewModel ViewModel => VeyroContainer.Instance.PairingViewModel;

    public PairingView()
    {
        InitializeComponent();
        Loaded += (_, _) =>
        {
            ViewModel.PropertyChanged += ViewModel_PropertyChanged;
            _ = ShowQrAsync();
        };
        Unloaded += (_, _) => ViewModel.PropertyChanged -= ViewModel_PropertyChanged;
    }

    private void ViewModel_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PairingViewModel.QrPng)) _ = ShowQrAsync();
    }

    private async Task ShowQrAsync()
    {
        int version = ++_renderVersion;
        byte[]? png = ViewModel.QrPng;
        if (png is null)
        {
            QrImage.Source = null;
            return;
        }
        try
        {
            var bitmap = await QrCodeRenderer.ToBitmapAsync(png);
            // A newer code may have arrived while this one decoded.
            if (version == _renderVersion) QrImage.Source = bitmap;
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Showing the pairing QR code failed", error);
        }
    }

    private void NewCode_Click(object sender, RoutedEventArgs e) => ViewModel.NewCode();

    private async void CheckAgain_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshNetworkChecksAsync();

    private async void CheckAction_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: NetworkCheckUiModel check }) return;
        switch (check.Action)
        {
            case NetworkCheckAction.AddFirewallRule:
                await ViewModel.AddFirewallRuleAsync();
                break;
            case NetworkCheckAction.OpenNetworkSettings:
                try { await global::Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:network-status")); }
                catch (Exception error) { VeyroTrace.Error("Opening network settings failed", error); }
                break;
        }
    }
}
