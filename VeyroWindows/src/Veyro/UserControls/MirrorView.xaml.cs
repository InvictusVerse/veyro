using Veyro.Core.Mirroring;
using Veyro.Services;

namespace Veyro.UserControls;

/// <summary>
/// The mirror window's content (ADR-066): the phone's picture, its status (asking, declined,
/// stopped, each with Ask again), the phone-sound bar (denied or blocked, with Ask again), and
/// the PC-side controls. Every quality control saves to this PC's settings and applies to the
/// live session at once. All on the UI thread.
/// </summary>
public sealed partial class MirrorView : UserControl
{
    private readonly ISettingsStore _settings;
    private MirrorController? _controller;
    private bool _hasPicture;
    private bool _updating;

    public MirrorView(ISettingsStore settings)
    {
        _settings = settings;
        InitializeComponent();
        foreach (var preset in MirrorQuality.Presets) QualityCombo.Items.Add(new ComboBoxItem { Content = preset.Label, Tag = preset.Key });
        LoadSettings();
    }

    /// <summary>The element the controller's player renders into.</summary>
    public MediaPlayerElement PlayerElement => Player;

    /// <summary>The user pressed Close in the status panel.</summary>
    public event Action? CloseRequested;

    /// <summary>Asks the window to enter or leave full screen.</summary>
    public event Action<bool>? FullScreenRequested;

    private bool _cleanView;
    private bool _fullScreen;
    private DispatcherQueueTimer? _overlayTimer;

    /// <summary>"Clean view": only the picture, for OBS Window Capture.</summary>
    public bool CleanView
    {
        get => _cleanView;
        set
        {
            _cleanView = value;
            CleanViewMenuItem.IsChecked = value;
            ApplyChrome();
        }
    }

    /// <summary>Set by the window once its presenter changed.</summary>
    public bool FullScreen
    {
        get => _fullScreen;
        set
        {
            _fullScreen = value;
            FullScreenMenuItem.IsChecked = value;
            ApplyChrome();
            if (value) ShowOverlayBriefly();
        }
    }

    /// <summary>Controls hidden in full screen and Clean view; the exit button shows while the mouse moves.</summary>
    private void ApplyChrome()
    {
        bool hidden = _cleanView || _fullScreen;
        Toolbar.Visibility = hidden ? Visibility.Collapsed : Visibility.Visible;
        ExitOverlayText.Text = _fullScreen ? "Leave full screen (Esc)" : "Show controls (Esc)";
        if (!hidden) ExitOverlay.Visibility = Visibility.Collapsed;
        UpdateAudioBar();
    }

    private void ShowOverlayBriefly()
    {
        if (!_cleanView && !_fullScreen) return;
        ExitOverlay.Visibility = Visibility.Visible;
        _overlayTimer ??= CreateOverlayTimer();
        _overlayTimer.Stop();
        _overlayTimer.Start();
    }

    private DispatcherQueueTimer CreateOverlayTimer()
    {
        DispatcherQueueTimer timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromSeconds(2.5);
        timer.IsRepeating = false;
        // Kept while the pointer is on the button itself, so it can be clicked.
        timer.Tick += (_, _) => { if (!ExitOverlay.IsPointerOver) ExitOverlay.Visibility = Visibility.Collapsed; else timer.Start(); };
        return timer;
    }

    /// <summary>Back to the normal window with controls.</summary>
    private void LeaveImmersive()
    {
        if (_fullScreen) FullScreenRequested?.Invoke(false);
        if (_cleanView) CleanView = false;
    }

    private void Root_PointerMoved(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e) => ShowOverlayBriefly();

    private void Root_DoubleTapped(object sender, Microsoft.UI.Xaml.Input.DoubleTappedRoutedEventArgs e)
    {
        if (e.OriginalSource is FrameworkElement { } source && IsInside(source, Toolbar)) return;
        FullScreenRequested?.Invoke(!_fullScreen);
        e.Handled = true;
    }

    private static bool IsInside(DependencyObject element, DependencyObject container)
    {
        for (DependencyObject? d = element; d is not null; d = Microsoft.UI.Xaml.Media.VisualTreeHelper.GetParent(d))
            if (ReferenceEquals(d, container)) return true;
        return false;
    }

    private void Escape_Invoked(Microsoft.UI.Xaml.Input.KeyboardAccelerator sender, Microsoft.UI.Xaml.Input.KeyboardAcceleratorInvokedEventArgs args)
    {
        if (!_fullScreen && !_cleanView) return;
        LeaveImmersive();
        args.Handled = true;
    }

    private void F11_Invoked(Microsoft.UI.Xaml.Input.KeyboardAccelerator sender, Microsoft.UI.Xaml.Input.KeyboardAcceleratorInvokedEventArgs args)
    {
        FullScreenRequested?.Invoke(!_fullScreen);
        args.Handled = true;
    }

    private void ExitOverlay_Click(object sender, RoutedEventArgs e) => LeaveImmersive();

    private void FullScreen_Click(object sender, RoutedEventArgs e) => FullScreenRequested?.Invoke(true);

    private void FullScreenMenuItem_Click(object sender, RoutedEventArgs e) => FullScreenRequested?.Invoke(FullScreenMenuItem.IsChecked);

    private async void QuietToggle_Click(object sender, RoutedEventArgs e)
    {
        bool on = QuietToggle.IsChecked == true;
        await _settings.UpdateAsync(s => s with { NotificationPopupsPaused = on });
    }

    /// <summary>Follows Do not disturb when it is changed elsewhere (tray, Notifications page).</summary>
    public void RefreshQuiet() => QuietToggle.IsChecked = _settings.Current.NotificationPopupsPaused;

    public void Attach(MirrorController controller)
    {
        _controller = controller;
        controller.StatusChanged += OnStatusChanged;
        controller.PictureShown += OnPictureShown;
        OnStatusChanged(controller.Status);
    }

    public void Detach()
    {
        if (_controller is null) return;
        _controller.StatusChanged -= OnStatusChanged;
        _controller.PictureShown -= OnPictureShown;
        _controller = null;
    }

    /// <summary>The window title used in the OBS steps.</summary>
    public void SetWindowTitle(string title) => ObsWindowRun.Text = $"2. Window: [Veyro.exe] {title}";

    private void LoadSettings()
    {
        _updating = true;
        try
        {
            AppSettings current = _settings.Current;
            var keys = MirrorQuality.Presets.Select(p => p.Key).ToList();
            int index = keys.IndexOf(current.MirrorQualityPreset);
            if (index < 0) index = keys.IndexOf("balanced");
            QualityCombo.SelectedIndex = index;
            QualityShortText.Text = ShortLabel(MirrorQuality.Presets[index].Key);
            HardwareSwitch.IsOn = current.MirrorHardwareEncode;
            SoundToggle.IsChecked = current.MirrorAudio;
            SoundIcon.Glyph = current.MirrorAudio ? "\uE767" : "\uE74F";
            QuietToggle.IsChecked = current.NotificationPopupsPaused;
        }
        finally
        {
            _updating = false;
        }
    }

    private static string ShortLabel(string key) => key switch
    {
        "battery" => "Battery saver",
        "high" => "High",
        "4k" => "4K",
        _ => "Balanced",
    };

    private void OnPictureShown()
    {
        _hasPicture = true;
        if (_controller is { } controller) OnStatusChanged(controller.Status);
    }

    private void OnStatusChanged(MirrorStatus status)
    {
        if (status.Phase != MirrorPhase.Streaming && status.Phase != MirrorPhase.Paused) _hasPicture = false;
        bool waitingForPicture = status.Phase == MirrorPhase.Streaming && !_hasPicture;
        bool showPanel = status.Phase != MirrorPhase.Streaming || waitingForPicture;
        StatusPanel.Visibility = showPanel ? Visibility.Visible : Visibility.Collapsed;
        bool busy = status.Phase is MirrorPhase.Starting or MirrorPhase.Asking or MirrorPhase.Connecting || waitingForPicture;
        StatusRing.IsActive = busy;
        StatusRing.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        StatusIcon.Visibility = busy ? Visibility.Collapsed : Visibility.Visible;
        StatusIcon.Glyph = status.Phase switch
        {
            MirrorPhase.Paused => "\uE769",
            MirrorPhase.Stopped or MirrorPhase.Unavailable => "\uE7BA",
            _ => "\uE8EA",
        };
        StatusTitle.Text = waitingForPicture ? $"Connected to {status.Title}" : status.Title;
        string? detail = waitingForPicture ? "Waiting for the first picture." : status.Detail;
        StatusDetail.Text = detail ?? "";
        StatusDetail.Visibility = string.IsNullOrEmpty(detail) ? Visibility.Collapsed : Visibility.Visible;

        AskAgainButton.Visibility = status.CanAskAgain ? Visibility.Visible : Visibility.Collapsed;
        AskAgainButton.Content = status.AskAgainLabel;
        AskAgainButton.IsEnabled = _controller?.MayAskAgain == true;
        CloseButton.Visibility = status.CanAskAgain ? Visibility.Visible : Visibility.Collapsed;
        StopButton.IsEnabled = status.IsLive;
        // The hardware switch only means something when the phone has both kinds of encoder.
        HardwareSwitch.Visibility = _controller?.PhoneHasEncoderChoice == true ? Visibility.Visible : Visibility.Collapsed;
        UpdateAudioBar();
    }

    private void UpdateAudioBar()
    {
        if (_controller is not { } controller) return;
        MirrorStatus status = controller.Status;
        bool shown = !CleanView && status.Phase is MirrorPhase.Streaming or MirrorPhase.Paused && status.AudioText is not null;
        AudioInfoBar.IsOpen = shown;
        if (!shown) return;
        AudioInfoBar.Title = status.Audio switch
        {
            PhoneAudioState.Denied => "Phone sound denied",
            PhoneAudioState.Blocked => "Phone sound blocked",
            PhoneAudioState.Unsupported => "No phone sound",
            _ => "Phone sound",
        };
        AudioInfoBar.Message = status.AudioText;
        AudioInfoBar.Severity = status.Audio switch
        {
            PhoneAudioState.Denied or PhoneAudioState.Blocked => InfoBarSeverity.Warning,
            _ => InfoBarSeverity.Informational,
        };
        AudioAskButton.Visibility = status.CanAskAudioAgain ? Visibility.Visible : Visibility.Collapsed;
        AudioAskButton.IsEnabled = controller.MayAskAudioAgain;
        AudioAskButton.Content = status.Audio == PhoneAudioState.Blocked ? "Open settings on the phone" : "Ask again";
    }

    private async void QualityCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updating || QualityCombo.SelectedItem is not ComboBoxItem { Tag: string key }) return;
        QualityShortText.Text = ShortLabel(key);
        await _settings.UpdateAsync(s => s with { MirrorQualityPreset = key });
        if (_controller is { } controller) await controller.OnQualityChangedAsync();
    }

    private async void HardwareSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        bool on = HardwareSwitch.IsOn;
        await _settings.UpdateAsync(s => s with { MirrorHardwareEncode = on });
        if (_controller is { } controller) await controller.OnQualityChangedAsync();
    }

    private async void SoundToggle_Click(object sender, RoutedEventArgs e)
    {
        bool on = SoundToggle.IsChecked == true;
        SoundIcon.Glyph = on ? "\uE767" : "\uE74F";
        await _settings.UpdateAsync(s => s with { MirrorAudio = on });
        if (_controller is { } controller) await controller.OnAudioChangedAsync();
    }


    private void CleanViewMenuItem_Click(object sender, RoutedEventArgs e) => CleanView = CleanViewMenuItem.IsChecked;

    private void CleanViewOn_Click(object sender, RoutedEventArgs e) => CleanView = true;

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        if (_controller is { } controller) await controller.StopAsync();
    }

    private async void AskAgain_Click(object sender, RoutedEventArgs e)
    {
        if (_controller is { } controller) await controller.AskAgainAsync();
    }

    private async void AudioAsk_Click(object sender, RoutedEventArgs e)
    {
        if (_controller is { } controller) await controller.AskAudioAgainAsync();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke();
}
