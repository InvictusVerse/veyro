// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/Views/MainPage.xaml.cs, GPL-3.0.
// Modified for Veyro.

using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;

namespace Veyro.Views;

public sealed partial class MainPage : Page
{
    /// <summary>Navigation parameter: open Settings scrolled to Bluetooth calling.</summary>
    public const string BluetoothSettingsSection = SettingsPage.BluetoothSection;

    // Left = inline expanded pane. Used at >= 1200 px and when the window is as narrow as the pane.
    // LeftCompact = rail when closed, overlay when open.
    private const double ExpandedPaneMinWidth = 1200;

    private readonly Dictionary<string, Type> _pages = new()
    {
        { "Calls", typeof(CallsPage) },
        { "Messages", typeof(MessagesPage) },
        { "Transfers", typeof(TransfersPage) },
        { "Photos", typeof(PhotosPage) }
    };

    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public NotificationsViewModel NotificationsViewModel => VeyroContainer.Instance.NotificationsViewModel;
    public PhoneMediaViewModel MediaViewModel => VeyroContainer.Instance.PhoneMediaViewModel;
    public DeviceStatusViewModel StatusViewModel => VeyroContainer.Instance.DeviceStatusViewModel;
    public MessagesViewModel MessagesViewModel => VeyroContainer.Instance.MessagesViewModel;

    private string? _pendingSettingsSection;
    private bool IsInline => SideBar.PaneDisplayMode is NavigationViewPaneDisplayMode.Left;

    public MainPage()
    {
        InitializeComponent();
        // A toast asked for a conversation: switch to the Messages tab (the view model selects the thread).
        VeyroContainer.Instance.MessagesViewModel.OpenRequested += () => NavigateToPage("Messages");
        ViewModel.OpenDeviceSettingsRequested += () => OpenSettings(SettingsPage.DevicesSection);
        ViewModel.PairRequested += OpenPairing;
        ViewModel.PropertyChanged += (_, e) =>
        {
            // The phone tabs hide without a phone; do not leave one of them on screen.
            if (e.PropertyName == nameof(MainPageViewModel.HasActiveDevice) && !ViewModel.HasActiveDevice &&
                MainNavigationView.SelectedItem is NavigationViewItem { Tag: "Calls" or "Messages" or "Photos" })
            {
                OpenSettings(null);
            }
        };
        Loaded += (_, _) =>
        {
            ApplyPaneMode(ActualWidth);
            UpdatePaneCustomHeight();
        };
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _pendingSettingsSection = e.Parameter as string;
    }

    private void MainNavigationView_Loaded(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(_pendingSettingsSection))
        {
            OpenSettings(_pendingSettingsSection);
            return;
        }
        // As in Sefirah: no phone, start in Settings where pairing lives.
        if (ViewModel.HasActiveDevice) NavigateToPage("Messages");
        else OpenSettings(null);
    }

    private void NavigationView_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.IsSettingsSelected)
        {
            ContentFrame.Navigate(typeof(SettingsPage), _pendingSettingsSection);
            _pendingSettingsSection = null;
            return;
        }

        if (args.SelectedItemContainer is NavigationViewItem { Tag: string tag } && _pages.TryGetValue(tag, out Type? page))
            ContentFrame.Navigate(page);
    }

    private void NavigateToPage(string tag)
    {
        NavigationViewItem? item = tag switch
        {
            "Calls" => CallsNavigationItem,
            "Messages" => MessagesNavigationItem,
            "Transfers" => TransfersNavigationItem,
            "Photos" => PhotosNavigationItem,
            _ => null
        };
        if (item is not null && !ReferenceEquals(MainNavigationView.SelectedItem, item))
            MainNavigationView.SelectedItem = item;
    }

    private void OpenSettings(string? section)
    {
        _pendingSettingsSection = section;
        if (ReferenceEquals(MainNavigationView.SelectedItem, MainNavigationView.SettingsItem))
        {
            ContentFrame.Navigate(typeof(SettingsPage), _pendingSettingsSection);
            _pendingSettingsSection = null;
        }
        else
        {
            MainNavigationView.SelectedItem = MainNavigationView.SettingsItem;
        }
    }

    private void OpenPairing()
    {
        // The pairing screen is a page of its own; clearing the tab lets any tab bring the user back.
        MainNavigationView.SelectedItem = null;
        ContentFrame.Navigate(typeof(PairDevicePage));
    }

    private void PairPhone_Click(object sender, RoutedEventArgs e) => ViewModel.RequestPairing();

    // Discovery is untrusted: a phone seen on the network is only an invitation to pair.
    private void DiscoveredDevice_Click(object sender, RoutedEventArgs e) => ViewModel.RequestPairing();

    // Responsive pane, as in Sefirah: inline when wide, a rail with an overlay when narrow.
    private NavigationViewPaneDisplayMode NavigationMode(double width)
        => width <= SideBar.OpenPaneLength || width >= ExpandedPaneMinWidth
            ? NavigationViewPaneDisplayMode.Left
            : NavigationViewPaneDisplayMode.LeftCompact;

    private void SideBar_SizeChanged(object sender, SizeChangedEventArgs e) => ApplyPaneMode(ActualWidth);

    private void ApplyPaneMode(double width)
    {
        // The first layout can report the rail width rather than the window.
        if (width <= SideBar.CompactPaneLength) return;
        NavigationViewPaneDisplayMode mode = NavigationMode(width);
        if (SideBar.PaneDisplayMode == mode) return;
        SideBar.PaneDisplayMode = mode;
        bool open = mode is NavigationViewPaneDisplayMode.Left;
        if (SideBar.IsPaneOpen != open) SideBar.IsPaneOpen = open;
        UpdatePaneVisuals(open);
    }

    private void SideBar_PaneOpening(NavigationView sender, object args) => UpdatePaneVisuals(isPaneOpen: true);

    private void SideBar_PaneClosing(NavigationView sender, NavigationViewPaneClosingEventArgs args) => UpdatePaneVisuals(isPaneOpen: false);

    private void SideBar_PaneClosed(NavigationView sender, object args)
    {
        if (IsInline)
        {
            // Inline should stay open; a close here would leave a zero-width pane.
            if (!SideBar.IsPaneOpen) SideBar.IsPaneOpen = true;
            return;
        }
        UpdatePaneVisuals(isPaneOpen: false);
    }

    private void SideBar_LayoutUpdated(object? sender, object e) => UpdatePaneCustomHeight();

    private void OpenSideBar_Click(object sender, RoutedEventArgs e) => SideBar.IsPaneOpen = true;

    private void CloseSideBar_Click(object sender, RoutedEventArgs e) => SideBar.IsPaneOpen = false;

    /// <summary>PaneCustomContent does not stretch to the navigation height on its own.</summary>
    private void UpdatePaneCustomHeight()
    {
        double height = SideBar.ActualHeight;
        if (height > 0 && PaneCustomRoot.Height != height) PaneCustomRoot.Height = height;
    }

    private void UpdatePaneVisuals(bool? isPaneOpen = null)
    {
        bool paneOpen = isPaneOpen ?? SideBar.IsPaneOpen;
        bool rail = !paneOpen && !IsInline;
        CompactPaneContent.Visibility = rail ? Visibility.Visible : Visibility.Collapsed;
        ExpandedPaneContent.Visibility = rail ? Visibility.Collapsed : Visibility.Visible;
        CollapsePaneButton.Visibility = !IsInline && paneOpen ? Visibility.Visible : Visibility.Collapsed;
    }

    private void Grid_DragOver(object sender, DragEventArgs e)
    {
        // A photo dragged out of the Photos tab came from the phone; dropping it here must not send it back.
        if (PhotosPage.IsDraggingPhoto) return;
        if (!ViewModel.HasActiveDevice || !e.DataView.Contains(StandardDataFormats.StorageItems)) return;
        e.AcceptedOperation = DataPackageOperation.Copy;
        e.DragUIOverride.Caption = $"Send to {ViewModel.Device?.Name}";
    }

    private async void Page_Drop(object sender, DragEventArgs e)
    {
        if (PhotosPage.IsDraggingPhoto) return;
        if (!ViewModel.HasActiveDevice || !e.DataView.Contains(StandardDataFormats.StorageItems)) return;

        var items = await e.DataView.GetStorageItemsAsync();
        var filePaths = items
            .OfType<StorageFile>()
            .Select(f => f.Path)
            .Where(p => !string.IsNullOrEmpty(p))
            .ToList();

        if (filePaths.Count > 0)
            await VeyroContainer.Instance.TransfersPageViewModel.SendFilesToActiveDeviceAsync(filePaths);
    }
}
