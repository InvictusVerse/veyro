using Veyro.Platforms.Windows.Calling;
using Veyro.Platforms.Windows.Services;
using Veyro.Services;
using Veyro.ViewModels.Settings;

namespace Veyro.Container;

/// <summary>
/// Process-scoped manual dependency injection container (ADR-005).
/// Mirrors VeyroContainer on Android — zero reflection or third-party DI framework overhead.
/// </summary>
public sealed class VeyroContainer
{
    public static VeyroContainer Instance { get; private set; } = null!;

    public string DataDirectory { get; }
    public ISeedStore SeedStore { get; }
    public IIdentityManager IdentityManager { get; }
    public ISettingsStore SettingsStore { get; }
    public ITrustStore TrustStore { get; }
    public ITransferHistoryStore HistoryStore { get; }
    public IDiscoveryService DiscoveryService { get; }
    public VeyroLinkServer LinkServer { get; }
    public PeerSessionRegistry PeerSessions { get; }
    public NotificationMirrorService NotificationMirror { get; }
    public PhoneMediaService PhoneMedia { get; }
    public IWindowsMediaSource PcMediaSource { get; }
    public PcMediaPublisher PcMediaPublisher { get; }
    public IWindowsBatterySource PcBatterySource { get; }
    public DeviceStatusService DeviceStatus { get; }
    public MessagingService Messaging { get; }
    public CallService Calls { get; }
    public string? StartupError { get; set; }

    public ISystemTrayService SystemTrayService { get; }
    public IAppThemeModeService ThemeModeService { get; }
    public IDeviceManager DeviceManager { get; }
    public IFileTransferService FileTransferService { get; }
    public ILoginStartupService LoginStartup { get; }
    public INotificationToastService NotificationToastService { get; }
    public IBluetoothCallingService BluetoothCalling { get; }
    public CallRouter CallRouter { get; }
    public CallWindowService CallWindowService { get; }
    public INetworkDiagnosticsService NetworkDiagnostics { get; }
    public IQrCodeRenderer QrCodeRenderer { get; }

    // ViewModels
    public NotificationsViewModel NotificationsViewModel { get; }
    public PhoneMediaViewModel PhoneMediaViewModel { get; }
    public DeviceStatusViewModel DeviceStatusViewModel { get; }
    public MainPageViewModel MainPageViewModel { get; }
    public TransfersPageViewModel TransfersPageViewModel { get; }
    public MessagesViewModel MessagesViewModel { get; }
    public CallsPageViewModel CallsPageViewModel { get; }
    public AppsViewModel AppsViewModel { get; }
    public GeneralViewModel GeneralViewModel { get; }
    public DevicesViewModel DevicesViewModel { get; }
    public BluetoothCallingViewModel BluetoothCallingViewModel { get; }
    public PairingViewModel PairingViewModel { get; }
    public OnboardingViewModel OnboardingViewModel { get; }

    public VeyroContainer(string? dataDirectory = null)
    {
        DataDirectory = dataDirectory ?? Constants.LocalAppDataDirectory;
        Directory.CreateDirectory(DataDirectory);

        SeedStore = new DpapiSeedStore(DataDirectory);
        IdentityManager = new DefaultIdentityManager(SeedStore);
        SettingsStore = new FileSettingsStore(DataDirectory);
        TrustStore = new FileTrustStore(DataDirectory, IdentityManager.DeriveLocalKey("trust-store-hmac"));
        HistoryStore = new FileTransferHistoryStore(DataDirectory);
        PeerSessions = new PeerSessionRegistry();
        LinkServer = new VeyroLinkServer(
            IdentityManager.DeviceId.Value,
            SettingsStore.Current.DeviceName,
            TrustStore,
            SettingsStore.Current.DownloadDirectory,
            IdentityManager.PublicKey.Span,
            authorize: request => Task.FromResult(request.Peer.AutoAcceptTransfers),
            sessions: PeerSessions);
        NotificationMirror = new NotificationMirrorService(PeerSessions);
        PhoneMedia = new PhoneMediaService(PeerSessions);
        PcMediaSource = new WindowsMediaSource();
        PcMediaPublisher = new PcMediaPublisher(PeerSessions, PcMediaSource);
        PcBatterySource = new WindowsBatterySource();
        DeviceStatus = new DeviceStatusService(PeerSessions, PcBatterySource);
        // ADR-027: in-memory only, cleared when the phone's session closes.
        Messaging = new MessagingService(PeerSessions);
        Calls = new CallService(PeerSessions);
        DiscoveryService = new UdpDiscoveryService(
            IdentityManager.DeviceId.Value,
            SettingsStore.Current.DeviceName,
            IdentityManager.DeriveLocalKey("presence-token"),
            IdentityManager.PublicKey.Span,
            controlPort: () => LinkServer.IsRunning ? LinkServer.Port : 0);

        // Tray clicks arrive long after construction, once the main window exists.
        SystemTrayService = new SystemTrayService(open: App.ShowMainWindow, quit: App.ExitApplication);
        LoginStartup = new LoginStartupService();
        ThemeModeService = new AppThemeModeService();
        // Paired phones from the trust store, connected state from the live sessions.
        DeviceManager = new DeviceManagerService(TrustStore, DiscoveryService, PeerSessions);
        FileTransferService = new FileTransferService(HistoryStore, TrustStore, IdentityManager, SettingsStore, (DeviceManagerService)DeviceManager);
        // ADR-029: Bluetooth hands-free call audio (Windows 11); answers route through CallRouter.
        BluetoothCalling = new BluetoothCallingService(SettingsStore);
        CallRouter = new CallRouter(Calls, BluetoothCalling);
        CallWindowService = new CallWindowService(BluetoothCalling, Calls, ThemeModeService);
        NotificationToastService = new NotificationToastService(
            NotificationMirror, Messaging, Calls, CallRouter,
            // Invoked on toast activation, long after construction has assigned the view model.
            openThread: (peerId, threadId) => MessagesViewModel!.RequestOpenThread(peerId, threadId),
            DataDirectory);

        NotificationsViewModel = new NotificationsViewModel(NotificationMirror, DeviceManager);
        PhoneMediaViewModel = new PhoneMediaViewModel(PhoneMedia, DeviceManager);
        DeviceStatusViewModel = new DeviceStatusViewModel(DeviceStatus, DeviceManager);

        MainPageViewModel = new MainPageViewModel(DeviceManager, FileTransferService);
        TransfersPageViewModel = new TransfersPageViewModel(DeviceManager, FileTransferService, SettingsStore);
        MessagesViewModel = new MessagesViewModel(Messaging, DeviceManager);
        CallsPageViewModel = new CallsPageViewModel(Calls, CallRouter, DeviceManager);
        BluetoothCallingViewModel = new BluetoothCallingViewModel(BluetoothCalling, DeviceManager);
        AppsViewModel = new AppsViewModel();
        GeneralViewModel = new GeneralViewModel(IdentityManager, SettingsStore, LoginStartup, SystemTrayService);
        DevicesViewModel = new DevicesViewModel(DeviceManager, TrustStore);

        // ADR-030: QR pairing screen (shared by onboarding and Settings) and first-run flow.
        NetworkDiagnostics = new WindowsNetworkDiagnostics();
        QrCodeRenderer = new QrCodeRenderer();
        PairingViewModel = new PairingViewModel(
            LinkServer, DiscoveryService, TrustStore, IdentityManager, SettingsStore,
            NetworkDiagnostics, QrCodeRenderer, startupError: () => StartupError);
        OnboardingViewModel = new OnboardingViewModel(SettingsStore);
    }

    public static VeyroContainer Initialize(string? dataDirectory = null)
    {
        Instance = new VeyroContainer(dataDirectory);
        return Instance;
    }
}
