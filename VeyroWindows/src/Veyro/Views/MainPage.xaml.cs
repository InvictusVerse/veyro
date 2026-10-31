using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;

namespace Veyro.Views;

/// <summary>
/// The Classic window (ADR-074): a phone pane on the left and a tab strip with the page of the
/// selected tab on the right. Choosing the Modern layout swaps the whole window to
/// <see cref="ModernShellPage"/>.
/// </summary>
public sealed partial class MainPage : Page
{
    /// <summary>Navigation parameter: open Settings scrolled to Bluetooth calling.</summary>
    public const string BluetoothSettingsSection = SettingsPage.BluetoothSection;

    /// <summary>From this window width the phone pane is always shown inline.</summary>
    private const double InlinePaneMinWidth = 1200;

    /// <summary>Tab tag to page type. Settings is the NavigationView's own settings item.</summary>
    private static readonly IReadOnlyDictionary<string, Type> TabPages = new Dictionary<string, Type>
    {
        ["Calls"] = typeof(CallsPage),
        ["Messages"] = typeof(MessagesPage),
        ["Transfers"] = typeof(TransfersPage),
        ["Photos"] = typeof(PhotosPage),
        ["Clipboard"] = typeof(ClipboardPage)
    };

    /// <summary>Tabs that only make sense while a phone is paired.</summary>
    private static readonly string[] PhoneOnlyTabs = { "Calls", "Messages", "Photos" };

    private string? _settingsSectionToShow;
    private bool _listening;

    public MainPage()
    {
        InitializeComponent();

        // Listen from construction on: a toast can ask for a conversation right after navigation,
        // before Loaded. Stop on Unloaded, because the view models outlive the page (ADR-047 drops
        // the page while Veyro sits in the tray) and a leftover handler would keep it alive.
        StartListening();
        Loaded += (_, _) =>
        {
            StartListening();
            FitPaneToWindow(ActualWidth);
            StretchPaneHost();
        };
        Unloaded += (_, _) => StopListening();
    }

    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;

    public NotificationsViewModel NotificationsViewModel => VeyroContainer.Instance.NotificationsViewModel;

    public PhoneMediaViewModel MediaViewModel => VeyroContainer.Instance.PhoneMediaViewModel;

    public DeviceStatusViewModel StatusViewModel => VeyroContainer.Instance.DeviceStatusViewModel;

    public MessagesViewModel MessagesViewModel => VeyroContainer.Instance.MessagesViewModel;

    private bool PaneIsInline => PhonePane.PaneDisplayMode is NavigationViewPaneDisplayMode.Left;

    // ---- Wiring ----------------------------------------------------------------------------

    private void StartListening()
    {
        if (_listening) return;
        _listening = true;
        MessagesViewModel.OpenRequested += ShowMessagesTab;
        ViewModel.OpenDeviceSettingsRequested += ShowDeviceSettings;
        ViewModel.PairRequested += ShowPairingPage;
        ViewModel.PropertyChanged += ViewModel_PropertyChanged;
        VeyroContainer.Instance.SettingsStore.SettingsChanged += SettingsStore_SettingsChanged;
    }

    private void StopListening()
    {
        if (!_listening) return;
        _listening = false;
        MessagesViewModel.OpenRequested -= ShowMessagesTab;
        ViewModel.OpenDeviceSettingsRequested -= ShowDeviceSettings;
        ViewModel.PairRequested -= ShowPairingPage;
        ViewModel.PropertyChanged -= ViewModel_PropertyChanged;
        VeyroContainer.Instance.SettingsStore.SettingsChanged -= SettingsStore_SettingsChanged;
    }

    private void SettingsStore_SettingsChanged(AppSettings settings) => DispatcherQueue.TryEnqueue(() =>
    {
        if (AppUiStyle.IsClassic(settings) || Frame is not { } frame || !ReferenceEquals(frame.Content, this)) return;
        frame.Navigate(typeof(ModernShellPage), ModernShellPage.SettingsTarget);
        frame.BackStack.Clear();
    });

    private void ViewModel_PropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        // Calls, Messages and Photos disappear without a phone; move off them when that happens.
        if (e.PropertyName != nameof(MainPageViewModel.HasActiveDevice) || ViewModel.HasActiveDevice) return;
        if (TabBar.SelectedItem is NavigationViewItem { Tag: string tag } && PhoneOnlyTabs.Contains(tag))
            ShowSettings(null);
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _settingsSectionToShow = e.Parameter as string;
    }

    // ---- Tabs ------------------------------------------------------------------------------

    private void TabBar_Loaded(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(_settingsSectionToShow))
        {
            ShowSettings(_settingsSectionToShow);
        }
        else if (ViewModel.HasActiveDevice)
        {
            SelectTab("Messages");
        }
        else
        {
            // Without a phone the first screen is Settings, where pairing starts.
            ShowSettings(null);
        }
    }

    private void TabBar_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.IsSettingsSelected)
        {
            TabContent.Navigate(typeof(SettingsPage), _settingsSectionToShow);
            _settingsSectionToShow = null;
        }
        else if (args.SelectedItemContainer?.Tag is string tag && TabPages.TryGetValue(tag, out Type? pageType))
        {
            TabContent.Navigate(pageType);
        }
    }

    private void SelectTab(string tag)
    {
        NavigationViewItem? tab = tag switch
        {
            "Calls" => CallsTab,
            "Messages" => MessagesTab,
            "Transfers" => TransfersTab,
            "Photos" => PhotosTab,
            "Clipboard" => ClipboardTab,
            _ => null
        };
        if (tab is not null && !ReferenceEquals(TabBar.SelectedItem, tab)) TabBar.SelectedItem = tab;
    }

    private void ShowSettings(string? section)
    {
        _settingsSectionToShow = section;
        if (!ReferenceEquals(TabBar.SelectedItem, TabBar.SettingsItem))
        {
            // Selecting the item raises SelectionChanged, which navigates.
            TabBar.SelectedItem = TabBar.SettingsItem;
            return;
        }
        TabContent.Navigate(typeof(SettingsPage), _settingsSectionToShow);
        _settingsSectionToShow = null;
    }

    // A toast asked for a conversation; the view model selects the thread itself.
    private void ShowMessagesTab() => SelectTab("Messages");

    private void ShowDeviceSettings() => ShowSettings(SettingsPage.DevicesSection);

    private void ShowPairingPage()
    {
        // Pairing is a page of its own. No tab stays selected, so any tab leads back out of it.
        TabBar.SelectedItem = null;
        TabContent.Navigate(typeof(PairDevicePage));
    }

    // ---- Pairing prompt in the pane --------------------------------------------------------

    private void PairPhone_Click(object sender, RoutedEventArgs e) => ViewModel.RequestPairing();

    // Discovery data is untrusted; PairingFlow (shared with the Modern shell) asks the user first.
    private async void NearbyPhone_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: string deviceId })
            await PairingFlow.PairWithDiscoveredAsync(XamlRoot, DispatcherQueue, deviceId, ViewModel);
    }

    // ---- Phone pane: inline when the window is wide, a rail plus overlay when it is narrow --

    private void PhonePane_SizeChanged(object sender, SizeChangedEventArgs e) => FitPaneToWindow(ActualWidth);

    private void FitPaneToWindow(double windowWidth)
    {
        // Before the first layout the width can be the rail's, not the window's.
        if (windowWidth <= PhonePane.CompactPaneLength) return;

        bool inline = windowWidth <= PhonePane.OpenPaneLength || windowWidth >= InlinePaneMinWidth;
        NavigationViewPaneDisplayMode wanted = inline
            ? NavigationViewPaneDisplayMode.Left
            : NavigationViewPaneDisplayMode.LeftCompact;
        if (PhonePane.PaneDisplayMode == wanted) return;

        PhonePane.PaneDisplayMode = wanted;
        if (PhonePane.IsPaneOpen != inline) PhonePane.IsPaneOpen = inline;
        ShowPaneContent(inline);
    }

    private void PhonePane_PaneOpening(NavigationView sender, object args) => ShowPaneContent(paneOpen: true);

    private void PhonePane_PaneClosing(NavigationView sender, NavigationViewPaneClosingEventArgs args) => ShowPaneContent(paneOpen: false);

    private void PhonePane_PaneClosed(NavigationView sender, object args)
    {
        if (PaneIsInline)
        {
            // An inline pane never closes; a closed one would leave an empty strip.
            if (!PhonePane.IsPaneOpen) PhonePane.IsPaneOpen = true;
            return;
        }
        ShowPaneContent(paneOpen: false);
    }

    private void PhonePane_LayoutUpdated(object? sender, object e) => StretchPaneHost();

    private void PaneOpenRequested(object sender, RoutedEventArgs e) => PhonePane.IsPaneOpen = true;

    private void PaneCloseRequested(object sender, RoutedEventArgs e) => PhonePane.IsPaneOpen = false;

    /// <summary>The pane's custom content is not stretched by the NavigationView; give it the full height.</summary>
    private void StretchPaneHost()
    {
        double height = PhonePane.ActualHeight;
        if (height > 0 && PaneHost.Height != height) PaneHost.Height = height;
    }

    private void ShowPaneContent(bool? paneOpen = null)
    {
        bool open = paneOpen ?? PhonePane.IsPaneOpen;
        bool showRail = !open && !PaneIsInline;
        RailView.Visibility = showRail ? Visibility.Visible : Visibility.Collapsed;
        FullPaneView.Visibility = showRail ? Visibility.Collapsed : Visibility.Visible;
        ClosePaneButton.Visibility = open && !PaneIsInline ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---- Dropping files on the window sends them to the phone ------------------------------

    private bool CanDropFiles(DataPackageView data) =>
        // A photo dragged out of the Photos tab came from the phone and must not go back to it.
        !PhotosPage.IsDraggingPhoto && ViewModel.HasActiveDevice && data.Contains(StandardDataFormats.StorageItems);

    private void FilesDragOver(object sender, DragEventArgs e)
    {
        if (!CanDropFiles(e.DataView)) return;
        e.AcceptedOperation = DataPackageOperation.Copy;
        e.DragUIOverride.Caption = $"Send to {ViewModel.Device?.Name}";
    }

    private async void FilesDropped(object sender, DragEventArgs e)
    {
        if (!CanDropFiles(e.DataView)) return;

        // Files and whole folders are both accepted (ADR-064).
        IReadOnlyList<IStorageItem> dropped = await e.DataView.GetStorageItemsAsync();
        List<string> paths = dropped
            .Where(item => item is StorageFile or StorageFolder)
            .Select(item => item.Path)
            .Where(path => !string.IsNullOrEmpty(path))
            .ToList();

        if (paths.Count > 0)
            await VeyroContainer.Instance.TransfersPageViewModel.SendFilesToActiveDeviceAsync(paths);
    }
}
