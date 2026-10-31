using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;

namespace Veyro.UserControls;

/// <summary>
/// ADR-074: the Modern Home panel. The same actions as the Classic <see cref="DeviceControlCenter"/>,
/// all visible. Tool tiles follow what the shown phone can do now (connected, capabilities, the
/// speaker's state); they are refreshed on changes and every few seconds while shown.
/// </summary>
public sealed partial class ModernDeviceHome : UserControl
{
    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public DeviceStatusViewModel StatusViewModel => VeyroContainer.Instance.DeviceStatusViewModel;

    private DispatcherQueueTimer? _refresh;

    public ModernDeviceHome()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ViewModel.PropertyChanged += OnViewModelChanged;
        VeyroContainer.Instance.Speaker.Changed += OnSpeakerChanged;
        VeyroContainer.Instance.SettingsStore.SettingsChanged += OnSettingsChanged;
        _refresh ??= CreateRefreshTimer();
        _refresh.Start();
        RefreshTools();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        ViewModel.PropertyChanged -= OnViewModelChanged;
        VeyroContainer.Instance.Speaker.Changed -= OnSpeakerChanged;
        VeyroContainer.Instance.SettingsStore.SettingsChanged -= OnSettingsChanged;
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

    private string? ConnectedId => ViewModel.Device is { IsConnected: true } device ? device.DeviceId : null;

    private void RefreshTools()
    {
        var container = VeyroContainer.Instance;
        string? id = ConnectedId;
        WebcamTile.IsEnabled = id is not null;
        bool playing = id is not null && string.Equals(container.Speaker.PeerId, id, StringComparison.OrdinalIgnoreCase);
        SpeakerTile.IsEnabled = id is not null && (playing || container.Speaker.CanUse(id));
        SpeakerTile.Style = (Style)Resources[playing ? "ToolTileActiveStyle" : "ToolTileStyle"];
        SpeakerTileText.Text = playing ? (container.Speaker.IsPlaying ? "Playing now. Click to stop" : "Starting...") : "Off";
        bool capture = container.PhoneCapture.CanUse(id);
        PhotoTile.IsEnabled = capture;
        ScanTile.IsEnabled = capture;
        PcQuietToggle.IsChecked = container.SettingsStore.Current.NotificationPopupsPaused;
    }

    // ── Phone card ───────────────────────────────────────────────────────────────────

    private void PhoneFrame_Tapped(object sender, TappedRoutedEventArgs e)
    {
        OpenMirror();
        e.Handled = true;
    }

    private void PhoneFrame_PointerWheelChanged(object sender, PointerRoutedEventArgs e)
    {
        ViewModel.SwitchToNextDevice(e.GetCurrentPoint(PhoneFrameGrid).Properties.MouseWheelDelta);
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
        {
            await speaker.StopAsync();
        }
        else if (await speaker.StartAsync(id) is { } problem)
        {
            VeyroContainer.Instance.NotificationToastService.ShowStatus("PC sound not played on the phone", problem, "speaker");
        }
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

    // ── Quick controls: the toggle shows the reported state until the phone confirms ──

    private async void Torch_Click(object sender, RoutedEventArgs e)
    {
        bool requested = !StatusViewModel.IsTorchOn;
        TorchToggle.IsChecked = StatusViewModel.IsTorchOn;
        await StatusViewModel.RequestTorchAsync(requested);
    }

    private async void PhoneDnd_Click(object sender, RoutedEventArgs e)
    {
        bool requested = !StatusViewModel.IsDndOn;
        PhoneDndToggle.IsChecked = StatusViewModel.IsDndOn;
        await StatusViewModel.RequestDndAsync(requested);
    }

    private async void Ring_Click(object sender, RoutedEventArgs e)
    {
        RingToggle.IsChecked = StatusViewModel.IsRinging;
        await StatusViewModel.ToggleRingAsync();
    }

    private async void PcQuiet_Click(object sender, RoutedEventArgs e)
    {
        bool on = PcQuietToggle.IsChecked == true;
        await VeyroContainer.Instance.SettingsStore.UpdateAsync(s => s with { NotificationPopupsPaused = on });
    }

    private async void RingerSilent_Click(object sender, RoutedEventArgs e) => await StatusViewModel.RequestRingerAsync(0);

    private async void RingerVibrate_Click(object sender, RoutedEventArgs e) => await StatusViewModel.RequestRingerAsync(1);

    private async void RingerNormal_Click(object sender, RoutedEventArgs e) => await StatusViewModel.RequestRingerAsync(2);

    // A value equal to the shown one came from the binding (the phone reported it): not a request.
    private void MediaVolume_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        int value = (int)Math.Round(e.NewValue);
        if (value != (int)Math.Round(StatusViewModel.ShownMediaVolume)) StatusViewModel.RequestMediaVolume(value);
    }

    private void Brightness_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        int value = (int)Math.Round(e.NewValue);
        if (value != (int)Math.Round(StatusViewModel.ShownBrightness)) StatusViewModel.RequestBrightness(value);
    }

    private void ReceivedFiles_Click(object sender, RoutedEventArgs e)
    {
        string dir = VeyroContainer.Instance.SettingsStore.Current.DownloadDirectory;
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo { FileName = dir, UseShellExecute = true });
    }

    private void DeviceSettings_Click(object sender, RoutedEventArgs e) => ViewModel.OpenDeviceSettings();
}
