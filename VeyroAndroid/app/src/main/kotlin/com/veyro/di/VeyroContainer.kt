package com.veyro.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.veyro.ui.onboarding.FeatureAccess
import com.veyro.ui.onboarding.OnboardingFeature
import com.veyro.ui.onboarding.SpecialAccess
import com.veyro.core.common.AndroidLogSink
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.CompositeLogSink
import com.veyro.core.common.DefaultAppDispatchers
import com.veyro.core.common.Diagnostics
import com.veyro.core.common.LogLevel
import com.veyro.core.common.RecordingLogSink
import com.veyro.core.common.VeyroLog
import com.veyro.core.crypto.DefaultIdentityManager
import com.veyro.core.crypto.HandshakeEngine
import com.veyro.core.crypto.NoiseHandshakeEngine
import com.veyro.core.crypto.IdentityManager
import com.veyro.core.crypto.IdentityUnavailableException
import com.veyro.core.crypto.LocalKeys
import com.veyro.core.crypto.SeedStore
import com.veyro.core.crypto.VlpKdf
import com.veyro.core.crypto.keystoreSeedStore
import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.FileSettingsStore
import com.veyro.core.data.FileTransferHistoryStore
import com.veyro.core.data.FileTrustStore
import com.veyro.core.data.SettingsStore
import com.veyro.core.data.TransferHistoryStore
import com.veyro.core.data.TrustStore
import com.veyro.core.discovery.DiscoveryManager
import com.veyro.core.discovery.DiscoveryManagerImpl
import com.veyro.core.discovery.DiscoveryReason
import com.veyro.core.discovery.DiscoveryRequest
import com.veyro.core.discovery.MulticastLockHolder
import com.veyro.core.discovery.NsdMdnsAdvertiser
import com.veyro.core.discovery.NsdMdnsBrowser
import com.veyro.core.discovery.UdpBeaconImpl
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import com.veyro.core.companion.AndroidDeviceController
import com.veyro.core.companion.AndroidDeviceStatusSource
import com.veyro.core.companion.AndroidCallController
import com.veyro.core.companion.AndroidCallSource
import com.veyro.core.companion.AndroidMediaSource
import com.veyro.core.companion.AndroidSmsSource
import com.veyro.core.companion.CallBridge
import com.veyro.core.companion.MessagingPermissions
import com.veyro.core.companion.SmsBridge
import com.veyro.core.companion.AndroidNotificationSource
import com.veyro.core.companion.AndroidPhotoSource
import com.veyro.core.companion.CompanionLink
import com.veyro.core.companion.PeerConnectionLink
import com.veyro.core.companion.PhotoBridge
import com.veyro.core.companion.PhotoSender
import com.veyro.core.link.SendResult
import com.veyro.core.companion.DeviceStatusPublisher
import com.veyro.core.companion.MediaMirror
import com.veyro.core.companion.NotificationMirror
import com.veyro.core.companion.VeyroNotificationListener
import com.veyro.core.companion.asCompanionLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap
import com.veyro.core.link.ConnectionManager
import com.veyro.core.link.TransferCoordinator
import com.veyro.core.link.TransferRunner
import com.veyro.core.transfer.TransferStoragePublisher
import com.veyro.storage.AndroidMediaStorePublisher
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import com.veyro.core.model.Platform
import com.veyro.core.link.LinkMessage
import com.veyro.core.link.OutgoingFile
import com.veyro.core.companion.AndroidWallpaperSource
import com.veyro.core.companion.WallpaperBridge
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.veyro.core.model.fingerprint
import com.veyro.core.network.AndroidNetworkMonitor
import com.veyro.core.network.AndroidWifiDirectManager
import com.veyro.core.network.DefaultLocalAddressProvider
import com.veyro.core.network.InterfaceClassifier
import com.veyro.core.network.LocalAddressProvider
import com.veyro.core.network.NetworkMonitor
import com.veyro.core.network.WifiDirectManager
import com.veyro.core.protocol.PresenceRecord
import com.veyro.core.protocol.Vlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/**
 * The object graph, written by hand (ADR-005).
 *
 * There is no DI framework. The graph is small, process-scoped and almost entirely singletons,
 * which is the case where a framework buys the least and costs the most.
 *
 * Rules for editing this file:
 *  - Everything is `by lazy`, so nothing is constructed until something needs it. That keeps cold
 *    start fast (NFR-09) and - more importantly here - means **no socket is opened and no packet
 *    is sent merely because the app launched** (NFR-02).
 *  - Construction order is expressed by reference, not an `init()` sequence.
 *  - Nothing here may hold an Activity or a View.
 */
public class VeyroContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    public val dispatchers: AppDispatchers by lazy { DefaultAppDispatchers() }

    /**
     * Process-lifetime scope for long-running components.
     *
     * `SupervisorJob` so one failing collector - a discovery mechanism that throws on an odd
     * network, say - cannot cancel the others and take the whole app's background work with it.
     */
    private val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + dispatchers.default)
    }

    /** In-memory log tail, so the debug screen works without a laptop attached. */
    public val logRecorder: RecordingLogSink by lazy { RecordingLogSink() }

    // ── P1 — identity and local storage ────────────────────────────────────────────────────

    private val storageDir: File by lazy { appContext.filesDir }

    public val seedStore: SeedStore by lazy { keystoreSeedStore(appContext) }

    public val identityManager: IdentityManager by lazy { DefaultIdentityManager(seedStore) }

    /**
     * The trust store is authenticated with a key derived from the identity seed.
     *
     * That binding is the mechanism behind REQ-I10: after an identity reset every derived key
     * changes, so the trust file stops authenticating and reads as empty. "Reset my identity"
     * therefore really does mean "all pairings are gone", enforced by cryptography rather than by
     * remembering to delete a second file.
     */
    public val trustStore: TrustStore by lazy {
        val macKey = runBlocking {
            try {
                identityManager.deriveLocalKey(LocalKeys.TRUST_STORE_MAC)
            } catch (e: IdentityUnavailableException) {
                VeyroLog.e("Container", e) { "No identity; trust store will be unreadable" }
                ByteArray(32)
            }
        }
        FileTrustStore(storageDir, macKey, dispatchers)
    }

    public val settingsStore: SettingsStore by lazy {
        FileSettingsStore(storageDir, defaultDeviceName = defaultDeviceName())
    }

    // ── P2 — network and discovery ─────────────────────────────────────────────────────────

    public val localAddressProvider: LocalAddressProvider by lazy {
        DefaultLocalAddressProvider(dispatchers)
    }

    public val networkMonitor: NetworkMonitor by lazy {
        AndroidNetworkMonitor(appContext, dispatchers, localAddressProvider)
    }

    public val discoveryManager: DiscoveryManager by lazy {
        DiscoveryManagerImpl(
            dispatchers = dispatchers,
            scope = appScope,
            advertiser = NsdMdnsAdvertiser(appContext, dispatchers),
            browser = NsdMdnsBrowser(appContext, dispatchers) {
                runCatching { runBlocking { identityManager.currentIdentity()?.deviceId?.value } }
                    .getOrNull()
            },
            beacon = UdpBeaconImpl(
                dispatchers = dispatchers,
                addressProvider = localAddressProvider,
                scope = appScope,
                recordProvider = { port -> buildPresenceRecord(port, includeName = false) },
                selfDeviceId = {
                    runCatching { runBlocking { identityManager.currentIdentity()?.deviceId?.value } }
                        .getOrNull()
                },
            ),
            multicastLock = MulticastLockHolder(appContext),
            trustedPeers = { runCatching { trustStore.snapshot() }.getOrDefault(emptyList()) },
            presenceRecord = { port, includeName -> buildPresenceRecord(port, includeName) },
            listeningPort = { connectionManager.startListening() },
        )
    }

    public val deviceRepository: DeviceRepository by lazy {
        DefaultDeviceRepository(discoveryManager)
    }

    /**
     * The connection layer: pairing and device-to-device peer connections.
     *
     * Ported from the working Invox ecosystem code. It handles WebSocket transport, preamble
     * exchange, PIN pairing, and authenticated session management.
     */
    public val connectionManager: ConnectionManager by lazy {
        ConnectionWiring.create(
            dispatchers = dispatchers,
            scope = appScope,
            identityManager = identityManager,
            trustStore = trustStore,
            deviceName = { runCatching { settingsStore.deviceName.first() }.getOrDefault("Veyro device") },
        ).also { connectionManagerReady.value = it }
    }

    /**
     * Set the moment [connectionManager] is first built, by whatever needed it.
     *
     * Lets always-on observers (media, device status) follow the connection without being the
     * reason it, the trust store and the identity get constructed at launch.
     */
    private val connectionManagerReady = MutableStateFlow<ConnectionManager?>(null)

    /** The live paired session as a [CompanionLink], null while there is none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val companionLinks: Flow<CompanionLink?> by lazy {
        connectionManagerReady
            .flatMapLatest { manager -> manager?.activeConnection ?: flowOf(null) }
            .map { it?.asCompanionLink() }
    }

    /** Device id of the PC the live session is with, for the Home screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val connectedPeerId: Flow<String?> by lazy {
        connectionManagerReady
            .flatMapLatest { manager -> manager?.activeConnection ?: flowOf(null) }
            .map { it?.peerDeviceId }
    }

    /** What the connected PC advertised in its hello, for Home's Lock PC (ADR-038). */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val connectedPeerCapabilities: Flow<Capabilities> by lazy {
        connectionManagerReady
            .flatMapLatest { manager -> manager?.activeConnection ?: flowOf(null) }
            .flatMapLatest { connection -> connection?.peerCapabilities ?: flowOf(Capabilities.NONE) }
    }

    /** Home's Lock PC: `pcLock` over the live session (ADR-038). False when there is none. */
    public suspend fun lockConnectedPc(): Boolean {
        val connection = connectionManagerReady.value?.activeConnection?.value ?: return false
        if (!connection.isConnected || Capability.DEVICE_STATUS !in connection.peerCapabilities.value) return false
        return runCatching { connection.send(LinkMessage.PcLock) }
            .onFailure { VeyroLog.w("Container") { "pcLock not sent: ${it.javaClass.simpleName}" } }
            .isSuccess
    }

    /**
     * Sends what the picker returned (ADR-038: no Send screen). [deviceId] is the PC the user
     * chose; null means the connected PC, else the most recently used paired PC. Runs on the app
     * scope, so leaving the app does not stop it; progress and the outcome show in the banner.
     */
    public fun sendFiles(deviceId: String?, files: List<OutgoingFile>) {
        if (files.isEmpty()) return
        appScope.launch {
            val peers = runCatching { trustStore.snapshot() }.getOrDefault(emptyList()).filterNot { it.revoked }
            val connected = connectionManagerReady.value?.activeConnection?.value?.peerDeviceId
            val target = peers.firstOrNull { it.deviceId.value == deviceId }
                ?: peers.firstOrNull { it.deviceId.value == connected }
                ?: peers.maxWithOrNull(compareBy({ it.lastConnectedEpochMillis }, { it.pairedAtEpochMillis }))
            if (target == null) {
                VeyroLog.w("Container") { "Send files: no paired device to send to" }
                return@launch
            }
            transferCoordinator.sendTo(target.deviceId, target.name, files)
        }
    }

    /** Legacy alias for callers expecting linkManager. */
    public val linkManager: ConnectionManager get() = connectionManager

    // ── Transfer, over peer connections ────────────────────────────────────────────────────

    public val historyStore: TransferHistoryStore by lazy {
        FileTransferHistoryStore(storageDir)
    }

    public val storagePublisher: TransferStoragePublisher by lazy {
        AndroidMediaStorePublisher(
            context = appContext,
            customDestinationDir = { runCatching { settingsStore.destinationDir.first() }.getOrNull() },
        )
    }

    public val wifiDirectManager: WifiDirectManager by lazy {
        AndroidWifiDirectManager(appContext, dispatchers)
    }

    private val transferRunner: TransferRunner by lazy {
        TransferRunner(dispatchers, wifiDirectManager, localAddressProvider)
    }

    /**
     * The one surface the Send and Receive screens use.
     *
     * Backed by [connectionManager] (connection + session) and [transferRunner] (chunk streaming).
     */
    private val lastKnownEndpoints = ConcurrentHashMap<DeviceId, Pair<List<Endpoint>, Long>>()

    public val transferCoordinator: TransferCoordinator by lazy {
        TransferCoordinator(
            connectionManager = connectionManager,
            runner = transferRunner,
            dispatchers = dispatchers,
            // Cache, not files: staged copies are reproducible and the OS may reclaim them.
            stagingDir = File(appContext.cacheDir, "staging"),
            destinationProvider = { destinationDirectory() },
            localName = { runCatching { settingsStore.deviceName.first() }.getOrDefault("Veyro device") },
            endpointsFor = { id -> knownEndpoints(id) },
            autoAcceptFor = { deviceId ->
                // REQ-T03: per-device, never for an unpaired device. `find` returning null for an
                // unknown peer is what enforces the last part. Off until the first prompt from that
                // device is accepted with "Always accept" ticked, which it is by default (ADR-038).
                trustStore.find(deviceId)?.takeUnless { it.revoked }?.autoAcceptTransfers == true
            },
            discoverEndpoints = { id, timeoutMs -> discoverEndpoints(id, timeoutMs) },
            storagePublisher = storagePublisher,
            onTransferCompleted = { transfer ->
                historyStore.append(transfer)
            },
            // "Always accept from this PC" on the prompt; only ever for a paired, unrevoked peer.
            rememberAutoAccept = { deviceId ->
                if (trustStore.find(deviceId)?.revoked == false) trustStore.setAutoAccept(deviceId, true)
            },
        )
    }

    /** What discovery currently reports for [id], else what it reported in the last minute. */
    private fun knownEndpoints(id: DeviceId): List<Endpoint> {
        val current = discoveryManager.peers.value.firstOrNull { it.deviceId == id }?.endpoints.orEmpty()
        if (current.isNotEmpty()) {
            lastKnownEndpoints[id] = current to System.currentTimeMillis()
            return current
        }
        val cached = lastKnownEndpoints[id]
        return if (cached != null && (System.currentTimeMillis() - cached.second) < LAST_KNOWN_ENDPOINT_TTL_MS) {
            cached.first
        } else {
            emptyList()
        }
    }

    /** Opens one bounded discovery window and waits up to [timeoutMs] for [id] to appear. */
    private suspend fun discoverEndpoints(id: DeviceId, timeoutMs: Long): List<Endpoint> {
        val handle = discoveryManager.openWindow(
            DiscoveryRequest(
                reason = DiscoveryReason.TRANSFER,
                timeoutMillis = timeoutMs + 2_000L,
                includeUnpaired = false,
            ),
        )
        return try {
            withTimeoutOrNull(timeoutMs) {
                discoveryManager.peers
                    .mapNotNull { peers ->
                        peers.firstOrNull { it.deviceId == id }?.endpoints?.takeIf { it.isNotEmpty() }
                    }
                    .first()
            }?.also { fresh ->
                lastKnownEndpoints[id] = fresh to System.currentTimeMillis()
            }.orEmpty()
        } finally {
            handle.close()
        }
    }

    private companion object {
        const val LAST_KNOWN_ENDPOINT_TTL_MS = 60_000L
        const val THUMBNAIL_PX = 192
    }

    // ── Background running (ADR-036) ───────────────────────────────────────────────────────

    /** Whether any Activity is started. Written by VeyroApplication's lifecycle callbacks. */
    public val appVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** The incoming-file notification while the app is not visible. */
    public val offerNotifier: OfferNotifier by lazy {
        OfferNotifier(
            context = appContext,
            scope = appScope,
            coordinator = { transferCoordinator },
            appVisible = appVisible,
        )
    }

    /** Keeps the session to the paired PC alive in the background and drives the service. */
    public val backgroundController: BackgroundController by lazy {
        BackgroundController(
            context = appContext,
            scope = appScope,
            dispatchers = dispatchers,
            settings = settingsStore,
            trustStore = trustStore,
            connectionManager = { connectionManager },
            networkMonitor = networkMonitor,
            discoveredPeers = discoveryManager.peers,
            knownEndpoints = { id -> knownEndpoints(id) },
            discoverEndpoints = { id, timeoutMs -> discoverEndpoints(id, timeoutMs) },
            ensureReceiving = {
                // Building the coordinator installs the receive loop on every session.
                transferCoordinator
                offerNotifier.start()
            },
        )
    }

    /** Whether Android exempts Veyro from battery optimisation, for the Settings row. */
    public fun isIgnoringBatteryOptimizations(): Boolean =
        appContext.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(appContext.packageName) == true

    // ── Companion: notification mirroring (ADR-025) ────────────────────────────────────────

    /** Reads the bound notification listener and executes PC requests against it. */
    public val notificationSource: AndroidNotificationSource by lazy {
        AndroidNotificationSource(appContext, dispatchers)
    }

    /**
     * Sends notifications to the connected PC while the user has mirroring on.
     *
     * Both inputs are wrapped in `flow {}` so building this touches neither the settings file nor
     * the connection layer; they are reached only once the mirror collects them on [appScope], and
     * the connection only while the setting is on.
     */
    public val notificationMirror: NotificationMirror by lazy {
        NotificationMirror(
            links = flow { emitAll(connectionManager.activeConnection) }
                .map { it?.asCompanionLink() },
            enabled = flow { emitAll(settingsStore.notificationMirrorEnabled) },
            source = notificationSource,
            executor = notificationSource,
            scope = appScope,
        )
    }

    /** Whether notification access is granted, for the Settings row. */
    public fun isNotificationAccessGranted(): Boolean =
        VeyroNotificationListener.isAccessGranted(appContext)

    // ── Companion: media control and device status (ADR-026) ───────────────────────────────

    private val mediaSource: AndroidMediaSource by lazy { AndroidMediaSource(appContext, dispatchers) }

    /**
     * Phone media to the PC (behind "Share media with PC", off by default) and the PC's media on
     * the phone. Follows the session only once something else has opened the connection layer.
     */
    public val mediaMirror: MediaMirror by lazy {
        MediaMirror(
            links = companionLinks,
            enabled = flow { emitAll(settingsStore.mediaShareEnabled) },
            source = mediaSource,
            executor = mediaSource,
            scope = appScope,
        )
    }

    private val deviceController: AndroidDeviceController by lazy {
        AndroidDeviceController(appContext, dispatchers)
    }

    /** This phone's battery/ringer/DND to the PC, the PC's status back, and ring-my-phone. */
    public val deviceStatusPublisher: DeviceStatusPublisher by lazy {
        DeviceStatusPublisher(
            links = companionLinks,
            controlAllowed = flow { emitAll(settingsStore.deviceControlAllowed) },
            source = AndroidDeviceStatusSource(appContext),
            controller = deviceController,
            peerName = { id ->
                DeviceId.parseOrNull(id)?.let { runCatching { trustStore.find(it)?.name }.getOrNull() }
            },
            scope = appScope,
        )
    }

    /** Whether Do Not Disturb access is granted, for the Settings row. */
    public fun isPolicyAccessGranted(): Boolean =
        AndroidDeviceController.isPolicyAccessGranted(appContext)

    // ── Companion: SMS and calls (ADR-027) ────────────────────────────────────────────────

    /**
     * SMS threads, history, new-message push and sending for the PC, behind "Messages and calls
     * on PC" (off by default). Follows the session only once something else opened it.
     */
    public val smsBridge: SmsBridge by lazy {
        val source = AndroidSmsSource(appContext, dispatchers)
        SmsBridge(
            links = companionLinks,
            enabled = flow { emitAll(settingsStore.messagesAndCallsEnabled) },
            source = source,
            sender = source,
            scope = appScope,
        )
    }

    /** Call state, call actions and the call log for the PC, behind the same switch. */
    public val callBridge: CallBridge by lazy {
        CallBridge(
            links = companionLinks,
            enabled = flow { emitAll(settingsStore.messagesAndCallsEnabled) },
            source = AndroidCallSource(appContext, dispatchers),
            controller = AndroidCallController(appContext, dispatchers),
            scope = appScope,
        )
    }

    // ── Companion: photos (ADR-034) ─────────────────────────────────────────────────────────

    /**
     * Recent camera photos and screenshots for the PC, behind "Photos on PC" (off by default).
     * Originals go back over the very session that asked, as a transfer answering the request.
     * Follows the session only once something else opened it.
     */
    public val photoBridge: PhotoBridge by lazy {
        PhotoBridge(
            links = companionLinks,
            enabled = flow { emitAll(settingsStore.photosEnabled) },
            source = AndroidPhotoSource(appContext, dispatchers),
            sender = PhotoSender { link, file, requestId ->
                val connection = (link as? PeerConnectionLink)?.connection ?: return@PhotoSender false
                transferCoordinator.sendRequested(connection, file, requestId) is SendResult.Success
            },
            scope = appScope,
        )
    }

    /** Whether the photo read permission is held, for the Settings row. */
    public fun isPhotosPermissionGranted(): Boolean = AndroidPhotoSource.isGranted(appContext)

    // ── Companion: wallpaper (ADR-038) ──────────────────────────────────────────────────────

    /**
     * This phone's wallpaper to the PC (on by default) and the PC's wallpaper for Home. Follows
     * the session only once something else opened it. The PC's last wallpaper is cached so Home
     * can draw it while disconnected.
     */
    public val wallpaperBridge: WallpaperBridge by lazy {
        WallpaperBridge(
            links = companionLinks,
            enabled = flow { emitAll(settingsStore.wallpaperShareEnabled) },
            source = AndroidWallpaperSource(appContext, dispatchers),
            cache = FilePeerWallpaperCache(File(appContext.cacheDir, "wallpapers"), dispatchers),
            scope = appScope,
        )
    }

    /** A small preview of a received image for Home's Recent card. Null if unreadable. */
    public suspend fun loadThumbnail(uri: String): ImageBitmap? = withContext(dispatchers.io) {
        runCatching {
            appContext.contentResolver
                .loadThumbnail(android.net.Uri.parse(uri), android.util.Size(THUMBNAIL_PX, THUMBNAIL_PX), null)
                .asImageBitmap()
        }.getOrNull()
    }

    /** User-facing labels of the messaging permissions not granted yet, for the Settings row. */
    public fun missingMessagingPermissions(): List<String> =
        MessagingPermissions.missing(appContext).mapNotNull { MessagingPermissions.ALL[it] }

    /**
     * What an onboarding feature still needs from the system (ADR-030), read fresh each call.
     * Each feature lists only its own grants, so turning one on asks for nothing else.
     */
    public fun onboardingFeatureAccess(feature: OnboardingFeature): FeatureAccess = when (feature) {
        OnboardingFeature.NOTIFICATIONS -> {
            // Mirroring needs the listener; POST_NOTIFICATIONS lets Veyro post its own alerts
            // (incoming files, transfer progress) on Android 13+.
            val needsPost = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            FeatureAccess(
                missingPermissions = if (needsPost) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
                missingLabels = if (needsPost) listOf("Show notifications") else emptyList(),
                specialAccess = SpecialAccess.NOTIFICATION_LISTENER,
                specialGranted = isNotificationAccessGranted(),
            )
        }
        OnboardingFeature.MEDIA -> FeatureAccess(
            // Other apps' media sessions are read on behalf of the notification listener.
            specialAccess = SpecialAccess.NOTIFICATION_LISTENER,
            specialGranted = isNotificationAccessGranted(),
        )
        OnboardingFeature.MESSAGES_AND_CALLS -> {
            val missing = MessagingPermissions.missing(appContext)
            FeatureAccess(
                missingPermissions = missing,
                missingLabels = missing.mapNotNull { MessagingPermissions.ALL[it] },
            )
        }
        OnboardingFeature.PHOTOS -> {
            val missing = !isPhotosPermissionGranted()
            FeatureAccess(
                missingPermissions = if (missing) listOf(AndroidPhotoSource.permission()) else emptyList(),
                missingLabels = if (missing) listOf("Photos") else emptyList(),
            )
        }
        OnboardingFeature.RING_AND_SOUND -> FeatureAccess(
            specialAccess = SpecialAccess.NOTIFICATION_POLICY,
            specialGranted = isPolicyAccessGranted(),
        )
    }

    /**
     * Where received files land.
     *
     * Defaults to the app-private Downloads folder rather than the shared one. That is deliberate:
     * writing to shared storage needs either a SAF tree the user granted or a permission, and
     * neither should be required before the first transfer works. The user can point it elsewhere
     * from Settings.
     */
    public suspend fun destinationDirectory(): File {
        val chosen = runCatching { settingsStore.destinationDir.first() }.getOrNull()
        val dir = chosen ?: File(appContext.cacheDir, "staging_receive")
        dir.mkdirs()
        return dir
    }

    /**
     * Builds the record both discovery mechanisms advertise.
     *
     * Note what is and is not included. The display name is present only when [includeName] is
     * true - that is "open mode", used while pairing. Outside pairing the name is withheld so a
     * stranger on a public network cannot harvest it (REQ-D06), and recognition relies on the
     * rotating presence token instead.
     */
    private suspend fun buildPresenceRecord(port: Int, includeName: Boolean): PresenceRecord? {
        val identity = runCatching { identityManager.ensureIdentity() }.getOrNull() ?: return null
        if (port !in 1..65535) return null

        // The key published here must be the one the handshake authenticates with, which is the
        // *derived* Noise static key (LocalKeys.NOISE_STATIC), not the raw identity public key.
        // Publishing the wrong one would make every handshake fail with a key mismatch while
        // everything else looked correct - the worst kind of bug to track down.
        val noisePublicKey = runCatching {
            com.veyro.core.crypto.X25519.publicKeyOf(
                identityManager.deriveLocalKey(LocalKeys.NOISE_STATIC, 32),
            )
        }.getOrNull() ?: return null

        val publicKeyHash = MessageDigest.getInstance("SHA-256").run {
            update("veyro-pk-v1".toByteArray(Charsets.US_ASCII))
            update(noisePublicKey)
            digest()
        }.copyOf(PresenceRecord.HASH_LENGTH)

        // In open mode the token is random per window, so it carries no linkable information. In
        // private mode it would be HMAC-derived per peer; that path arrives with pairing in P3.
        val token = ByteArray(PresenceRecord.TOKEN_LENGTH).also {
            java.security.SecureRandom().nextBytes(it)
        }

        val name = if (includeName) {
            runCatching { settingsStore.deviceName.first() }.getOrNull()
        } else {
            null
        }

        return PresenceRecord(
            version = Vlp.VERSION,
            deviceId = identity.deviceId,
            platform = Platform.ANDROID,
            capabilities = Capabilities.ANDROID_CURRENT,
            publicKeyHash = publicKeyHash,
            presenceToken = token,
            name = name,
            port = port,
            // Published only in open mode. Outside pairing there is nothing to gain from
            // broadcasting it, and withholding it keeps the record minimal (REQ-D06).
            publicKey = if (includeName) noisePublicKey else null,
        )
    }

    // ── Debug ──────────────────────────────────────────────────────────────────────────────

    /** Installs logging. Called once from `VeyroApplication.onCreate`. */
    public fun installLogging(isDebuggable: Boolean) {
        VeyroLog.minLevel = if (isDebuggable) LogLevel.DEBUG else LogLevel.WARN
        VeyroLog.install(CompositeLogSink(AndroidLogSink, logRecorder))
    }

    /**
     * Collects the facts worth knowing when something does not work.
     *
     * Nothing secret appears here - this snapshot is built to be pasted into a bug report, so the
     * seed, pairing secrets and session keys must never reach it (AGENTS.md).
     */
    public suspend fun diagnostics(): Diagnostics {
        val identity = runCatching { identityManager.currentIdentity() }.getOrNull()
        val peers = runCatching { trustStore.snapshot() }.getOrDefault(emptyList())
        val network = runCatching { networkMonitor.currentStatus() }.getOrNull()
        val addresses = runCatching { localAddressProvider.localAddresses() }.getOrDefault(emptyList())
        val window = discoveryManager.windowState.value

        return Diagnostics.builder()
            .section("App") {
                fact("Package", appContext.packageName)
                fact("Protocol version", Vlp.VERSION.toString())
            }
            .section("Device") {
                fact("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                fact("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
            .section("Identity") {
                if (identity == null) {
                    problem("State", "not loaded")
                } else {
                    fact("Device ID", identity.deviceId.value)
                    fact("Fingerprint", identity.deviceId.fingerprint())
                }
            }
            .section("Network") {
                if (network == null) {
                    problem("State", "unknown")
                } else {
                    fact("Transport", network.transport.name)
                    fact("Usable", network.hasUsableNetwork.toString(), isProblem = !network.hasUsableNetwork)
                    fact("Hotspot active", network.isHotspotActive.toString())
                    fact("VPN active", network.isVpnActive.toString(), isProblem = network.isVpnActive)
                    fact("Diagnosis", network.diagnosis.name, isProblem = network.diagnosis.isNetworkProblem)
                }
            }
            .section("Interfaces") {
                if (addresses.isEmpty()) {
                    problem("Usable addresses", "none")
                } else {
                    for (a in addresses) {
                        val advertisable = InterfaceClassifier.isAdvertisable(a.kind)
                        fact(
                            "${a.interfaceName} (${a.kind})",
                            "${a.address}/${a.prefixLength}" +
                                (if (advertisable) "" else "  not advertised") +
                                (if (a.isLinkLocal) "  link-local" else ""),
                            isProblem = a.isLinkLocal,
                        )
                    }
                }
            }
            .section("Discovery") {
                fact("Window", if (window.isOpen) "open (${window.reason})" else "closed")
                fact(
                    "Listening port",
                    if (connectionManager.listeningPort != 0) connectionManager.listeningPort.toString() else "not listening",
                )
                fact(
                    "Multicast lock",
                    (discoveryManager as? DiscoveryManagerImpl)?.isMulticastLockHeld()?.toString() ?: "unknown",
                )
                fact("Peers visible", discoveryManager.peers.value.size.toString())
            }
            .section("Storage") {
                fact("Files dir", storageDir.absolutePath)
                fact("Identity file", File(storageDir, "identity.bin").existsLabel())
                fact("Trust file", File(storageDir, "trust.cbor").existsLabel())
                fact("Settings file", File(storageDir, "settings.json").existsLabel())
                fact("Free space", "${storageDir.usableSpace / (1024 * 1024)} MiB")
            }
            .section("Trust") {
                fact("Paired devices", peers.count { !it.revoked }.toString())
                fact("Revoked", peers.count { it.revoked }.toString())
                fact("Auto-accept enabled", peers.count { it.autoAcceptTransfers }.toString())
            }
            .build()
    }

    private fun File.existsLabel(): String =
        if (exists()) "present (${length()} bytes)" else "absent"

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        return when {
            model.isEmpty() -> "Android device"
            // Many devices already include the manufacturer in MODEL; avoid "Samsung Samsung S24".
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isEmpty() -> model
            else -> "$manufacturer $model"
        }
    }
}
