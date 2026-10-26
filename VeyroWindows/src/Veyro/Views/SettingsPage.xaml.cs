using Veyro.Core.Diagnostics;
using Veyro.ViewModels.Settings;

namespace Veyro.Views;

public sealed partial class SettingsPage : Page
{
    public GeneralViewModel ViewModel => VeyroContainer.Instance.GeneralViewModel;
    public BluetoothCallingViewModel Bluetooth => VeyroContainer.Instance.BluetoothCallingViewModel;

    public SettingsPage()
    {
        InitializeComponent();
    }

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
