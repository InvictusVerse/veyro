namespace Veyro.Data.Models;

/// <summary>
/// A paired phone in the sidebar device switcher. Identity comes from the trust store and
/// <see cref="IsConnected"/> from the live session registry; nothing here has a made-up default.
/// Battery, ringer, media and notifications are read from their services by the view models,
/// and only while the phone reports them.
/// </summary>
public sealed class PairedDeviceUiModel : INotifyPropertyChanged
{
    private string _name = string.Empty;
    private bool _isConnected;

    public string DeviceId { get; init; } = string.Empty;
    public string Fingerprint { get; init; } = string.Empty;
    public PlatformType Platform { get; init; } = PlatformType.Android;

    public string Name
    {
        get => _name;
        set { if (_name != value) { _name = value; OnPropertyChanged(); } }
    }

    /// <summary>True while the phone holds an authenticated session with this PC.</summary>
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
                OnPropertyChanged(nameof(StatusOpacity));
                OnPropertyChanged(nameof(ShowsConnecting));
            }
        }
    }

    private bool _isConnecting;

    /// <summary>This PC is dialling the phone right now (ADR-040).</summary>
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
                OnPropertyChanged(nameof(ShowsConnecting));
            }
        }
    }

    /// <summary>The switcher's amber dot: dialling and not yet connected.</summary>
    public bool ShowsConnecting => _isConnecting && !_isConnected;

    public string ConnectionStatusText => IsConnected ? "Connected" : IsConnecting ? "Connecting" : "Disconnected";

    private bool _autoAcceptTransfers;

    /// <summary>Files from this phone are saved without a prompt (trust store, ADR-038).</summary>
    public bool AutoAcceptTransfers
    {
        get => _autoAcceptTransfers;
        set
        {
            if (_autoAcceptTransfers != value)
            {
                _autoAcceptTransfers = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(AutoAcceptText));
            }
        }
    }

    public string AutoAcceptText => _autoAcceptTransfers
        ? "Files from this phone are saved without asking."
        : "You are asked before files from this phone are saved.";

    private bool _isActive;

    /// <summary>This is the phone the pages currently follow.</summary>
    public bool IsActive
    {
        get => _isActive;
        set
        {
            if (_isActive != value)
            {
                _isActive = value;
                OnPropertyChanged();
                OnPropertyChanged(nameof(IsNotActive));
                OnPropertyChanged(nameof(SelectText));
            }
        }
    }

    public bool IsNotActive => !_isActive;
    public string SelectText => _isActive ? "Selected" : "Select";

    /// <summary>Sefirah dims the phone's status row while it is offline.</summary>
    public double StatusOpacity => IsConnected ? 1.0 : 0.4;

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

/// <summary>A phone seen by discovery (untrusted: shown for pairing only, never as identity proof).</summary>
public sealed record DiscoveredDeviceUiModel(
    string DeviceId,
    string Name,
    string VerificationKey,
    string EndpointText,
    PlatformType Platform = PlatformType.Android
);
