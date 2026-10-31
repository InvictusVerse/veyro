using Microsoft.UI.Xaml.Controls.Primitives;

namespace Veyro.UserControls;

/// <summary>
/// The phone's now-playing card (ADR-026). It tells the view model when it is on screen, so the
/// playback-position ticker only runs while the card is visible, and forwards transport clicks
/// and slider input.
/// </summary>
public sealed partial class MediaSessionsControl : UserControl
{
    private Window? _hostWindow;

    public PhoneMediaViewModel ViewModel => VeyroContainer.Instance.PhoneMediaViewModel;

    public MediaSessionsControl()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    // ---- Visibility: the card is "active" while loaded in a visible window -----------------

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _hostWindow = App.MainWindow;
        if (_hostWindow is not null) _hostWindow.VisibilityChanged += OnHostVisibilityChanged;
        ViewModel.SetViewActive(_hostWindow?.Visible ?? true);
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        if (_hostWindow is not null) _hostWindow.VisibilityChanged -= OnHostVisibilityChanged;
        _hostWindow = null;
        ViewModel.SetViewActive(false);
    }

    private void OnHostVisibilityChanged(object sender, WindowVisibilityChangedEventArgs args)
        => ViewModel.SetViewActive(args.Visible);

    // ---- Transport -----------------------------------------------------------------------

    private async void Previous_Click(object sender, RoutedEventArgs e) => await ViewModel.PreviousAsync();

    private async void PlayPause_Click(object sender, RoutedEventArgs e) => await ViewModel.TogglePlayPauseAsync();

    private async void Next_Click(object sender, RoutedEventArgs e) => await ViewModel.NextAsync();

    private void VolumeToggle_Click(object sender, RoutedEventArgs e) => ViewModel.IsVolumeOpen = !ViewModel.IsVolumeOpen;

    // ---- Sliders -------------------------------------------------------------------------
    // The view model also moves these sliders itself (position ticks, phone snapshots, range
    // changes). Only a change on a slider that has focus, outside such an update, is the user.

    private bool IsUserInput(Slider slider) => !ViewModel.IsApplying && slider.FocusState != FocusState.Unfocused;

    private void PositionSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (IsUserInput(PositionSlider)) ViewModel.RequestSeek(e.NewValue);
    }

    private void LevelSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (IsUserInput(LevelSlider)) ViewModel.RequestVolume(e.NewValue);
    }
}
