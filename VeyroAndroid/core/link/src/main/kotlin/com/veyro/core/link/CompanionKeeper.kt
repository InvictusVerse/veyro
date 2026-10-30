package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.random.Random

/** A paired PC the background connection is kept to. */
public data class KeepTarget(val deviceId: String, val name: String)

/** What the background notification says about one target. */
public sealed interface KeeperState {
    /** Nothing to keep (not a target, or switched off). */
    public data object Idle : KeeperState

    /** Not connected; trying with backoff, woken early by network changes. */
    public data class Looking(val target: KeepTarget) : KeeperState

    public data class Connected(val target: KeepTarget) : KeeperState
}

/**
 * Keeps the companion session to every target PC alive while the app runs in the background
 * (`VeyroBackgroundService`). One keeper loop per target, started and stopped as [targets]
 * changes (multiple PCs); a PC that drops never affects the loops of the others.
 *
 * Battery rules, per loop: there is no loop that spins. While connected it only waits for the
 * session to change. While disconnected it makes one bounded [attempt] (a short discovery window
 * plus a dial), then sleeps on an exponential backoff from [BACKOFF_MS], up to [MAX_BACKOFF_MS].
 * A [wakeups] signal (a network change from the NetworkCallback, or a PC turning up in
 * discovery) is shared: it cuts every loop's sleep short and resets its backoff, which is when a
 * retry is likely to work.
 *
 * ADR-061: every backoff sleep gets +/-[JITTER] random jitter from [random], so several phones
 * (or a phone and its PC) that lost the network together do not retry in lockstep. A session that
 * dies within [FLAP_MS] of connecting does not reset the backoff: the next attempt waits the next
 * backoff step instead of dialling at once, so a connect-drop-connect loop slows down. A session
 * that lasted longer resets it, and the first retry after it is immediate as before.
 *
 * Pure Kotlin over flows and lambdas, so the policy is JVM-tested without a device.
 */
public class CompanionKeeper(
    private val targets: Flow<List<KeepTarget>>,
    /** Device ids of the peers with a live session. */
    private val connectedPeerIds: Flow<Set<String>>,
    private val wakeups: Flow<Unit>,
    /** One bounded connection attempt; true if a session is (being) established. */
    private val attempt: suspend (KeepTarget) -> Boolean,
    private val backoffMs: (failures: Int) -> Long = ::defaultBackoff,
    /** How long a successful attempt gets to show up in [connectedPeerIds] before retrying. */
    private val settleMs: Long = SETTLE_MS,
    /** The jitter source; injected so tests are deterministic. */
    private val random: Random = Random.Default,
    /** A monotonic clock in milliseconds, for how long a session lasted. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    public companion object {
        private const val TAG = "CompanionKeeper"

        public val BACKOFF_MS: List<Long> = listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
        public const val MAX_BACKOFF_MS: Long = 300_000L
        public const val SETTLE_MS: Long = 15_000L
        private const val WAKE_SETTLE_MS = 1_000L

        /** A backoff sleep is its step times a factor in [1 - JITTER, 1 + JITTER). */
        public const val JITTER: Double = 0.2

        /** A session shorter than this counts as flapping: the backoff keeps growing. */
        public const val FLAP_MS: Long = 15_000L

        public fun defaultBackoff(failures: Int): Long =
            BACKOFF_MS.getOrElse(failures) { MAX_BACKOFF_MS }

        /** [baseMs] scaled by a factor in [1 - JITTER, 1 + JITTER); [unit] is in [0, 1). */
        public fun jittered(baseMs: Long, unit: Double): Long =
            (baseMs * (1.0 + JITTER * (2.0 * unit - 1.0))).roundToLong().coerceAtLeast(0L)
    }

    /** Consecutive failures for one target, kept across short sessions. */
    private class Streak {
        var failures = 0
    }

    private val _states = MutableStateFlow<Map<String, KeeperState>>(emptyMap())

    /** Each target's state, by device id. A device that is not a target is absent. */
    public val states: StateFlow<Map<String, KeeperState>> = _states.asStateFlow()

    /** [deviceId]'s state; [KeeperState.Idle] when it is not a target. */
    public fun stateOf(deviceId: String): KeeperState = _states.value[deviceId] ?: KeeperState.Idle

    /** Runs until cancelled. Call from the background service's scope. */
    public suspend fun run() {
        try {
            coroutineScope {
                val wakes = ConcurrentHashMap<String, Channel<Unit>>()
                launch { wakeups.collect { wakes.values.forEach { it.trySend(Unit) } } }
                val running = HashMap<KeepTarget, Job>()
                targets.distinctUntilChanged().collect { list ->
                    val wanted = list.toSet()
                    for (gone in running.keys.filter { it !in wanted }) {
                        running.remove(gone)?.cancel()
                        wakes.remove(gone.deviceId)
                        _states.update { it - gone.deviceId }
                    }
                    for (t in wanted) {
                        if (t in running) continue
                        val wake = Channel<Unit>(Channel.CONFLATED)
                        wakes[t.deviceId] = wake
                        running[t] = launch { keep(t, wake) }
                    }
                }
            }
        } finally {
            _states.value = emptyMap()
        }
    }

    private suspend fun keep(t: KeepTarget, wake: Channel<Unit>) {
        val streak = Streak()
        var connectedAt = -1L
        connectedPeerIds
            .map { t.deviceId in it }
            .distinctUntilChanged()
            .collectLatest { connected ->
                if (connected) {
                    connectedAt = nowMs()
                    _states.update { it + (t.deviceId to KeeperState.Connected(t)) }
                } else {
                    _states.update { it + (t.deviceId to KeeperState.Looking(t)) }
                    // A session that died young is flapping: wait the next step before dialling.
                    val flapping = connectedAt >= 0 && nowMs() - connectedAt < FLAP_MS
                    if (connectedAt >= 0 && !flapping) streak.failures = 0
                    connectedAt = -1L
                    // Signals from before this disconnect say nothing about now.
                    wake.tryReceive()
                    keepTrying(t, wake, streak, waitFirst = flapping)
                }
            }
    }

    private suspend fun keepTrying(t: KeepTarget, wake: Channel<Unit>, streak: Streak, waitFirst: Boolean) {
        if (waitFirst) {
            val wait = jittered(backoffMs(streak.failures++), random.nextDouble())
            VeyroLog.i(TAG) { "Session dropped soon after connecting; next try in ${wait / 1000}s unless the network changes" }
            sleepOrWake(wait, wake, streak)
        }
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
            val wait = if (ok) settleMs else jittered(backoffMs(streak.failures++), random.nextDouble())
            if (!ok) VeyroLog.i(TAG) { "Reconnect failed; next try in ${wait / 1000}s unless the network changes" }
            sleepOrWake(wait, wake, streak)
        }
    }

    /** Sleeps [waitMs], or less when [wake] fires; a wake resets the backoff. */
    private suspend fun sleepOrWake(waitMs: Long, wake: Channel<Unit>, streak: Streak) {
        val woke = withTimeoutOrNull(waitMs) { wake.receive() } != null
        if (woke) {
            streak.failures = 0
            // Let a burst of network callbacks settle before dialling.
            delay(WAKE_SETTLE_MS)
            wake.tryReceive()
        }
    }
}
