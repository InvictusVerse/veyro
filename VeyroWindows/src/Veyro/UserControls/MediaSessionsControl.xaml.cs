using Microsoft.UI.Xaml.Controls.Primitives;

namespace Veyro.UserControls;

/// <summary>The phone's media session in the sidebar card (ADR-026).</summary>
public sealed partial class MediaSessionsControl : UserControl
{
    private Window? _window;

    public PhoneMediaViewModel ViewModel => VeyroContainer.Instance.PhoneMediaViewModel;

    public MediaSessionsControl()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _window = App.MainWindow;
        if (_window is not null) _window.VisibilityChanged += OnWindowVisibilityChanged;
        ViewModel.SetViewActive(_window?.Visible ?? true);
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        if (_window is not null) _window.VisibilityChanged -= OnWindowVisibilityChanged;
        _window = null;
        ViewModel.SetViewActive(false);
    }

    private void OnWindowVisibilityChanged(object sender, WindowVisibilityChangedEventArgs args)
        => ViewModel.SetViewActive(args.Visible);

    private async void PlayPause_Click(object sender, RoutedEventArgs e) => await ViewModel.TogglePlayPauseAsync();

    private async void Previous_Click(object sender, RoutedEventArgs e) => await ViewModel.PreviousAsync();

    private async void Next_Click(object sender, RoutedEventArgs e) => await ViewModel.NextAsync();

    // Model-driven updates (ticks, phone snapshots, layout coercion) are not user input: only a
    // focused slider outside a model update counts as the user seeking or changing volume.
    private void SeekSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (ViewModel.IsApplying || SeekSlider.FocusState == FocusState.Unfocused) return;
        ViewModel.RequestSeek(e.NewValue);
    }

    private void VolumeSlider_ValueChanged(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (ViewModel.IsApplying || VolumeSlider.FocusState == FocusState.Unfocused) return;
        ViewModel.RequestVolume(e.NewValue);
    }
}
