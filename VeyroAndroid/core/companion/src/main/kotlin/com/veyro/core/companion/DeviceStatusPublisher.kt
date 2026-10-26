package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The phone's own battery / ringer / DND state. */
public interface DeviceStatusSource {

    /** Hot "something may have changed" signal. Spurious signals are fine; sends are deduped. */
    public val changes: Flow<Unit>

    /** The current status, battery as a whole percentage. */
    public suspend fun current(): LinkMessage.DeviceStatus
}

/** Applies a validated `devControl`. Permission checks are made by the caller via [hasPolicyAccess]. */
public interface DeviceController {
    /** Starts the find-my-phone alarm. Idempotent; a second call while ringing keeps ringing. */
    public suspend fun startRing(fromName: String?)

    /** Stops the alarm and restores what [startRing] changed. Idempotent. */
    public suspend fun stopRing()

    /** Whether the user granted notification-policy (DND) access to this app. */
    public fun hasPolicyAccess(): Boolean

    /** [mode] is one of [DeviceStatusPublisher.RINGER_NORMAL] / `_VIBRATE` / `_SILENT`. */
    public suspend fun setRinger(mode: String)
    public suspend fun setDnd(enabled: Boolean)
}

/**
 * Device status in both directions plus ring-my-phone (ADR-026, 04-PROTOCOL 6.2).
 *
 * Per link that advertises [Capability.DEVICE_STATUS]:
 *  - sends this phone's `devStatus` on connect and whenever it changes. A change is anything that
 *    alters the message: a whole-point battery move, charging, ringer mode or DND;
 *  - keeps the PC's own `devStatus` as [peerStatus] (null when disconnected);
 *  - executes `devControl` only while [controlAllowed] is on. Ringer and DND changes additionally
 *    need notification-policy access and are ignored without it. A ring stops by itself after
 *    [RING_TIMEOUT_MS]. The resulting status is always sent back after a `devControl`.
 */
public class DeviceStatusPublisher(
    /** Every live paired session (multiple PCs). */
    private val links: Flow<List<CompanionLink>>,
    private val controlAllowed: Flow<Boolean>,
    private val source: DeviceStatusSource,
    private val controller: DeviceController,
    /** Display name for the peer with this device id, for the "Ringing from" notification. */
    private val peerName: suspend (String?) -> String?,
    private val scope: CoroutineScope,
) {

    public companion object {
        private const val TAG = "DeviceStatus"

        public const val RING_TIMEOUT_MS: Long = 30_000L

        public const val RINGER_NORMAL: String = "normal"
        public const val RINGER_VIBRATE: String = "vibrate"
        public const val RINGER_SILENT: String = "silent"

        private val RINGER_MODES = setOf(RINGER_NORMAL, RINGER_VIBRATE, RINGER_SILENT)
    }

    private val _peerStatus = MutableStateFlow<Map<String, LinkMessage.DeviceStatus>>(emptyMap())

    /** Each connected capable PC's own status, by device id. A PC that left is absent. */
    public val peerStatus: StateFlow<Map<String, LinkMessage.DeviceStatus>> = _peerStatus.asStateFlow()

    /** One PC's session: what it was last sent, so each PC gets every change exactly once. */
    private class Peer {
        val sendLock = Mutex()
        var lastSent: LinkMessage.DeviceStatus? = null
    }

    private val ringLock = Mutex()
    private var ringTimeout: Job? = null

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    private suspend fun run() {
        // One session per connected PC: this phone's status goes to every capable PC, each PC's
        // status is kept under its id, and a devControl is answered on the PC that sent it.
        links.forEachLink { link ->
            link.peerCapabilities
                .map { Capability.DEVICE_STATUS in it }
                .distinctUntilChanged()
                .collectLatest { capable -> if (capable) serve(link) }
        }
    }

    private suspend fun serve(link: CompanionLink) {
        val key = link.peerKey
        val peer = Peer()
        try {
            coroutineScope {
                launch {
                    link.incoming.collect { message ->
                        when (message) {
                            is LinkMessage.DeviceStatus -> _peerStatus.update { it + (key to message) }
                            is LinkMessage.DeviceControl -> handleControl(link, peer, message)
                            else -> Unit
                        }
                    }
                }

                val signals = Channel<Unit>(Channel.CONFLATED)
                launch(start = CoroutineStart.UNDISPATCHED) {
                    source.changes.collect { signals.trySend(Unit) }
                }
                publish(link, peer, force = true)
                for (signal in signals) publish(link, peer, force = false)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Status session ended: ${t.javaClass.simpleName}" }
        } finally {
            _peerStatus.update { it - key }
        }
    }

    private suspend fun publish(link: CompanionLink, peer: Peer, force: Boolean) {
        peer.sendLock.withLock {
            val status = source.current()
            if (!force && status == peer.lastSent) return
            link.send(status)
            peer.lastSent = status
        }
    }

    private suspend fun handleControl(link: CompanionLink, peer: Peer, control: LinkMessage.DeviceControl) {
        try {
            if (!controlAllowed.first()) {
                VeyroLog.i(TAG) { "Ignored devControl: not allowed in Settings" }
            } else {
                when (control.ring) {
                    true -> startRing(peerName(link.peerDeviceId))
                    false -> stopRing()
                    null -> Unit
                }
                control.ringer?.let { mode ->
                    when {
                        mode !in RINGER_MODES -> VeyroLog.d(TAG) { "Ignored unknown ringer mode" }
                        !controller.hasPolicyAccess() -> VeyroLog.i(TAG) { "Ignored ringer change: no policy access" }
                        else -> controller.setRinger(mode)
                    }
                }
                control.dnd?.let { on ->
                    if (controller.hasPolicyAccess()) {
                        controller.setDnd(on)
                    } else {
                        VeyroLog.i(TAG) { "Ignored DND change: no policy access" }
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "devControl failed: ${t.javaClass.simpleName}" }
        }
        // The PC's toggles follow what actually happened, applied or not. Other PCs hear of a
        // ringer or DND change through the store's change signal, like any other change.
        publish(link, peer, force = true)
    }

    private suspend fun startRing(fromName: String?) {
        ringLock.withLock {
            ringTimeout?.cancel()
            controller.startRing(fromName)
            // On the outer scope, so a ring outlives a dropped connection but never 30 s.
            ringTimeout = scope.launch {
                delay(RING_TIMEOUT_MS)
                controller.stopRing()
            }
        }
    }

    private suspend fun stopRing() {
        ringLock.withLock {
            ringTimeout?.cancel()
            ringTimeout = null
            controller.stopRing()
        }
    }
}
