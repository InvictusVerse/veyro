using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Veyro.UserControls;

public sealed partial class DeviceControlCenter : UserControl
{
    public MainPageViewModel ViewModel => VeyroContainer.Instance.MainPageViewModel;
    public DeviceStatusViewModel StatusViewModel => VeyroContainer.Instance.DeviceStatusViewModel;

    public DeviceControlCenter()
    {
        InitializeComponent();
    }

    private async void Ring_Click(object sender, RoutedEventArgs e) => await StatusViewModel.ToggleRingAsync();

    private async void RingerCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        int requested = RingerCombo.SelectedIndex;
        // Equal to the reported mode: this is the binding applying the phone's state.
        if (requested < 0 || requested == StatusViewModel.RingerIndex) return;
        // Show the reported mode until the phone confirms the change in its next devStatus.
        DispatcherQueue.TryEnqueue(() => RingerCombo.SelectedIndex = StatusViewModel.RingerIndex);
        await StatusViewModel.RequestRingerAsync(requested);
    }

    private async void Dnd_Click(object sender, RoutedEventArgs e)
    {
        bool requested = !StatusViewModel.IsDndOn;
        DndToggle.IsChecked = StatusViewModel.IsDndOn;
        await StatusViewModel.RequestDndAsync(requested);
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
