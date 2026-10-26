namespace Veyro.ViewModels;

public sealed class TransfersPageViewModel : BaseViewModel
{
    private readonly IDeviceManager _deviceManager;
    private readonly IFileTransferService _transferService;
    private readonly ISettingsStore _settingsStore;
    private ActiveTransfer? _activeTransfer;

    public ObservableCollection<TransferRecord> History => _transferService.History;
    public ObservableCollection<PairedDeviceUiModel> PairedDevices => _deviceManager.PairedDevices;

    public string DownloadDirectory => _settingsStore.Current.DownloadDirectory;

    public ActiveTransfer? ActiveTransfer
    {
        get => _activeTransfer;
        private set
        {
            if (SetProperty(ref _activeTransfer, value))
            {
                OnPropertyChanged(nameof(HasActiveTransfer));
            }
        }
    }

    public bool HasActiveTransfer => ActiveTransfer is not null;

    public TransfersPageViewModel(
        IDeviceManager deviceManager,
        IFileTransferService transferService,
        ISettingsStore settingsStore)
    {
        _deviceManager = deviceManager;
        _transferService = transferService;
        _settingsStore = settingsStore;
        _activeTransfer = _transferService.ActiveTransfer;

        _transferService.ActiveTransferChanged += t => ActiveTransfer = t;
    }

    public async Task SendFilesToActiveDeviceAsync(IReadOnlyList<string> filePaths)
    {
        var target = _deviceManager.ActiveDevice ?? _deviceManager.PairedDevices.FirstOrDefault();
        if (target is null) return;
        await _transferService.SendFilesAsync(target, filePaths);
    }

    public void CancelTransfer() => _transferService.CancelCurrentTransfer();
}

public sealed class MessagesViewModel(IDeviceManager deviceManager) : BaseViewModel
{
    public IDeviceManager DeviceManager { get; } = deviceManager;
}

public sealed class CallsPageViewModel(IDeviceManager deviceManager) : BaseViewModel
{
    public IDeviceManager DeviceManager { get; } = deviceManager;
}

public sealed class AppsViewModel(IDeviceManager deviceManager) : BaseViewModel
{
    public IDeviceManager DeviceManager { get; } = deviceManager;
}
