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

    /// <summary>The saved file or folder of a received entry, if it is still there.</summary>
    private static string? ExistingTarget(object sender)
        => (sender as FrameworkElement)?.DataContext is TransferHistoryItemViewModel { SavedTarget: { } target } &&
           (File.Exists(target) || Directory.Exists(target))
            ? target
            : null;

    private void OpenReceived_Click(object sender, RoutedEventArgs e)
    {
        if (ExistingTarget(sender) is not { } target) return;
        try { Process.Start(new ProcessStartInfo { FileName = target, UseShellExecute = true }); }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Veyro.Core.Diagnostics.VeyroTrace.Warn($"Opening a received file failed: {error.GetType().Name}");
        }
    }

    private void ShowReceivedInFolder_Click(object sender, RoutedEventArgs e)
    {
        if (ExistingTarget(sender) is not { } target) return;
        // A Windows path cannot contain a quote, so quoting it is enough.
        try { Process.Start(new ProcessStartInfo { FileName = "explorer.exe", Arguments = $"/select,\"{target}\"", UseShellExecute = true }); }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Veyro.Core.Diagnostics.VeyroTrace.Warn($"Showing a received file failed: {error.GetType().Name}");
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
