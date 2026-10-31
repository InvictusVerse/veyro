using System.Collections.Specialized;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;

namespace Veyro.Views;

/// <summary>
/// ADR-074: Modern Home. The same actions as the Classic control center, all visible. Tool tiles
/// follow what the shown phone can do now (connected, capabilities, the speaker's state) and are
/// refreshed on changes and every few seconds while the page is shown. Recent lists the phone's
/// newest notifications (missed calls and messages arrive there too).
/// </summary>
public sealed partial class HomePage : Page
{
    private const int RecentCount = 4;

    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public DeviceStatusViewModel StatusViewModel => VeyroContainer.Instance.DeviceStatusViewModel;
    public PhoneMediaViewModel MediaViewModel => VeyroContainer.Instance.PhoneMediaViewModel;
    public NotificationsViewModel NotificationsViewModel => VeyroContainer.Instance.NotificationsViewModel;

    public ObservableCollection<NotificationItemViewModel> Recent { get; } = [];

    private DispatcherQueueTimer? _refresh;

    public HomePage()
    {
        InitializeComponent();
        PhoneOverlay.OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(200) };
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    public string OnOff(bool on) => on ? "On" : "Off";

    private const double MaxContentWidth = 1180;

    /// <summary>The page is exactly as wide as what is visible (at most 1180), never wider.</summary>
    private void PageScroller_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        double width = Math.Min(MaxContentWidth, Math.Max(320, e.NewSize.Width));
        if (Math.Abs(PageContent.Width - width) > 0.5) PageContent.Width = width;
    }

    public string RingText(bool ringing) => ringing ? "Ringing. Click to stop" : "Ring the phone";

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        var container = VeyroContainer.Instance;
        ViewModel.PropertyChanged += OnViewModelChanged;
        container.Speaker.Changed += OnSpeakerChanged;
        container.SettingsStore.SettingsChanged += OnSettingsChanged;
        NotificationsViewModel.Notifications.CollectionChanged += OnNotificationsChanged;
        _refresh ??= CreateRefreshTimer();
        _refresh.Start();
        RefreshTools();
        RefreshRecent();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        var container = VeyroContainer.Instance;
        ViewModel.PropertyChanged -= OnViewModelChanged;
        container.Speaker.Changed -= OnSpeakerChanged;
        container.SettingsStore.SettingsChanged -= OnSettingsChanged;
        NotificationsViewModel.Notifications.CollectionChanged -= OnNotificationsChanged;
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

    private void OnSettingsChanged(AppSettings settings) => DispatcherQueue.TryEnqueue(RefreshTools);

    private void OnNotificationsChanged(object? sender, NotifyCollectionChangedEventArgs e) => DispatcherQueue.TryEnqueue(RefreshRecent);

    private void RefreshRecent()
    {
        Recent.Clear();
        foreach (NotificationItemViewModel item in NotificationsViewModel.Notifications.Take(RecentCount)) Recent.Add(item);
        RecentEmpty.Visibility = Recent.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private string? ConnectedId => ViewModel.Device is { IsConnected: true } device ? device.DeviceId : null;

    private void RefreshTools()
    {
        var container = VeyroContainer.Instance;
        string? id = ConnectedId;
        WebcamTile.IsEnabled = id is not null;

        bool mine = id is not null && string.Equals(container.Speaker.PeerId, id, StringComparison.OrdinalIgnoreCase);
        SpeakerTile.IsEnabled = id is not null && (mine || container.Speaker.CanUse(id));
        SpeakerPillText.Text = mine ? "On" : "Off";
        SpeakerPill.Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources[mine ? "AccentFillColorDefaultBrush" : "ControlFillColorSecondaryBrush"];
        SpeakerPillText.Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources[mine ? "TextOnAccentFillColorPrimaryBrush" : "TextFillColorPrimaryBrush"];
        SpeakerText.Text = !mine ? "Hear this PC through the phone" : container.Speaker.IsPlaying ? "Playing now" : "Starting on the phone...";
        SpeakerLink.Text = mine ? "Stop ›" : "Start ›";
        SpeakerTile.BorderBrush = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources[mine ? "AccentFillColorDefaultBrush" : "CardStrokeColorDefaultBrush"];

        bool capture = container.PhoneCapture.CanUse(id);
        PhotoTile.IsEnabled = capture;
        ScanTile.IsEnabled = capture;

        AppSettings s = container.SettingsStore.Current;
        QuietToggle.IsChecked = s.NotificationPopupsPaused;
        QuietText.Text = s.NotificationPopupsPaused ? "On" : s.QuietWhileMirroring ? "Auto while mirroring" : "Off";
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

    private async void Reconnect_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshDiscoveryAsync();

    private void PairPhone_Click(object sender, RoutedEventArgs e) => ViewModel.RequestPairing();

    private async void DiscoveredDevice_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is string deviceId) await PairingFlow.PairWithDiscoveredAsync(XamlRoot, DispatcherQueue, deviceId, ViewModel);
    }

    // ── Phone tools ──────────────────────────────────────────────────────────────────

    private void Webcam_Click(object sender, RoutedEventArgs e)
    {
        if (ConnectedId is { } id) VeyroContainer.Instance.Mirror.OpenCamera(id);
    }

    private async void Speaker_Click(object sender, RoutedEventArgs e)
    {
        var speaker = VeyroContainer.Instance.Speaker;
        if (ConnectedId is not { } id) return;
        if (string.Equals(speaker.PeerId, id, StringComparison.OrdinalIgnoreCase))
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

    // ── Quick controls: a toggle shows the reported state until the phone confirms ──

    private async void Torch_Click(object sender, RoutedEventArgs e)
    {
        bool requested = !StatusViewModel.IsTorchOn;
        TorchToggle.IsChecked = StatusViewModel.IsTorchOn;
        await StatusViewModel.RequestTorchAsync(requested);
    }

    private async void Quiet_Click(object sender, RoutedEventArgs e)
    {
        bool on = QuietToggle.IsChecked == true;
        await VeyroContainer.Instance.SettingsStore.UpdateAsync(s => s with { NotificationPopupsPaused = on });
    }

    private async void Ring_Click(object sender, RoutedEventArgs e)
    {
        RingToggle.IsChecked = StatusViewModel.IsRinging;
        await StatusViewModel.ToggleRingAsync();
    }

    private async void PhoneDnd_Click(object sender, RoutedEventArgs e) => await StatusViewModel.RequestDndAsync(!StatusViewModel.IsDndOn);

    private async void RingerSilent_Click(object sender, RoutedEventArgs e) => await StatusViewModel.RequestRingerAsync(0);

    private async void RingerVibrate_Click(object sender, RoutedEventArgs e) => await StatusViewModel.RequestRingerAsync(1);

    private async void RingerNormal_Click(object sender, RoutedEventArgs e) => await StatusViewModel.RequestRingerAsync(2);

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

    private void SeeAll_Click(object sender, RoutedEventArgs e) => ModernShellPage.Open("Notifications");
}
