namespace Veyro.ViewModels.Settings;

public sealed class GeneralViewModel : BaseViewModel
{
    private readonly IIdentityManager _identityManager;
    private readonly ISettingsStore _settingsStore;

    private string _deviceName;
    private bool _isDiscoverable;
    private string _downloadDirectory;
    private bool _autoStartOnLogin;
    private bool _minimizeToTrayOnClose;

    public string DeviceId => _identityManager.DeviceId.Value;
    public string Fingerprint => _identityManager.Fingerprint;

    public string DeviceName
    {
        get => _deviceName;
        set
        {
            if (SetProperty(ref _deviceName, value))
                _ = _settingsStore.UpdateAsync(s => s with { DeviceName = value });
        }
    }

    public bool IsDiscoverable
    {
        get => _isDiscoverable;
        set
        {
            if (SetProperty(ref _isDiscoverable, value))
                _ = _settingsStore.UpdateAsync(s => s with { IsDiscoverable = value });
        }
    }

    public string DownloadDirectory
    {
        get => _downloadDirectory;
        set
        {
            if (SetProperty(ref _downloadDirectory, value))
                _ = _settingsStore.UpdateAsync(s => s with { DownloadDirectory = value });
        }
    }

    public bool AutoStartOnLogin
    {
        get => _autoStartOnLogin;
        set
        {
            if (SetProperty(ref _autoStartOnLogin, value))
                _ = _settingsStore.UpdateAsync(s => s with { AutoStartOnLogin = value });
        }
    }

    public bool MinimizeToTrayOnClose
    {
        get => _minimizeToTrayOnClose;
        set
        {
            if (SetProperty(ref _minimizeToTrayOnClose, value))
                _ = _settingsStore.UpdateAsync(s => s with { MinimizeToTrayOnClose = value });
        }
    }

    public GeneralViewModel(IIdentityManager identityManager, ISettingsStore settingsStore)
    {
        _identityManager = identityManager;
        _settingsStore = settingsStore;

        var current = _settingsStore.Current;
        _deviceName = current.DeviceName;
        _isDiscoverable = current.IsDiscoverable;
        _downloadDirectory = current.DownloadDirectory;
        _autoStartOnLogin = current.AutoStartOnLogin;
        _minimizeToTrayOnClose = current.MinimizeToTrayOnClose;
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
        BluetoothLineStatus.NotSupported => "Bluetooth calling needs Windows 11. Calls still work over Wi-Fi with audio on the phone.",
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

    public async Task RevokeDeviceAsync(string deviceId)
    {
        await trustStore.RemoveAsync(deviceId);
        var existing = PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId);
        if (existing is not null)
            PairedDevices.Remove(existing);
    }
}
