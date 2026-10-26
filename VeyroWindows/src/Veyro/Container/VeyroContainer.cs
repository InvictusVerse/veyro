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
    public string? StartupError { get; set; }

    public ISystemTrayService SystemTrayService { get; }
    public IAppThemeModeService ThemeModeService { get; }
    public IDeviceManager DeviceManager { get; }
    public IFileTransferService FileTransferService { get; }
    public IScreenMirrorService ScreenMirrorService { get; }
    public INotificationToastService NotificationToastService { get; }

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
        DiscoveryService = new UdpDiscoveryService(
            IdentityManager.DeviceId.Value,
            SettingsStore.Current.DeviceName,
            IdentityManager.DeriveLocalKey("presence-token"),
            IdentityManager.PublicKey.Span,
            controlPort: () => LinkServer.IsRunning ? LinkServer.Port : 0);

        SystemTrayService = new SystemTrayService();
        ThemeModeService = new AppThemeModeService();
        DeviceManager = new DeviceManagerService(TrustStore, DiscoveryService);
        FileTransferService = new FileTransferService(HistoryStore, TrustStore, IdentityManager, SettingsStore, (DeviceManagerService)DeviceManager);
        ScreenMirrorService = new ScreenMirrorService();
        NotificationToastService = new NotificationToastService(NotificationMirror, DataDirectory);

        NotificationsViewModel = new NotificationsViewModel(NotificationMirror, DeviceManager);
        PhoneMediaViewModel = new PhoneMediaViewModel(PhoneMedia, DeviceManager);
        DeviceStatusViewModel = new DeviceStatusViewModel(DeviceStatus, DeviceManager);

        MainPageViewModel = new MainPageViewModel(DeviceManager, FileTransferService, ScreenMirrorService);
        TransfersPageViewModel = new TransfersPageViewModel(DeviceManager, FileTransferService, SettingsStore);
        MessagesViewModel = new MessagesViewModel(DeviceManager);
        CallsPageViewModel = new CallsPageViewModel(DeviceManager);
        AppsViewModel = new AppsViewModel(DeviceManager);
        GeneralViewModel = new GeneralViewModel(IdentityManager, SettingsStore);
        DevicesViewModel = new DevicesViewModel(DeviceManager, TrustStore);
    }

    public static VeyroContainer Initialize(string? dataDirectory = null)
    {
        Instance = new VeyroContainer(dataDirectory);
        return Instance;
    }
}
