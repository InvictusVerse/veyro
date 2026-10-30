// Adapted from Sefirah (https://github.com/shrimqy/Sefirah), src/Sefirah/ViewModels/MainPageViewModel.cs, GPL-3.0.
// Modified for Veyro.

namespace Veyro.ViewModels;

/// <summary>
/// The Veyro shell: sidebar device switcher and control center plus the top navigation tabs.
/// Every value comes from <see cref="IDeviceManager"/> (trust store + live sessions + discovery)
/// or the transfer service; with no phone connected nothing here is invented.
/// </summary>
public sealed class MainPageViewModel : BaseViewModel
{
    /// <summary>The phone frame's tooltip (ADR-066: clicking it opens the mirror window).</summary>
    public const string ScreenMirrorText = "Mirror this phone's screen";

    /// <summary>How long the pill says Connecting after start-up or a refresh.</summary>
    private static readonly TimeSpan ConnectingWindow = TimeSpan.FromSeconds(15);

    private readonly IDeviceManager _deviceManager;
    private readonly IFileTransferService _transferService;
    private readonly DispatcherQueueTimer? _connectingTimer;
    private readonly DateTimeOffset _connectingUntil = DateTimeOffset.UtcNow + ConnectingWindow;
    private bool _refreshing;

    private PairedDeviceUiModel? _activeDevice;
    private ActiveTransfer? _activeTransfer;

    public ObservableCollection<PairedDeviceUiModel> PairedDevices => _deviceManager.PairedDevices;
    public ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices => _deviceManager.DiscoveredDevices;
    public bool HasNoDiscoveredDevices => DiscoveredDevices.Count == 0;
    public bool HasDiscoveredDevices => DiscoveredDevices.Count > 0;

    /// <summary>The phone every page follows. Two-way: the switcher sets it.</summary>
    public PairedDeviceUiModel? Device
    {
        get => _activeDevice;
        set
        {
            // The ComboBox briefly reports null while its items are rebuilt; keep the phone.
            if (value is null && PairedDevices.Count > 0) return;
            if (SetProperty(ref _activeDevice, value))
            {
                _deviceManager.ActiveDevice = value;
                RaiseDeviceProperties();
            }
        }
    }

    public bool HasActiveDevice => Device is not null;
    public bool HasNoActiveDevice => Device is null;

    /// <summary>Sefirah shows a plain name for one phone and a switcher for several.</summary>
    public bool HasMultipleDevices => PairedDevices.Count > 1;
    public bool HasSingleDevice => PairedDevices.Count <= 1;

    public bool IsDeviceConnected => Device?.IsConnected == true;

    /// <summary>
    /// Phone Link's pill (ADR-038). "Connecting" while this PC dials the phone or a refresh is in
    /// flight (ADR-040), and for a short window after start-up while the phone is expected to open
    /// its session; otherwise not connected is Offline.
    /// </summary>
    public string ConnectionPillText => Device is null ? string.Empty
        : IsDeviceConnected ? "Connected"
        : IsConnecting ? "Connecting" : "Offline";

    public bool IsConnecting => Device is { IsConnected: false } device &&
        (device.IsConnecting || _refreshing || DateTimeOffset.UtcNow < _connectingUntil);
    public bool IsOffline => Device is { IsConnected: false } && !IsConnecting;

    public string ConnectionHelpText => Device switch
    {
        null => string.Empty,
        { IsConnected: true } => $"{Device.Name} is connected to this PC over the encrypted link.",
        _ => $"{Device.Name} is not connected. Keep Veyro installed on the phone and both on the same Wi-Fi or hotspot; the phone connects on its own, and this PC reconnects to it when it is seen on the network."
    };

    /// <summary>What discovery reports for the shown phone right now (untrusted, informational only).</summary>
    public string NetworkText
    {
        get
        {
            if (Device is null) return string.Empty;
            IReadOnlyList<string> seen = _deviceManager.GetSeenEndpoints(Device.DeviceId);
            return seen.Count == 0
                ? "Not seen on this network right now."
                : $"Seen on this network at {string.Join(", ", seen)}.";
        }
    }

    public string Fingerprint => Device?.Fingerprint ?? string.Empty;
    public bool HasFingerprint => !string.IsNullOrEmpty(Device?.Fingerprint);

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

    /// <summary>The control center asked for the device settings (Sefirah: device settings window).</summary>
    public event Action? OpenDeviceSettingsRequested;

    /// <summary>The sidebar asked for the pairing screen.</summary>
    public event Action? PairRequested;

    public MainPageViewModel(IDeviceManager deviceManager, IFileTransferService transferService)
    {
        _deviceManager = deviceManager;
        _transferService = transferService;

        _deviceManager.DiscoveredDevices.CollectionChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(HasNoDiscoveredDevices));
            OnPropertyChanged(nameof(HasDiscoveredDevices));
        };
        _deviceManager.PairedDevices.CollectionChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(HasMultipleDevices));
            OnPropertyChanged(nameof(HasSingleDevice));
        };

        _activeDevice = _deviceManager.ActiveDevice;
        _activeTransfer = _transferService.ActiveTransfer;

        _deviceManager.ActiveDeviceChanged += device =>
        {
            _activeDevice = device;
            OnPropertyChanged(nameof(Device));
            RaiseDeviceProperties();
        };
        _deviceManager.ConnectionChanged += deviceId =>
        {
            if (string.Equals(deviceId, _activeDevice?.DeviceId, StringComparison.OrdinalIgnoreCase)) RaiseDeviceProperties();
        };
        _deviceManager.DiscoveryChanged += () => OnPropertyChanged(nameof(NetworkText));

        _transferService.ActiveTransferChanged += transfer => ActiveTransfer = transfer;

        if (DispatcherQueue.GetForCurrentThread() is { } dispatcher)
        {
            _connectingTimer = dispatcher.CreateTimer();
            _connectingTimer.IsRepeating = false;
            _connectingTimer.Tick += (_, _) => RaiseConnectionPill();
            _connectingTimer.Interval = ConnectingWindow;
            _connectingTimer.Start();
        }
    }

    private void RaiseConnectionPill()
    {
        OnPropertyChanged(nameof(ConnectionPillText));
        OnPropertyChanged(nameof(IsConnecting));
        OnPropertyChanged(nameof(IsOffline));
    }

    public void SwitchToNextDevice(int wheelDelta) => _deviceManager.SelectNext(wheelDelta);

    public void OpenDeviceSettings() => OpenDeviceSettingsRequested?.Invoke();

    public void RequestPairing() => PairRequested?.Invoke();

    /// <summary>
    /// Refresh (ADR-040): dials the shown phone at once, without waiting for it to reconnect by
    /// itself, and rescans discovery. The pill says Connecting while that is in flight.
    /// </summary>
    public async Task RefreshDiscoveryAsync()
    {
        if (_refreshing) return;
        if (Device is not { } device)
        {
            await _deviceManager.RefreshDiscoveryAsync();
            return;
        }
        _refreshing = true;
        RaiseConnectionPill();
        try
        {
            await _deviceManager.ConnectNowAsync(device.DeviceId);
        }
        finally
        {
            _refreshing = false;
            RaiseConnectionPill();
            OnPropertyChanged(nameof(NetworkText));
        }
    }

    private void RaiseDeviceProperties()
    {
        foreach (string name in new[]
                 {
                     nameof(HasActiveDevice), nameof(HasNoActiveDevice), nameof(IsDeviceConnected),
                     nameof(ConnectionHelpText), nameof(NetworkText), nameof(Fingerprint), nameof(HasFingerprint),
                     nameof(ConnectionPillText), nameof(IsConnecting), nameof(IsOffline)
                 })
        {
            OnPropertyChanged(name);
        }
    }
}
