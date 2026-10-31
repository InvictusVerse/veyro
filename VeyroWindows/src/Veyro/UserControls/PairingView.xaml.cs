using Veyro.Core.Diagnostics;
using Veyro.Services;

namespace Veyro.UserControls;

/// <summary>
/// Body of the pairing screen (ADR-030), used by onboarding and by Settings > Devices. The host
/// calls <see cref="PairingViewModel.Start"/> and <see cref="PairingViewModel.Stop"/>, so the
/// pairing window is open only while the screen is visible. This control only renders.
/// </summary>
public sealed partial class PairingView : UserControl
{
    private const string NetworkSettingsUri = "ms-settings:network-status";

    /// <summary>Counts QR renders so a slow decode cannot overwrite a newer code.</summary>
    private int _qrGeneration;

    public PairingView()
    {
        InitializeComponent();
        Loaded += (_, _) =>
        {
            ViewModel.PropertyChanged += ViewModel_PropertyChanged;
            _ = RenderQrAsync();
        };
        Unloaded += (_, _) => ViewModel.PropertyChanged -= ViewModel_PropertyChanged;
    }

    public PairingViewModel ViewModel => VeyroContainer.Instance.PairingViewModel;

    private void ViewModel_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PairingViewModel.QrPng)) _ = RenderQrAsync();
    }

    private async Task RenderQrAsync()
    {
        int generation = ++_qrGeneration;
        byte[]? png = ViewModel.QrPng;
        if (png is null)
        {
            QrPicture.Source = null;
            return;
        }

        try
        {
            var bitmap = await QrCodeRenderer.ToBitmapAsync(png);
            if (generation == _qrGeneration) QrPicture.Source = bitmap;
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
                await OpenNetworkSettingsAsync();
                break;
        }
    }

    private static async Task OpenNetworkSettingsAsync()
    {
        try
        {
            await global::Windows.System.Launcher.LaunchUriAsync(new Uri(NetworkSettingsUri));
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Opening network settings failed", error);
        }
    }
}
