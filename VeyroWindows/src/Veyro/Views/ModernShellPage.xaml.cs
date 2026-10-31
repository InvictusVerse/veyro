using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.ApplicationModel.DataTransfer;

namespace Veyro.Views;

/// <summary>
/// ADR-074: the Modern layout's window, a narrow rail and the page. The same requests the Classic
/// <see cref="MainPage"/> handles (open a conversation, device settings, pairing) are handled here.
/// Choosing Classic in Settings swaps the window back to MainPage.
/// </summary>
public sealed partial class ModernShellPage : Page
{
    /// <summary>Navigation parameter: open Settings (used when the layout is switched from Settings).</summary>
    public const string SettingsTarget = "general";

    private static WeakReference<ModernShellPage>? _current;

    /// <summary>Lets Home open another page of this window (Clipboard, Transfers).</summary>
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
        { "Transfers", typeof(TransfersPage) },
        { "Clipboard", typeof(ClipboardPage) },
        { "Settings", typeof(SettingsPage) },
    };

    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public MessagesViewModel MessagesViewModel => VeyroContainer.Instance.MessagesViewModel;

    private string? _pendingSettingsSection;
    private string? _selected;
    private bool _subscribed;

    public ModernShellPage()
    {
        InitializeComponent();
        _current = new WeakReference<ModernShellPage>(this);
        Subscribe();
        Loaded += (_, _) =>
        {
            Subscribe();
            if (_selected is null)
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
            _selected is "Calls" or "Messages" or "Photos")
        {
            NavigateTo("Home");
        }
    }

    private void Rail_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not string tag) return;
        if (tag == "Settings") OpenSettings(null);
        else NavigateTo(tag);
    }

    public void NavigateTo(string tag)
    {
        if (!_pages.TryGetValue(tag, out Type? page)) return;
        ContentFrame.Navigate(page, tag == "Settings" ? _pendingSettingsSection : null, new EntranceNavigationTransitionInfo());
        if (tag == "Settings") _pendingSettingsSection = null;
        Select(tag);
    }

    private void OpenSettings(string? section)
    {
        _pendingSettingsSection = section;
        NavigateTo("Settings");
    }

    private void OpenPairing()
    {
        ContentFrame.Navigate(typeof(PairDevicePage));
        Select(null);
        PageTitle.Text = "Pair a phone";
    }

    /// <summary>Highlights the rail item of the page on screen.</summary>
    private void Select(string? tag)
    {
        _selected = tag;
        var selected = (Brush)Application.Current.Resources["SubtleFillColorSecondaryBrush"];
        var accent = (Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"];
        var normal = (Brush)Application.Current.Resources["TextFillColorPrimaryBrush"];
        foreach (Button item in RailItems.Children.OfType<Button>().Append(SettingsItem))
        {
            bool on = (item.Tag as string) == tag;
            item.Background = on ? selected : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
            item.Foreground = on ? accent : normal;
        }
        if (tag is not null) PageTitle.Text = tag;
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
