using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Veyro.Views;

public sealed partial class TransfersPage : Page
{
    public TransfersPageViewModel ViewModel => VeyroContainer.Instance.TransfersPageViewModel;

    public TransfersPage()
    {
        InitializeComponent();
    }

    private async void SendFiles_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        InitializeWithWindow.Initialize(picker, App.WindowHandle);
        picker.FileTypeFilter.Add("*");

        var files = await picker.PickMultipleFilesAsync();
        if (files is { Count: > 0 })
        {
            var paths = files.Select(f => f.Path).Where(p => !string.IsNullOrEmpty(p)).ToList();
            await ViewModel.SendFilesToActiveDeviceAsync(paths);
        }
    }

    private void OpenDownloadsFolder_Click(object sender, RoutedEventArgs e)
    {
        string dir = ViewModel.DownloadDirectory;
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo
        {
            FileName = dir,
            UseShellExecute = true
        });
    }
}
