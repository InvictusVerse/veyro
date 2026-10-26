namespace Veyro.ViewModels;

/// <summary>
/// ViewModel for the Veyro shell: Control Center sidebar plus the top navigation tabs.
/// </summary>
public sealed class MainPageViewModel : BaseViewModel
{
    private readonly IDeviceManager _deviceManager;
    private readonly IFileTransferService _transferService;
    private readonly IScreenMirrorService _screenMirrorService;

    private PairedDeviceUiModel? _activeDevice;
    private ActiveTransfer? _activeTransfer;
    private bool _loadingScrcpy;

    public ObservableCollection<PairedDeviceUiModel> PairedDevices => _deviceManager.PairedDevices;
    public ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices => _deviceManager.DiscoveredDevices;
    public bool HasNoDiscoveredDevices => DiscoveredDevices.Count == 0;

    public PairedDeviceUiModel? Device
    {
        get => _activeDevice;
        set
        {
            if (SetProperty(ref _activeDevice, value))
            {
                _deviceManager.ActiveDevice = value;
                OnPropertyChanged(nameof(HasActiveDevice));
                OnPropertyChanged(nameof(HasNoActiveDevice));
            }
        }
    }

    public bool HasActiveDevice => Device is not null;
    public bool HasNoActiveDevice => Device is null;

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

    public bool LoadingScrcpy
    {
        get => _loadingScrcpy;
        set => SetProperty(ref _loadingScrcpy, value);
    }

    public MainPageViewModel(
        IDeviceManager deviceManager,
        IFileTransferService transferService,
        IScreenMirrorService screenMirrorService)
    {
        _deviceManager = deviceManager;
        _transferService = transferService;
        _screenMirrorService = screenMirrorService;

        _deviceManager.DiscoveredDevices.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasNoDiscoveredDevices));

        _activeDevice = _deviceManager.ActiveDevice;
        _activeTransfer = _transferService.ActiveTransfer;

        _deviceManager.ActiveDeviceChanged += dev =>
        {
            _activeDevice = dev;
            OnPropertyChanged(nameof(Device));
            OnPropertyChanged(nameof(HasActiveDevice));
            OnPropertyChanged(nameof(HasNoActiveDevice));
        };

        _transferService.ActiveTransferChanged += transfer =>
        {
            ActiveTransfer = transfer;
        };
    }

    public async Task StartScreenMirrorAsync()
    {
        if (Device is null) return;
        LoadingScrcpy = true;
        try
        {
            await _screenMirrorService.StartMirroringAsync(Device);
        }
        finally
        {
            LoadingScrcpy = false;
        }
    }

    public Task RefreshDiscoveryAsync() => _deviceManager.RefreshDiscoveryAsync();
}
