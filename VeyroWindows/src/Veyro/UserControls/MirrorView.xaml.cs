using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Media;
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

    private readonly bool _camera;
    private string? _cameraSignature;

    /// <param name="camera">ADR-067: the phone's camera: lens, resolution, frame rate and mirror instead of quality and sound.</param>
    public MirrorView(ISettingsStore settings, bool camera = false)
    {
        _settings = settings;
        _camera = camera;
        InitializeComponent();
        foreach (var preset in MirrorQuality.Presets) QualityCombo.Items.Add(new ComboBoxItem { Content = preset.Label, Tag = preset.Key });
        if (camera)
        {
            SettingsTitle.Text = "Camera on this PC";
            QualityCombo.Visibility = Visibility.Collapsed;
            SoundToggle.Visibility = Visibility.Collapsed;
            LensCombo.Visibility = Visibility.Visible;
            SizeCombo.Visibility = Visibility.Visible;
            FpsCombo.Visibility = Visibility.Visible;
            MirrorSwitch.Visibility = Visibility.Visible;
            QualityShortText.Text = "Camera";
            AutomationProperties.SetName(Player, "Phone camera");
            VcamPanel.Visibility = Visibility.Visible;
            RefreshVcam();
            VeyroContainer.Instance.VirtualCamera.InUseChanged += OnVcamInUse;
            Unloaded += (_, _) => VeyroContainer.Instance.VirtualCamera.InUseChanged -= OnVcamInUse;
        }
        LoadSettings();
        Toolbar.OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(200) };
        LiveChip.OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(200) };
        VcamChip.OpacityTransition = new ScalarTransition { Duration = TimeSpan.FromMilliseconds(200) };
        Toolbar.PointerEntered += (_, _) =>
        {
            _pointerOnBar = true;
            ShowBar();
        };
        Toolbar.PointerExited += (_, _) => _pointerOnBar = false;
        ApplyUiStyle();
    }

    // ── Layout (ADR-074) ─────────────────────────────────────────────────────────────

    private bool _modern;
    private bool _pointerOnBar;
    private DispatcherQueueTimer? _barTimer;

    /// <summary>
    /// Modern: the controls float over the picture at the bottom and fade out 2.5 s after the
    /// mouse stops (they stay while the pointer is on them or no picture shows). Classic: the
    /// toolbar above the picture. Called again when the setting changes.
    /// </summary>
    public void ApplyUiStyle()
    {
        _modern = !AppUiStyle.IsClassic(_settings.Current);
        Grid.SetRow(Toolbar, _modern ? 2 : 0);
        Canvas.SetZIndex(Toolbar, _modern ? 10 : 0);
        Toolbar.VerticalAlignment = _modern ? VerticalAlignment.Bottom : VerticalAlignment.Stretch;
        Toolbar.HorizontalAlignment = _modern ? HorizontalAlignment.Center : HorizontalAlignment.Stretch;
        Toolbar.Margin = _modern ? new Thickness(0, 0, 0, 20) : new Thickness(0);
        Toolbar.Padding = _modern ? new Thickness(10, 8, 10, 8) : new Thickness(8, 6, 8, 6);
        Toolbar.CornerRadius = _modern ? new CornerRadius(12) : new CornerRadius(0);
        Toolbar.BorderThickness = _modern ? new Thickness(1) : new Thickness(0, 0, 0, 1);
        Toolbar.ColumnSpacing = _modern ? 6 : 4;
        Toolbar.ColumnDefinitions[4].Width = _modern ? new GridLength(10) : new GridLength(1, GridUnitType.Star);
        Toolbar.Background = _modern
            ? new SolidColorBrush(global::Windows.UI.Color.FromArgb(0xE6, 0x1D, 0x21, 0x29))
            : (Brush)Application.Current.Resources["LayerFillColorDefaultBrush"];
        Toolbar.BorderBrush = _modern
            ? new SolidColorBrush(global::Windows.UI.Color.FromArgb(0xFF, 0x3A, 0x40, 0x50))
            : (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"];
        Toolbar.CornerRadius = _modern ? new CornerRadius(24) : new CornerRadius(0);
        Toolbar.RequestedTheme = _modern ? ElementTheme.Dark : ElementTheme.Default;
        Toolbar.Shadow = _modern ? new ThemeShadow() : null;
        Toolbar.Translation = _modern ? new System.Numerics.Vector3(0, 0, 24) : System.Numerics.Vector3.Zero;

        // The mockup's bar: icons only, a red Stop pill, Clean view on the bar.
        QualityShortText.Visibility = _modern ? Visibility.Collapsed : Visibility.Visible;
        StopText.Visibility = _modern ? Visibility.Visible : Visibility.Collapsed;
        StopButton.CornerRadius = _modern ? new CornerRadius(16) : new CornerRadius(4);
        StopButton.Padding = _modern ? new Thickness(14, 6, 16, 6) : new Thickness(11, 5, 11, 6);
        if (_modern)
        {
            StopButton.Background = new SolidColorBrush(global::Windows.UI.Color.FromArgb(0xFF, 0xC4, 0x2B, 0x1C));
            StopButton.Foreground = new SolidColorBrush(Microsoft.UI.Colors.White);
        }
        else
        {
            StopButton.ClearValue(Control.BackgroundProperty);
            StopButton.ClearValue(Control.ForegroundProperty);
        }
        CleanViewButton.Visibility = _modern ? Visibility.Visible : Visibility.Collapsed;
        MirrorImageToggle.Visibility = _camera && _modern ? Visibility.Visible : Visibility.Collapsed;

        // Waiting, declined and stopped: a centred card over the dimmed picture.
        StatusPanel.Background = new SolidColorBrush(_modern
            ? global::Windows.UI.Color.FromArgb(0x99, 0, 0, 0)
            : global::Windows.UI.Color.FromArgb(0xE6, 0, 0, 0));
        StatusCard.Background = _modern ? new SolidColorBrush(global::Windows.UI.Color.FromArgb(0xF2, 0x1D, 0x21, 0x29)) : null;
        StatusCard.Padding = _modern ? new Thickness(28, 24, 28, 24) : new Thickness(0);
        StatusCard.CornerRadius = _modern ? new CornerRadius(16) : new CornerRadius(0);
        RefreshChips();
        ShowBar();
    }

    /// <summary>Live chip ("1920x1080 · 60 fps · HW") and, for the camera, "Virtual camera: in use".</summary>
    private void RefreshChips()
    {
        string summary = _controller?.LiveSummary ?? string.Empty;
        LiveChipText.Text = summary;
        LiveChip.Visibility = _modern && _hasPicture && summary.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        VcamChip.Visibility = _modern && _camera && _hasPicture && Vcam.InUse ? Visibility.Visible : Visibility.Collapsed;
        LensButton.Visibility = _camera && _controller?.NextCameraId is not null ? Visibility.Visible : Visibility.Collapsed;
    }

    private void SetOverlayOpacity(double opacity)
    {
        Toolbar.Opacity = opacity;
        LiveChip.Opacity = opacity;
        VcamChip.Opacity = opacity;
    }

    /// <summary>Next lens: picking it in the lens list applies size, frame rate and flash as a manual pick does.</summary>
    private void Lens_Click(object sender, RoutedEventArgs e)
    {
        if (_controller?.NextCameraId is not { } next) return;
        for (int i = 0; i < LensCombo.Items.Count; i++)
        {
            if (LensCombo.Items[i] is ComboBoxItem { Tag: string id } && id == next)
            {
                LensCombo.SelectedIndex = i;
                return;
            }
        }
    }

    private void MirrorImageToggle_Click(object sender, RoutedEventArgs e) => MirrorSwitch.IsOn = MirrorImageToggle.IsChecked == true;

    private void ShowBar()
    {
        SetOverlayOpacity(1);
        Toolbar.IsHitTestVisible = true;
        if (!_modern)
        {
            _barTimer?.Stop();
            return;
        }
        _barTimer ??= CreateBarTimer();
        _barTimer.Stop();
        _barTimer.Start();
    }

    private DispatcherQueueTimer CreateBarTimer()
    {
        DispatcherQueueTimer timer = DispatcherQueue.CreateTimer();
        timer.Interval = TimeSpan.FromSeconds(2.5);
        timer.IsRepeating = false;
        timer.Tick += (_, _) =>
        {
            if (!_modern) return;
            if (_pointerOnBar || !_hasPicture || StatusPanel.Visibility == Visibility.Visible)
            {
                timer.Start();
                return;
            }
            SetOverlayOpacity(0);
            Toolbar.IsHitTestVisible = false;
        };
        return timer;
    }

    // ── Virtual camera add-on (ADR-067) ──────────────────────────────────────────────

    private static VirtualCameraService Vcam => VeyroContainer.Instance.VirtualCamera;

    private void OnVcamInUse(bool inUse)
    {
        RefreshVcam();
        RefreshChips();
    }

    private void RefreshVcam()
    {
        if (!_camera) return;
        Veyro.Platform.Media.VirtualCameraState state = Vcam.Setup.State;
        VcamStatus.Text = state switch
        {
            Veyro.Platform.Media.VirtualCameraState.Unsupported => "Needs Windows 11.",
            Veyro.Platform.Media.VirtualCameraState.Ready => Vcam.InUse
                ? "Ready. An app is using \"Veyro Phone Camera\" now."
                : "Ready. Pick \"Veyro Phone Camera\" in Zoom, Teams, Meet, Discord, OBS or the Camera app; the phone asks for its tap when an app starts using it.",
            _ => "Adds \"Veyro Phone Camera\" to Zoom, Teams, Meet, Discord, OBS and the Camera app. Needs one administrator prompt. Nothing is installed until you press Set up; Remove takes it out again.",
        };
        VcamSetUpButton.Visibility = state == Veyro.Platform.Media.VirtualCameraState.NotSetUp ? Visibility.Visible : Visibility.Collapsed;
        VcamRemoveButton.Visibility = state == Veyro.Platform.Media.VirtualCameraState.Ready ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void VcamSetUp_Click(object sender, RoutedEventArgs e)
    {
        VcamSetUpButton.IsEnabled = false;
        VcamStatus.Text = "Setting up… approve the administrator prompt.";
        string? failed = await Vcam.Setup.SetUpAsync();
        VcamSetUpButton.IsEnabled = true;
        RefreshVcam();
        if (failed is not null) VcamStatus.Text = failed;
    }

    private async void VcamRemove_Click(object sender, RoutedEventArgs e)
    {
        VcamRemoveButton.IsEnabled = false;
        VcamStatus.Text = "Removing… approve the administrator prompt.";
        string? failed = await Vcam.Setup.RemoveAsync();
        VcamRemoveButton.IsEnabled = true;
        RefreshVcam();
        if (failed is not null) VcamStatus.Text = failed;
    }

    // ── Camera (ADR-067) ─────────────────────────────────────────────────────────────

    /// <summary>Fills lens, resolution and frame rate from what the phone offers; only when the offer changed.</summary>
    private void RefreshCameraChoices()
    {
        if (!_camera || _controller?.Caps is not { } caps) return;
        var cameras = caps.Cameras ?? [];
        string signature = string.Join(";", cameras.Select(c => c.Id + ":" + string.Join(",", (c.Sizes ?? []).Select(s => $"{s.W}x{s.H}@{s.Fps}"))));
        if (signature == _cameraSignature) return;
        _cameraSignature = signature;
        _updating = true;
        try
        {
            LensCombo.Items.Clear();
            int back = 0, front = 0;
            foreach (var camera in cameras)
            {
                string name = camera.Facing switch
                {
                    "back" => ++back == 1 ? "Back camera" : $"Back camera {back}",
                    "front" => ++front == 1 ? "Front camera" : $"Front camera {front}",
                    _ => "External camera",
                };
                LensCombo.Items.Add(new ComboBoxItem { Content = name, Tag = camera.Id });
            }
            var chosen = CameraPlanner.Choose(caps, _settings.Current.WebcamCamera);
            LensCombo.SelectedIndex = chosen is null ? -1 : cameras.ToList().FindIndex(c => c.Id == chosen.Id);
            FillSizes(chosen);
        }
        finally
        {
            _updating = false;
        }
    }

    private void FillSizes(PeerMessage.StreamCamera? camera)
    {
        SizeCombo.Items.Clear();
        FpsCombo.Items.Clear();
        if (camera is null) return;
        AppSettings s = _settings.Current;
        foreach (var size in camera.Sizes ?? []) SizeCombo.Items.Add(new ComboBoxItem { Content = CameraPlanner.Label(size), Tag = size });
        var pick = CameraPlanner.ChooseSize(camera, s.WebcamWidth, s.WebcamHeight);
        SizeCombo.SelectedIndex = pick is null ? -1 : (camera.Sizes ?? []).ToList().IndexOf(pick);
        FillFps(pick);
        if (pick is not null) QualityShortText.Text = CameraPlanner.Label(pick);
    }

    private void FillFps(PeerMessage.StreamSize? size)
    {
        FpsCombo.Items.Clear();
        if (size is null) return;
        var rates = CameraPlanner.FrameRates(size);
        foreach (int fps in rates) FpsCombo.Items.Add(new ComboBoxItem { Content = $"{fps} fps", Tag = fps });
        int want = _settings.Current.WebcamFps;
        int index = rates.ToList().IndexOf(rates.Contains(want) ? want : rates[0]);
        FpsCombo.SelectedIndex = index;
    }

    private async void LensCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updating || LensCombo.SelectedItem is not ComboBoxItem { Tag: string id }) return;
        await _settings.UpdateAsync(s => s with { WebcamCamera = id });
        var camera = _controller?.Caps?.Cameras?.FirstOrDefault(c => c.Id == id);
        _updating = true;
        try { FillSizes(camera); }
        finally { _updating = false; }
        if (SizeCombo.SelectedItem is ComboBoxItem { Tag: PeerMessage.StreamSize size })
            await _settings.UpdateAsync(s => s with { WebcamWidth = size.W, WebcamHeight = size.H });
        if (_controller is { } controller)
        {
            await controller.OnQualityChangedAsync();
            // A lens without flash: the light goes off with the switch; a lens with one keeps the choice.
            if (!controller.CameraHasFlash && controller.Torch) await controller.SetTorchAsync(false);
            RefreshTorch();
        }
    }

    private async void SizeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updating || SizeCombo.SelectedItem is not ComboBoxItem { Tag: PeerMessage.StreamSize size }) return;
        QualityShortText.Text = CameraPlanner.Label(size);
        await _settings.UpdateAsync(s => s with { WebcamWidth = size.W, WebcamHeight = size.H });
        _updating = true;
        try { FillFps(size); }
        finally { _updating = false; }
        if (_controller is { } controller) await controller.OnQualityChangedAsync();
    }

    private async void FpsCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updating || FpsCombo.SelectedItem is not ComboBoxItem { Tag: int fps }) return;
        await _settings.UpdateAsync(s => s with { WebcamFps = fps });
        if (_controller is { } controller) await controller.OnQualityChangedAsync();
    }

    private async void MirrorSwitch_Toggled(object sender, RoutedEventArgs e)
    {
        if (_updating) return;
        bool on = MirrorSwitch.IsOn;
        PlayerFlip.ScaleX = on ? -1 : 1;
        MirrorImageToggle.IsChecked = on;
        await _settings.UpdateAsync(s => s with { WebcamMirror = on });
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

    private void Root_PointerMoved(object sender, Microsoft.UI.Xaml.Input.PointerRoutedEventArgs e)
    {
        ShowOverlayBriefly();
        ShowBar();
    }

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
            if (!_camera) QualityShortText.Text = ShortLabel(MirrorQuality.Presets[index].Key);
            HardwareSwitch.IsOn = current.MirrorHardwareEncode;
            SoundToggle.IsChecked = current.MirrorAudio;
            SoundIcon.Glyph = current.MirrorAudio ? "\uE767" : "\uE74F";
            QuietToggle.IsChecked = current.NotificationPopupsPaused;
            MirrorSwitch.IsOn = current.WebcamMirror;
            MirrorImageToggle.IsChecked = current.WebcamMirror;
            PlayerFlip.ScaleX = _camera && current.WebcamMirror ? -1 : 1;
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

    private void RefreshTorch()
    {
        if (!_camera || _controller is not { } controller) return;
        bool shown = controller.CameraHasFlash;
        TorchToggle.Visibility = shown ? Visibility.Visible : Visibility.Collapsed;
        TorchToggle.IsEnabled = controller.Status.IsLive;
        TorchToggle.IsChecked = shown && controller.Torch;
    }

    private async void TorchToggle_Click(object sender, RoutedEventArgs e)
    {
        if (_controller is { } controller) await controller.SetTorchAsync(TorchToggle.IsChecked == true);
    }

    private void OnStatusChanged(MirrorStatus status)
    {
        RefreshCameraChoices();
        RefreshTorch();
        RefreshChips();
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
