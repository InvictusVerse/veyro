using Microsoft.UI.Xaml.Media.Animation;
using Windows.ApplicationModel.DataTransfer;

namespace Veyro.Views;

/// <summary>
/// ADR-074: the Modern layout's window, a left rail and the page. The same requests the Classic
/// <see cref="MainPage"/> handles (open a conversation, device settings, pairing) are handled here.
/// Choosing Classic in Settings swaps the window back to MainPage.
/// </summary>
public sealed partial class ModernShellPage : Page
{
    /// <summary>Navigation parameter: open Settings (used when the layout is switched from Settings).</summary>
    public const string SettingsTarget = "general";

    private static WeakReference<ModernShellPage>? _current;

    /// <summary>Lets Home open another page of this window (See all notifications, Clipboard...).</summary>
    public static void Open(string tag)
    {
        if (_current?.TryGetTarget(out ModernShellPage? shell) == true) shell.NavigateTo(tag);
    }

    private readonly Dictionary<string, Type> _pages = new()
    {
        { "Home", typeof(HomePage) },
        { "Messages", typeof(MessagesPage) },
        { "Calls", typeof(CallsPage) },
        { "Photos", typeof(PhotosPage) },
        { "Notifications", typeof(NotificationsPage) },
        { "Transfers", typeof(TransfersPage) },
        { "Clipboard", typeof(ClipboardPage) },
    };

    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public MessagesViewModel MessagesViewModel => VeyroContainer.Instance.MessagesViewModel;

    private string? _pendingSettingsSection;
    private bool _subscribed;

    public ModernShellPage()
    {
        InitializeComponent();
        _current = new WeakReference<ModernShellPage>(this);
        Subscribe();
        Loaded += (_, _) =>
        {
            Subscribe();
            if (Rail.SelectedItem is null)
            {
                if (!string.IsNullOrEmpty(_pendingSettingsSection)) OpenSettings(_pendingSettingsSection);
                else NavigateTo("Home");
            }
        };
        Unloaded += (_, _) => Unsubscribe();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _pendingSettingsSection = e.Parameter as string;
    }

    private void Subscribe()
    {
        if (_subscribed) return;
        _subscribed = true;
        MessagesViewModel.OpenRequested += OnOpenMessages;
        ViewModel.OpenDeviceSettingsRequested += OnOpenDeviceSettings;
        ViewModel.PairRequested += OpenPairing;
        ViewModel.PropertyChanged += OnViewModelChanged;
        VeyroContainer.Instance.SettingsStore.SettingsChanged += OnSettingsChanged;
    }

    private void Unsubscribe()
    {
        if (!_subscribed) return;
        _subscribed = false;
        MessagesViewModel.OpenRequested -= OnOpenMessages;
        ViewModel.OpenDeviceSettingsRequested -= OnOpenDeviceSettings;
        ViewModel.PairRequested -= OpenPairing;
        ViewModel.PropertyChanged -= OnViewModelChanged;
        VeyroContainer.Instance.SettingsStore.SettingsChanged -= OnSettingsChanged;
    }

    private void OnSettingsChanged(AppSettings settings) => DispatcherQueue.TryEnqueue(() =>
    {
        if (AppUiStyle.IsClassic(settings) && Frame is { } frame && ReferenceEquals(frame.Content, this))
        {
            frame.Navigate(typeof(MainPage), SettingsTarget);
            frame.BackStack.Clear();
        }
    });

    private void OnOpenMessages() => NavigateTo("Messages");

    private void OnOpenDeviceSettings() => OpenSettings(SettingsPage.DevicesSection);

    private void OnViewModelChanged(object? sender, PropertyChangedEventArgs e)
    {
        // The phone pages hide without a phone; go Home, which then offers pairing.
        if (e.PropertyName == nameof(MainPageViewModel.HasActiveDevice) && !ViewModel.HasActiveDevice &&
            Rail.SelectedItem is NavigationViewItem { Tag: "Calls" or "Messages" or "Photos" or "Notifications" })
        {
            NavigateTo("Home");
        }
    }

    public void NavigateTo(string tag)
    {
        NavigationViewItem? item = tag switch
        {
            "Home" => HomeItem,
            "Messages" => MessagesItem,
            "Calls" => CallsItem,
            "Photos" => PhotosItem,
            "Notifications" => NotificationsItem,
            "Transfers" => TransfersItem,
            "Clipboard" => ClipboardItem,
            _ => null,
        };
        if (item is null) return;
        if (ReferenceEquals(Rail.SelectedItem, item)) ContentFrame.Navigate(_pages[tag]);
        else Rail.SelectedItem = item;
    }

    private void OpenSettings(string? section)
    {
        _pendingSettingsSection = section;
        if (ReferenceEquals(Rail.SelectedItem, Rail.SettingsItem))
        {
            ContentFrame.Navigate(typeof(SettingsPage), _pendingSettingsSection);
            _pendingSettingsSection = null;
        }
        else
        {
            Rail.SelectedItem = Rail.SettingsItem;
        }
    }

    private void OpenPairing()
    {
        Rail.SelectedItem = null;
        ContentFrame.Navigate(typeof(PairDevicePage));
    }

    private void Rail_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        var transition = new EntranceNavigationTransitionInfo();
        if (args.IsSettingsSelected)
        {
            ContentFrame.Navigate(typeof(SettingsPage), _pendingSettingsSection, transition);
            _pendingSettingsSection = null;
            return;
        }
        if (args.SelectedItemContainer is NavigationViewItem { Tag: string tag } && _pages.TryGetValue(tag, out Type? page))
            ContentFrame.Navigate(page, null, transition);
    }

    private void Root_DragOver(object sender, DragEventArgs e)
    {
        // A photo dragged out of the Photos page came from the phone; it must not be sent back.
        if (PhotosPage.IsDraggingPhoto) return;
        if (!ViewModel.HasActiveDevice || !e.DataView.Contains(StandardDataFormats.StorageItems)) return;
        e.AcceptedOperation = DataPackageOperation.Copy;
        e.DragUIOverride.Caption = $"Send to {ViewModel.Device?.Name}";
    }

    private async void Root_Drop(object sender, DragEventArgs e)
    {
        if (PhotosPage.IsDraggingPhoto) return;
        if (!ViewModel.HasActiveDevice || !e.DataView.Contains(StandardDataFormats.StorageItems)) return;
        await PairingFlow.SendDroppedAsync(e);
    }
}
