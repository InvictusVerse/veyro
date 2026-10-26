using Windows.UI.ViewManagement;

namespace Veyro.UserControls;

/// <summary>
/// The outbound transfer banner. A completed transfer slides out <see cref="AutoHideDelay"/>
/// after it finished (ADR-038); failed and cancelled ones stay until dismissed. With Windows
/// animations turned off the banner just disappears.
/// </summary>
public sealed partial class ActiveTransferBanner : UserControl
{
    private static readonly TimeSpan AutoHideDelay = TimeSpan.FromSeconds(4);

    private readonly DispatcherQueueTimer _autoHide;
    private bool _sliding;

    public TransfersPageViewModel ViewModel => VeyroContainer.Instance.TransfersPageViewModel;

    public ActiveTransferBanner()
    {
        InitializeComponent();
        _autoHide = DispatcherQueue.CreateTimer();
        _autoHide.Interval = AutoHideDelay;
        _autoHide.IsRepeating = false;
        _autoHide.Tick += (_, _) => SlideOut();
        SlideOutStoryboard.Completed += (_, _) =>
        {
            // Collapse first, then release the held animation values for the next banner.
            ViewModel.HideBanner();
            SlideOutStoryboard.Stop();
            ResetVisuals();
        };
        Loaded += (_, _) =>
        {
            ViewModel.PropertyChanged += OnViewModelChanged;
            Sync();
        };
        Unloaded += (_, _) =>
        {
            ViewModel.PropertyChanged -= OnViewModelChanged;
            _autoHide.Stop();
        };
    }

    private void OnViewModelChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(TransfersPageViewModel.ActiveTransfer) or nameof(TransfersPageViewModel.IsTransferSucceeded))
            Sync();
    }

    /// <summary>A new transfer, or a failure, cancels a pending slide-out and restores the card.</summary>
    private void Sync()
    {
        _autoHide.Stop();
        if (_sliding)
        {
            SlideOutStoryboard.Stop();
            ResetVisuals();
        }
        if (ViewModel.IsTransferSucceeded && ViewModel.IsBannerVisible) _autoHide.Start();
    }

    private void SlideOut()
    {
        if (!ViewModel.IsBannerVisible) return;
        if (!new UISettings().AnimationsEnabled)
        {
            ViewModel.HideBanner();
            return;
        }
        _sliding = true;
        SlideOutStoryboard.Begin();
    }

    private void ResetVisuals()
    {
        _sliding = false;
        BannerTransform.TranslateX = 0;
        BannerCard.Opacity = 1;
    }

    private void CancelTransfer_Click(object sender, RoutedEventArgs e) => ViewModel.CancelTransfer();

    private void Dismiss_Click(object sender, RoutedEventArgs e)
    {
        _autoHide.Stop();
        if (ViewModel.IsTransferFinished) SlideOut();
        else ViewModel.HideBanner();
    }
}
