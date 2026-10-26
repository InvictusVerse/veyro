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

/// <summary>Shows mirrored phone notifications as Windows toasts and routes toast input back.</summary>
public interface INotificationToastService : IDisposable
{
    /// <summary>Registers for toast activation. Call once at startup, before any toast is shown.</summary>
    void Initialize();
}

/// <summary>
/// This PC's media sessions and master volume, published to the phone (ADR-026). Windows:
/// GlobalSystemMediaTransportControls and Core Audio.
/// </summary>
public interface IWindowsMediaSource : IPcMediaSource, IDisposable
{
    /// <summary>Starts watching sessions and volume. Failures are logged; the snapshot stays empty.</summary>
    Task StartAsync();
}

/// <summary>This PC's battery, published to the phone (ADR-026). Windows: PowerManager.</summary>
public interface IWindowsBatterySource : IPcBatterySource, IDisposable
{
    void Start();
}

/// <summary>A paired Bluetooth phone that offers the hands-free profile.</summary>
public sealed record BluetoothPhoneInfo(string TransportId, string Name);

/// <summary>
/// One call on the Bluetooth hands-free line (ADR-029). Windows: Windows.ApplicationModel.Calls.PhoneCall.
/// <see cref="Number"/> and <see cref="Name"/> are user data: never logged.
/// </summary>
public interface IBluetoothCall : IDisposable
{
    string CallId { get; }
    string? Number { get; }
    string? Name { get; }
    BluetoothCallStatus Status { get; }
    bool IsMuted { get; }
    CallAudioRoute AudioRoute { get; }

    /// <summary>Status, mute or audio route changed. Raised on a WinRT thread.</summary>
    event Action? Changed;

    Task<bool> AcceptAsync();
    Task<bool> RejectAsync();
    Task<bool> EndAsync();
    Task<bool> HoldAsync();
    Task<bool> ResumeAsync();
    Task<bool> MuteAsync();
    Task<bool> UnmuteAsync();
    Task<bool> SetAudioRouteAsync(CallAudioRoute route);
}

/// <summary>
/// The PC as the phone's Bluetooth hands-free device (ADR-029). Windows 11 only; on Windows 10
/// <see cref="IBluetoothCallLine.Status"/> stays <see cref="BluetoothLineStatus.NotSupported"/>.
/// Windows: PhoneLineTransportDevice, PhoneLine and PhoneCallManager.
/// </summary>
public interface IBluetoothCallingService : IBluetoothCallLine, IDisposable
{
    bool IsSupported { get; }
    IReadOnlyList<BluetoothPhoneInfo> Phones { get; }
    string? SelectedTransportId { get; }
    IReadOnlyList<IBluetoothCall> Calls { get; }

    /// <summary>A new call appeared on the connected line. Raised on a WinRT thread.</summary>
    event Action<IBluetoothCall>? CallAdded;

    /// <summary>Starts watching lines and calls and reconnects the saved phone. Failures are logged.</summary>
    Task InitializeAsync();

    Task RefreshPhonesAsync();

    /// <summary>Registers Veyro for the phone's line and connects it, linked to Veyro peer <paramref name="peerId"/>.</summary>
    Task ConnectAsync(string transportId, string? peerId);

    /// <summary>Gives the line back to Windows and forgets the choice.</summary>
    Task DisconnectAsync();
}

public interface IScreenMirrorService
{
    bool IsMirroring { get; }
    Task StartMirroringAsync(PairedDeviceUiModel device);
}
