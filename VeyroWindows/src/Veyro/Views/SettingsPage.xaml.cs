using Veyro.Core.Diagnostics;
using Veyro.ViewModels.Settings;

namespace Veyro.Views;

public sealed partial class SettingsPage : Page
{
    /// <summary>Navigation parameter that scrolls to the Bluetooth calling card.</summary>
    public const string BluetoothSection = "bluetooth";

    public GeneralViewModel ViewModel => VeyroContainer.Instance.GeneralViewModel;
    public BluetoothCallingViewModel Bluetooth => VeyroContainer.Instance.BluetoothCallingViewModel;
    public DevicesViewModel Devices => VeyroContainer.Instance.DevicesViewModel;

    public SettingsPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter as string == BluetoothSection)
        {
            void BringIntoView(object sender, RoutedEventArgs args)
            {
                Loaded -= BringIntoView;
                BluetoothCard.StartBringIntoView(new BringIntoViewOptions { VerticalAlignmentRatio = 0.0 });
            }
            Loaded += BringIntoView;
        }
    }

    /// <summary>Opens the shared QR pairing screen (ADR-030).</summary>
    private void PairNewDevice_Click(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(PairDevicePage));

    private void ResetIdentity_Click(object sender, RoutedEventArgs e)
    {
        ViewModel.ResetIdentity();
    }

    /// <summary>
    /// In-app Bluetooth pairing is unreliable for phones, so pairing happens in Windows Settings;
    /// the device list is refreshed when the user comes back and presses Refresh.
    /// </summary>
    private async void PairBluetooth_Click(object sender, RoutedEventArgs e)
    {
        try { await global::Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:bluetooth")); }
        catch (Exception error) { VeyroTrace.Error("Opening Bluetooth settings failed", error); }
    }

    private async void RefreshBluetooth_Click(object sender, RoutedEventArgs e) => await Bluetooth.RefreshAsync();

    private async void ConnectBluetooth_Click(object sender, RoutedEventArgs e) => await Bluetooth.ConnectAsync();

    private async void DisconnectBluetooth_Click(object sender, RoutedEventArgs e) => await Bluetooth.DisconnectAsync();
}
