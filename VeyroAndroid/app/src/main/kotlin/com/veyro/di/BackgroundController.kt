package com.veyro.di

import android.content.Context
import android.content.Intent
import com.veyro.MainActivity
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.data.SettingsStore
import com.veyro.core.data.TrustStore
import com.veyro.core.link.CompanionKeeper
import com.veyro.core.link.ConnectionManager
import com.veyro.core.link.KeepTarget
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import com.veyro.core.model.Platform
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.TrustedPeer
import com.veyro.core.link.PeerConnection
import com.veyro.core.network.LocalNetworkPermission
import com.veyro.core.network.NetworkMonitor
import com.veyro.core.service.BackgroundHost
import com.veyro.core.service.BackgroundStatus
import com.veyro.core.service.VeyroBackgroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs Veyro in the background (ADR-036): decides when [VeyroBackgroundService] should run, feeds
 * it the status line, and owns the [CompanionKeeper] that reconnects to the paired PC.
 *
 * Runs when "Run in background" is on (default), a PC or another phone is paired, and the user
 * has not pressed Disconnect on the notification since they last connected. Every paired device
 * is kept connected, one keeper loop each (multiple PCs, ADR-052 for phones), except one the user
 * disconnected on Home until they connect it again.
 *
 * Nothing here is touched at process start: [observe] is first called when the app becomes
 * visible, at boot, or when a sticky restart brings the service back, so a process started only
 * for the notification listener builds none of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class BackgroundController(
    private val context: Context,
    private val scope: CoroutineScope,
    override val dispatchers: AppDispatchers,
    private val settings: SettingsStore,
    private val trustStore: TrustStore,
    private val connectionManager: () -> ConnectionManager,
    private val networkMonitor: NetworkMonitor,
    private val discoveredPeers: Flow<List<PeerDevice>>,
    private val knownEndpoints: suspend (DeviceId) -> List<Endpoint>,
    private val discoverEndpoints: suspend (DeviceId, Long) -> List<Endpoint>,
    /** Makes sure sessions get a receive loop and offers a notification (the coordinator). */
    private val ensureReceiving: () -> Unit,
    /**
     * ADR-061: true while Android 17 blocks the local network for Veyro. The notification says so
     * instead of "Looking for", attempts are skipped, and a grant wakes every keeper loop.
     */
    private val localNetworkBlocked: Flow<Boolean> = flowOf(false),
) : BackgroundHost {

    private companion object {
        const val TAG = "Background"

        /** One reconnect attempt's discovery window. Bounded: the keeper backs off between them. */
        const val DISCOVERY_WINDOW_MS = 8_000L

        val KEPT_PLATFORMS = setOf(Platform.WINDOWS, Platform.ANDROID)

        const val PAUSE_FILE = "disconnected-devices"
        const val ALL = "all"
    }

    /**
     * What the user disconnected, kept in a small file so it survives the app restarting
     * (ADR-053): the first line `all` for the notification's Disconnect, then one device id per
     * line. Read once, when this controller is first built.
     */
    private val pauseFile = File(context.noBackupFilesDir, PAUSE_FILE)
    private val saved: Pair<Boolean, Set<String>> = runCatching {
        val lines = pauseFile.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        (lines.firstOrNull() == ALL) to lines.filterNot { it == ALL }.toSet()
    }.getOrDefault(false to emptySet())

    /** Set by the notification's Disconnect; cleared when the user connects again or toggles the setting. */
    private val pausedAll = MutableStateFlow(saved.first)

    /** Devices the user disconnected on Home; the others stay kept (multiple PCs). */
    private val pausedPeers = MutableStateFlow(saved.second)

    /**
     * True while the user has disconnected [deviceId] here, alone or with everything (ADR-053).
     * Such a device is not reconnected by this phone, and its own dial-ins are refused.
     */
    public fun isDisconnectedByUser(deviceId: String): Boolean =
        pausedAll.value || canonical(deviceId) in pausedPeers.value

    /** The paired devices the user has disconnected, for Home's "Disconnected" state. */
    public val userDisconnectedIds: Flow<Set<String>> by lazy {
        combine(pausedAll, pausedPeers, trustStore.peers) { all, ids, peers ->
            if (all) peers.map { it.deviceId.value }.toSet() else ids
        }.distinctUntilChanged()
    }

    private fun canonical(deviceId: String): String = DeviceId.parseOrNull(deviceId)?.value ?: deviceId

    private fun savePauses() {
        val all = pausedAll.value
        val ids = pausedPeers.value
        scope.launch(dispatchers.io) {
            runCatching {
                if (!all && ids.isEmpty()) {
                    pauseFile.delete()
                } else {
                    pauseFile.writeText((listOfNotNull(ALL.takeIf { all }) + ids).joinToString("\n"))
                }
            }.onFailure { VeyroLog.w(TAG) { "Disconnects not saved: ${it.javaClass.simpleName}" } }
        }
    }

    /** Live sessions by peer id, oldest first. */
    private val connections: Flow<Map<String, PeerConnection>> by lazy {
        flowOf(Unit).flatMapLatest { connectionManager().connections }
    }

    private val connectedPeerIds: Flow<Set<String>> by lazy {
        connections.map { it.keys.toSet() }.distinctUntilChanged()
    }

    /**
     * Every paired PC and phone the user has not disconnected, most recently used first. Phones
     * are kept too (ADR-052): the other phone can then send files and control media while this
     * one is in a pocket.
     */
    private val targets: Flow<List<KeepTarget>> by lazy {
        combine(trustStore.peers, pausedPeers) { peers, paused ->
            peers
                .filter { !it.revoked && it.platform in KEPT_PLATFORMS && it.deviceId.value !in paused }
                .sortedWith(compareByDescending<TrustedPeer> { it.lastConnectedEpochMillis }.thenByDescending { it.pairedAtEpochMillis })
                .map { KeepTarget(it.deviceId.value, it.displayName) }
        }.distinctUntilChanged()
    }

    private val shouldRun: Flow<Boolean> by lazy {
        combine(settings.runInBackground, pausedAll, targets) { on, isPaused, t -> on && !isPaused && t.isNotEmpty() }
            .distinctUntilChanged()
    }

    override val backgroundStatus: Flow<BackgroundStatus?> by lazy {
        val selected = flowOf(Unit).flatMapLatest { connectionManager().activeConnection }.map { it?.peerDeviceId }
        combine(shouldRun, targets, connectedPeerIds, selected, localNetworkBlocked) { run, t, live, chosen, blocked ->
            when {
                !run || t.isEmpty() -> null
                // ADR-061: a start from boot without the permission must not look like "Looking for".
                blocked -> statusOf(t, live, chosen).copy(
                    problemTitle = LocalNetworkPermission.BACKGROUND_TITLE,
                    problemText = LocalNetworkPermission.BACKGROUND_TEXT,
                )
                else -> statusOf(t, live, chosen)
            }
        }.distinctUntilChanged()
    }

    /**
     * The notification's content for every kept PC: which are connected, which are looked for.
     * Send files goes to the PC selected on Home while it is connected, else the first connected,
     * else the first target.
     */
    private fun statusOf(targets: List<KeepTarget>, live: Set<String>, selected: String?): BackgroundStatus {
        val connected = targets.filter { it.deviceId in live }
        val looking = targets.filterNot { it.deviceId in live }
        val primary = connected.firstOrNull { it.deviceId == selected } ?: connected.firstOrNull() ?: targets.first()
        return BackgroundStatus(
            peerDeviceId = primary.deviceId,
            peerName = primary.name,
            connected = connected.isNotEmpty(),
            connectedNames = connected.map { it.name },
            lookingNames = looking.map { it.name },
        )
    }

    private val keeper: CompanionKeeper by lazy {
        CompanionKeeper(
            targets = combine(targets, pausedAll) { t, isPaused -> if (isPaused) emptyList() else t },
            connectedPeerIds = connectedPeerIds,
            wakeups = wakeups(),
            attempt = ::attempt,
        )
    }

    private var observing: Job? = null
    private var keeping: Job? = null

    /** Starts following the setting and the trust store. Idempotent. */
    @Synchronized
    public fun observe() {
        if (observing?.isActive == true) return
        observing = scope.launch {
            shouldRun.collect { run ->
                if (run) {
                    ensureReceiving()
                    VeyroBackgroundService.start(context)
                } else {
                    VeyroBackgroundService.stop(context)
                }
            }
        }
    }

    /** The app came to the foreground: the one moment a stopped service may always be started. */
    public fun onAppVisible() {
        observe()
        scope.launch {
            if (shouldRun.first()) VeyroBackgroundService.start(context)
        }
    }

    /**
     * Home's Connect, or a send to [deviceId]: the user wants that device again, so background
     * keeping resumes for it and it may dial in. After the notification's Disconnect, only this
     * device resumes; the others stay disconnected until they are connected one by one (ADR-053).
     */
    public suspend fun onUserConnect(deviceId: String) {
        val id = canonical(deviceId)
        if (pausedAll.value) {
            val everyone = runCatching { trustStore.snapshot() }.getOrDefault(emptyList()).map { it.deviceId.value }
            pausedPeers.value = everyone.toSet() + pausedPeers.value
            pausedAll.value = false
        }
        pausedPeers.update { it - id }
        savePauses()
    }

    /**
     * Home's Disconnect for one device: close its sessions, stop reconnecting to it, and refuse
     * it when it dials in, until the user connects it again (ADR-053). Other devices stay.
     */
    public fun onUserDisconnect(deviceId: String) {
        pausedPeers.update { it + canonical(deviceId) }
        savePauses()
        connectionManager().disconnect(deviceId)
    }

    /** The notification's Disconnect: close every session, stop reconnecting, refuse dial-ins. */
    public fun onUserDisconnectAll() {
        pausedAll.value = true
        savePauses()
        connectionManager().disconnect()
    }

    /** Called when the user flips "Run in background", so an old Disconnect does not linger. */
    public fun onSettingChanged() {
        pausedAll.value = false
        pausedPeers.value = emptySet()
        savePauses()
    }

    // ── BackgroundHost ──────────────────────────────────────────────────────────────────────

    @Synchronized
    override fun onBackgroundStarted() {
        observe()
        ensureReceiving()
        if (keeping?.isActive == true) return
        VeyroLog.i(TAG) { "Background connection started" }
        keeping = scope.launch { keeper.run() }
    }

    @Synchronized
    override fun onBackgroundStopped() {
        keeping?.cancel()
        keeping = null
        VeyroLog.i(TAG) { "Background connection stopped" }
    }

    override fun onDisconnectRequested() {
        onUserDisconnectAll()
    }

    override fun onBoot(context: Context, done: () -> Unit) {
        startIfWanted(context, "Boot", done)
    }

    /** ADR-061: an update killed the service; the same rules as boot bring it back. */
    override fun onAppUpdated(context: Context, done: () -> Unit) {
        startIfWanted(context, "App updated", done)
    }

    private fun startIfWanted(context: Context, reason: String, done: () -> Unit) {
        scope.launch {
            try {
                val wanted = settings.startOnBoot.first() && settings.runInBackground.first()
                if (wanted) {
                    observe()
                    if (shouldRun.first()) VeyroBackgroundService.start(context)
                }
                VeyroLog.i(TAG) { "$reason: start on boot=$wanted" }
            } finally {
                done()
            }
        }
    }

    override fun openAppIntent(context: Context, sendToDeviceId: String?): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (sendToDeviceId != null) {
                action = MainActivity.ACTION_SEND_FILES
                putExtra(MainActivity.EXTRA_SEND_TO, sendToDeviceId)
            }
        }

    // ── Reconnect policy ────────────────────────────────────────────────────────────────────

    /** One bounded attempt: skip without a network, use known endpoints, else one short window. */
    private suspend fun attempt(t: KeepTarget): Boolean {
        // ADR-061: every packet would be dropped; no discovery window, no dial.
        if (localNetworkBlocked.first()) return false
        val network = runCatching { networkMonitor.currentStatus() }.getOrNull()
        if (network != null && !network.hasUsableNetwork) return false
        val id = DeviceId.parseOrNull(t.deviceId) ?: return false
        val known = knownEndpoints(id)
        if (known.isNotEmpty() && connectionManager().connect(t.deviceId, t.name, known)) return true
        // A phone's address is remembered longer (ADR-052), so it can be out of date: look once.
        val found = discoverEndpoints(id, DISCOVERY_WINDOW_MS)
        if (found.isEmpty() || found == known) return false
        return connectionManager().connect(t.deviceId, t.name, found)
    }

    /** A usable network appearing or changing (NetworkCallback), or the PC turning up in discovery. */
    private fun wakeups(): Flow<Unit> = merge(
        networkMonitor.status
            .map { it.hasUsableNetwork to it.localAddresses }
            .distinctUntilChanged()
            .drop(1)
            .filter { (usable, _) -> usable }
            .map { },
        // A target PC turning up in discovery: the set of visible targets grew.
        combine(discoveredPeers, targets) { peers, t ->
            t.filter { target -> peers.any { it.deviceId.value == target.deviceId && it.endpoints.isNotEmpty() } }
                .map { it.deviceId }
                .toSet()
        }
            .distinctUntilChanged()
            .runningFold(emptySet<String>() to emptySet<String>()) { (_, previous), now -> previous to now }
            .filter { (previous, now) -> (now - previous).isNotEmpty() }
            .map { },
        // ADR-061: the local network permission was just granted.
        localNetworkBlocked
            .distinctUntilChanged()
            .drop(1)
            .filter { blocked -> !blocked }
            .map { },
    )
}
