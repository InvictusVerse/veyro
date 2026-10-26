namespace Veyro.Data.Models;

/// <summary>
/// Observable UI model for a paired peer shown in the Veyro Control Center sidebar.
/// </summary>
public sealed class PairedDeviceUiModel : INotifyPropertyChanged
{
    private string _name = string.Empty;
    private bool _isConnected;
    private bool _isConnecting;
    private int _batteryLevel = 85;
    private bool _isCharging;
    private RingerMode _ringerMode = RingerMode.Normal;
    private bool _dndEnabled;
    private bool _bluetoothEnabled = true;

    public string DeviceId { get; init; } = string.Empty;
    public string Fingerprint { get; init; } = string.Empty;
    public PlatformType Platform { get; init; } = PlatformType.Android;

    public string Name
    {
        get => _name;
        set { if (_name != value) { _name = value; OnPropertyChanged(); } }
    }

    public bool IsConnected
    {
        get => _isConnected;
        set
        {
            if (_isConnected != value)
            {
                _isConnected = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(ConnectionStatusText));
            }
        }
    }

    public bool IsConnecting
    {
        get => _isConnecting;
        set
        {
            if (_isConnecting != value)
            {
                _isConnecting = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(ConnectionStatusText));
            }
        }
    }

    public string ConnectionStatusText => IsConnecting ? "Connecting..." : (IsConnected ? "Connected" : "Disconnected");

    public int BatteryLevel
    {
        get => _batteryLevel;
        set { if (_batteryLevel != value) { _batteryLevel = value; OnPropertyChanged(); OnPropertyChanged(nameof(BatteryText)); } }
    }

    public bool IsCharging
    {
        get => _isCharging;
        set { if (_isCharging != value) { _isCharging = value; OnPropertyChanged(); } }
    }

    public string BatteryText => $"{BatteryLevel}%";

    public RingerMode RingerMode
    {
        get => _ringerMode;
        set { if (_ringerMode != value) { _ringerMode = value; OnPropertyChanged(); } }
    }

    public bool DndEnabled
    {
        get => _dndEnabled;
        set { if (_dndEnabled != value) { _dndEnabled = value; OnPropertyChanged(); } }
    }

    public bool BluetoothEnabled
    {
        get => _bluetoothEnabled;
        set { if (_bluetoothEnabled != value) { _bluetoothEnabled = value; OnPropertyChanged(); } }
    }

    public ObservableCollection<string> Addresses { get; } = [];
    public ObservableCollection<AudioStreamModel> AudioStreams { get; } = [];
    public ObservableCollection<MediaSessionModel> MediaSessions { get; } = [];
    public ObservableCollection<NotificationModel> Notifications { get; } = [];

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? prop = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(prop));
}

/// <summary>A phone seen by discovery while the pairing screen is open (ADR-030). Discovery is untrusted.</summary>
public sealed record NearbyPhoneUiModel(string DeviceId, string Name, string Detail, bool IsPaired);

/// <summary>One line of the pairing screen's network check.</summary>
public sealed record NetworkCheckUiModel(
    string Title,
    string Detail,
    bool IsOk,
    string? ActionText = null,
    NetworkCheckAction Action = NetworkCheckAction.None)
{
    public bool IsProblem => !IsOk;
    public bool HasAction => ActionText is not null;
}

public sealed record DiscoveredDeviceUiModel(
    string DeviceId,
    string Name,
    string VerificationKey,
    string EndpointText,
    PlatformType Platform = PlatformType.Android
);
