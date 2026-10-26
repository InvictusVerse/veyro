namespace Veyro.ViewModels.Settings;

/// <summary>
/// Settings > General. Every switch is backed by something that acts on it: sign-in startup by
/// the MSIX startup task, minimize-to-tray by the real tray icon. Name and received-files folder
/// are read by the listener at start, so the page says they apply after a restart.
/// </summary>
public sealed class GeneralViewModel : BaseViewModel
{
    public const string AppliesAfterRestart = "Applies the next time Veyro starts.";

    private readonly IIdentityManager _identityManager;
    private readonly ISettingsStore _settingsStore;
    private readonly ILoginStartupService _startup;
    private readonly ISystemTrayService _tray;

    private string _deviceName;
    private string _downloadDirectory;
    private bool _minimizeToTrayOnClose;
    private LoginStartupState _startupState = LoginStartupState.Unavailable;
    private bool _startupBusy;

    public string DeviceId => _identityManager.DeviceId.Value;
    public string Fingerprint => _identityManager.Fingerprint;

    public string DeviceName
    {
        get => _deviceName;
        set
        {
            if (SetProperty(ref _deviceName, value) && !string.IsNullOrWhiteSpace(value))
                _ = _settingsStore.UpdateAsync(s => s with { DeviceName = value.Trim() });
        }
    }

    public string DownloadDirectory
    {
        get => _downloadDirectory;
        set
        {
            if (!string.IsNullOrWhiteSpace(value) && SetProperty(ref _downloadDirectory, value))
                _ = _settingsStore.UpdateAsync(s => s with { DownloadDirectory = value });
        }
    }

    /// <summary>The startup task's real state; toggling asks Windows and shows what it applied.</summary>
    public bool AutoStartOnLogin
    {
        get => _startupState == LoginStartupState.Enabled;
        set
        {
            if (value != AutoStartOnLogin && !_startupBusy) _ = SetStartupAsync(value);
        }
    }

    public bool CanChangeAutoStart => !_startupBusy && _startupState is LoginStartupState.Enabled or LoginStartupState.Disabled;

    public string AutoStartText => _startupState switch
    {
        LoginStartupState.DisabledByUser => "Turned off in Windows Settings > Apps > Startup. Turn it on there.",
        LoginStartupState.DisabledByPolicy => "Your organisation does not allow Veyro to start at sign-in.",
        LoginStartupState.Unavailable => "Not available when Veyro runs without its installer package.",
        _ => "Veyro starts when you sign in to Windows, so the phone can connect without opening it."
    };

    public bool MinimizeToTrayOnClose
    {
        get => _minimizeToTrayOnClose;
        set
        {
            if (SetProperty(ref _minimizeToTrayOnClose, value))
                _ = _settingsStore.UpdateAsync(s => s with { MinimizeToTrayOnClose = value });
        }
    }

    public string TrayText => _tray.IsAvailable
        ? "Closing the window keeps Veyro running in the notification area. Right-click the icon to quit."
        : "The notification-area icon could not be created, so closing the window quits Veyro.";

    public string VersionText
    {
        get
        {
            try
            {
                var version = global::Windows.ApplicationModel.Package.Current.Id.Version;
                return $"{version.Major}.{version.Minor}.{version.Build}.{version.Revision}";
            }
            catch (Exception)
            {
                return typeof(GeneralViewModel).Assembly.GetName().Version?.ToString() ?? string.Empty;
            }
        }
    }

    public string ProtocolText => Constants.ProtocolVersionText;

    public GeneralViewModel(IIdentityManager identityManager, ISettingsStore settingsStore, ILoginStartupService startup, ISystemTrayService tray)
    {
        _identityManager = identityManager;
        _settingsStore = settingsStore;
        _startup = startup;
        _tray = tray;

        var current = _settingsStore.Current;
        _deviceName = current.DeviceName;
        _downloadDirectory = current.DownloadDirectory;
        _minimizeToTrayOnClose = current.MinimizeToTrayOnClose;
    }

    /// <summary>Reads the startup task state; call when the page is shown.</summary>
    public async Task RefreshAsync()
    {
        _startupState = await _startup.GetStateAsync();
        RaiseStartup();
        OnPropertyChanged(nameof(TrayText));
    }

    private async Task SetStartupAsync(bool enabled)
    {
        _startupBusy = true;
        RaiseStartup();
        try
        {
            _startupState = await _startup.SetEnabledAsync(enabled);
            await _settingsStore.UpdateAsync(s => s with { AutoStartOnLogin = _startupState == LoginStartupState.Enabled });
        }
        finally
        {
            _startupBusy = false;
            RaiseStartup();
        }
    }

    private void RaiseStartup()
    {
        OnPropertyChanged(nameof(AutoStartOnLogin));
        OnPropertyChanged(nameof(CanChangeAutoStart));
        OnPropertyChanged(nameof(AutoStartText));
    }

    public void ResetIdentity()
    {
        _identityManager.ResetIdentity();
        OnPropertyChanged(nameof(DeviceId));
        OnPropertyChanged(nameof(Fingerprint));
    }
}

/// <summary>
/// Settings section for Bluetooth call audio (ADR-029). Bluetooth carries only call audio and
/// call control; everything else stays on the encrypted Wi-Fi link.
/// </summary>
public sealed class BluetoothCallingViewModel : BaseViewModel
{
    private readonly IBluetoothCallingService _bluetooth;
    private readonly IDeviceManager _deviceManager;
    private readonly DispatcherQueue? _dispatcher;
    private int _selectedIndex = -1;
    private bool _busy;

    public BluetoothCallingViewModel(IBluetoothCallingService bluetooth, IDeviceManager deviceManager)
    {
        _bluetooth = bluetooth;
        _deviceManager = deviceManager;
        _dispatcher = DispatcherQueue.GetForCurrentThread();
        _bluetooth.Changed += () => Post(Sync);
        Sync();
    }

    public ObservableCollection<BluetoothPhoneInfo> Phones { get; } = [];

    public bool IsSupported => _bluetooth.IsSupported;
    public bool IsUnsupported => !IsSupported;

    public int SelectedIndex
    {
        get => _selectedIndex;
        set
        {
            if (SetProperty(ref _selectedIndex, value)) OnPropertyChanged(nameof(CanConnect));
        }
    }

    public BluetoothLineStatus Status => _bluetooth.Status;

    public bool CanConnect => IsSupported && !_busy && _selectedIndex >= 0 && _selectedIndex < Phones.Count
                              && Status is not BluetoothLineStatus.Connecting
                              && !(Status == BluetoothLineStatus.Connected && Phones[_selectedIndex].TransportId == _bluetooth.SelectedTransportId);

    public bool CanDisconnect => IsSupported && !_busy && _bluetooth.SelectedTransportId is not null;

    public bool IsBusy => _busy || Status == BluetoothLineStatus.Connecting;

    public string StatusText => Status switch
    {
        BluetoothLineStatus.NotSupported => "Bluetooth calling is not available on this PC. Calls still work over Wi-Fi with audio on the phone.",
        BluetoothLineStatus.NotPaired => "No phone is paired over Bluetooth yet. Pair it in Windows Bluetooth settings, then refresh.",
        BluetoothLineStatus.NotSelected => "Choose your phone and connect.",
        BluetoothLineStatus.Connecting => "Connecting...",
        BluetoothLineStatus.Connected => _bluetooth.LinkedPeerId is null
            ? $"Connected to {SelectedName}. Pair the phone with Veyro too so incoming-call alerts can answer here."
            : $"Connected to {SelectedName}. Answering a call from Veyro plays the audio on this PC.",
        BluetoothLineStatus.Disconnected => "Not connected. Turn on Bluetooth, keep the phone nearby and connect.",
        BluetoothLineStatus.AccessDenied => "Windows did not give Veyro the phone line. Another app, such as Phone Link, may be using it.",
        _ => "Could not connect. Check that Bluetooth is on and the phone is in range, then try again."
    };

    public async Task RefreshAsync()
    {
        await RunAsync(_bluetooth.RefreshPhonesAsync);
    }

    public async Task ConnectAsync()
    {
        if (!CanConnect) return;
        string transportId = Phones[_selectedIndex].TransportId;
        // Calls answered from this phone's Wi-Fi alerts use Bluetooth only when the two match.
        string? peerId = _deviceManager.ActiveDevice?.DeviceId;
        await RunAsync(() => _bluetooth.ConnectAsync(transportId, peerId));
    }

    public async Task DisconnectAsync()
    {
        await RunAsync(_bluetooth.DisconnectAsync);
    }

    private string SelectedName => Phones.FirstOrDefault(p => p.TransportId == _bluetooth.SelectedTransportId)?.Name ?? "the phone";

    private async Task RunAsync(Func<Task> action)
    {
        _busy = true;
        Sync();
        try { await action(); }
        catch (Exception error) { Core.Diagnostics.VeyroTrace.Error("Bluetooth calling action failed", error); }
        finally
        {
            _busy = false;
            Sync();
        }
    }

    private void Sync()
    {
        IReadOnlyList<BluetoothPhoneInfo> phones = _bluetooth.Phones;
        if (!phones.SequenceEqual(Phones))
        {
            string? keep = _selectedIndex >= 0 && _selectedIndex < Phones.Count ? Phones[_selectedIndex].TransportId : null;
            Phones.Clear();
            foreach (BluetoothPhoneInfo phone in phones) Phones.Add(phone);
            keep ??= _bluetooth.SelectedTransportId;
            SelectedIndex = phones.ToList().FindIndex(p => p.TransportId == keep);
            if (SelectedIndex < 0 && Phones.Count == 1) SelectedIndex = 0;
        }
        foreach (string name in new[]
                 {
                     nameof(Status), nameof(StatusText), nameof(CanConnect), nameof(CanDisconnect), nameof(IsBusy)
                 })
        {
            OnPropertyChanged(name);
        }
    }

    private void Post(Action action)
    {
        if (_dispatcher is null || _dispatcher.HasThreadAccess) action();
        else _dispatcher.TryEnqueue(() => action());
    }
}

public sealed class DevicesViewModel : BaseViewModel
{
    private readonly IDeviceManager deviceManager;
    private readonly ITrustStore trustStore;

    public DevicesViewModel(IDeviceManager deviceManager, ITrustStore trustStore)
    {
        this.deviceManager = deviceManager;
        this.trustStore = trustStore;
        deviceManager.PairedDevices.CollectionChanged += (_, _) => OnPropertyChanged(nameof(HasNoPairedDevices));
    }

    public ObservableCollection<PairedDeviceUiModel> PairedDevices => deviceManager.PairedDevices;
    public ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices => deviceManager.DiscoveredDevices;
    public bool HasNoPairedDevices => PairedDevices.Count == 0;

    /// <summary>
    /// Forgets the phone: it can no longer authenticate and must pair again. A session that is
    /// open right now stays until it drops (the link layer owns it).
    /// </summary>
    public async Task RevokeDeviceAsync(string deviceId)
    {
        await trustStore.RemoveAsync(deviceId);
        deviceManager.ReloadPairedDevices();
    }

    /// <summary>Makes the phone the one every page follows.</summary>
    public void Select(string deviceId)
    {
        PairedDeviceUiModel? device = PairedDevices.FirstOrDefault(d => string.Equals(d.DeviceId, deviceId, StringComparison.OrdinalIgnoreCase));
        if (device is not null) deviceManager.ActiveDevice = device;
    }
}
