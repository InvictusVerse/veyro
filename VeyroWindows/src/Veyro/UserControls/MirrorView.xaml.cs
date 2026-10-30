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

    /// <summary>"Clean view": only the picture, for OBS Window Capture.</summary>
    public bool CleanView
    {
        get => CleanViewToggle.IsChecked == true;
        set
        {
            CleanViewToggle.IsChecked = value;
            CleanViewMenuItem.IsChecked = value;
            Toolbar.Visibility = value ? Visibility.Collapsed : Visibility.Visible;
            UpdateAudioBar();
        }
    }

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

    private void CleanViewToggle_Click(object sender, RoutedEventArgs e) => CleanView = CleanViewToggle.IsChecked == true;

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
