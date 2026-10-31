// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Views/SettingsPage.xaml.cs, GPL-3.0.
// Modified for Veyro.

using Veyro.Core.Diagnostics;
using Veyro.ViewModels.Settings;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Veyro.Views;

public sealed partial class SettingsPage : Page
{
    /// <summary>Navigation parameter that opens the Bluetooth calling section.</summary>
    public const string BluetoothSection = "bluetooth";

    /// <summary>Navigation parameter that opens the paired devices section.</summary>
    public const string DevicesSection = "devices";

    public GeneralViewModel ViewModel => VeyroContainer.Instance.GeneralViewModel;
    public BluetoothCallingViewModel Bluetooth => VeyroContainer.Instance.BluetoothCallingViewModel;
    public DevicesViewModel Devices => VeyroContainer.Instance.DevicesViewModel;
    public NotificationRulesViewModel NotificationRules => VeyroContainer.Instance.NotificationRulesViewModel;

    /// <summary>Navigation parameter that opens the per-app notification rules (ADR-063).</summary>
    public const string NotificationsSection = "notifications";

    private string _initialSection = "general";

    public SettingsPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter is string section && !string.IsNullOrEmpty(section)) _initialSection = section;
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        SettingsNav.SelectedItem = _initialSection switch
        {
            DevicesSection => DevicesItem,
            NotificationsSection => NotificationsItem,
            BluetoothSection => BluetoothItem,
            "about" => AboutItem,
            _ => GeneralItem
        };
        Devices.RefreshAutoAccept();
        await ViewModel.RefreshAsync();
    }

    /// <summary>The binding is one-way, so an unchanged value (a model update) is not saved again.</summary>
    private async void AutoAccept_Toggled(object sender, RoutedEventArgs e)
    {
        if (sender is not ToggleSwitch { Tag: string deviceId } toggle) return;
        PairedDeviceUiModel? device = Devices.PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId);
        if (device is null || device.AutoAcceptTransfers == toggle.IsOn) return;
        try { await Devices.SetAutoAcceptAsync(deviceId, toggle.IsOn); }
        catch (Exception error) { VeyroTrace.Error("Saving auto-accept failed", error); }
    }

    private void SettingsNav_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        string tag = (args.SelectedItem as NavigationViewItem)?.Tag as string ?? "general";
        GeneralSection.Visibility = tag == "general" ? Visibility.Visible : Visibility.Collapsed;
        DevicesSectionPanel.Visibility = tag == DevicesSection ? Visibility.Visible : Visibility.Collapsed;
        NotificationsSectionPanel.Visibility = tag == NotificationsSection ? Visibility.Visible : Visibility.Collapsed;
        BluetoothSectionPanel.Visibility = tag == BluetoothSection ? Visibility.Visible : Visibility.Collapsed;
        AboutSection.Visibility = tag == "about" ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>Opens the shared QR pairing screen (ADR-030).</summary>
    private void PairNewDevice_Click(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(PairDevicePage));

    private void SelectDevice_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is string deviceId) Devices.Select(deviceId);
    }

    private async void RemoveDevice_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not string deviceId) return;
        string name = Devices.PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId)?.Name ?? "this phone";
        var dialog = new ContentDialog
        {
            Title = $"Remove {name}?",
            Content = new TextBlock
            {
                Text = "The phone will no longer be trusted by this PC and has to pair again to connect.",
                TextWrapping = TextWrapping.Wrap
            },
            PrimaryButtonText = "Remove",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try { await Devices.RevokeDeviceAsync(deviceId); }
        catch (Exception error) { VeyroTrace.Error("Removing a paired device failed", error); }
    }

    private async void ResetIdentity_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            Title = "Reset this PC's identity?",
            Content = new TextBlock
            {
                Text = "A new key is created and every paired phone has to pair again. Restart Veyro afterwards.",
                TextWrapping = TextWrapping.Wrap
            },
            PrimaryButtonText = "Reset",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary) ViewModel.ResetIdentity();
    }

    // ADR-074: Modern or Classic layout; Home and open mirror/camera windows follow at once.
    private bool _loadingUiStyle;

    private void UiStyleCombo_Loaded(object sender, RoutedEventArgs e)
    {
        _loadingUiStyle = true;
        UiStyleCombo.SelectedIndex = AppUiStyle.IsClassic(VeyroContainer.Instance.SettingsStore.Current) ? 1 : 0;
        _loadingUiStyle = false;
    }

    private async void UiStyleCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loadingUiStyle || UiStyleCombo.SelectedItem is not ComboBoxItem { Tag: string style }) return;
        await VeyroContainer.Instance.SettingsStore.UpdateAsync(s => s with { UiStyle = style });
    }

    private void OpenDownloads_Click(object sender, RoutedEventArgs e)
    {
        string dir = ViewModel.DownloadDirectory;
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo { FileName = dir, UseShellExecute = true });
    }

    private async void ChangeDownloads_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FolderPicker();
        InitializeWithWindow.Initialize(picker, App.WindowHandle);
        picker.FileTypeFilter.Add("*");
        var folder = await picker.PickSingleFolderAsync();
        if (folder is not null && !string.IsNullOrEmpty(folder.Path)) ViewModel.DownloadDirectory = folder.Path;
    }

    private void UseDefaultDownloads_Click(object sender, RoutedEventArgs e) => ViewModel.UseDefaultDownloadDirectory();

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
