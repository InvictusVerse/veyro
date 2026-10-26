namespace Veyro.Data.Contracts;

/// <summary>
/// The Veyro icon in the Windows notification area, shown only while tray mode is on (default
/// off, ADR-036). <see cref="IsAvailable"/> is true only while the icon actually exists, so
/// "keep running in the tray" never hides the window without a way back.
/// </summary>
public interface ISystemTrayService : IDisposable
{
    bool IsAvailable { get; }

    /// <summary>Prepares the icon's message window. UI thread. Adds no icon by itself.</summary>
    void Initialize(nint windowHandle);

    /// <summary>Adds or removes the icon. UI thread. A failure to add leaves IsAvailable false.</summary>
    void SetEnabled(bool enabled);
}

public interface IAppThemeModeService
{
    AppThemeMode CurrentTheme { get; }
    void ApplyTheme(Window window, AppThemeMode mode);
}

/// <summary>
/// Paired phones for the sidebar switcher (trust store) with live connection state (session
/// registry), the phone the UI follows, and phones discovery currently sees. UI thread only;
/// events are raised on the UI thread.
/// </summary>
public interface IDeviceManager
{
    ObservableCollection<PairedDeviceUiModel> PairedDevices { get; }

    /// <summary>Unpaired phones seen by discovery. Untrusted: offered for pairing only.</summary>
    ObservableCollection<DiscoveredDeviceUiModel> DiscoveredDevices { get; }

    PairedDeviceUiModel? ActiveDevice { get; set; }
    event Action<PairedDeviceUiModel?>? ActiveDeviceChanged;

    /// <summary>A paired phone connected or disconnected (argument: device id).</summary>
    event Action<string>? ConnectionChanged;

    /// <summary>Discovery saw, refreshed or lost a phone.</summary>
    event Action? DiscoveryChanged;

    /// <summary>Starts discovery if needed and re-reads its current peers.</summary>
    Task RefreshDiscoveryAsync();

    /// <summary>Re-reads the trust store, for example after a new device paired or one was removed.</summary>
    void ReloadPairedDevices();

    /// <summary>Moves the selection through the paired phones (mouse wheel over the phone).</summary>
    void SelectNext(int delta);

    /// <summary>Addresses discovery currently reports for the phone; empty when it is not seen. Untrusted.</summary>
    IReadOnlyList<string> GetSeenEndpoints(string deviceId);
}

public interface IFileTransferService
{
    ActiveTransfer? ActiveTransfer { get; }
    ObservableCollection<TransferRecord> History { get; }
    event Action<ActiveTransfer?>? ActiveTransferChanged;
    Task SendFilesAsync(PairedDeviceUiModel target, IReadOnlyList<string> filePaths);
    void CancelCurrentTransfer();

    /// <summary>Hides a finished, failed or cancelled transfer from the banner. No effect while one runs.</summary>
    void DismissFinishedTransfer();
}


/// <summary>Shows mirrored phone notifications as Windows toasts and routes toast input back.</summary>
public interface INotificationToastService : IDisposable
{
    /// <summary>Registers for toast activation. Call once at startup, before any toast is shown.</summary>
    void Initialize();

    /// <summary>
    /// Incoming-files toast (ADR-036, ADR-038): Accept (and accept this phone's files from now on,
    /// the pre-ticked default), Only this time, Decline. Null when the user clicked the toast body
    /// (Veyro is shown). Throws when toasts are unavailable; cancelling <paramref name="ct"/>
    /// removes the toast. Counts only, never file names.
    /// </summary>
    Task<ConsentAnswer?> ShowTransferRequestAsync(string peerName, int fileCount, long totalBytes, CancellationToken ct);
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

/// <summary>This PC's desktop wallpaper, published to the phone (ADR-038). Windows: SystemParametersInfo.</summary>
public interface IWindowsWallpaperSource : IPcWallpaperSource, IDisposable
{
    void Start();
}

/// <summary>This PC's Wi-Fi signal, published to the phone (ADR-038). Windows: NetworkInformation.</summary>
public interface IWindowsNetworkSource : IPcNetworkSource, IDisposable
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

public enum LoginStartupState
{
    Enabled,
    Disabled,
    /// <summary>Turned off in Task Manager or Settings; only the user can turn it back on there.</summary>
    DisabledByUser,
    DisabledByPolicy,
    /// <summary>Not running as a packaged app, or the startup task is missing.</summary>
    Unavailable
}

/// <summary>Start Veyro at sign-in through the MSIX startup task (Package.appxmanifest VeyroStartupTask).</summary>
public interface ILoginStartupService
{
    Task<LoginStartupState> GetStateAsync();

    /// <summary>Requests the change and returns the state Windows actually applied.</summary>
    Task<LoginStartupState> SetEnabledAsync(bool enabled);
}

/// <summary>
/// What the Photos page can do with a fetched original (ADR-034). Windows: Clipboard,
/// FileSavePicker, Launcher, DataTransferManager. Paths and names are user data: never logged.
/// </summary>
public interface IPhotoActions
{
    /// <summary>Puts the image on the clipboard as a bitmap.</summary>
    Task CopyAsync(string path);

    /// <summary>Asks where to save a copy. False when the user cancelled.</summary>
    Task<bool> SaveAsAsync(string path, string suggestedName);

    /// <summary>Opens the file in the default app for its type.</summary>
    Task<bool> OpenAsync(string path);

    /// <summary>Shows the Windows share sheet for the file.</summary>
    Task ShareAsync(string path, string title);
}

/// <summary>Renders QR codes as PNG bytes (ADR-030). The content may hold a pairing code: never log it.</summary>
public interface IQrCodeRenderer
{
    byte[] RenderPng(string content, int pixelsPerModule = 10);
}

public enum FirewallRuleState
{
    Unknown,
    Present,
    Missing
}

/// <summary>A connected network and whether Windows treats it as Public.</summary>
public sealed record NetworkProfileInfo(string InterfaceName, bool IsPublic);

public sealed record NetworkCheckResult(FirewallRuleState Firewall, IReadOnlyList<NetworkProfileInfo> Profiles);

/// <summary>
/// Windows Firewall and network-profile checks for the pairing screen, so problems are named
/// instead of failing silently. Windows: NetSecurity / NetConnection PowerShell cmdlets.
/// </summary>
public interface INetworkDiagnosticsService
{
    /// <summary>Reads the Veyro firewall rule and the network profiles. Never throws.</summary>
    Task<NetworkCheckResult> CheckAsync();

    /// <summary>Adds the inbound rule for TCP 5150-5169 on private networks after a UAC prompt. True on success.</summary>
    Task<bool> RequestFirewallRuleAsync();
}
