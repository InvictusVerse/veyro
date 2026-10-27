package com.veyro.core.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [SmsSource] and [SmsSender] backed by the Telephony provider and `SmsManager` (ADR-027).
 *
 * SMS only: MMS rows live in a different table and are skipped in v1, so a thread that mixes
 * both is listed with its SMS parts. Drafts are never served. Every read checks `READ_SMS` and
 * answers empty without it; sending is gated on `SEND_SMS` by [SmsBridge].
 *
 * Message bodies, numbers, contact names and SIM labels are user data: nothing here logs them.
 */
public class AndroidSmsSource(
    context: Context,
    private val dispatchers: AppDispatchers,
) : SmsSource, SmsSender {

    public companion object {
        private const val TAG = "SmsSource"
        internal const val EXTRA_TOKEN: String = "com.veyro.sms.token"

        private const val MAX_NEW_PER_PASS = 50
        private const val MAX_SCAN_ROWS = 20_000
        private const val MAX_THREAD_ADDRESSES = 10
        private const val SNIPPET_CHARS = 160

        private const val ID = Telephony.Sms._ID
        private const val THREAD = Telephony.Sms.THREAD_ID
        private const val ADDRESS = Telephony.Sms.ADDRESS
        private const val BODY = Telephony.Sms.BODY
        private const val DATE = Telephony.Sms.DATE
        private const val TYPE = Telephony.Sms.TYPE
        private const val READ = Telephony.Sms.READ
        private const val SUB_ID = Telephony.Sms.SUBSCRIPTION_ID

        /** Inbox, sent, outbox, failed, queued. Drafts and anything unknown are excluded. */
        private val SERVED_TYPES = "$TYPE IN (" + listOf(
            Telephony.Sms.MESSAGE_TYPE_INBOX,
            Telephony.Sms.MESSAGE_TYPE_SENT,
            Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            Telephony.Sms.MESSAGE_TYPE_FAILED,
            Telephony.Sms.MESSAGE_TYPE_QUEUED,
        ).joinToString(",") + ")"

        /** The live source, for [SmsSentReceiver]. App-scoped, so no leak. */
        @Volatile internal var instance: AndroidSmsSource? = null
    }

    private val appContext: Context = context.applicationContext
    private val names = AndroidContactNames(appContext)

    private val pending = ConcurrentHashMap<Long, Channel<String>>()
    private val nextToken = AtomicLong()
    private val nextRequestCode = AtomicInteger()

    init {
        instance = this
    }

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ── SmsSource ───────────────────────────────────────────────────────────────────────────

    override val changes: Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val registered = try {
            appContext.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            true
        } catch (e: SecurityException) {
            VeyroLog.w(TAG) { "SMS observer refused: ${e.javaClass.simpleName}" }
            false
        }
        awaitClose { if (registered) appContext.contentResolver.unregisterContentObserver(observer) }
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE checked just below.
    override suspend fun sims(): List<PeerMessage.SimInfo> = withContext(dispatchers.io) {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return@withContext emptyList()
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return@withContext emptyList()
        }
        val manager = appContext.getSystemService(SubscriptionManager::class.java)
            ?: return@withContext emptyList()
        try {
            manager.activeSubscriptionInfoList.orEmpty().map { info ->
                val slot = info.simSlotIndex.takeIf { it >= 0 }
                PeerMessage.SimInfo(
                    id = info.subscriptionId,
                    label = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: "SIM ${(slot ?: 0) + 1}",
                    slot = slot,
                )
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    override suspend fun threads(limit: Int): List<PeerMessage.SmsThread> = read(emptyList()) {
        data class Acc(val newestId: Long, val ts: Long, val addresses: LinkedHashSet<String>)

        // Pass 1, light columns only: newest message and addresses per thread, newest thread first.
        val acc = LinkedHashMap<Long, Acc>()
        query(arrayOf(ID, THREAD, ADDRESS, DATE), SERVED_TYPES, null, "$DATE DESC, $ID DESC")?.use { c ->
            var scanned = 0
            while (c.moveToNext() && scanned++ < MAX_SCAN_ROWS) {
                val threadId = c.getLong(1)
                val address = c.getString(2).orEmpty()
                val existing = acc[threadId]
                if (existing == null) {
                    if (acc.size >= limit) break
                    acc[threadId] = Acc(c.getLong(0), c.getLong(3), linkedSetOf(address))
                } else if (existing.addresses.size < MAX_THREAD_ADDRESSES && address.isNotEmpty()) {
                    existing.addresses += address
                }
            }
        }
        if (acc.isEmpty()) return@read emptyList()

        // Pass 2: snippets, one query for every thread's newest body.
        val snippets = HashMap<Long, String>()
        val ids = acc.values.map { it.newestId }
        query(arrayOf(ID, BODY), "$ID IN (${ids.joinToString(",")})", null, null)?.use { c ->
            while (c.moveToNext()) snippets[c.getLong(0)] = c.getString(1).orEmpty().take(SNIPPET_CHARS)
        }

        val unread = unreadCounts(null)
        acc.map { (threadId, a) -> buildThread(threadId, a.addresses.toList(), snippets[a.newestId], a.ts, unread[threadId] ?: 0) }
    }

    override suspend fun thread(threadId: Long): PeerMessage.SmsThread? = read(null) {
        var newest: Pair<String?, Long>? = null
        val addresses = LinkedHashSet<String>()
        query(arrayOf(ADDRESS, BODY, DATE), "$THREAD = ? AND $SERVED_TYPES", arrayOf(threadId.toString()), "$DATE DESC, $ID DESC")
            ?.use { c ->
                var rows = 0
                while (c.moveToNext() && rows++ < MAX_THREAD_ADDRESSES * 5) {
                    if (newest == null) newest = c.getString(1)?.take(SNIPPET_CHARS) to c.getLong(2)
                    c.getString(0)?.takeIf { it.isNotEmpty() && addresses.size < MAX_THREAD_ADDRESSES }?.let { addresses += it }
                }
            }
        val (snippet, ts) = newest ?: return@read null
        buildThread(threadId, addresses.toList(), snippet, ts, unreadCounts(threadId)[threadId] ?: 0)
    }

    override suspend fun messages(threadId: Long, beforeTs: Long?, limit: Int): List<PeerMessage.SmsMessage> =
        read(emptyList()) {
            val selection = buildString {
                append("$THREAD = ? AND $SERVED_TYPES")
                if (beforeTs != null) append(" AND $DATE < ?")
            }
            val args = listOfNotNull(threadId.toString(), beforeTs?.toString()).toTypedArray()
            // A null projection, because sub_id is missing on some OEM providers and naming a
            // missing column throws. The page size bounds the cost.
            query(null, selection, args, "$DATE DESC, $ID DESC")?.use { c -> readMessages(c, limit) }.orEmpty()
        }

    override suspend fun maxId(): Long = read(0L) {
        query(arrayOf(ID), null, null, "$ID DESC")?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
    }

    override suspend fun messagesAfter(id: Long): List<PeerMessage.SmsMessage> = read(emptyList()) {
        query(null, "$ID > ? AND $SERVED_TYPES", arrayOf(id.toString()), "$ID ASC")
            ?.use { c -> readMessages(c, MAX_NEW_PER_PASS) }
            .orEmpty()
    }

    private fun buildThread(
        threadId: Long,
        addresses: List<String>,
        snippet: String?,
        ts: Long,
        unread: Int,
    ): PeerMessage.SmsThread {
        val contactNames = addresses.map { names.nameFor(it) }
        return PeerMessage.SmsThread(
            threadId = threadId,
            addresses = addresses,
            names = contactNames.takeIf { list -> list.any { it != null } },
            snippet = snippet,
            ts = ts,
            unread = unread,
        )
    }

    /** Unread incoming messages per thread; only [threadId] when given. */
    private fun unreadCounts(threadId: Long?): Map<Long, Int> {
        val counts = HashMap<Long, Int>()
        val selection = "$READ = 0 AND $TYPE = ${Telephony.Sms.MESSAGE_TYPE_INBOX}" +
            if (threadId != null) " AND $THREAD = ?" else ""
        query(arrayOf(THREAD), selection, threadId?.let { arrayOf(it.toString()) }, null)?.use { c ->
            while (c.moveToNext()) {
                val t = c.getLong(0)
                counts[t] = (counts[t] ?: 0) + 1
            }
        }
        return counts
    }

    private fun readMessages(c: Cursor, max: Int): List<PeerMessage.SmsMessage> {
        val iId = c.getColumnIndex(ID)
        val iThread = c.getColumnIndex(THREAD)
        val iAddress = c.getColumnIndex(ADDRESS)
        val iBody = c.getColumnIndex(BODY)
        val iDate = c.getColumnIndex(DATE)
        val iType = c.getColumnIndex(TYPE)
        val iRead = c.getColumnIndex(READ)
        val iSub = c.getColumnIndex(SUB_ID)
        if (iId < 0 || iThread < 0 || iType < 0) return emptyList()

        val out = ArrayList<PeerMessage.SmsMessage>()
        while (out.size < max && c.moveToNext()) {
            val (outgoing, status) = when (c.getInt(iType)) {
                Telephony.Sms.MESSAGE_TYPE_INBOX -> false to null
                Telephony.Sms.MESSAGE_TYPE_SENT -> true to "sent"
                Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> true to "pending"
                Telephony.Sms.MESSAGE_TYPE_FAILED -> true to "failed"
                else -> continue
            }
            out += PeerMessage.SmsMessage(
                id = c.getLong(iId),
                threadId = c.getLong(iThread),
                address = if (iAddress >= 0) c.getString(iAddress).orEmpty() else "",
                body = if (iBody >= 0) c.getString(iBody).orEmpty() else "",
                ts = if (iDate >= 0) c.getLong(iDate) else 0L,
                out = outgoing,
                read = if (iRead >= 0) c.getInt(iRead) != 0 else true,
                status = status,
                sim = if (iSub >= 0 && !c.isNull(iSub)) c.getInt(iSub).takeIf { it >= 0 } else null,
            )
        }
        return out
    }

    private fun query(
        projection: Array<String>?,
        selection: String?,
        args: Array<String>?,
        sort: String?,
    ): Cursor? = appContext.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, sort)

    /** Runs [block] on IO with `READ_SMS`; [fallback] without it or when the provider refuses. */
    private suspend fun <T> read(fallback: T, block: () -> T): T = withContext(dispatchers.io) {
        if (!granted(Manifest.permission.READ_SMS)) return@withContext fallback
        try {
            block()
        } catch (e: SecurityException) {
            fallback
        } catch (e: IllegalArgumentException) {
            fallback
        } catch (e: android.database.SQLException) {
            VeyroLog.w(TAG) { "SMS query failed: ${e.javaClass.simpleName}" }
            fallback
        }
    }

    // ── SmsSender ───────────────────────────────────────────────────────────────────────────

    override fun canSend(): Boolean = granted(Manifest.permission.SEND_SMS)

    override suspend fun send(address: String, body: String, sim: Int?): SmsDispatch = withContext(dispatchers.io) {
        val failed = SmsDispatch(1, flowOf(SmsBridge.ERR_FAILED))
        val manager = smsManager(sim) ?: return@withContext failed
        val parts = manager.divideMessage(body)
        if (parts.isNullOrEmpty()) return@withContext failed

        val token = nextToken.incrementAndGet()
        val results = Channel<String>(Channel.UNLIMITED)
        pending[token] = results
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        repeat(parts.size) {
            val intent = Intent(appContext, SmsSentReceiver::class.java).putExtra(EXTRA_TOKEN, token)
            // A unique request code per part, or the system would hand back one shared PendingIntent.
            sentIntents += PendingIntent.getBroadcast(
                appContext,
                nextRequestCode.incrementAndGet(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        }
        try {
            manager.sendMultipartTextMessage(address, null, parts, sentIntents, null)
        } catch (e: RuntimeException) {
            // SecurityException without SEND_SMS, IllegalArgumentException for a refused address.
            pending.remove(token)
            VeyroLog.w(TAG) { "SMS hand-off refused: ${e.javaClass.simpleName}" }
            return@withContext failed
        }
        SmsDispatch(
            parts = parts.size,
            results = flow {
                try {
                    emitAll(results)
                } finally {
                    pending.remove(token)
                }
            },
        )
    }

    /** One part of send [token] reported. Called by [SmsSentReceiver]. */
    internal fun onPartSent(token: Long, result: String) {
        pending[token]?.trySend(result)
    }

    @Suppress("DEPRECATION") // The pre-31 factories are the only ones on those releases.
    private fun smsManager(sim: Int?): SmsManager? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = appContext.getSystemService(SmsManager::class.java)
            if (sim != null) base?.createForSubscriptionId(sim) else base
        } else {
            if (sim != null) SmsManager.getSmsManagerForSubscriptionId(sim) else SmsManager.getDefault()
        }
    } catch (e: RuntimeException) {
        null
    }
}

/** Per-part "sent" results of [AndroidSmsSource.send]. Not exported: only our PendingIntents reach it. */
public class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val token = intent?.getLongExtra(AndroidSmsSource.EXTRA_TOKEN, -1L) ?: return
        if (token < 0) return
        val result = when (resultCode) {
            Activity.RESULT_OK -> SmsBridge.PART_OK
            SmsManager.RESULT_ERROR_RADIO_OFF -> SmsBridge.ERR_RADIO
            SmsManager.RESULT_ERROR_NO_SERVICE -> SmsBridge.ERR_NO_SERVICE
            else -> SmsBridge.ERR_FAILED
        }
        AndroidSmsSource.instance?.onPartSent(token, result)
    }
}
