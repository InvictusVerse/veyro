using System.Collections.Specialized;
using System.Numerics;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.Foundation;
using Windows.Storage;

namespace Veyro.UserControls;

/// <summary>
/// The phone panel of the main window's sidebar: the phone picture, its status, the connection
/// pill, the "more" menu and the quick-control tiles. A toggle tile never decides its own state:
/// the click puts it back to what the phone reported and sends a request; the phone's next
/// <c>devStatus</c> then moves it (ADR-026, ADR-060).
/// </summary>
public sealed partial class DeviceControlCenter : UserControl
{
    private const string TipSeenKey = "PhoneFrameScrollTipShown";

    /// <summary>Gap kept between a flyout and the window's edges.</summary>
    private const double FlyoutEdgeGap = 12;

    private Storyboard? _runningHint;

    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public DeviceStatusViewModel StatusViewModel => VeyroContainer.Instance.DeviceStatusViewModel;
    public PhoneMediaViewModel MediaViewModel => VeyroContainer.Instance.PhoneMediaViewModel;
    public string ScreenMirrorText => MainPageViewModel.ScreenMirrorText;

    /// <summary>What the Media flyout says when there is no session to show.</summary>
    public string NoMediaMessage(bool connected) => connected
        ? "Nothing is playing on the phone."
        : "Media controls work while the phone is connected.";

    public DeviceControlCenter()
    {
        InitializeComponent();
        SwitchPhoneTip.Closed += (_, _) => TipSeen = true;
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        MirrorHintHide.Completed += OnMirrorHintHidden;
        ViewModel.PairedDevices.CollectionChanged += OnPairedPhonesChanged;
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        MirrorHintHide.Completed -= OnMirrorHintHidden;
        ViewModel.PairedDevices.CollectionChanged -= OnPairedPhonesChanged;
    }

    // ---- "Switch phones" tip -------------------------------------------------------------

    /// <summary>Whether the tip was shown before. Without package identity there is no store: skip the tip.</summary>
    private static bool TipSeen
    {
        get
        {
            try { return ApplicationData.Current.LocalSettings.Values[TipSeenKey] is true; }
            catch (InvalidOperationException) { return true; }
        }
        set
        {
            try { ApplicationData.Current.LocalSettings.Values[TipSeenKey] = value; }
            catch (InvalidOperationException) { }
        }
    }

    private void OnPairedPhonesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        if (TipSeen || SwitchPhoneTip.IsOpen || ViewModel.PairedDevices.Count <= 1) return;
        SwitchPhoneTip.IsOpen = true;
    }

    // ---- The phone picture ---------------------------------------------------------------

    private void PhoneScreen_PointerWheelChanged(object sender, PointerRoutedEventArgs e)
    {
        ViewModel.SwitchToNextDevice(e.GetCurrentPoint(PhoneScreen).Properties.MouseWheelDelta);
        e.Handled = true;
    }

    private void PhoneScreen_PointerEntered(object sender, PointerRoutedEventArgs e) => FadeMirrorHint(visible: true);

    private void PhoneScreen_PointerExited(object sender, PointerRoutedEventArgs e) => FadeMirrorHint(visible: false);

    /// <summary>ADR-066: a tap on the phone opens its mirror window.</summary>
    private void PhoneScreen_Tapped(object sender, TappedRoutedEventArgs e)
    {
        if (ViewModel.Device is not { } device) return;
        VeyroContainer.Instance.Mirror.Open(device.DeviceId);
        e.Handled = true;
    }

    private void FadeMirrorHint(bool visible)
    {
        _runningHint?.Stop();
        if (visible) MirrorHint.Visibility = Visibility.Visible;
        _runningHint = visible ? MirrorHintShow : MirrorHintHide;
        _runningHint.Begin();
    }

    private void OnMirrorHintHidden(object? sender, object e)
    {
        MirrorHint.Visibility = Visibility.Collapsed;
        _runningHint = null;
    }

    // ---- Connection row and "more" menu --------------------------------------------------

    private async void Reconnect_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshDiscoveryAsync();

    /// <summary>ADR-038: straight to the system file picker; sending starts once files are chosen.</summary>
    private async void SendFiles_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is null) return;
        await App.PickAndSendFilesAsync();
    }

    /// <summary>ADR-064: the folder picker; the whole folder goes as soon as one is chosen.</summary>
    private async void SendFolder_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is null) return;
        await App.PickAndSendFolderAsync();
    }

    private void OpenReceived_Click(object sender, RoutedEventArgs e)
    {
        string folder = VeyroContainer.Instance.SettingsStore.Current.DownloadDirectory;
        Directory.CreateDirectory(folder);
        Process.Start(new ProcessStartInfo { FileName = folder, UseShellExecute = true });
    }

    private void DeviceSettings_Click(object sender, RoutedEventArgs e) => ViewModel.OpenDeviceSettings();

    /// <summary>ADR-073: enable only what the shown phone can do right now.</summary>
    private void MoreMenu_Opening(object sender, object e)
    {
        var container = VeyroContainer.Instance;
        string? phoneId = ViewModel.Device is { IsConnected: true } device ? device.DeviceId : null;

        bool soundPlaying = phoneId is not null
            && string.Equals(container.Speaker.PeerId, phoneId, StringComparison.OrdinalIgnoreCase);
        SpeakerItem.Text = soundPlaying ? "Stop PC sound on phone" : "Play PC sound on phone";
        SpeakerItem.IsEnabled = phoneId is not null && (soundPlaying || container.Speaker.CanUse(phoneId));

        bool canCapture = container.PhoneCapture.CanUse(phoneId);
        PhotoItem.IsEnabled = canCapture;
        ScanItem.IsEnabled = canCapture;
    }

    private async void Speaker_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is not { } device) return;
        var speaker = VeyroContainer.Instance.Speaker;
        if (string.Equals(speaker.PeerId, device.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            await speaker.StopAsync();
            return;
        }
        if (await speaker.StartAsync(device.DeviceId) is { } problem)
            VeyroContainer.Instance.NotificationToastService.ShowStatus("PC sound not played on the phone", problem, "speaker");
    }

    private void TakePhoto_Click(object sender, RoutedEventArgs e) => Capture(scan: false);

    private void ScanDocument_Click(object sender, RoutedEventArgs e) => Capture(scan: true);

    private void Capture(bool scan)
    {
        if (ViewModel.Device is { } device) _ = VeyroContainer.Instance.PhoneCapture.TakeAsync(device.DeviceId, scan);
    }

    /// <summary>ADR-067: the camera window (and the virtual camera once it is set up).</summary>
    private void Webcam_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is { } device) VeyroContainer.Instance.Mirror.OpenCamera(device.DeviceId);
    }

    // ---- Toggle tiles: show the reported state, send a request ---------------------------

    private async void DndTile_Click(object sender, RoutedEventArgs e)
    {
        bool wanted = !StatusViewModel.IsDndOn;
        DndTile.IsChecked = StatusViewModel.IsDndOn;
        await StatusViewModel.RequestDndAsync(wanted);
    }

    private async void FindTile_Click(object sender, RoutedEventArgs e)
    {
        FindTile.IsChecked = StatusViewModel.IsRinging;
        await StatusViewModel.ToggleRingAsync();
    }

    /// <summary>ADR-060.</summary>
    private async void TorchTile_Click(object sender, RoutedEventArgs e)
    {
        bool wanted = !StatusViewModel.IsTorchOn;
        TorchTile.IsChecked = StatusViewModel.IsTorchOn;
        await StatusViewModel.RequestTorchAsync(wanted);
    }

    /// <summary>The three buttons in the Sound flyout carry their position (0 silent, 1 vibrate, 2 ring) in Tag.</summary>
    private async void RingerMode_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not ToggleButton button || !int.TryParse(button.Tag as string, out int index)) return;
        button.IsChecked = StatusViewModel.RingerIndex == index;
        await StatusViewModel.RequestRingerAsync(index);
    }

    // ---- Sliders (ADR-060) ---------------------------------------------------------------

    // A value equal to the shown one came from the binding (the phone reported it): not a request.
    private void VolumeSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        int percent = (int)Math.Round(e.NewValue);
        if (percent != (int)Math.Round(StatusViewModel.ShownMediaVolume)) StatusViewModel.RequestMediaVolume(percent);
    }

    private void BrightnessSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        int percent = (int)Math.Round(e.NewValue);
        if (percent != (int)Math.Round(StatusViewModel.ShownBrightness)) StatusViewModel.RequestBrightness(percent);
    }

    private async void AutoBrightness_Toggled(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleSwitch toggle && toggle.IsOn != StatusViewModel.IsAutoBrightness)
            await StatusViewModel.RequestAutoBrightnessAsync(toggle.IsOn);
    }

    // ---- Media flyout --------------------------------------------------------------------

    private async void MediaPrevious_Click(object sender, RoutedEventArgs e) => await MediaViewModel.PreviousAsync();

    private async void MediaPlayPause_Click(object sender, RoutedEventArgs e) => await MediaViewModel.TogglePlayPauseAsync();

    private async void MediaNext_Click(object sender, RoutedEventArgs e) => await MediaViewModel.NextAsync();

    // ---- Flyout placement ----------------------------------------------------------------

    private void SidebarFlyout_Opened(object sender, object e)
    {
        if (sender is Flyout flyout) CentreOnSidebar(flyout);
    }

    /// <summary>
    /// Centres an opened flyout on this panel, but never lets it cross the window edge: a flyout
    /// wider than the sidebar is shifted so it stays <see cref="FlyoutEdgeGap"/> inside the
    /// window (a centred one used to be cut off when Veyro sat at the screen's left).
    /// </summary>
    private void CentreOnSidebar(Flyout flyout)
    {
        if ((flyout.Content as FrameworkElement)?.Parent is not FlyoutPresenter presenter) return;
        if (PanelRoot.ActualWidth <= 0 || presenter.ActualWidth <= 0) return;

        presenter.Translation = Vector3.Zero;
        try
        {
            Rect panel = PanelRoot.TransformToVisual(presenter)
                .TransformBounds(new Rect(0, 0, PanelRoot.ActualWidth, PanelRoot.ActualHeight));
            double shift = panel.X + (panel.Width - presenter.ActualWidth) / 2;

            if (XamlRoot?.Content is FrameworkElement windowContent && windowContent.ActualWidth > 0)
            {
                Rect window = windowContent.TransformToVisual(presenter)
                    .TransformBounds(new Rect(0, 0, windowContent.ActualWidth, windowContent.ActualHeight));
                double leftmost = window.X + FlyoutEdgeGap;
                double rightmost = window.Right - presenter.ActualWidth - FlyoutEdgeGap;
                shift = rightmost < leftmost ? leftmost : Math.Clamp(shift, leftmost, rightmost);
            }

            presenter.Translation = new Vector3((float)shift, 0, 0);
        }
        catch (InvalidOperationException)
        {
            // The flyout is not in the same visual tree yet: keep the default placement.
        }
    }
}
