package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The paired PC the background connection is kept to. */
public data class KeepTarget(val deviceId: String, val name: String)

/** What the background notification says. */
public sealed interface KeeperState {
    /** Nothing to keep (no paired PC, or switched off). */
    public data object Idle : KeeperState

    /** Not connected; trying with backoff, woken early by network changes. */
    public data class Looking(val target: KeepTarget) : KeeperState

    public data class Connected(val target: KeepTarget) : KeeperState
}

/**
 * Keeps the companion session to one paired PC alive while the app runs in the background
 * (`VeyroBackgroundService`).
 *
 * Battery rules: there is no loop that spins. While connected it only waits for the session to
 * change. While disconnected it makes one bounded [attempt] (a short discovery window plus a
 * dial), then sleeps on an exponential backoff from [BACKOFF_MS], up to [MAX_BACKOFF_MS]. A
 * [wakeups] signal (a network change from the NetworkCallback, or the PC turning up in discovery)
 * cuts the sleep short and resets the backoff, which is when a retry is likely to work.
 *
 * Pure Kotlin over flows and lambdas, so the policy is JVM-tested without a device.
 */
public class CompanionKeeper(
    private val target: Flow<KeepTarget?>,
    /** Device id of the live session's peer, null while there is none. */
    private val connectedPeerId: Flow<String?>,
    private val wakeups: Flow<Unit>,
    /** One bounded connection attempt; true if a session is (being) established. */
    private val attempt: suspend (KeepTarget) -> Boolean,
    private val backoffMs: (failures: Int) -> Long = ::defaultBackoff,
    /** How long a successful attempt gets to show up as [connectedPeerId] before retrying. */
    private val settleMs: Long = SETTLE_MS,
) {

    public companion object {
        private const val TAG = "CompanionKeeper"

        public val BACKOFF_MS: List<Long> = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
        public const val MAX_BACKOFF_MS: Long = 300_000L
        public const val SETTLE_MS: Long = 15_000L
        private const val WAKE_SETTLE_MS = 1_000L

        public fun defaultBackoff(failures: Int): Long =
            BACKOFF_MS.getOrElse(failures) { MAX_BACKOFF_MS }
    }

    private val _state = MutableStateFlow<KeeperState>(KeeperState.Idle)
    public val state: StateFlow<KeeperState> = _state.asStateFlow()

    /** Runs until cancelled. Call from the background service's scope. */
    public suspend fun run() {
        try {
            coroutineScope {
                val wake = Channel<Unit>(Channel.CONFLATED)
                launch { wakeups.collect { wake.trySend(Unit) } }
                combine(target, connectedPeerId) { t, peer -> t to peer }
                    .distinctUntilChanged()
                    .collectLatest { (t, peer) ->
                        when {
                            t == null -> _state.value = KeeperState.Idle
                            peer == t.deviceId -> _state.value = KeeperState.Connected(t)
                            else -> {
                                _state.value = KeeperState.Looking(t)
                                // Signals from before this disconnect say nothing about now.
                                wake.tryReceive()
                                keepTrying(t, wake)
                            }
                        }
                    }
            }
        } finally {
            _state.value = KeeperState.Idle
        }
    }

    private suspend fun keepTrying(t: KeepTarget, wake: Channel<Unit>) {
        var failures = 0
        while (true) {
            val ok = try {
                attempt(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VeyroLog.w(TAG) { "Reconnect attempt threw ${e.javaClass.simpleName}" }
                false
            }
            // On success the session normally appears within [settleMs] and collectLatest
            // cancels this loop; if it never does, try again.
            val wait = if (ok) settleMs else backoffMs(failures++)
            if (!ok) VeyroLog.i(TAG) { "Reconnect failed; next try in ${wait / 1000}s unless the network changes" }
            val woke = withTimeoutOrNull(wait) { wake.receive() } != null
            if (woke) {
                failures = 0
                // Let a burst of network callbacks settle before dialling.
                delay(WAKE_SETTLE_MS)
                wake.tryReceive()
            }
        }
    }
}
