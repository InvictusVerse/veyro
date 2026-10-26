package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val links: Flow<CompanionLink?>,
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

    private val _peerStatus = MutableStateFlow<LinkMessage.DeviceStatus?>(null)

    /** The connected PC's status, or null when no capable PC is connected. */
    public val peerStatus: StateFlow<LinkMessage.DeviceStatus?> = _peerStatus.asStateFlow()

    private val sendLock = Mutex()
    private var lastSent: LinkMessage.DeviceStatus? = null

    private val ringLock = Mutex()
    private var ringTimeout: Job? = null

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        links
            .flatMapLatest { link ->
                link?.peerCapabilities?.map { caps ->
                    link.takeIf { Capability.DEVICE_STATUS in caps }
                } ?: flowOf(null)
            }
            .distinctUntilChanged()
            .collectLatest { link -> if (link != null) serve(link) }
    }

    private suspend fun serve(link: CompanionLink) {
        try {
            coroutineScope {
                launch {
                    link.incoming.collect { message ->
                        when (message) {
                            is LinkMessage.DeviceStatus -> _peerStatus.value = message
                            is LinkMessage.DeviceControl -> handleControl(link, message)
                            else -> Unit
                        }
                    }
                }

                val signals = Channel<Unit>(Channel.CONFLATED)
                launch(start = CoroutineStart.UNDISPATCHED) {
                    source.changes.collect { signals.trySend(Unit) }
                }
                publish(link, force = true)
                for (signal in signals) publish(link, force = false)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Status session ended: ${t.javaClass.simpleName}" }
        } finally {
            _peerStatus.value = null
            sendLock.withLock { lastSent = null }
        }
    }

    private suspend fun publish(link: CompanionLink, force: Boolean) {
        sendLock.withLock {
            val status = source.current()
            if (!force && status == lastSent) return
            link.send(status)
            lastSent = status
        }
    }

    private suspend fun handleControl(link: CompanionLink, control: LinkMessage.DeviceControl) {
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
        // The PC's toggles follow what actually happened, applied or not.
        publish(link, force = true)
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
