namespace Veyro.Views;

public sealed partial class TransfersPage : Page
{
    public TransfersPageViewModel ViewModel => VeyroContainer.Instance.TransfersPageViewModel;
    public MainPageViewModel Main => VeyroContainer.Instance.MainPageViewModel;

    public TransfersPage()
    {
        InitializeComponent();
    }

    /// <summary>ADR-038: straight to the system file picker.</summary>
    private async void SendFiles_Click(object sender, RoutedEventArgs e) => await App.PickAndSendFilesAsync();

    /// <summary>ADR-064: straight to the system folder picker.</summary>
    private async void SendFolder_Click(object sender, RoutedEventArgs e) => await App.PickAndSendFolderAsync();

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
