// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/UserControls/DeviceControlCenter.xaml.cs, GPL-3.0.
// Modified for Veyro.

using System.Collections.Specialized;
using System.Numerics;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.Foundation;
using Windows.Storage;

namespace Veyro.UserControls;

public sealed partial class DeviceControlCenter : UserControl
{
    private const string ScrollTipShownKey = "PhoneFrameScrollTipShown";
    private Storyboard? _currentOverlayAnimation;

    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public DeviceStatusViewModel StatusViewModel => VeyroContainer.Instance.DeviceStatusViewModel;
    public PhoneMediaViewModel MediaViewModel => VeyroContainer.Instance.PhoneMediaViewModel;
    public string ScreenMirrorText => MainPageViewModel.ScreenMirrorText;

    /// <summary>Why the Media flyout is empty.</summary>
    public string MediaEmptyText(bool connected) => connected
        ? "Nothing is playing on the phone."
        : "Media controls work while the phone is connected.";

    public DeviceControlCenter()
    {
        InitializeComponent();
        FadeOutStoryboard.Completed += OnFadeOutStoryboardCompleted;
        ViewModel.PairedDevices.CollectionChanged += OnPairedDevicesChanged;
        PhoneFrameScrollTeachingTip.Closed += (_, _) => ScrollTipShown = true;
        Unloaded += OnUnloaded;
    }

    private static bool ScrollTipShown
    {
        get
        {
            try { return ApplicationData.Current.LocalSettings.Values[ScrollTipShownKey] is true; }
            catch (InvalidOperationException) { return true; } // no package identity: skip the tip
        }
        set
        {
            try { ApplicationData.Current.LocalSettings.Values[ScrollTipShownKey] = value; }
            catch (InvalidOperationException) { }
        }
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        Unloaded -= OnUnloaded;
        FadeOutStoryboard.Completed -= OnFadeOutStoryboardCompleted;
        ViewModel.PairedDevices.CollectionChanged -= OnPairedDevicesChanged;
    }

    private void OnPairedDevicesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        if (ScrollTipShown || ViewModel.PairedDevices.Count <= 1 || PhoneFrameScrollTeachingTip.IsOpen) return;
        PhoneFrameScrollTeachingTip.IsOpen = true;
    }

    private void PhoneFrame_PointerWheelChanged(object sender, PointerRoutedEventArgs e)
    {
        ViewModel.SwitchToNextDevice(e.GetCurrentPoint(PhoneFrameGrid).Properties.MouseWheelDelta);
        e.Handled = true;
    }

    private void PhoneFrame_PointerEntered(object sender, PointerRoutedEventArgs e) => AnimateOverlay(true);

    // ADR-073: the More menu shows what the shown phone can do now.
    private void DeviceActions_Opening(object sender, object e)
    {
        var container = VeyroContainer.Instance;
        string? id = ViewModel.Device is { IsConnected: true } device ? device.DeviceId : null;
        bool playing = id is not null && string.Equals(container.Speaker.PeerId, id, StringComparison.OrdinalIgnoreCase);
        SpeakerMenuItem.Text = playing ? "Stop PC sound on phone" : "Play PC sound on phone";
        SpeakerMenuItem.IsEnabled = id is not null && (playing || container.Speaker.CanUse(id));
        bool capture = container.PhoneCapture.CanUse(id);
        TakePhotoMenuItem.IsEnabled = capture;
        ScanMenuItem.IsEnabled = capture;
    }

    private async void Speaker_Click(object sender, RoutedEventArgs e)
    {
        var speaker = VeyroContainer.Instance.Speaker;
        if (ViewModel.Device is not { } device) return;
        if (string.Equals(speaker.PeerId, device.DeviceId, StringComparison.OrdinalIgnoreCase))
        {
            await speaker.StopAsync();
            return;
        }
        if (await speaker.StartAsync(device.DeviceId) is { } problem)
            VeyroContainer.Instance.NotificationToastService.ShowStatus("PC sound not played on the phone", problem, "speaker");
    }

    private void TakePhoto_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is { } device) _ = VeyroContainer.Instance.PhoneCapture.TakeAsync(device.DeviceId, scan: false);
    }

    private void Scan_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is { } device) _ = VeyroContainer.Instance.PhoneCapture.TakeAsync(device.DeviceId, scan: true);
    }

    // ADR-067: the camera window (and the virtual camera, when it is set up).
    private void PhoneCamera_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is { } device) VeyroContainer.Instance.Mirror.OpenCamera(device.DeviceId);
    }

    // ADR-066: the phone frame opens its mirror window (Sefirah starts scrcpy here).
    private void PhoneFrame_Tapped(object sender, TappedRoutedEventArgs e)
    {
        if (ViewModel.Device is not { } device) return;
        VeyroContainer.Instance.Mirror.Open(device.DeviceId);
        e.Handled = true;
    }

    private void PhoneFrame_PointerExited(object sender, PointerRoutedEventArgs e) => AnimateOverlay(false);

    private async void RingerSilent_Click(object sender, RoutedEventArgs e) => await RequestRingerAsync(sender, 0);

    private async void RingerVibrate_Click(object sender, RoutedEventArgs e) => await RequestRingerAsync(sender, 1);

    private async void RingerNormal_Click(object sender, RoutedEventArgs e) => await RequestRingerAsync(sender, 2);

    /// <summary>Shows the reported mode until the phone confirms the change in its next devStatus.</summary>
    private async Task RequestRingerAsync(object sender, int index)
    {
        if (sender is ToggleButton toggle) toggle.IsChecked = StatusViewModel.RingerIndex == index;
        await StatusViewModel.RequestRingerAsync(index);
    }

    private async void Dnd_Click(object sender, RoutedEventArgs e)
    {
        bool requested = !StatusViewModel.IsDndOn;
        DndToggle.IsChecked = StatusViewModel.IsDndOn;
        await StatusViewModel.RequestDndAsync(requested);
    }

    private async void Ring_Click(object sender, RoutedEventArgs e)
    {
        RingToggle.IsChecked = StatusViewModel.IsRinging;
        await StatusViewModel.ToggleRingAsync();
    }

    // ADR-060. Like DND: the toggle shows the reported state until the phone's devStatus confirms.
    private async void Torch_Click(object sender, RoutedEventArgs e)
    {
        bool requested = !StatusViewModel.IsTorchOn;
        TorchToggle.IsChecked = StatusViewModel.IsTorchOn;
        await StatusViewModel.RequestTorchAsync(requested);
    }

    // A value equal to the shown one came from the binding (the phone reported it): not a request.
    private void MediaVolumeSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        int value = (int)Math.Round(e.NewValue);
        if (value != (int)Math.Round(StatusViewModel.ShownMediaVolume)) StatusViewModel.RequestMediaVolume(value);
    }

    private void BrightnessSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        int value = (int)Math.Round(e.NewValue);
        if (value != (int)Math.Round(StatusViewModel.ShownBrightness)) StatusViewModel.RequestBrightness(value);
    }

    private async void AutoBrightness_Toggled(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleSwitch toggle && toggle.IsOn != StatusViewModel.IsAutoBrightness)
            await StatusViewModel.RequestAutoBrightnessAsync(toggle.IsOn);
    }

    private async void MediaPrevious_Click(object sender, RoutedEventArgs e) => await MediaViewModel.PreviousAsync();

    private async void MediaPlayPause_Click(object sender, RoutedEventArgs e) => await MediaViewModel.TogglePlayPauseAsync();

    private async void MediaNext_Click(object sender, RoutedEventArgs e) => await MediaViewModel.NextAsync();

    /// <summary>ADR-038: straight to the system file picker; sending starts as soon as files are chosen.</summary>
    private async void SendFilesAction_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is null) return;
        await App.PickAndSendFilesAsync();
    }

    /// <summary>ADR-064: the folder picker; the whole folder goes as soon as one is chosen.</summary>
    private async void SendFolderAction_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.Device is null) return;
        await App.PickAndSendFolderAsync();
    }

    private void OpenReceivedFolderAction_Click(object sender, RoutedEventArgs e)
    {
        string dir = VeyroContainer.Instance.SettingsStore.Current.DownloadDirectory;
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo { FileName = dir, UseShellExecute = true });
    }

    private void OpenDeviceSettingsAction_Click(object sender, RoutedEventArgs e) => ViewModel.OpenDeviceSettings();

    private async void RefreshConnection_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshDiscoveryAsync();

    private void PaneFlyout_Opened(object sender, object e)
    {
        if (sender is Flyout flyout) CenterFlyout(flyout);
    }

    /// <summary>Space kept between a flyout and the window's edges.</summary>
    private const double FlyoutEdgeMargin = 12;

    /// <summary>
    /// Centres a flyout on the sidebar pane, as Sefirah does, but never past the window's edges:
    /// a flyout wider than the pane used to be centred out over the left edge (the brightness one
    /// was cut off when Veyro sat at the screen's left). It is kept <see cref="FlyoutEdgeMargin"/>
    /// inside the window instead.
    /// </summary>
    private void CenterFlyout(Flyout flyout)
    {
        if ((flyout.Content as FrameworkElement)?.Parent is not FlyoutPresenter presenter) return;
        if (Root.ActualWidth <= 0 || presenter.ActualWidth <= 0) return;
        presenter.Translation = Vector3.Zero;
        try
        {
            Rect pane = Root.TransformToVisual(presenter).TransformBounds(new Rect(0, 0, Root.ActualWidth, Root.ActualHeight));
            double offsetX = pane.X + (pane.Width / 2) - (presenter.ActualWidth / 2);
            if (XamlRoot?.Content is FrameworkElement window && window.ActualWidth > 0)
            {
                Rect bounds = window.TransformToVisual(presenter).TransformBounds(new Rect(0, 0, window.ActualWidth, window.ActualHeight));
                double min = bounds.X + FlyoutEdgeMargin;
                double max = bounds.X + bounds.Width - presenter.ActualWidth - FlyoutEdgeMargin;
                offsetX = max < min ? min : Math.Clamp(offsetX, min, max);
            }
            presenter.Translation = new Vector3((float)offsetX, 0, 0);
        }
        catch (InvalidOperationException)
        {
            // Not in the same visual tree yet: leave the default placement.
        }
    }

    private void AnimateOverlay(bool show)
    {
        _currentOverlayAnimation?.Stop();
        _currentOverlayAnimation = null;
        if (show)
        {
            PhoneFrameOverlay.Visibility = Visibility.Visible;
            _currentOverlayAnimation = FadeInStoryboard;
            FadeInStoryboard.Begin();
        }
        else
        {
            _currentOverlayAnimation = FadeOutStoryboard;
            FadeOutStoryboard.Begin();
        }
    }

    private void OnFadeOutStoryboardCompleted(object? sender, object e)
    {
        PhoneFrameOverlay.Visibility = Visibility.Collapsed;
        _currentOverlayAnimation = null;
    }
}
