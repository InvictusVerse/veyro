using System.Collections.Specialized;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Windows.ApplicationModel.DataTransfer;

namespace Veyro.Views;

/// <summary>
/// ADR-074: Modern Home, laid out like NectarLink. Tool buttons follow what the shown phone can do
/// now (connected, capabilities, the speaker's state); they are refreshed on changes and every few
/// seconds while the page is shown. The timeline is the newest transfers from the transfer store.
/// </summary>
public sealed partial class HomePage : Page
{
    private const int TimelineCount = 6;

    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public DeviceStatusViewModel StatusViewModel => VeyroContainer.Instance.DeviceStatusViewModel;
    public PhoneMediaViewModel MediaViewModel => VeyroContainer.Instance.PhoneMediaViewModel;
    private TransfersPageViewModel Transfers => VeyroContainer.Instance.TransfersPageViewModel;

    public ObservableCollection<TransferHistoryItemViewModel> Timeline { get; } = [];

    private DispatcherQueueTimer? _refresh;
    private bool _settingSpeaker;

    public HomePage()
    {
        InitializeComponent();
        PhoneOverlay.OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(200) };
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    public string BatteryChip(string battery, bool charging) => charging ? $"{battery} · Charging" : battery;

    public string FindText(bool ringing) => ringing ? "Stop ringing" : "Find phone";

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        var container = VeyroContainer.Instance;
        ViewModel.PropertyChanged += OnViewModelChanged;
        container.Speaker.Changed += OnSpeakerChanged;
        Transfers.History.CollectionChanged += OnHistoryChanged;
        _refresh ??= CreateRefreshTimer();
        _refresh.Start();
        RefreshTools();
        RefreshTimeline();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        var container = VeyroContainer.Instance;
        ViewModel.PropertyChanged -= OnViewModelChanged;
        container.Speaker.Changed -= OnSpeakerChanged;
        Transfers.History.CollectionChanged -= OnHistoryChanged;
        _refresh?.Stop();
    }

    private DispatcherQueueTimer CreateRefreshTimer()
    {
        DispatcherQueueTimer timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromSeconds(3);
        timer.Tick += (_, _) => RefreshTools();
        return timer;
    }

    private void OnViewModelChanged(object? sender, PropertyChangedEventArgs e) => DispatcherQueue.TryEnqueue(RefreshTools);

    private void OnSpeakerChanged(string? problem)
    {
        RefreshTools();
        if (problem is not null) VeyroContainer.Instance.NotificationToastService.ShowStatus("PC sound on the phone", problem, "speaker");
    }

    private void OnHistoryChanged(object? sender, NotifyCollectionChangedEventArgs e) => DispatcherQueue.TryEnqueue(RefreshTimeline);

    private void RefreshTimeline()
    {
        Timeline.Clear();
        foreach (TransferHistoryItemViewModel item in Transfers.History.Take(TimelineCount)) Timeline.Add(item);
        TimelineEmpty.Visibility = Timeline.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private string? ConnectedId => ViewModel.Device is { IsConnected: true } device ? device.DeviceId : null;

    private void RefreshTools()
    {
        var container = VeyroContainer.Instance;
        string? id = ConnectedId;
        WebcamTile.IsEnabled = id is not null;

        bool mine = id is not null && string.Equals(container.Speaker.PeerId, id, StringComparison.OrdinalIgnoreCase);
        _settingSpeaker = true;
        SpeakerSwitch.IsEnabled = id is not null && (mine || container.Speaker.CanUse(id));
        SpeakerSwitch.IsOn = mine;
        _settingSpeaker = false;
        SpeakerText.Text = !mine ? "Play this PC's sound through the phone" : container.Speaker.IsPlaying ? "Playing now" : "Starting on the phone...";

        bool capture = container.PhoneCapture.CanUse(id);
        PhotoTile.IsEnabled = capture;
        ScanButton.IsEnabled = capture;
    }

    // ── Phone card ───────────────────────────────────────────────────────────────────

    private void PhoneFrame_PointerEntered(object sender, PointerRoutedEventArgs e) => PhoneOverlay.Opacity = 1;

    private void PhoneFrame_PointerExited(object sender, PointerRoutedEventArgs e) => PhoneOverlay.Opacity = 0;

    private void PhoneFrame_Tapped(object sender, TappedRoutedEventArgs e)
    {
        OpenMirror();
        e.Handled = true;
    }

    private void PhoneFrame_PointerWheelChanged(object sender, PointerRoutedEventArgs e)
    {
        ViewModel.SwitchToNextDevice(e.GetCurrentPoint(PhoneFrame).Properties.MouseWheelDelta);
        e.Handled = true;
    }

    private void Mirror_Click(object sender, RoutedEventArgs e) => OpenMirror();

    private void OpenMirror()
    {
        if (ViewModel.Device is { } device) VeyroContainer.Instance.Mirror.Open(device.DeviceId);
    }

    private async void SendFiles_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is not null) await App.PickAndSendFilesAsync();
    }

    private async void SendFolder_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is not null) await App.PickAndSendFolderAsync();
    }

    /// <summary>Sends the text on this PC's clipboard now, whatever the auto-send setting says.</summary>
    private async void SendClipboard_Click(object sender, RoutedEventArgs e)
    {
        var toasts = VeyroContainer.Instance.NotificationToastService;
        string? text = null;
        try
        {
            DataPackageView view = Clipboard.GetContent();
            if (view.Contains(StandardDataFormats.Text)) text = await view.GetTextAsync();
        }
        catch (Exception error) when (error is UnauthorizedAccessException or System.Runtime.InteropServices.COMException)
        {
            // The clipboard is held by another app for a moment.
        }
        if (string.IsNullOrEmpty(text))
        {
            toasts.ShowStatus("Clipboard not sent", "There is no text on this PC's clipboard.", "clip-send");
            return;
        }
        bool sent = await VeyroContainer.Instance.Clipboard.SendTextNowAsync(text);
        if (!sent) toasts.ShowStatus("Clipboard not sent", "No connected phone takes clipboard copies. Check \"Auto-fetch clipboard\" in Veyro on the phone.", "clip-send");
    }

    private void ClipboardPage_Click(object sender, RoutedEventArgs e) => ModernShellPage.Open("Clipboard");

    private async void Reconnect_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshDiscoveryAsync();

    private void PairPhone_Click(object sender, RoutedEventArgs e) => ViewModel.RequestPairing();

    private async void DiscoveredDevice_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is string deviceId) await PairingFlow.PairWithDiscoveredAsync(XamlRoot, DispatcherQueue, deviceId, ViewModel);
    }

    private void ViewAll_Click(object sender, RoutedEventArgs e) => ModernShellPage.Open("Transfers");

    // ── Phone tools ──────────────────────────────────────────────────────────────────

    private void Webcam_Click(object sender, RoutedEventArgs e)
    {
        if (ConnectedId is { } id) VeyroContainer.Instance.Mirror.OpenCamera(id);
    }

    private async void SpeakerSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_settingSpeaker || ConnectedId is not { } id) return;
        var speaker = VeyroContainer.Instance.Speaker;
        bool mine = string.Equals(speaker.PeerId, id, StringComparison.OrdinalIgnoreCase);
        if (SpeakerSwitch.IsOn == mine) return;
        if (mine)
            await speaker.StopAsync();
        else if (await speaker.StartAsync(id) is { } problem)
            VeyroContainer.Instance.NotificationToastService.ShowStatus("PC sound not played on the phone", problem, "speaker");
        RefreshTools();
    }

    private void TakePhoto_Click(object sender, RoutedEventArgs e)
    {
        if (ConnectedId is { } id) _ = VeyroContainer.Instance.PhoneCapture.TakeAsync(id, scan: false);
    }

    private void Scan_Click(object sender, RoutedEventArgs e)
    {
        if (ConnectedId is { } id) _ = VeyroContainer.Instance.PhoneCapture.TakeAsync(id, scan: true);
    }

    // ── Phone controls: a toggle shows the reported state until the phone confirms ──

    private async void PhoneDnd_Click(object sender, RoutedEventArgs e)
    {
        bool requested = !StatusViewModel.IsDndOn;
        PhoneDndToggle.IsChecked = StatusViewModel.IsDndOn;
        await StatusViewModel.RequestDndAsync(requested);
    }

    private async void Torch_Click(object sender, RoutedEventArgs e)
    {
        bool requested = !StatusViewModel.IsTorchOn;
        TorchToggle.IsChecked = StatusViewModel.IsTorchOn;
        await StatusViewModel.RequestTorchAsync(requested);
    }

    private async void Ring_Click(object sender, RoutedEventArgs e)
    {
        RingToggle.IsChecked = StatusViewModel.IsRinging;
        await StatusViewModel.ToggleRingAsync();
    }

    // Silent and Vibrate tiles: on picks that mode, off goes back to ringing.
    private async void SilentTile_Click(object sender, RoutedEventArgs e)
    {
        bool wasSilent = StatusViewModel.IsRingerSilent;
        SilentToggle.IsChecked = wasSilent;
        await StatusViewModel.RequestRingerAsync(wasSilent ? 2 : 0);
    }

    private async void VibrateTile_Click(object sender, RoutedEventArgs e)
    {
        bool wasVibrate = StatusViewModel.IsRingerVibrate;
        VibrateToggle.IsChecked = wasVibrate;
        await StatusViewModel.RequestRingerAsync(wasVibrate ? 2 : 1);
    }

    private async void RingerSilent_Click(object sender, RoutedEventArgs e) => await RequestRingerAsync(sender, 0);

    private async void RingerVibrate_Click(object sender, RoutedEventArgs e) => await RequestRingerAsync(sender, 1);

    private async void RingerNormal_Click(object sender, RoutedEventArgs e) => await RequestRingerAsync(sender, 2);

    private async Task RequestRingerAsync(object sender, int index)
    {
        if (sender is ToggleButton toggle) toggle.IsChecked = StatusViewModel.RingerIndex == index;
        await StatusViewModel.RequestRingerAsync(index);
    }

    // A value equal to the shown one came from the binding (the phone reported it): not a request.
    private void Volume_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        int value = (int)Math.Round(e.NewValue);
        if (value != (int)Math.Round(StatusViewModel.ShownMediaVolume)) StatusViewModel.RequestMediaVolume(value);
    }

    private void Brightness_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        int value = (int)Math.Round(e.NewValue);
        if (value != (int)Math.Round(StatusViewModel.ShownBrightness)) StatusViewModel.RequestBrightness(value);
    }

    private async void AutoBrightness_Toggled(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleSwitch toggle && toggle.IsOn != StatusViewModel.IsAutoBrightness)
            await StatusViewModel.RequestAutoBrightnessAsync(toggle.IsOn);
    }
}
