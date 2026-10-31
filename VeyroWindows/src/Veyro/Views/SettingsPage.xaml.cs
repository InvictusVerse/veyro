using Veyro.Core.Diagnostics;
using Veyro.ViewModels.Settings;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Veyro.Views;

/// <summary>
/// Settings: a list of sections on the left and one panel on the right. The navigation parameter
/// (one of the section constants) chooses which panel opens first.
/// </summary>
public sealed partial class SettingsPage : Page
{
    /// <summary>Navigation parameter that opens the Bluetooth calling section.</summary>
    public const string BluetoothSection = "bluetooth";

    /// <summary>Navigation parameter that opens the paired devices section.</summary>
    public const string DevicesSection = "devices";

    /// <summary>Navigation parameter that opens the per-app notification rules (ADR-063).</summary>
    public const string NotificationsSection = "notifications";

    private const string GeneralSection = "general";
    private const string AboutSection = "about";

    private const string BluetoothSettingsUri = "ms-settings:bluetooth";

    /// <summary>Section key to its entry in the list and the panel that entry shows.</summary>
    private readonly Dictionary<string, (ListViewItem Entry, UIElement Panel)> _sections;

    private string _firstSection = GeneralSection;
    private bool _applyingLayoutChoice;

    public SettingsPage()
    {
        InitializeComponent();
        _sections = new Dictionary<string, (ListViewItem, UIElement)>
        {
            [GeneralSection] = (GeneralEntry, GeneralPanel),
            [DevicesSection] = (DevicesEntry, DevicesPanel),
            [NotificationsSection] = (NotificationsEntry, NotificationsPanel),
            [BluetoothSection] = (BluetoothEntry, BluetoothPanel),
            [AboutSection] = (AboutEntry, AboutPanel)
        };
    }

    public GeneralViewModel ViewModel => VeyroContainer.Instance.GeneralViewModel;

    public BluetoothCallingViewModel Bluetooth => VeyroContainer.Instance.BluetoothCallingViewModel;

    public DevicesViewModel Devices => VeyroContainer.Instance.DevicesViewModel;

    public NotificationRulesViewModel NotificationRules => VeyroContainer.Instance.NotificationRulesViewModel;

    // ---- Page lifecycle and section list ----------------------------------------------------

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter is string { Length: > 0 } section) _firstSection = section;
    }

    private async void Page_Loaded(object sender, RoutedEventArgs e)
    {
        // An unknown section name falls back to General.
        var (entry, _) = _sections.TryGetValue(_firstSection, out var target) ? target : _sections[GeneralSection];
        SectionList.SelectedItem = entry;

        Devices.RefreshAutoAccept();
        await ViewModel.RefreshAsync();
    }

    private void SectionList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        string selected = (SectionList.SelectedItem as ListViewItem)?.Tag as string ?? GeneralSection;
        foreach (var (key, (_, panel)) in _sections)
            panel.Visibility = key == selected ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---- General ---------------------------------------------------------------------------

    // ADR-074: Modern or Classic layout. Home and any open mirror or camera window follow at once.
    private void UiStyleCombo_Loaded(object sender, RoutedEventArgs e)
    {
        _applyingLayoutChoice = true;
        UiStyleCombo.SelectedIndex = AppUiStyle.IsClassic(VeyroContainer.Instance.SettingsStore.Current) ? 1 : 0;
        _applyingLayoutChoice = false;
    }

    private async void UiStyleCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_applyingLayoutChoice || UiStyleCombo.SelectedItem is not ComboBoxItem { Tag: string style }) return;
        await VeyroContainer.Instance.SettingsStore.UpdateAsync(settings => settings with { UiStyle = style });
    }

    private void OpenDownloads_Click(object sender, RoutedEventArgs e)
    {
        string folder = ViewModel.DownloadDirectory;
        Directory.CreateDirectory(folder);
        Process.Start(new ProcessStartInfo { FileName = folder, UseShellExecute = true });
    }

    private async void ChangeDownloads_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FolderPicker();
        InitializeWithWindow.Initialize(picker, App.WindowHandle);
        picker.FileTypeFilter.Add("*");

        var chosen = await picker.PickSingleFolderAsync();
        if (chosen is not null && !string.IsNullOrEmpty(chosen.Path)) ViewModel.DownloadDirectory = chosen.Path;
    }

    private void UseDefaultDownloads_Click(object sender, RoutedEventArgs e) => ViewModel.UseDefaultDownloadDirectory();

    private async void ResetIdentity_Click(object sender, RoutedEventArgs e)
    {
        bool confirmed = await ConfirmAsync(
            "Reset this PC's identity?",
            "A new key is created and every paired phone has to pair again. Restart Veyro afterwards.",
            "Reset");
        if (confirmed) ViewModel.ResetIdentity();
    }

    // ---- Devices ---------------------------------------------------------------------------

    /// <summary>Opens the shared QR pairing screen (ADR-030).</summary>
    private void PairNewDevice_Click(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(PairDevicePage));

    private void SelectDevice_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string deviceId }) Devices.Select(deviceId);
    }

    private async void RemoveDevice_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { Tag: string deviceId }) return;

        string name = Devices.PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId)?.Name ?? "this phone";
        bool confirmed = await ConfirmAsync(
            $"Remove {name}?",
            "The phone will no longer be trusted by this PC and has to pair again to connect.",
            "Remove");
        if (!confirmed) return;

        try
        {
            await Devices.RevokeDeviceAsync(deviceId);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Removing a paired device failed", error);
        }
    }

    // The toggle binding is one-way, so a model update that changes IsOn must not be saved back.
    private async void AutoAccept_Toggled(object sender, RoutedEventArgs e)
    {
        if (sender is not ToggleSwitch { Tag: string deviceId } toggle) return;

        PairedDeviceUiModel? device = Devices.PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId);
        if (device is null || device.AutoAcceptTransfers == toggle.IsOn) return;

        try
        {
            await Devices.SetAutoAcceptAsync(deviceId, toggle.IsOn);
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Saving auto-accept failed", error);
        }
    }

    // ---- Bluetooth calling -----------------------------------------------------------------

    /// <summary>
    /// Pairing a phone over Bluetooth from inside the app is unreliable, so it happens in Windows
    /// Settings. The device list is refreshed when the user returns and presses Refresh.
    /// </summary>
    private async void PairBluetooth_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await global::Windows.System.Launcher.LaunchUriAsync(new Uri(BluetoothSettingsUri));
        }
        catch (Exception error)
        {
            VeyroTrace.Error("Opening Bluetooth settings failed", error);
        }
    }

    private async void RefreshBluetooth_Click(object sender, RoutedEventArgs e) => await Bluetooth.RefreshAsync();

    private async void ConnectBluetooth_Click(object sender, RoutedEventArgs e) => await Bluetooth.ConnectAsync();

    private async void DisconnectBluetooth_Click(object sender, RoutedEventArgs e) => await Bluetooth.DisconnectAsync();

    // ---- Shared ----------------------------------------------------------------------------

    /// <summary>Asks a yes/no question; Cancel is the default button. True when confirmed.</summary>
    private async Task<bool> ConfirmAsync(string title, string message, string confirmText)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = confirmText,
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot
        };
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }
}
