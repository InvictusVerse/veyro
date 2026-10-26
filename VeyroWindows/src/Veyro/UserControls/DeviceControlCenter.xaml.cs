using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Veyro.UserControls;

public sealed partial class DeviceControlCenter : UserControl
{
    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;

    public DeviceControlCenter()
    {
        InitializeComponent();
    }

    private async void SendFilesButton_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        InitializeWithWindow.Initialize(picker, App.WindowHandle);
        picker.FileTypeFilter.Add("*");

        var files = await picker.PickMultipleFilesAsync();
        if (files is { Count: > 0 })
        {
            var paths = files.Select(f => f.Path).Where(p => !string.IsNullOrEmpty(p)).ToList();
            await VeyroContainer.Instance.TransfersPageViewModel.SendFilesToActiveDeviceAsync(paths);
        }
    }

    private void SendClipboardButton_Click(object sender, RoutedEventArgs e)
    {
    }

    private void OpenDownloadsButton_Click(object sender, RoutedEventArgs e)
    {
        string dir = VeyroContainer.Instance.SettingsStore.Current.DownloadDirectory;
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo
        {
            FileName = dir,
            UseShellExecute = true
        });
    }

    private async void RefreshConnection_Click(object sender, RoutedEventArgs e)
    {
        await ViewModel.RefreshDiscoveryAsync();
    }
}
