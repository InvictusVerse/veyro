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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Phone-number checks shared by `smsSend` and `callAction: dial` (04-PROTOCOL 6.3). */
public object PhoneNumbers {

    private val SEPARATORS = setOf(' ', '-', '.', '(', ')', '[', ']')
    private val VALID = Regex("^[+]?[0-9*#]{3,32}$")

    /** [raw] with spaces, dashes, dots and brackets stripped, or null when it is not a valid number. */
    public fun normalize(raw: String): String? {
        val stripped = raw.filterNot { it in SEPARATORS || it == '\u00A0' }
        return stripped.takeIf { VALID.matches(it) }
    }
}

/** Where SMS data comes from. On Android, the Telephony provider (`content://sms`). */
public interface SmsSource {

    /** Hot "the SMS store changed" signal. Spurious signals are fine. */
    public val changes: Flow<Unit>

    /** SIMs able to send SMS; empty without `READ_PHONE_STATE`. */
    public suspend fun sims(): List<LinkMessage.SimInfo>

    /** Up to [limit] threads, newest first. Empty without `READ_SMS`. */
    public suspend fun threads(limit: Int): List<LinkMessage.SmsThread>

    /** One thread's summary, or null when unknown or unreadable. */
    public suspend fun thread(threadId: Long): LinkMessage.SmsThread?

    /** Up to [limit] messages of [threadId], newest first, strictly older than [beforeTs] if set. */
    public suspend fun messages(threadId: Long, beforeTs: Long?, limit: Int): List<LinkMessage.SmsMessage>

    /** The highest message id in the store right now, 0 when empty or unreadable. */
    public suspend fun maxId(): Long

    /** Messages with an id greater than [id], ascending by id. */
    public suspend fun messagesAfter(id: Long): List<LinkMessage.SmsMessage>
}

/** One text handed to the radio: how many parts it was split into and one result per part. */
public class SmsDispatch(
    public val parts: Int,
    /** One of [SmsBridge.PART_OK] / `ERR_*` per part, in any order. */
    public val results: Flow<String>,
)

/** Hands texts to the radio. On Android, `SmsManager`. */
public interface SmsSender {
    /** Whether `SEND_SMS` is held. */
    public fun canSend(): Boolean

    /** Sends [body] to one [address] on subscription [sim] (null = default SMS SIM). */
    public suspend fun send(address: String, body: String, sim: Int?): SmsDispatch
}

/**
 * Serves SMS to the connected PC (ADR-027, 04-PROTOCOL 6.3).
 *
 * Per link that advertises [Capability.MESSAGING]:
 *  - with [enabled] on: sends `simList`, then pushes `smsNew` for every message whose id is above
 *    the high-water mark taken when serving started (debounced by [DEBOUNCE_MS]);
 *  - answers `smsThreadsReq` / `smsMessagesReq`, with empty lists while [enabled] is off, so the
 *    answer never tells the PC whether data exists;
 *  - executes `smsSend` and answers exactly one `smsSendResult` per `clientId`.
 *
 * Bodies, numbers, names and SIM labels are user data: nothing here logs them, only counts.
 */
public class SmsBridge(
    /** Every live paired session (multiple PCs). */
    private val links: Flow<List<CompanionLink>>,
    private val enabled: Flow<Boolean>,
    private val source: SmsSource,
    private val sender: SmsSender,
    private val scope: CoroutineScope,
) {

    public companion object {
        private const val TAG = "SmsBridge"

        public const val DEBOUNCE_MS: Long = 300L
        public const val SEND_TIMEOUT_MS: Long = 60_000L
        public const val MAX_LIMIT: Int = 200
        public const val MAX_ADDRESSES: Int = 10
        public const val MAX_BODY_CHARS: Int = 2000

        public const val PART_OK: String = "ok"
        public const val ERR_PERMISSION: String = "permission"
        public const val ERR_DISABLED: String = "disabled"
        public const val ERR_INVALID: String = "invalid"
        public const val ERR_RADIO: String = "radio"
        public const val ERR_NO_SERVICE: String = "no-service"
        public const val ERR_FAILED: String = "failed"

        /** Normalized addresses, or null when the send must be refused as `invalid`. */
        public fun validate(addresses: List<String>, body: String): List<String>? {
            if (addresses.isEmpty() || addresses.size > MAX_ADDRESSES) return null
            if (body.isEmpty() || body.length > MAX_BODY_CHARS) return null
            val normalized = addresses.map { PhoneNumbers.normalize(it) ?: return null }
            return normalized
        }
    }

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    private suspend fun run() {
        // One session per connected PC: new texts are pushed to every capable PC; a PC's
        // requests are answered on its own session only.
        links.forEachLink { link ->
            link.peerCapabilities
                .map { Capability.MESSAGING in it }
                .distinctUntilChanged()
                .collectLatest { capable -> if (capable) serve(link) }
        }
    }

    private suspend fun serve(link: CompanionLink) {
        try {
            coroutineScope {
                // Tracks [enabled] for this session's per-request checks. Read before any request
                // is handled, then followed; the settings store is only touched once a capable
                // session exists.
                val on = MutableStateFlow(enabled.first())
                launch { enabled.collect { on.value = it } }
                launch {
                    link.incoming.collect { message ->
                        when (message) {
                            is LinkMessage.SmsThreadsRequest -> link.send(threads(on.value, message))
                            is LinkMessage.SmsMessagesRequest -> link.send(messages(on.value, message))
                            // Concurrent, so a slow radio never blocks history requests.
                            is LinkMessage.SmsSend -> launch { link.send(send(on.value, message)) }
                            else -> Unit
                        }
                    }
                }
                on.collectLatest { enabled -> if (enabled) push(link) }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Usually the socket closing under a send. The next session starts afresh.
            VeyroLog.w(TAG) { "SMS session ended: ${t.javaClass.simpleName}" }
        }
    }

    /** `simList` once, then `smsNew` for every message above the high-water mark. */
    private suspend fun push(link: CompanionLink) {
        coroutineScope {
            val signals = Channel<Unit>(Channel.CONFLATED)
            // Subscribed before the mark is taken, so nothing inserted in between is missed.
            launch(start = CoroutineStart.UNDISPATCHED) {
                source.changes.collect { signals.trySend(Unit) }
            }
            val sims = safely(emptyList()) { source.sims() }
            link.send(LinkMessage.SimList(sims))
            var mark = safely(0L) { source.maxId() }
            VeyroLog.i(TAG) { "SMS serving started; ${sims.size} SIM(s)" }
            while (true) {
                signals.receive()
                // Debounce: a burst of provider notifications (insert, then status update) is one pass.
                delay(DEBOUNCE_MS)
                signals.tryReceive()
                val fresh = safely(emptyList()) { source.messagesAfter(mark) }
                for (message in fresh.sortedBy { it.id }) {
                    if (message.id <= mark) continue
                    val thread = safely(null) { source.thread(message.threadId) }
                    link.send(LinkMessage.SmsNew(message = message, thread = thread))
                    mark = message.id
                }
                if (fresh.isNotEmpty()) VeyroLog.d(TAG) { "Pushed ${fresh.size} new SMS" }
            }
        }
    }

    private suspend fun threads(on: Boolean, request: LinkMessage.SmsThreadsRequest): LinkMessage.SmsThreads {
        if (!on) return LinkMessage.SmsThreads(emptyList())
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        val threads = safely(emptyList()) { source.threads(limit) }.take(limit)
        VeyroLog.d(TAG) { "smsThreads: ${threads.size}" }
        return LinkMessage.SmsThreads(threads)
    }

    private suspend fun messages(on: Boolean, request: LinkMessage.SmsMessagesRequest): LinkMessage.SmsMessages {
        if (!on) return LinkMessage.SmsMessages(threadId = request.threadId, messages = emptyList())
        val limit = request.limit.coerceIn(1, MAX_LIMIT)
        // One extra row tells whether an older page exists.
        val rows = safely(emptyList()) { source.messages(request.threadId, request.beforeTs, limit + 1) }
        val page = rows.take(limit)
        VeyroLog.d(TAG) { "smsMessages: ${page.size}" }
        return LinkMessage.SmsMessages(threadId = request.threadId, messages = page, more = rows.size > limit)
    }

    /**
     * Refusals are checked in privacy order: switched off first (tells the PC nothing), then the
     * request's own shape, then the permission. One result per `clientId`, always.
     */
    private suspend fun send(on: Boolean, request: LinkMessage.SmsSend): LinkMessage.SmsSendResult {
        fun fail(error: String) = LinkMessage.SmsSendResult(clientId = request.clientId, ok = false, error = error)
        if (!on) return fail(ERR_DISABLED)
        val addresses = validate(request.addresses, request.body) ?: return fail(ERR_INVALID)
        if (!sender.canSend()) return fail(ERR_PERMISSION)

        val outcome = withTimeoutOrNull(SEND_TIMEOUT_MS) {
            var firstError: String? = null
            // One message per address: a plain group send, never MMS.
            for (address in addresses) {
                val dispatch = try {
                    sender.send(address, request.body, request.sim)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    VeyroLog.w(TAG) { "SMS hand-off failed: ${t.javaClass.simpleName}" }
                    null
                }
                if (dispatch == null || dispatch.parts <= 0) {
                    if (firstError == null) firstError = ERR_FAILED
                    continue
                }
                dispatch.results.take(dispatch.parts).collect { result ->
                    if (result != PART_OK && firstError == null) firstError = result
                }
            }
            firstError ?: PART_OK
        } ?: ERR_FAILED

        VeyroLog.i(TAG) { "smsSend to ${addresses.size} address(es): $outcome" }
        return if (outcome == PART_OK) {
            LinkMessage.SmsSendResult(clientId = request.clientId, ok = true)
        } else {
            fail(outcome)
        }
    }

    private suspend fun <T> safely(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        VeyroLog.w(TAG) { "SMS read failed: ${t.javaClass.simpleName}" }
        fallback
    }
}
