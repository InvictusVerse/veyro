namespace Veyro.ViewModels;

/// <summary>
/// State behind the Classic window: the phone every page follows, its connection pill, the
/// phones discovery can see, and the transfer that is running. All values are read from
/// <see cref="IDeviceManager"/> (trust store, live sessions, discovery) and
/// <see cref="IFileTransferService"/>; with no phone connected nothing is invented.
/// </summary>
public sealed class MainPageViewModel : BaseViewModel
{
    /// <summary>Tooltip of the phone frame; clicking it opens the mirror window (ADR-066).</summary>
    public const string ScreenMirrorText = "Mirror this phone's screen";

    /// <summary>Time after start-up (or a manual refresh) during which Offline reads as Connecting.</summary>
    private static readonly TimeSpan StartupGrace = TimeSpan.FromSeconds(15);

    /// <summary>Everything that depends on which phone is shown or on its link state.</summary>
    private static readonly string[] DependentOnDevice =
    {
        nameof(HasActiveDevice), nameof(HasNoActiveDevice), nameof(IsDeviceConnected),
        nameof(ConnectionHelpText), nameof(NetworkText), nameof(Fingerprint), nameof(HasFingerprint),
        nameof(ConnectionPillText), nameof(IsConnecting), nameof(IsOffline)
    };

    private readonly IDeviceManager _devices;
    private readonly IFileTransferService _transfers;
    private readonly DateTimeOffset _graceEndsAt = DateTimeOffset.UtcNow + StartupGrace;
    private readonly DispatcherQueueTimer? _graceTimer;

    private PairedDeviceUiModel? _shownDevice;
    private ActiveTransfer? _runningTransfer;
    private bool _redialInFlight;

    public MainPageViewModel(IDeviceManager deviceManager, IFileTransferService transferService)
    {
        _devices = deviceManager;
        _transfers = transferService;

        _shownDevice = _devices.ActiveDevice;
        _runningTransfer = _transfers.ActiveTransfer;

        _devices.PairedDevices.CollectionChanged += (_, _) => RaiseAll(nameof(HasMultipleDevices), nameof(HasSingleDevice));
        _devices.DiscoveredDevices.CollectionChanged += (_, _) => RaiseAll(nameof(HasNoDiscoveredDevices), nameof(HasDiscoveredDevices));
        _devices.DiscoveryChanged += () => OnPropertyChanged(nameof(NetworkText));
        _devices.ActiveDeviceChanged += device =>
        {
            _shownDevice = device;
            OnPropertyChanged(nameof(Device));
            RaiseAll(DependentOnDevice);
        };
        _devices.ConnectionChanged += deviceId =>
        {
            if (string.Equals(deviceId, _shownDevice?.DeviceId, StringComparison.OrdinalIgnoreCase)) RaiseAll(DependentOnDevice);
        };
        _transfers.ActiveTransferChanged += transfer => ActiveTransfer = transfer;

        // One-shot timer: when the grace window ends the pill re-evaluates Connecting versus Offline.
        if (DispatcherQueue.GetForCurrentThread() is { } dispatcher)
        {
            _graceTimer = dispatcher.CreateTimer();
            _graceTimer.IsRepeating = false;
            _graceTimer.Interval = StartupGrace;
            _graceTimer.Tick += (_, _) => RaisePill();
            _graceTimer.Start();
        }
    }

    // ---- Devices -------------------------------------------------------------------------

    public ObservableCollection<PairedDeviceUiModel> PairedDevices => _devices.PairedDevices;

    public ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices => _devices.DiscoveredDevices;

    public bool HasDiscoveredDevices => DiscoveredDevices.Count > 0;

    public bool HasNoDiscoveredDevices => DiscoveredDevices.Count == 0;

    /// <summary>One phone is shown by name, several by a switcher.</summary>
    public bool HasMultipleDevices => PairedDevices.Count > 1;

    public bool HasSingleDevice => PairedDevices.Count <= 1;

    /// <summary>The phone every page follows. Two-way: the device switcher assigns it.</summary>
    public PairedDeviceUiModel? Device
    {
        get => _shownDevice;
        set
        {
            // A ComboBox reports null for a moment while it rebuilds its items; keep the phone then.
            if (value is null && PairedDevices.Count > 0) return;
            if (!SetProperty(ref _shownDevice, value)) return;
            _devices.ActiveDevice = value;
            RaiseAll(DependentOnDevice);
        }
    }

    public bool HasActiveDevice => _shownDevice is not null;

    public bool HasNoActiveDevice => _shownDevice is null;

    public string Fingerprint => _shownDevice?.Fingerprint ?? string.Empty;

    public bool HasFingerprint => !string.IsNullOrEmpty(_shownDevice?.Fingerprint);

    // ---- Connection pill (ADR-038, ADR-040) -----------------------------------------------

    public bool IsDeviceConnected => _shownDevice?.IsConnected == true;

    /// <summary>
    /// Not connected reads as Connecting while this PC is dialling the phone, while a manual
    /// refresh runs, and for a short grace window after start-up; otherwise it is Offline.
    /// </summary>
    public bool IsConnecting =>
        _shownDevice is { IsConnected: false } phone &&
        (phone.IsConnecting || _redialInFlight || DateTimeOffset.UtcNow < _graceEndsAt);

    public bool IsOffline => _shownDevice is { IsConnected: false } && !IsConnecting;

    public string ConnectionPillText
    {
        get
        {
            if (_shownDevice is null) return string.Empty;
            if (IsDeviceConnected) return "Connected";
            return IsConnecting ? "Connecting" : "Offline";
        }
    }

    public string ConnectionHelpText
    {
        get
        {
            if (_shownDevice is not { } phone) return string.Empty;
            return phone.IsConnected
                ? $"{phone.Name} is connected to this PC over the encrypted link."
                : $"{phone.Name} is not connected. Keep Veyro installed on the phone and both on the same Wi-Fi or hotspot; the phone connects on its own, and this PC reconnects to it when it is seen on the network.";
        }
    }

    /// <summary>Where discovery currently sees the shown phone (untrusted, informational only).</summary>
    public string NetworkText
    {
        get
        {
            if (_shownDevice is not { } phone) return string.Empty;
            IReadOnlyList<string> endpoints = _devices.GetSeenEndpoints(phone.DeviceId);
            return endpoints.Count == 0
                ? "Not seen on this network right now."
                : $"Seen on this network at {string.Join(", ", endpoints)}.";
        }
    }

    // ---- Transfers -------------------------------------------------------------------------

    public ActiveTransfer? ActiveTransfer
    {
        get => _runningTransfer;
        private set
        {
            if (SetProperty(ref _runningTransfer, value)) OnPropertyChanged(nameof(HasActiveTransfer));
        }
    }

    public bool HasActiveTransfer => _runningTransfer is not null;

    // ---- Requests raised for the shell -----------------------------------------------------

    /// <summary>A control asked for the device settings.</summary>
    public event Action? OpenDeviceSettingsRequested;

    /// <summary>A control asked for the pairing screen.</summary>
    public event Action? PairRequested;

    public void OpenDeviceSettings() => OpenDeviceSettingsRequested?.Invoke();

    public void RequestPairing() => PairRequested?.Invoke();

    public void SwitchToNextDevice(int wheelDelta) => _devices.SelectNext(wheelDelta);

    /// <summary>
    /// Refresh (ADR-040): dial the shown phone now instead of waiting for it to reconnect, or
    /// rescan discovery when no phone is shown. The pill reads Connecting while the dial runs.
    /// </summary>
    public async Task RefreshDiscoveryAsync()
    {
        if (_redialInFlight) return;
        if (_shownDevice is not { } phone)
        {
            await _devices.RefreshDiscoveryAsync();
            return;
        }

        _redialInFlight = true;
        RaisePill();
        try
        {
            await _devices.ConnectNowAsync(phone.DeviceId);
        }
        finally
        {
            _redialInFlight = false;
            RaisePill();
            OnPropertyChanged(nameof(NetworkText));
        }
    }

    // ---- Change notification ---------------------------------------------------------------

    private void RaisePill() => RaiseAll(nameof(ConnectionPillText), nameof(IsConnecting), nameof(IsOffline));

    private void RaiseAll(params string[] propertyNames)
    {
        foreach (string propertyName in propertyNames) OnPropertyChanged(propertyName);
    }
}
