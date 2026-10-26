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

public sealed class DevicesViewModel(IDeviceManager deviceManager, ITrustStore trustStore) : BaseViewModel
{
    public ObservableCollection<PairedDeviceUiModel> PairedDevices => deviceManager.PairedDevices;
    public ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices => deviceManager.DiscoveredDevices;

    public async Task RevokeDeviceAsync(string deviceId)
    {
        await trustStore.RemoveAsync(deviceId);
        var existing = PairedDevices.FirstOrDefault(d => d.DeviceId == deviceId);
        if (existing is not null)
            PairedDevices.Remove(existing);
    }
}
