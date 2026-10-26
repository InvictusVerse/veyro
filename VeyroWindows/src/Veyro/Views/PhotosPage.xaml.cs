using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Imaging;
using Veyro.Core.Diagnostics;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;

namespace Veyro.Views;

/// <summary>
/// Photos tab (ADR-034). Tiles ask for thumbnails as they are realized and drop them when
/// recycled; the viewer, context menu and drag-out all fetch the original on demand. Names,
/// paths and images are never logged.
/// </summary>
public sealed partial class PhotosPage : Page
{
    private const int MaxViewerDecodePixels = 4096;
    private const float ZoomStep = 1.25f;

    private readonly MenuFlyout _tileMenu = new();
    private PhotoItemViewModel? _menuItem;
    private CancellationTokenSource? _viewerLoad;

    public PhotosPage()
    {
        InitializeComponent();
        PhotoGroups.Source = ViewModel.Groups;
        PhotoGrid.ItemsSource = PhotoGroups.View;
        BuildTileMenu();
    }

    public PhotosViewModel ViewModel => VeyroContainer.Instance.PhotosViewModel;
    private static IPhotoActions Actions => VeyroContainer.Instance.PhotoActions;

    /// <summary>A photo is being dragged out of the grid; the main window must not treat it as a file to send.</summary>
    public static bool IsDraggingPhoto { get; private set; }

    private void Page_Loaded(object sender, RoutedEventArgs e)
    {
        ViewModel.PropertyChanged += ViewModel_PropertyChanged;
        ViewModel.Activate();
        ApplyViewer();
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        ViewModel.PropertyChanged -= ViewModel_PropertyChanged;
        ViewModel.Deactivate();
        ViewModel.CloseViewer();
        HideViewer();
    }

    private void ViewModel_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PhotosViewModel.ViewerItem)) ApplyViewer();
        else if (e.PropertyName is nameof(PhotosViewModel.CanGoPrevious) or nameof(PhotosViewModel.CanGoNext)) UpdateViewerNavigation();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await ViewModel.RefreshAsync();

    private void Status_Closed(InfoBar sender, InfoBarClosedEventArgs args) => ViewModel.StatusText = string.Empty;

    // ── Grid ───────────────────────────────────────────────────────────────────────

    private void PhotoGrid_ContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.Item is not PhotoItemViewModel item) return;
        if (args.InRecycleQueue) ViewModel.OnTileRecycled(item);
        else ViewModel.OnTileRealized(item);
    }

    private void PhotoGrid_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is PhotoItemViewModel item) ViewModel.Open(item);
    }

    private void Tile_ContextRequested(UIElement sender, ContextRequestedEventArgs args)
    {
        if (sender is not FrameworkElement { DataContext: PhotoItemViewModel item } tile) return;
        _menuItem = item;
        args.Handled = true;
        if (args.TryGetPosition(tile, out var point))
            _tileMenu.ShowAt(tile, new FlyoutShowOptions { Position = point });
        else
            _tileMenu.ShowAt(tile);
    }

    private void BuildTileMenu()
    {
        AddMenuItem("Open", "\uE8B9", item => ViewModel.Open(item));
        AddMenuItem("Copy", "\uE8C8", item => _ = CopyAsync(item));
        AddMenuItem("Save as...", "\uE74E", item => _ = SaveAsync(item));
        AddMenuItem("Open with default app", "\uE8A7", item => _ = OpenWithDefaultAppAsync(item));
        AddMenuItem("Share", "\uE72D", item => _ = ShareAsync(item));
    }

    private void AddMenuItem(string text, string glyph, Action<PhotoItemViewModel> action)
    {
        var menuItem = new MenuFlyoutItem { Text = text, Icon = new FontIcon { Glyph = glyph } };
        menuItem.Click += (_, _) =>
        {
            if (_menuItem is { } item) action(item);
        };
        _tileMenu.Items.Add(menuItem);
    }

    /// <summary>
    /// Drag out to Explorer or another app: the original is fetched when the drag starts and
    /// handed over as a StorageItem when the target asks for it.
    /// </summary>
    private void PhotoGrid_DragItemsStarting(object sender, DragItemsStartingEventArgs e)
    {
        if (e.Items.FirstOrDefault() is not PhotoItemViewModel item)
        {
            e.Cancel = true;
            return;
        }
        IsDraggingPhoto = true;
        e.Data.RequestedOperation = DataPackageOperation.Copy;
        if (ViewModel.CachedOriginal(item) is { } cached)
        {
            e.Data.SetDataProvider(StandardDataFormats.StorageItems, request => _ = ProvideFileAsync(request, Task.FromResult(cached)));
            return;
        }
        Task<string> fetch = ViewModel.FetchOriginalAsync(item);
        // Observe a failure even if the drop target never asks for the data.
        _ = fetch.ContinueWith(t => _ = t.Exception, TaskContinuationOptions.OnlyOnFaulted);
        e.Data.SetDataProvider(StandardDataFormats.StorageItems, request => _ = ProvideFileAsync(request, fetch));
    }

    private static async Task ProvideFileAsync(DataProviderRequest request, Task<string> path)
    {
        DataProviderDeferral deferral = request.GetDeferral();
        try
        {
            StorageFile file = await StorageFile.GetFileFromPathAsync(await path);
            request.SetData(new List<IStorageItem> { file });
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Photo drag-out failed: {error.GetType().Name}");
        }
        finally
        {
            deferral.Complete();
        }
    }

    private void PhotoGrid_DragItemsCompleted(ListViewBase sender, DragItemsCompletedEventArgs args) => IsDraggingPhoto = false;

    // ── Viewer ─────────────────────────────────────────────────────────────────────

    private void ApplyViewer()
    {
        if (ViewModel.ViewerItem is { } item) _ = ShowInViewerAsync(item);
        else HideViewer();
    }

    private async Task ShowInViewerAsync(PhotoItemViewModel item)
    {
        _viewerLoad?.Cancel();
        _viewerLoad?.Dispose();
        var load = new CancellationTokenSource();
        _viewerLoad = load;

        Viewer.Visibility = Visibility.Visible;
        ViewerTitle.Text = item.Name;
        ViewerDetail.Text = item.DetailText;
        ViewerError.Visibility = Visibility.Collapsed;
        // The tile's thumbnail stands in until the original is here.
        ViewerImage.Source = item.Thumbnail;
        ViewerScroll.ChangeView(0, 0, 1, disableAnimation: true);
        UpdateViewerNavigation();
        ViewerProgress.IsActive = true;

        bool current() => !load.IsCancellationRequested && ReferenceEquals(ViewModel.ViewerItem, item);
        string path;
        try
        {
            path = await ViewModel.FetchOriginalAsync(item, load.Token);
        }
        catch (OperationCanceledException) when (load.IsCancellationRequested) { return; }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Opening photo failed: {error.GetType().Name}");
            if (current()) ShowViewerError(error is TimeoutException
                ? "The phone did not send this photo. Check that \"Photos on PC\" is on, then try again."
                : "Could not get this photo from the phone.");
            return;
        }
        if (!current()) return;

        try
        {
            var bitmap = new BitmapImage();
            // Very large originals are decoded at a bounded size; zoom still has plenty of detail.
            if (item.Info is { Width: > MaxViewerDecodePixels } wide && wide.Width >= (wide.Height ?? 0)) bitmap.DecodePixelWidth = MaxViewerDecodePixels;
            else if (item.Info is { Height: > MaxViewerDecodePixels }) bitmap.DecodePixelHeight = MaxViewerDecodePixels;
            await using (FileStream stream = File.OpenRead(path))
            {
                await bitmap.SetSourceAsync(stream.AsRandomAccessStream());
            }
            if (!current()) return;
            ViewerImage.Source = bitmap;
            ViewerProgress.IsActive = false;
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Decoding photo failed: {error.GetType().Name}");
            if (current()) ShowViewerError("This photo cannot be shown here. Use Open with default app.");
        }
    }

    private void ShowViewerError(string text)
    {
        ViewerProgress.IsActive = false;
        ViewerError.Text = text;
        ViewerError.Visibility = Visibility.Visible;
    }

    private void HideViewer()
    {
        _viewerLoad?.Cancel();
        _viewerLoad?.Dispose();
        _viewerLoad = null;
        Viewer.Visibility = Visibility.Collapsed;
        ViewerImage.Source = null;
        ViewerProgress.IsActive = false;
    }

    private void UpdateViewerNavigation()
    {
        PreviousButton.Visibility = ViewModel.CanGoPrevious ? Visibility.Visible : Visibility.Collapsed;
        NextButton.Visibility = ViewModel.CanGoNext ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ViewerScroll_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        // At zoom 1 the image exactly fits the window; zooming scales from there.
        ViewerImage.Width = e.NewSize.Width;
        ViewerImage.Height = e.NewSize.Height;
    }

    private void CloseViewer_Click(object sender, RoutedEventArgs e) => ViewModel.CloseViewer();

    private void Previous_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.IsViewerOpen) ViewModel.Step(-1);
    }

    private void Next_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.IsViewerOpen) ViewModel.Step(1);
    }

    private void ZoomIn_Click(object sender, RoutedEventArgs e)
        => ViewerScroll.ChangeView(null, null, Math.Min(ViewerScroll.MaxZoomFactor, ViewerScroll.ZoomFactor * ZoomStep));

    private void ZoomOut_Click(object sender, RoutedEventArgs e)
        => ViewerScroll.ChangeView(null, null, Math.Max(ViewerScroll.MinZoomFactor, ViewerScroll.ZoomFactor / ZoomStep));

    private void ZoomFit_Click(object sender, RoutedEventArgs e) => ViewerScroll.ChangeView(0, 0, 1);

    private async void ViewerCopy_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.ViewerItem is { } item) await CopyAsync(item);
    }

    private async void ViewerSave_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.ViewerItem is { } item) await SaveAsync(item);
    }

    private async void ViewerOpen_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.ViewerItem is { } item) await OpenWithDefaultAppAsync(item);
    }

    private async void ViewerShare_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.ViewerItem is { } item) await ShareAsync(item);
    }

    // ── Actions (viewer and context menu) ─────────────────────────────────────────

    private Task CopyAsync(PhotoItemViewModel item)
        => RunAsync(item, async path =>
        {
            await Actions.CopyAsync(path);
            ViewModel.StatusText = "Photo copied to the clipboard.";
        }, "Could not copy the photo.");

    private Task SaveAsync(PhotoItemViewModel item)
        => RunAsync(item, async path =>
        {
            if (await Actions.SaveAsAsync(path, item.Name)) ViewModel.StatusText = "Photo saved.";
        }, "Could not save the photo.");

    private Task OpenWithDefaultAppAsync(PhotoItemViewModel item)
        => RunAsync(item, async path =>
        {
            if (!await Actions.OpenAsync(path)) ViewModel.StatusText = "No app could open this photo.";
        }, "Could not open the photo.");

    private Task ShareAsync(PhotoItemViewModel item)
        => RunAsync(item, path => Actions.ShareAsync(path, item.Name), "Could not share the photo.");

    private async Task RunAsync(PhotoItemViewModel item, Func<string, Task> action, string failure)
    {
        try
        {
            string path = await ViewModel.FetchOriginalAsync(item);
            await action(path);
        }
        catch (Exception error)
        {
            VeyroTrace.Warn($"Photo action failed: {error.GetType().Name}");
            ViewModel.StatusText = failure;
        }
    }
}
