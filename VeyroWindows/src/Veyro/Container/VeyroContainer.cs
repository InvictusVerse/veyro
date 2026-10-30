using Veyro.Core.Devices;
using Veyro.Core.Diagnostics;
using Veyro.Platform.Calling;
using Veyro.Platform.Interop;
using Veyro.Platform.Services;
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
    public LinkServer LinkServer { get; }
    public PeerSessionRegistry PeerSessions { get; }
    public Veyro.Core.Clipboard.ClipboardService Clipboard { get; }
    public Veyro.Platform.Services.WindowsClipboard WindowsClipboard { get; }
    public ClipboardViewModel ClipboardViewModel { get; }
    public NotificationMirrorService NotificationMirror { get; }
    public NotificationAppRules NotificationRules { get; }
    public PhoneMediaService PhoneMedia { get; }
    public IWindowsMediaSource PcMediaSource { get; }
    public PcMediaPublisher PcMediaPublisher { get; }
    public IWindowsBatterySource PcBatterySource { get; }
    public IWindowsNetworkSource PcNetworkSource { get; }
    public IWindowsWallpaperSource PcWallpaperSource { get; }
    public SessionKeeper SessionKeeper { get; }
    public IWindowsNetworkChangeSignal NetworkChangeSignal { get; }
    public DeviceStatusService DeviceStatus { get; }
    public DeviceNameService DeviceNames { get; }
    public WallpaperService Wallpaper { get; }
    public MessagingService Messaging { get; }
    public CallService Calls { get; }
    public PhotoService Photos { get; }
    public TransferConsentCoordinator TransferConsent { get; }
    public string? StartupError { get; set; }

    public ISystemTrayService SystemTrayService { get; }
    public IAppThemeModeService ThemeModeService { get; }
    public IDeviceManager DeviceManager { get; }
    public IFileTransferService FileTransferService { get; }
    internal BackgroundPower BackgroundPower { get; }
    public ILoginStartupService LoginStartup { get; }
    public INotificationToastService NotificationToastService { get; }
    public IBluetoothCallingService BluetoothCalling { get; }
    public CallRouter CallRouter { get; }
    public CallWindowService CallWindowService { get; }
    public INetworkDiagnosticsService NetworkDiagnostics { get; }
    public IQrCodeRenderer QrCodeRenderer { get; }
    public IPhotoActions PhotoActions { get; }

    // ViewModels
    public NotificationsViewModel NotificationsViewModel { get; }
    public PhoneMediaViewModel PhoneMediaViewModel { get; }
    public DeviceStatusViewModel DeviceStatusViewModel { get; }
    public MainPageViewModel MainPageViewModel { get; }
    public TransfersPageViewModel TransfersPageViewModel { get; }
    public MessagesViewModel MessagesViewModel { get; }
    public CallsPageViewModel CallsPageViewModel { get; }
    public PhotosViewModel PhotosViewModel { get; }
    public GeneralViewModel GeneralViewModel { get; }
    public DevicesViewModel DevicesViewModel { get; }
    public NotificationRulesViewModel NotificationRulesViewModel { get; }
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
        LinkServer = new LinkServer(
            IdentityManager.DeviceId.Value,
            SettingsStore.Current.DeviceName,
            TrustStore,
            SettingsStore.Current.DownloadDirectory,
            IdentityManager.PublicKey.Span,
            authorize: request => Task.FromResult(request.Peer.AutoAcceptTransfers),
            sessions: PeerSessions,
            // ADR-031: confirm pairing derives its key inside the identity (private key stays there).
            deriveSharedKey: (peerKey, salt, info) => IdentityManager.DeriveSharedKey(peerKey, salt, info));
        // ADR-063: per-app rules (settings.json, every phone) are applied before any toast; the
        // Earlier history is in memory only.
        NotificationRules = new NotificationAppRules(SettingsStore);
        NotificationMirror = new NotificationMirrorService(PeerSessions, NotificationRules);
        PhoneMedia = new PhoneMediaService(PeerSessions);
        PcMediaSource = new WindowsMediaSource();
        PcMediaPublisher = new PcMediaPublisher(PeerSessions, PcMediaSource);
        PcBatterySource = new WindowsBatterySource();
        // ADR-038: PC Wi-Fi and wallpaper for the phone's PC card; the phone's wallpaper for ours.
        PcNetworkSource = new WindowsNetworkSource();
        DeviceStatus = new DeviceStatusService(PeerSessions, PcBatterySource, PcNetworkSource);
        PcWallpaperSource = new WindowsWallpaperSource();
        Wallpaper = new WallpaperService(PeerSessions, PcWallpaperSource);
        // ADR-038 "Lock PC": the server raises pcLock only from a trusted peer's live session.
        PeerSessions.DeviceMessageReceived += (peerId, message) =>
        {
            if (message is not PeerMessage.PcLock || !PeerSessions.IsConnected(peerId)) return;
            if (!InteropHelpers.LockWorkStation()) VeyroTrace.Warn("Locking the PC failed");
        };
        // ADR-043: clipboard both ways; virtual clipboard in memory. The watcher starts on the UI
        // thread in App.OnLaunched (WinRT clipboard calls need it).
        Clipboard = new Veyro.Core.Clipboard.ClipboardService(
            PeerSessions,
            sendEnabled: () => SettingsStore.Current.SendClipboardToPhones,
            imagesEnabled: () => SettingsStore.Current.SyncClipboardImages);
        WindowsClipboard = new WindowsClipboard(Clipboard, () => SettingsStore.Current.SyncClipboardImages);
        Clipboard.Sink = WindowsClipboard;
        Clipboard.Notifier = new ClipboardToasts(peerId => TrustStore.GetPeer(peerId)?.ShownName);
        // ADR-059: this PC's name goes to every connected phone on connect and after a rename; each
        // phone's own name is stored whenever it announces a new one (a nickname is kept).
        DeviceNames = new DeviceNameService(PeerSessions, TrustStore, () => SettingsStore.Current.DeviceName);
        // ADR-027: in-memory only, cleared when the phone's session closes.
        Messaging = new MessagingService(PeerSessions);
        Calls = new CallService(PeerSessions);
        // ADR-034: metadata and thumbnails in memory; fetched originals in LocalCache, deleted
        // with the session. Offers answering our photoFetch are accepted without a prompt.
        Photos = new PhotoService(PeerSessions, Constants.LocalCacheDirectory);
        LinkServer.RequestedOffers = Photos;
        PhotoActions = new WindowsPhotoActions(() => App.WindowHandle);
        DiscoveryService = new UdpDiscoveryService(
            IdentityManager.DeviceId.Value,
            SettingsStore.Current.DeviceName,
            IdentityManager.DeriveLocalKey("presence-token"),
            IdentityManager.PublicKey.Span,
            controlPort: () => LinkServer.IsRunning ? LinkServer.Port : 0,
            // Background power: a beacon a second only while someone may be looking for the PC.
            announceFast: () => App.WindowOnScreen || LinkServer.PairingWindow.IsOpen);
        // ADR-040: dial a paired phone that is on the network but did not reconnect by itself.
        // The dialled session runs in the listener's session loop, so "Pause sync" ends it too.
        NetworkChangeSignal = new WindowsNetworkChangeSignal();
        SessionKeeper = new SessionKeeper(
            PeerSessions,
            DiscoveryService,
            TrustStore,
            new PersistentSessionDialer(IdentityManager.DeviceId.Value, () => SettingsStore.Current.DeviceName, LinkServer, PeerSessions),
            isEnabled: () => SettingsStore.Current.ReconnectAutomatically && LinkServer.IsRunning,
            NetworkChangeSignal);
        NetworkChangeSignal.Start();

        // Tray clicks arrive long after construction, once the main window exists.
        SystemTrayService = new SystemTrayService(
            open: App.ShowMainWindow,
            sendFiles: App.SendFilesFromTray,
            toggleSync: App.ToggleSync,
            isSyncPaused: () => App.SyncPaused,
            quit: App.ExitApplication,
            isStartupEnabled: () => App.StartupEnabledForMenu,
            toggleStartup: App.ToggleStartupFromTray,
            stopBackground: App.StopBackgroundFromTray);
        LoginStartup = new LoginStartupService();
        ThemeModeService = new AppThemeModeService();
        // Paired phones from the trust store, connected state from the live sessions.
        DeviceManager = new DeviceManagerService(TrustStore, DiscoveryService, PeerSessions, SessionKeeper);
        // ADR-059: a phone renamed itself: sidebar, device list and headers follow at once; toasts
        // and the consent prompt read the trust store when they show.
        DeviceNames.PeerNameChanged += DeviceManager.RefreshName;
        // This PC renamed in Settings: new hellos and pairing replies carry it, open sessions hear it.
        string announcedName = SettingsStore.Current.DeviceName;
        SettingsStore.SettingsChanged += settings =>
        {
            if (settings.DeviceName == announcedName) return;
            announcedName = settings.DeviceName;
            LinkServer.SetLocalName(settings.DeviceName);
            (DiscoveryService as UdpDiscoveryService)?.SetLocalName(settings.DeviceName);
            DeviceNames.LocalNameChanged();
        };
        // ADR-034: sends over the phone's live session; dials only when there is none.
        FileTransferService = new FileTransferService(HistoryStore, TrustStore, IdentityManager, SettingsStore, (DeviceManagerService)DeviceManager, PeerSessions);
        // ADR-047: what Veyro may do in the background (polling, EcoQoS), from three facts.
        BackgroundPower = new BackgroundPower(
            PeerSessions, LinkServer, FileTransferService, PcMediaSource, PcWallpaperSource, PcNetworkSource,
            windowOnScreen: () => App.WindowOnScreen);
        // ADR-029: Bluetooth hands-free call audio (Windows 11); answers route through CallRouter.
        BluetoothCalling = new BluetoothCallingService(SettingsStore);
        CallRouter = new CallRouter(Calls, BluetoothCalling);
        CallWindowService = new CallWindowService(BluetoothCalling, Calls, ThemeModeService);
        NotificationToastService = new NotificationToastService(
            NotificationMirror, Messaging, Calls, CallRouter,
            // Invoked on toast activation, long after construction has assigned the view model.
            openThread: (peerId, threadId) => MessagesViewModel!.RequestOpenThread(peerId, threadId),
            DataDirectory,
            // ADR-040: toasts name their phone; opening one shows that phone (UI thread).
            phoneName: peerId => TrustStore.GetPeer(peerId)?.ShownName,
            showPhone: peerId => DeviceManager.Select(peerId),
            // ADR-063: Copy code goes through the clipboard service, so it is not echoed to the phones.
            copyText: Clipboard.CopyTextToPcAsync,
            rules: NotificationRules);
        // ADR-036: incoming files are asked about one at a time, by dialog or actionable toast,
        // with a 60 s deadline that reaches the phone as "timeout", not "declined".
        // ADR-038: "Always accept from this phone" (pre-ticked) is saved in the trust store.
        TransferConsent = new TransferConsentCoordinator(new TransferConsentUi(NotificationToastService), trustStore: TrustStore);
        LinkServer.OfferConsent = TransferConsent.DecideAsync;

        NotificationsViewModel = new NotificationsViewModel(NotificationMirror, DeviceManager, NotificationRules, Clipboard.CopyTextToPcAsync);
        PhoneMediaViewModel = new PhoneMediaViewModel(PhoneMedia, DeviceManager);
        DeviceStatusViewModel = new DeviceStatusViewModel(DeviceStatus, Wallpaper, DeviceManager);

        MainPageViewModel = new MainPageViewModel(DeviceManager, FileTransferService);
        TransfersPageViewModel = new TransfersPageViewModel(DeviceManager, FileTransferService, SettingsStore);
        MessagesViewModel = new MessagesViewModel(Messaging, DeviceManager);
        CallsPageViewModel = new CallsPageViewModel(Calls, CallRouter, DeviceManager);
        BluetoothCallingViewModel = new BluetoothCallingViewModel(BluetoothCalling, DeviceManager);
        PhotosViewModel = new PhotosViewModel(Photos, DeviceManager);
        ClipboardViewModel = new ClipboardViewModel(Clipboard, SettingsStore, peerId => TrustStore.GetPeer(peerId)?.ShownName);
        DeviceNames.PeerNameChanged += _ => App.MainWindow?.DispatcherQueue.TryEnqueue(ClipboardViewModel.RefreshNames);
        GeneralViewModel = new GeneralViewModel(IdentityManager, SettingsStore, LoginStartup, SystemTrayService);
        DevicesViewModel = new DevicesViewModel(DeviceManager, TrustStore, PeerSessions);
        NotificationRulesViewModel = new NotificationRulesViewModel(NotificationRules);
        TransferConsent.AutoAcceptEnabled += _ => App.MainWindow?.DispatcherQueue.TryEnqueue(DevicesViewModel.RefreshAutoAccept);

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
