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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Runs Veyro in the background (ADR-036): decides when [VeyroBackgroundService] should run, feeds
 * it the status line, and owns the [CompanionKeeper] that reconnects to the paired PC.
 *
 * Runs when "Run in background" is on (default), a Windows PC is paired, and the user has not
 * pressed Disconnect (on the notification or on Home) since they last connected. The PC kept is
 * the one last connected, else the most recently used paired PC.
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
) : BackgroundHost {

    private companion object {
        const val TAG = "Background"

        /** One reconnect attempt's discovery window. Bounded: the keeper backs off between them. */
        const val DISCOVERY_WINDOW_MS = 8_000L
    }

    /** Set by Disconnect; cleared when the user connects again or toggles the setting. */
    private val paused = MutableStateFlow(false)
    private val lastConnectedId = MutableStateFlow<String?>(null)

    private val connectedPeerId: Flow<String?> by lazy {
        flowOf(Unit).flatMapLatest { connectionManager().activeConnection }.map { it?.peerDeviceId }
    }

    private val target: Flow<KeepTarget?> by lazy {
        combine(trustStore.peers, lastConnectedId) { peers, last ->
            val pcs = peers.filter { !it.revoked && it.platform == Platform.WINDOWS }
            val chosen = pcs.firstOrNull { it.deviceId.value == last }
                ?: pcs.maxWithOrNull(compareBy({ it.lastConnectedEpochMillis }, { it.pairedAtEpochMillis }))
            chosen?.let { KeepTarget(it.deviceId.value, it.name) }
        }.distinctUntilChanged()
    }

    private val shouldRun: Flow<Boolean> by lazy {
        combine(settings.runInBackground, paused, target) { on, isPaused, t -> on && !isPaused && t != null }
            .distinctUntilChanged()
    }

    override val backgroundStatus: Flow<BackgroundStatus?> by lazy {
        combine(shouldRun, target, connectedPeerId) { run, t, peer ->
            if (!run || t == null) null else BackgroundStatus(t.deviceId, t.name, connected = peer == t.deviceId)
        }.distinctUntilChanged()
    }

    private val keeper: CompanionKeeper by lazy {
        CompanionKeeper(
            target = combine(target, paused) { t, isPaused -> t.takeUnless { isPaused } },
            connectedPeerId = connectedPeerId,
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
            launch { connectedPeerId.filterNotNull().collect { lastConnectedId.value = it } }
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

    /** Home's Connect: the user wants the PC again, so background keeping resumes. */
    public fun onUserConnect() {
        paused.value = false
    }

    /** Home's Disconnect or the notification's: close the session and stop reconnecting. */
    public fun onUserDisconnect() {
        paused.value = true
        connectionManager().disconnect()
    }

    /** Called when the user flips "Run in background", so an old Disconnect does not linger. */
    public fun onSettingChanged() {
        paused.value = false
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
        onUserDisconnect()
    }

    override fun onBoot(context: Context, done: () -> Unit) {
        scope.launch {
            try {
                val wanted = settings.startOnBoot.first() && settings.runInBackground.first()
                if (wanted) {
                    observe()
                    if (shouldRun.first()) VeyroBackgroundService.start(context)
                }
                VeyroLog.i(TAG) { "Boot: start on boot=$wanted" }
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
        val network = runCatching { networkMonitor.currentStatus() }.getOrNull()
        if (network != null && !network.hasUsableNetwork) return false
        val id = DeviceId.parseOrNull(t.deviceId) ?: return false
        val endpoints = knownEndpoints(id).ifEmpty { discoverEndpoints(id, DISCOVERY_WINDOW_MS) }
        if (endpoints.isEmpty()) return false
        return connectionManager().connect(t.deviceId, t.name, endpoints)
    }

    /** A usable network appearing or changing (NetworkCallback), or the PC turning up in discovery. */
    private fun wakeups(): Flow<Unit> = merge(
        networkMonitor.status
            .map { it.hasUsableNetwork to it.localAddresses }
            .distinctUntilChanged()
            .drop(1)
            .filter { (usable, _) -> usable }
            .map { },
        combine(discoveredPeers, lastConnectedId) { peers, last ->
            last != null && peers.any { it.deviceId.value == last && it.endpoints.isNotEmpty() }
        }
            .distinctUntilChanged()
            .filter { it }
            .map { },
    )
}
