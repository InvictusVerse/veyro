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
import com.veyro.core.model.TrustedPeer
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
import com.veyro.core.companion.ClipboardBridge
import com.veyro.core.companion.PhotoBridge
import com.veyro.core.companion.PhotoSender
import com.veyro.core.link.SendResult
import com.veyro.core.companion.AndroidPhoneControls
import com.veyro.core.companion.DeviceInfoSync
import com.veyro.core.companion.DeviceStatusPublisher
import com.veyro.core.companion.MediaMirror
import com.veyro.core.companion.NotificationMirror
import com.veyro.core.companion.VeyroNotificationListener
import com.veyro.core.companion.asCompanionLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
import com.veyro.core.link.PeerMessage
import com.veyro.core.link.OutgoingFile
import com.veyro.core.companion.AndroidWallpaperSource
import com.veyro.core.companion.WallpaperBridge
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.veyro.ui.clipboard.ClipKind
import com.veyro.ui.clipboard.ClipRow
import com.veyro.ui.clipboard.SendClipOutcome
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import com.veyro.ui.format.ChosenReceiveFolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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
import com.veyro.core.protocol.ProtocolConstants
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
            listenPortFile = File(appContext.noBackupFilesDir, "listen-port"),
            // ADR-053: asked only when a paired device dials in, so nothing is built at launch.
            disconnectedByUser = { id -> backgroundController.isDisconnectedByUser(id) },
        ).also { connectionManagerReady.value = it }
    }

    /**
     * Set the moment [connectionManager] is first built, by whatever needed it.
     *
     * Lets always-on observers (media, device status) follow the connection without being the
     * reason it, the trust store and the identity get constructed at launch.
     */
    private val connectionManagerReady = MutableStateFlow<ConnectionManager?>(null)

    /** Every live paired session, by peer device id (multiple PCs). Empty until the layer exists. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val liveConnections: Flow<Map<String, com.veyro.core.link.PeerConnection>> by lazy {
        connectionManagerReady.flatMapLatest { manager -> manager?.connections ?: flowOf(emptyMap()) }
    }

    /**
     * Each paired device's platform, by id (ADR-052). Read only once the connection layer exists,
     * so the trust store is still not built at launch just for this.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val peerPlatforms: Flow<Map<String, Platform>> by lazy {
        connectionManagerReady.flatMapLatest { manager ->
            if (manager == null) flowOf(emptyMap()) else trustStore.peers.map { peers -> peers.associate { it.deviceId.value to it.platform } }
        }.distinctUntilChanged()
    }

    /**
     * Every live paired session as a [CompanionLink]; each companion feature fans out over it.
     * Scoped by platform: with another phone only files, media and battery are visible (ADR-052).
     */
    private val companionLinks: Flow<List<CompanionLink>> by lazy {
        scopedLinks(liveConnections)
    }

    private fun scopedLinks(connections: Flow<Map<String, com.veyro.core.link.PeerConnection>>): Flow<List<CompanionLink>> =
        combine(connections, peerPlatforms) { live, platforms ->
            live.map { (id, connection) -> connection.asCompanionLink(platforms[id] ?: Platform.UNKNOWN) }
        }

    /** Device ids of the PCs with a live session, for Home and the Devices list. */
    public val connectedPeerIds: Flow<Set<String>> by lazy {
        liveConnections.map { it.keys.toSet() }.distinctUntilChanged()
    }

    /**
     * What each connected device advertised in its hello, by device id, for Lock PC (ADR-038);
     * scoped like [companionLinks], so Home never offers a PC feature for a phone (ADR-052).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val connectedPeerCapabilities: Flow<Map<String, Capabilities>> by lazy {
        companionLinks.flatMapLatest { links ->
            if (links.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(links.map { link -> link.peerCapabilities.map { link.peerDeviceId.orEmpty() to it } }) { it.toMap() }
            }
        }
    }

    /** Home's PC switcher: the selected PC is the one single-PC callers act on. */
    public fun selectPc(deviceId: String?) {
        connectionManagerReady.value?.selectPeer(deviceId)
    }

    /** Home's Lock PC: `pcLock` over that PC's live session (ADR-038). False when there is none. */
    public suspend fun lockPc(deviceId: String): Boolean {
        val connection = connectionManagerReady.value?.connectionFor(deviceId) ?: return false
        if (Capability.DEVICE_STATUS !in connection.peerCapabilities.value) return false
        return runCatching { connection.send(PeerMessage.PcLock) }
            .onFailure { VeyroLog.w("Container") { "pcLock not sent: ${it.javaClass.simpleName}" } }
            .isSuccess
    }

    /**
     * Sends what the picker returned (ADR-038: no Send screen) to every PC in [deviceIds], one
     * after the other so the banner shows each in turn (multiple PCs: "All connected"). Empty
     * means the selected connected PC, else the most recently used paired PC. Runs on the app
     * scope, so leaving the app does not stop it; progress and the outcome show in the banner.
     */
    public fun sendFiles(deviceIds: List<String>, files: List<OutgoingFile>) {
        if (files.isEmpty()) return
        appScope.launch { sendFilesAndWait(deviceIds, files) }
    }

    /**
     * Send folder (ADR-064): reads the picked tree, then sends it like picked files to every
     * device in [deviceIds], its files streamed from the tree with no staged copy. The read grant
     * is held for the send and given back after. A folder with no files, or with more than
     * [com.veyro.core.link.FolderWalker.MAX_ITEMS] entries, is refused with a toast; entries
     * that cannot be read are left out and counted.
     */
    public fun sendFolder(deviceIds: List<String>, treeUri: android.net.Uri) {
        appScope.launch {
            val resolver = appContext.contentResolver
            val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            val held = runCatching { resolver.takePersistableUriPermission(treeUri, read) }.isSuccess
            try {
                val folder = com.veyro.SharedFolder(appContext, treeUri)
                val walk = withContext(dispatchers.io) {
                    com.veyro.core.link.FolderWalker.walk(folder, folder.rootId, folder.rootName())
                }
                VeyroLog.i("Container") { "Send folder: ${walk.fileCount} file(s), ${walk.emptyDirectoryCount} empty dir(s), ${walk.skipped} skipped" }
                when {
                    walk.tooLarge -> toast("${walk.name} has more than ${com.veyro.core.link.FolderWalker.MAX_ITEMS} items. Pick a smaller folder.")
                    walk.fileCount == 0 && walk.skipped > 0 -> toast("The files in ${walk.name} could not be read.")
                    walk.fileCount == 0 -> toast("${walk.name} has no files to send.")
                    else -> {
                        if (walk.skipped > 0) toast("${walk.skipped} item(s) in ${walk.name} could not be read and are left out.")
                        sendFilesAndWait(deviceIds, walk.items)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                VeyroLog.w("Container") { "Send folder failed (${e.javaClass.simpleName})" }
                toast("The folder could not be read.")
            } finally {
                if (held) runCatching { resolver.releasePersistableUriPermission(treeUri, read) }
            }
        }
    }

    private suspend fun toast(text: String) = withContext(dispatchers.main) {
        android.widget.Toast.makeText(appContext, text, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Who a send went to and how many of those PCs took every file. [cancelled]: the user
     * cancelled it on this phone, so the devices after that one were skipped (ADR-055).
     */
    public data class SendSummary(val targets: List<String>, val succeeded: Int, val cancelled: Boolean = false)

    /** [sendFiles], suspending until every PC is done; used by the background share (ADR-043). */
    public suspend fun sendFilesAndWait(deviceIds: List<String>, files: List<OutgoingFile>): SendSummary {
        if (files.isEmpty()) return SendSummary(emptyList(), 0)
        val peers = runCatching { trustStore.snapshot() }.getOrDefault(emptyList()).filterNot { it.revoked }
        val selected = connectionManagerReady.value?.activeConnection?.value?.peerDeviceId
        val names = mutableListOf<String>()
        var succeeded = 0
        var cancelled = false
        SendTargets.sendToEach(peers, deviceIds, selected) { target ->
            names += target.displayName
            // ADR-053: sending to a device the user disconnected is asking for it again.
            if (backgroundController.isDisconnectedByUser(target.deviceId.value)) {
                backgroundController.onUserConnect(target.deviceId.value)
            }
            // SendResult reports failures in the banner; an exception must not skip the rest.
            val result = runCatching { transferCoordinator.sendTo(target.deviceId, target.displayName, files) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            if (result is SendResult.Success) succeeded++
            // ADR-055: Cancel on this phone stops the whole send, "All connected" included. A
            // device that cancels its own copy (CancelledByPeer) does not stop the others.
            cancelled = result is SendResult.Cancelled
            cancelled.not()
        }
        if (names.isEmpty()) VeyroLog.w("Container") { "Send files: no paired device to send to" }
        if (cancelled) VeyroLog.i("Container") { "Send files: cancelled, ${names.size} device(s) reached" }
        return SendSummary(names, succeeded, cancelled)
    }

    /**
     * Keeps the share sheet's Direct Share row ("Send to <device>") in step with the paired PCs
     * and phones (ADR-043, ADR-054): connected ones first, then most recently used. Called once
     * from the Application.
     */
    public fun startShareShortcuts() {
        appScope.launch {
            combine(trustStore.peers, connectedPeerIds) { peers, connected ->
                peers.filterNot { it.revoked }
                    .sortedWith(
                        compareByDescending<TrustedPeer> { it.deviceId.value in connected }
                            .thenByDescending { maxOf(it.lastConnectedEpochMillis, it.pairedAtEpochMillis) },
                    )
            }
                // ADR-059: a rename (the device's own or a nickname) republishes at once.
                .distinctUntilChanged { a, b -> a.map { it.deviceId to it.displayName } == b.map { it.deviceId to it.displayName } }
                .collect { ShareShortcuts.publish(appContext, it) }
        }
    }

    /**
     * The paired devices connected right now, Home's selected one first, for the share sheet's
     * "Send to" chooser (ADR-054). Empty while the connection layer has not been built.
     */
    public suspend fun connectedShareTargets(): List<TrustedPeer> {
        val manager = connectionManagerReady.value ?: return emptyList()
        val live = manager.connections.value.keys
        if (live.isEmpty()) return emptyList()
        val selected = manager.activeConnection.value?.peerDeviceId
        return runCatching { trustStore.snapshot() }.getOrDefault(emptyList())
            .filter { !it.revoked && it.deviceId.value in live }
            .sortedByDescending { it.deviceId.value == selected }
    }

    /**
     * How long clipboard and file-result notifications stay (Settings, ADR-044); null while
     * "Clear notifications automatically" is off. Read synchronously by the notifiers.
     */
    public val notificationAutoClearMs: StateFlow<Long?> by lazy {
        combine(settingsStore.notificationAutoClear, settingsStore.notificationAutoClearMinutes) { on, minutes ->
            if (on) minutes * 60_000L else null
        }.stateIn(appScope, SharingStarted.Eagerly, com.veyro.core.service.VeyroNotifications.AUTO_CLEAR_MS)
    }

    /** Cancel on a transfer notification. */
    public fun cancelTransfer() {
        transferCoordinator.cancel()
    }

    /** The running transfer, for the background share's progress notification. */
    public val activeTransfer: kotlinx.coroutines.flow.StateFlow<com.veyro.core.model.ActiveTransfer?>
        get() = transferCoordinator.activeTransfer

    /** Legacy alias for callers expecting linkManager. */
    public val linkManager: ConnectionManager get() = connectionManager

    // ── Transfer, over peer connections ────────────────────────────────────────────────────

    public val historyStore: TransferHistoryStore by lazy {
        FileTransferHistoryStore(storageDir)
    }

    private val mediaStorePublisher: AndroidMediaStorePublisher by lazy {
        AndroidMediaStorePublisher(
            context = appContext,
            // ADR-072: "Save received files to" in Settings; Download/Veyro when unset or gone.
            receiveFolderUri = { runCatching { settingsStore.receiveFolderUri.first() }.getOrNull() },
            onReceiveFolderLost = { settingsStore.setReceiveFolderLost(true) },
        )
    }

    /**
     * The folder chosen in Settings while Veyro can still write to it (ADR-072), for the incoming
     * prompt's label and Home's Received files row; null for Download/Veyro.
     */
    public val receiveFolder: Flow<ChosenReceiveFolder?> by lazy {
        combine(settingsStore.receiveFolderUri, settingsStore.receiveFolderName) { uri, name ->
            uri?.takeIf { usableTree(it) }?.let { ChosenReceiveFolder(it, name ?: "Chosen folder") }
        }.flowOn(Dispatchers.IO)
    }

    private fun usableTree(uri: String): Boolean = runCatching {
        val tree = android.net.Uri.parse(uri)
        appContext.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
    }.getOrDefault(false)

    public val storagePublisher: TransferStoragePublisher get() = mediaStorePublisher

    /**
     * Once per install: moves files received before "one Veyro folder" into Download/Veyro's
     * subfolders. A marker file records that it ran, so it never scans MediaStore again.
     */
    public fun moveOlderReceivedFilesOnce() {
        val marker = File(appContext.noBackupFilesDir, "received-folders-v2")
        if (marker.exists()) return
        appScope.launch {
            mediaStorePublisher.moveOlderFiles()
            runCatching { marker.createNewFile() }
        }
    }

    /**
     * ADR-049: files are received in place as hidden MediaStore entries. One killed mid-way (app
     * swiped, phone off) would hold its space for 7 days; this removes those from earlier runs.
     * The time is taken now, so a receive that starts during the sweep is never touched.
     */
    public fun deleteUnfinishedReceives() {
        val startedAtSeconds = System.currentTimeMillis() / 1000
        appScope.launch { mediaStorePublisher.deleteAbandonedPending(startedAtSeconds) }
    }

    public val wifiDirectManager: WifiDirectManager by lazy {
        AndroidWifiDirectManager(appContext, dispatchers)
    }

    private val transferRunner: TransferRunner by lazy {
        TransferRunner(dispatchers, wifiDirectManager, localAddressProvider)
    }

    /** Last endpoints discovery reported per device, when, and the device's platform (ADR-052 TTL). */
    private val lastKnownEndpoints = ConcurrentHashMap<DeviceId, Triple<List<Endpoint>, Long, Platform>>()

    /**
     * The one surface the Send and Receive screens use.
     *
     * Backed by [connectionManager] (connection + session) and [transferRunner] (chunk streaming).
     */
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
            // ADR-059: the prompt names a paired sender as Devices does (nickname first).
            peerDisplayName = { deviceId -> trustStore.find(deviceId)?.takeUnless { it.revoked }?.displayName },
        )
    }

    /**
     * What discovery currently reports for [id], else what it reported recently: a minute for a
     * PC, which beacons all the time anyway; [PHONE_ENDPOINT_TTL_MS] for a phone, which is silent
     * in the background but keeps the same address and port (ADR-052). A stale entry costs one
     * failed dial, after which the caller looks again.
     */
    private fun knownEndpoints(id: DeviceId): List<Endpoint> {
        val seen = discoveryManager.peers.value.firstOrNull { it.deviceId == id }
        val current = seen?.endpoints.orEmpty()
        if (current.isNotEmpty()) {
            lastKnownEndpoints[id] = Triple(current, System.currentTimeMillis(), seen?.platform ?: Platform.UNKNOWN)
            return current
        }
        val cached = lastKnownEndpoints[id] ?: return emptyList()
        val ttl = if (cached.third == Platform.ANDROID) PHONE_ENDPOINT_TTL_MS else LAST_KNOWN_ENDPOINT_TTL_MS
        return if ((System.currentTimeMillis() - cached.second) < ttl) cached.first else emptyList()
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
                        peers.firstOrNull { it.deviceId == id }?.takeIf { it.endpoints.isNotEmpty() }
                    }
                    .first()
            }?.let { fresh ->
                lastKnownEndpoints[id] = Triple(fresh.endpoints, System.currentTimeMillis(), fresh.platform)
                fresh.endpoints
            }.orEmpty()
        } finally {
            handle.close()
        }
    }

    private companion object {
        const val LAST_KNOWN_ENDPOINT_TTL_MS = 60_000L
        const val PHONE_ENDPOINT_TTL_MS = 12L * 60 * 60 * 1000
        const val THUMBNAIL_PX = 192
        const val CLIP_PREVIEW_PX = 480

        /** ADR-062: how long a widget or toast waits for the first snapshot (a cold trust store read). */
        const val WIDGET_FIRST_DRAW_MS = 5_000L
    }

    // ── Background running (ADR-036) ───────────────────────────────────────────────────────

    /** Whether any Activity is started. Written by VeyroApplication's lifecycle callbacks. */
    public val appVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Receiving progress and the result as notifications while the app is not visible (ADR-044). */
    public val transferNotifier: TransferNotifier by lazy {
        TransferNotifier(
            context = appContext,
            scope = appScope,
            transfers = { transferCoordinator.activeTransfer },
            appVisible = appVisible,
            autoClearMs = { notificationAutoClearMs.value },
        )
    }

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
                transferNotifier.start()
            },
            localNetworkBlocked = localNetworkAccess.state.map { it.isBlocked }.distinctUntilChanged(),
        )
    }

    /** Whether Android exempts Veyro from battery optimisation, for the Settings row. */
    public fun isIgnoringBatteryOptimizations(): Boolean =
        appContext.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(appContext.packageName) == true

    // ── Android 17 local network, "Keep Veyro connected" (ADR-061) ─────────────────────────

    /** `ACCESS_LOCAL_NETWORK` on API 37+; NOT_NEEDED below. MainActivity refreshes it on resume. */
    public val localNetworkAccess: LocalNetworkAccess by lazy { LocalNetworkAccess(appContext) }

    private val keepAliveProbe: KeepAliveProbe by lazy { KeepAliveProbe(appContext) }

    /** What the checklist shows, read now. */
    public fun keepAliveFacts(): com.veyro.ui.keepalive.KeepAliveFacts =
        keepAliveProbe.facts(localNetworkAccess.state.value)

    /** A checklist button: opens that system screen, App info when it is missing. */
    public fun openKeepAliveTarget(target: com.veyro.ui.keepalive.KeepAliveTarget): Boolean = keepAliveProbe.open(target)

    /** Checklist items that need a change, for the Settings row. */
    public fun keepAliveIssueCount(): Int = com.veyro.ui.keepalive.KeepConnectedChecklist.issueCount(keepAliveFacts())

    /** Home's "Android stops Veyro in the background" notice. */
    public suspend fun keepAliveHomeNotice(): Boolean = com.veyro.ui.keepalive.KeepConnectedChecklist.homeNotice(
        keepAliveFacts(),
        runInBackground = runCatching { settingsStore.runInBackground.first() }.getOrDefault(false),
    )

    // ── Quick Settings tiles and the home-screen widget (ADR-062) ──────────────────────────

    /**
     * What the tiles and the widget show, pushed from the connection flows (no polling). Reading
     * it builds the trust store, so only a visible tile or a placed widget reads it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val quickSnapshot: Flow<com.veyro.quick.QuickSnapshot> by lazy {
        val selected = connectionManagerReady
            .flatMapLatest { manager -> manager?.activeConnection ?: flowOf(null) }
            .map { it?.peerDeviceId }
        val lockable = connectedPeerCapabilities
            .map { caps -> caps.filterValues { Capability.DEVICE_STATUS in it }.keys }
        val blocked = localNetworkAccess.state.map { it.isBlocked }
        combine(
            trustStore.peers,
            connectedPeerIds,
            combine(lockable, selected) { l, s -> l to s },
            backgroundController.userDisconnectedIds,
            blocked,
        ) { peers, live, (lock, chosen), paused, isBlocked ->
            com.veyro.quick.QuickSnapshot.of(peers, live, lock, chosen, paused, isBlocked)
        }.distinctUntilChanged()
    }

    private var widgetUpdates: kotlinx.coroutines.Job? = null

    /**
     * Draws every placed widget now and keeps them following [quickSnapshot]. Does nothing (and
     * builds nothing) while no widget is placed. [done] runs once the first drawing is finished.
     */
    public fun refreshWidgets(done: () -> Unit = {}) {
        if (!com.veyro.quick.VeyroWidgetProvider.hasWidgets(appContext)) {
            done()
            return
        }
        appScope.launch {
            try {
                val snapshot = withTimeoutOrNull(WIDGET_FIRST_DRAW_MS) { quickSnapshot.first() }
                if (snapshot != null) {
                    com.veyro.quick.VeyroWidgetProvider.render(appContext, com.veyro.quick.QuickActions.widget(snapshot))
                }
            } finally {
                done()
            }
            followWidgets()
        }
    }

    @Synchronized
    private fun followWidgets() {
        if (widgetUpdates?.isActive == true) return
        widgetUpdates = appScope.launch {
            quickSnapshot.map { com.veyro.quick.QuickActions.widget(it) }.distinctUntilChanged().collect { spec ->
                if (!com.veyro.quick.VeyroWidgetProvider.hasWidgets(appContext)) return@collect
                com.veyro.quick.VeyroWidgetProvider.render(appContext, spec)
            }
        }
    }

    /**
     * The tile's and widget's Send clipboard (ADR-062): reads this phone's clipboard (the caller's
     * window has focus) and sends it like the Clipboard screen does, on the app scope so closing
     * the window does not cancel it. [onResult] gets the toast text, on the main thread.
     */
    public fun sendClipboardFromShortcut(onResult: (String) -> Unit) {
        appScope.launch {
            val outcome = runCatching { sendPhoneClipboard() }.getOrDefault(SendClipOutcome.NO_PC)
            val snapshot = withTimeoutOrNull(WIDGET_FIRST_DRAW_MS) { quickSnapshot.first() } ?: com.veyro.quick.QuickSnapshot.EMPTY
            val message = com.veyro.quick.QuickActions.clipboardToast(outcome, snapshot)
            withContext(dispatchers.main) { onResult(message) }
        }
    }

    /** The tile's and widget's Lock PC: [lockPc] on the app scope; [onResult] gets the toast text. */
    public fun lockPcFromShortcut(deviceId: String, name: String?, onResult: (String) -> Unit) {
        appScope.launch {
            val sent = runCatching { lockPc(deviceId) }.getOrDefault(false)
            val label = name
                ?: DeviceId.parseOrNull(deviceId)?.let { runCatching { trustStore.find(it)?.displayName }.getOrNull() }
                ?: "your PC"
            withContext(dispatchers.main) { onResult(com.veyro.quick.QuickActions.lockToast(sent, label)) }
        }
    }

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
            links = scopedLinks(flow { emitAll(connectionManager.connections) }),
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

    /**
     * This phone's battery/ringer/DND to the PC, the PC's status back, and ring-my-phone; with a
     * PC, also flashlight, media volume and brightness (ADR-060, behind its own switch).
     */
    public val deviceStatusPublisher: DeviceStatusPublisher by lazy {
        DeviceStatusPublisher(
            links = companionLinks,
            controlAllowed = flow { emitAll(settingsStore.deviceControlAllowed) },
            source = AndroidDeviceStatusSource(appContext),
            controller = deviceController,
            peerName = { id ->
                DeviceId.parseOrNull(id)?.let { runCatching { trustStore.find(it)?.displayName }.getOrNull() }
            },
            scope = appScope,
            phoneControls = AndroidPhoneControls(appContext, dispatchers),
            phoneControlsAllowed = flow { emitAll(settingsStore.phoneControlsAllowed) },
        )
    }

    /**
     * Live device names and the auto-fetch switch (ADR-059): this phone's name and "Auto-fetch
     * clipboard" go to every connected device at once and on every connect; each device's own
     * name is stored when it announces a new one. A nickname given here is kept.
     */
    public val deviceInfoSync: DeviceInfoSync by lazy {
        DeviceInfoSync(
            links = companionLinks,
            localName = flow { emitAll(settingsStore.deviceName) },
            clipReceive = flow { emitAll(settingsStore.clipboardReceiveEnabled) },
            storeAnnouncedName = { id, name -> DeviceId.parseOrNull(id)?.let { trustStore.setAnnouncedName(it, name) } },
            scope = appScope,
        )
    }

    /** Whether Do Not Disturb access is granted, for the Settings row. */
    public fun isPolicyAccessGranted(): Boolean =
        AndroidDeviceController.isPolicyAccessGranted(appContext)

    /** Whether "Modify system settings" is granted (brightness from the PC, ADR-060). */
    public fun canWriteSystemSettings(): Boolean = AndroidPhoneControls.canWriteSettings(appContext)

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

    /**
     * Clipboard with the PCs (ADR-043): shared text goes onto the PC clipboard, "Send clipboard"
     * into the PC's virtual clipboard, and the PC's copies into this phone's (with a notification).
     */
    public val clipboardBridge: ClipboardBridge by lazy {
        ClipboardBridge(
            links = companionLinks,
            receiveEnabled = flow { emitAll(settingsStore.clipboardReceiveEnabled) },
            notifier = AndroidClipNotifier(appContext, appScope, ::clipSourceName) { notificationAutoClearMs.value },
            scope = appScope,
        )
    }

    private suspend fun clipSourceName(peerId: String?): String =
        peerId?.let { com.veyro.core.model.DeviceId.parseOrNull(it) }
            ?.let { runCatching { trustStore.find(it) }.getOrNull()?.displayName }
            ?: "your PC"

    /**
     * Screen mirroring on the phone (ADR-066): the PC decides the quality; the phone asks its
     * user (notification, then Android's dialog) and captures. Behind "Let PCs mirror this screen".
     */
    public val screenShareCoordinator: com.veyro.mirror.ScreenShareCoordinator by lazy {
        com.veyro.mirror.ScreenShareCoordinator(
            context = appContext,
            scope = appScope,
            dispatchers = dispatchers,
            appVisible = appVisible,
            localSubnets = { localAddressProvider.localAddresses().map { it.address to it.prefixLength } },
        )
    }

    /** ADR-073: the PC's sound on this phone, and photos or scans taken for the PC. */
    public val speakerAndCapture: com.veyro.mirror.SpeakerAndCapture by lazy {
        com.veyro.mirror.SpeakerAndCapture(
            context = appContext,
            scope = appScope,
            dispatchers = dispatchers,
            appVisible = appVisible,
            localSubnets = { localAddressProvider.localAddresses().map { it.address to it.prefixLength } },
        )
    }

    /** Same rule as the other bridges: it only follows a session something else opened. */
    public val speakerCaptureBridge: com.veyro.core.companion.SpeakerCaptureBridge by lazy {
        com.veyro.core.companion.SpeakerCaptureBridge(
            links = companionLinks,
            speakerAllowed = flow { emitAll(settingsStore.pcAudioAllowed) },
            captureAllowed = flow { emitAll(settingsStore.photoCaptureAllowed) },
            host = speakerAndCapture,
            sender = PhotoSender { link, file, requestId ->
                val connection = (link as? PeerConnectionLink)?.connection ?: return@PhotoSender false
                transferCoordinator.sendRequested(connection, file, requestId) is SendResult.Success
            },
            pcName = { link -> clipSourceName(link.peerDeviceId) },
            scope = appScope,
        )
    }

    /** Same rule as the other bridges: it only follows a session something else opened. */
    public val screenShareBridge: com.veyro.core.companion.ScreenShareBridge by lazy {
        com.veyro.core.companion.ScreenShareBridge(
            links = companionLinks,
            allowed = flow { emitAll(settingsStore.screenShareAllowed) },
            host = screenShareCoordinator,
            pcName = { link -> clipSourceName(link.peerDeviceId) },
            scope = appScope,
            cameraAllowed = flow { emitAll(settingsStore.cameraShareAllowed) },
        )
    }

    /** The virtual clipboard as Clipboard-screen rows; image previews decoded off the main thread. */
    public val clipboardRows: Flow<List<ClipRow>> by lazy {
        val thumbnails = java.util.Collections.synchronizedMap(HashMap<String, ImageBitmap?>())
        clipboardBridge.history.map { items ->
            // Only the items still in the history keep a decoded preview.
            thumbnails.keys.retainAll(items.map { it.id }.toSet())
            items.map { item ->
                val smart = if (item.image != null) null else com.veyro.core.companion.ClipClassifier.classify(item.text)
                ClipRow(
                    id = item.id,
                    text = item.text,
                    thumbnail = item.image?.let { bytes -> thumbnails.getOrPut(item.id) { decodePreview(bytes) } },
                    source = clipSourceName(item.peerId),
                    atMillis = item.atMillis,
                    kind = when (smart?.kind) {
                        null -> ClipKind.IMAGE
                        com.veyro.core.companion.ClipClassifier.Kind.LINK -> ClipKind.LINK
                        com.veyro.core.companion.ClipClassifier.Kind.PHONE -> ClipKind.PHONE
                        com.veyro.core.companion.ClipClassifier.Kind.EMAIL -> ClipKind.EMAIL
                        com.veyro.core.companion.ClipClassifier.Kind.CODE -> ClipKind.CODE
                        com.veyro.core.companion.ClipClassifier.Kind.TEXT -> ClipKind.TEXT
                    },
                    value = smart?.value,
                )
            }
        }.flowOn(dispatchers.default)
    }

    private fun decodePreview(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= CLIP_PREVIEW_PX || bounds.outHeight / (sample * 2) >= CLIP_PREVIEW_PX) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()

    /** The Clipboard screen's Copy: puts a virtual-clipboard item on the system clipboard. */
    public fun copyClip(id: String): Boolean {
        val item = clipboardBridge.history.value.firstOrNull { it.id == id } ?: return false
        return SystemClipboard.copy(appContext, item)
    }

    /** "Copy code": a plain string onto the system clipboard. */
    public fun copyClipText(text: String): Boolean = SystemClipboard.copyText(appContext, text)

    /** "Send clipboard to PC" (ADR-043). Called from the open screen, which is what makes the read legal. */
    public suspend fun sendPhoneClipboard(): SendClipOutcome {
        val (text, image) = withContext(dispatchers.io) { SystemClipboard.read(appContext) }
        if (text.isNullOrBlank() && image == null) return SendClipOutcome.EMPTY
        val sendable = withContext(dispatchers.default) { image?.let(SystemClipboard::fitImage) }
        if (image != null && sendable == null && text.isNullOrBlank()) return SendClipOutcome.TOO_LARGE
        val reached = clipboardBridge.sendClipboard(if (sendable != null) null else text, sendable)
        return if (reached > 0) SendClipOutcome.SENT else SendClipOutcome.NO_PC
    }

    /**
     * "Send with Veyro" from the share sheet or text selection: to [target], or to every connected
     * PC and phone when null. A PC puts it on its clipboard; a phone shows it as a clipboard
     * notification (ADR-054). Runs in the app scope, so the sharing Activity can finish at once;
     * [onResult] gets how many devices it reached, on the main thread.
     */
    public fun shareTextToPcs(text: String, target: String? = null, onResult: (reached: Int) -> Unit) {
        appScope.launch {
            val reached = runCatching { clipboardBridge.share(text, target) }.getOrDefault(0)
            withContext(dispatchers.main) { onResult(reached) }
        }
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
        // Same access Sefirah uses for its wallpaper (WallpaperManager.drawable). Before Android 11
        // there is no such screen and the image is readable anyway.
        OnboardingFeature.WALLPAPER -> FeatureAccess(
            specialAccess = SpecialAccess.ALL_FILES,
            specialGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                android.os.Environment.isExternalStorageManager(),
        )
    }

    /**
     * Where received items are staged before the publisher puts them in Download/Veyro or the
     * folder chosen in Settings (ADR-072). App-private, so no permission is needed for it.
     */
    public suspend fun destinationDirectory(): File {
        val dir = File(appContext.cacheDir, "staging_receive")
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
            version = ProtocolConstants.VERSION,
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
                fact("Protocol version", ProtocolConstants.VERSION.toString())
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
