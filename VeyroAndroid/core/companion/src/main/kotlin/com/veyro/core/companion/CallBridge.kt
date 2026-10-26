package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.LinkMessage
import com.veyro.core.model.Capability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** The telephony call state as Android reports it. */
public enum class TelephonyCallState { IDLE, RINGING, OFFHOOK }

/** One report from the telephony stack. [number] is only known for incoming calls, and not always. */
public data class CallSignal(val state: TelephonyCallState, val number: String? = null)

/** Call state, contacts and the call log. On Android, `TelephonyManager` and `CallLog.Calls`. */
public interface CallSource {
    /**
     * Cold stream of call-state reports, starting with the current state. Collected only while a
     * capable session exists and the setting is on; empty without `READ_PHONE_STATE`.
     */
    public val signals: Flow<CallSignal>

    /** Contact display name for [number], or null. */
    public suspend fun contactName(number: String): String?

    /** Up to [limit] call-log entries, newest first. Empty without `READ_CALL_LOG`. */
    public suspend fun callLog(limit: Int): List<LinkMessage.CallLogEntry>
}

/** Acts on the phone's current call. Implementations check their own permissions and never throw. */
public interface CallController {
    public suspend fun answer()

    /** Declines a ringing call or hangs up an active one. */
    public suspend fun end()

    public suspend fun silence()

    /** Places a call to an already validated [number]. */
    public suspend fun dial(number: String)

    /** Undoes anything [silence] changed. Called when a call ends or tracking stops. */
    public fun restoreAfterCall()
}

/**
 * Calls over the paired session (ADR-027, 04-PROTOCOL 6.3).
 *
 * Per link that advertises [Capability.CALLS], with [enabled] on: follows the telephony state and
 * sends `callState` (RINGING -> ringing/in; OFFHOOK -> active, `out` unless it followed ringing;
 * IDLE -> ended), executes `callAction` for the current call, and answers `callLogReq`. With the
 * setting off nothing is tracked and `callLogReq` gets an empty list.
 *
 * Numbers and names are user data: nothing here logs them.
 */
public class CallBridge(
    private val links: Flow<CompanionLink?>,
    private val enabled: Flow<Boolean>,
    private val source: CallSource,
    private val controller: CallController,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newCallId: () -> String = { UUID.randomUUID().toString() },
) {

    public companion object {
        private const val TAG = "CallBridge"

        public const val MAX_LOG_LIMIT: Int = 500

        /** An outgoing call starting this soon after a PC `dial` is taken to be that call. */
        public const val DIAL_MATCH_WINDOW_MS: Long = 30_000L

        public const val STATE_RINGING: String = "ringing"
        public const val STATE_ACTIVE: String = "active"
        public const val STATE_ENDED: String = "ended"
        public const val DIR_IN: String = "in"
        public const val DIR_OUT: String = "out"

        public const val ACTION_DECLINE: String = "decline"
        public const val ACTION_SILENCE: String = "silence"
        public const val ACTION_ANSWER: String = "answer"
        public const val ACTION_END: String = "end"
        public const val ACTION_DIAL: String = "dial"
    }

    private data class Call(
        val id: String,
        val state: String,
        val direction: String,
        val number: String?,
        val name: String?,
    )

    private val on = MutableStateFlow(false)
    private val lock = Mutex()
    private var current: Call? = null
    private var pendingDial: Pair<String, Long>? = null

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        links
            .flatMapLatest { link ->
                link?.peerCapabilities?.map { caps -> link.takeIf { Capability.CALLS in caps } }
                    ?: flowOf(null)
            }
            .distinctUntilChanged()
            .collectLatest { link -> if (link != null) serve(link) }
    }

    private suspend fun serve(link: CompanionLink) {
        try {
            coroutineScope {
                // Read before any request is handled, then followed; the settings store is only
                // touched once a capable session exists.
                on.value = enabled.first()
                launch { enabled.collect { on.value = it } }
                launch {
                    link.incoming.collect { message ->
                        when (message) {
                            is LinkMessage.CallLogRequest -> link.send(callLog(message))
                            is LinkMessage.CallAction -> handleAction(message)
                            else -> Unit
                        }
                    }
                }
                on.collectLatest { enabled ->
                    if (enabled) {
                        track(link)
                    } else {
                        // Switched off mid-call: the PC must not keep showing a stale call.
                        val stale = lock.withLock { current.also { current = null } }
                        if (stale != null) link.send(stale.copy(state = STATE_ENDED).toMessage())
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Call session ended: ${t.javaClass.simpleName}" }
        } finally {
            on.value = false
            current = null
            pendingDial = null
            controller.restoreAfterCall()
        }
    }

    private suspend fun track(link: CompanionLink) {
        try {
            source.signals.collect { signal -> onSignal(link, signal) }
        } finally {
            controller.restoreAfterCall()
        }
    }

    private suspend fun onSignal(link: CompanionLink, signal: CallSignal) {
        val outgoing: Call? = lock.withLock {
            val call = current
            when (signal.state) {
                TelephonyCallState.RINGING -> when {
                    call == null -> {
                        val number = signal.number?.takeIf { it.isNotBlank() }
                        Call(newCallId(), STATE_RINGING, DIR_IN, number, number?.let { lookup(it) })
                    }
                    // The number often arrives in a second report for the same ring.
                    call.state == STATE_RINGING && call.number == null && !signal.number.isNullOrBlank() ->
                        call.copy(number = signal.number, name = lookup(signal.number))
                    else -> null
                }
                TelephonyCallState.OFFHOOK -> when {
                    call == null -> {
                        val dialed = pendingDial
                            ?.takeIf { (_, at) -> clock() - at <= DIAL_MATCH_WINDOW_MS }
                            ?.first
                        pendingDial = null
                        Call(newCallId(), STATE_ACTIVE, DIR_OUT, dialed, dialed?.let { lookup(it) })
                    }
                    call.state == STATE_RINGING -> call.copy(state = STATE_ACTIVE)
                    else -> null
                }
                TelephonyCallState.IDLE -> call?.copy(state = STATE_ENDED)
            }?.also { next ->
                current = next.takeUnless { it.state == STATE_ENDED }
            }
        }
        if (outgoing != null) {
            if (outgoing.state == STATE_ENDED) controller.restoreAfterCall()
            link.send(outgoing.toMessage())
            VeyroLog.d(TAG) { "callState ${outgoing.state}/${outgoing.direction}" }
        }
    }

    private suspend fun lookup(number: String): String? = try {
        source.contactName(number)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

    private suspend fun handleAction(action: LinkMessage.CallAction) {
        if (!on.value) {
            VeyroLog.i(TAG) { "Ignored callAction: messages and calls are off" }
            return
        }
        try {
            if (action.action == ACTION_DIAL) {
                val number = action.number?.let { PhoneNumbers.normalize(it) }
                if (number == null) {
                    VeyroLog.d(TAG) { "Ignored dial: invalid number" }
                    return
                }
                lock.withLock { pendingDial = number to clock() }
                controller.dial(number)
                return
            }
            val call = lock.withLock { current }
            if (call == null || action.callId != call.id) {
                VeyroLog.d(TAG) { "Ignored callAction for a call that is not current" }
                return
            }
            when (action.action) {
                ACTION_DECLINE, ACTION_END -> controller.end()
                ACTION_ANSWER -> if (call.state == STATE_RINGING) controller.answer()
                ACTION_SILENCE -> if (call.state == STATE_RINGING) controller.silence()
                else -> VeyroLog.d(TAG) { "Ignored unknown callAction" }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "callAction failed: ${t.javaClass.simpleName}" }
        }
    }

    private suspend fun callLog(request: LinkMessage.CallLogRequest): LinkMessage.CallLog {
        if (!on.value) return LinkMessage.CallLog(emptyList())
        val limit = request.limit.coerceIn(1, MAX_LOG_LIMIT)
        val entries = try {
            source.callLog(limit).take(limit)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            VeyroLog.w(TAG) { "Call log read failed: ${t.javaClass.simpleName}" }
            emptyList()
        }
        VeyroLog.d(TAG) { "callLog: ${entries.size}" }
        return LinkMessage.CallLog(entries)
    }

    private fun Call.toMessage(): LinkMessage.CallState =
        LinkMessage.CallState(callId = id, state = state, direction = direction, number = number, name = name)
}
