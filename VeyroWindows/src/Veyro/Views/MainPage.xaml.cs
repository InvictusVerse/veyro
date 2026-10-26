using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Veyro.ViewModels.Settings;

namespace Veyro.Views;

public sealed partial class MainPage : Page
{
    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public GeneralViewModel GeneralViewModel => VeyroContainer.Instance.GeneralViewModel;

    public MainPage()
    {
        InitializeComponent();
    }

    private void MainNavigationView_Loaded(object sender, RoutedEventArgs e)
    {
        if (MainNavigationView.MenuItems.FirstOrDefault() is NavigationViewItem firstItem)
        {
            MainNavigationView.SelectedItem = firstItem;
            ContentFrame.Navigate(typeof(TransfersPage));
        }
    }

    private void NavigationView_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.IsSettingsSelected)
        {
            ContentFrame.Navigate(typeof(SettingsPage));
            return;
        }

        if (args.SelectedItemContainer is NavigationViewItem item && item.Tag is string tag)
        {
            Type targetPage = tag switch
            {
                "Transfers" => typeof(TransfersPage),
                "Messages" => typeof(MessagesPage),
                "Calls" => typeof(CallsPage),
                "Apps" => typeof(AppsPage),
                _ => typeof(TransfersPage)
            };
            ContentFrame.Navigate(targetPage);
        }
    }

    private async void RefreshDiscovery_Click(object sender, RoutedEventArgs e)
    {
        await ViewModel.RefreshDiscoveryAsync();
    }

    private async void DiscoveredDevice_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not DiscoveredDeviceUiModel device) return;

        string message = VeyroContainer.Instance.StartupError is { } startupError
            ? startupError
            : $"This PC is visible at {device.EndpointText}. Open Pairing on your Android phone, tap this PC, and confirm the security code here.";
        var dialog = new ContentDialog
        {
            Title = $"Pair with {device.Name}",
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            CloseButtonText = "OK",
            XamlRoot = XamlRoot
        };
        await dialog.ShowAsync();
    }

    private void Grid_DragOver(object sender, DragEventArgs e)
    {
        if (e.DataView.Contains(StandardDataFormats.StorageItems))
        {
            e.AcceptedOperation = DataPackageOperation.Copy;
            e.DragUIOverride.Caption = "Send with Veyro";
        }
    }

    private async void Page_Drop(object sender, DragEventArgs e)
    {
        if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;

        var items = await e.DataView.GetStorageItemsAsync();
        var filePaths = items
            .OfType<StorageFile>()
            .Select(f => f.Path)
            .Where(p => !string.IsNullOrEmpty(p))
            .ToList();

        if (filePaths.Count > 0)
        {
            await VeyroContainer.Instance.TransfersPageViewModel.SendFilesToActiveDeviceAsync(filePaths);
        }
    }
}
