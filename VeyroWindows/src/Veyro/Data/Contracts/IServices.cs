namespace Veyro.Data.Contracts;

public interface ISystemTrayService : IDisposable
{
    bool IsAvailable { get; }
    void Initialize(nint windowHandle);
}

public interface IAppThemeModeService
{
    AppThemeMode CurrentTheme { get; }
    void ApplyTheme(Window window, AppThemeMode mode);
}

public interface IDeviceManager
{
    ObservableCollection<PairedDeviceUiModel> PairedDevices { get; }
    ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices { get; }
    PairedDeviceUiModel? ActiveDevice { get; set; }
    event Action<PairedDeviceUiModel?>? ActiveDeviceChanged;
    Task RefreshDiscoveryAsync();
}

public interface IFileTransferService
{
    ActiveTransfer? ActiveTransfer { get; }
    ObservableCollection<TransferRecord> History { get; }
    event Action<ActiveTransfer?>? ActiveTransferChanged;
    Task SendFilesAsync(PairedDeviceUiModel target, IReadOnlyList<string> filePaths);
    void CancelCurrentTransfer();
}

public interface IScreenMirrorService
{
    bool IsMirroring { get; }
    Task StartMirroringAsync(PairedDeviceUiModel device);
}
