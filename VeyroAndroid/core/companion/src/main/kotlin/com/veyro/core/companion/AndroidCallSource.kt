package com.veyro.core.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

private fun Context.granted(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/**
 * [CallSource] from `TelephonyManager` and `CallLog.Calls` (ADR-027).
 *
 * Call state comes from `TelephonyCallback.CallStateListener` on API 31+ and `PhoneStateListener`
 * below, both needing `READ_PHONE_STATE`. The incoming number is only exposed to holders of
 * `READ_CALL_LOG`, checked when the call rings: through `PhoneStateListener` (the number only on
 * API 31+) and the `PHONE_STATE` broadcast, both always registered so a later grant works at once.
 * [CallBridge] merges the reports of one ring and looks the number up in the contacts.
 *
 * Numbers and names are user data: nothing here logs them.
 */
public class AndroidCallSource(
    context: Context,
    private val dispatchers: AppDispatchers,
) : CallSource {

    private companion object {
        const val TAG = "CallSource"
    }

    private val appContext: Context = context.applicationContext
    private val names = AndroidContactNames(appContext)

    override val signals: Flow<CallSignal> = callbackFlow {
        val telephony = appContext.getSystemService(TelephonyManager::class.java)
        if (telephony == null || !appContext.granted(Manifest.permission.READ_PHONE_STATE)) {
            VeyroLog.i(TAG) { "Call state unavailable: no telephony or no phone-state permission" }
            awaitClose { }
            return@callbackFlow
        }
        val executor = Executor { it.run() }
        val unregister = mutableListOf<() -> Unit>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = Api31.callback { state -> trySend(CallSignal(map(state))) }
                telephony.registerTelephonyCallback(executor, callback)
                unregister += { telephony.unregisterTelephonyCallback(callback) }
            } else {
                unregister += listenLegacy(telephony, executor) { signal -> trySend(signal) }
            }
        } catch (e: SecurityException) {
            VeyroLog.w(TAG) { "Call state listener refused: ${e.javaClass.simpleName}" }
        }

        // The caller's number. Android puts it in the PHONE_STATE broadcast, and in the old
        // PhoneStateListener, only for holders of READ_CALL_LOG, and checks that when the call
        // rings, not when Veyro starts listening. So both are registered whenever phone state can
        // be read: a call-log grant given after Veyro connected (onboarding, Settings) brings the
        // number with the next call, without a reconnect. Before, the receiver was registered only
        // if the grant already existed when the session started, and the PC showed
        // "Unknown caller".
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION") // EXTRA_INCOMING_NUMBER: still the only source of the number.
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (!number.isNullOrBlank()) {
                    trySend(CallSignal(TelephonyCallState.RINGING, number))
                } else {
                    // No number (withheld, or no call-log access). Never logs the number itself.
                    VeyroLog.i(TAG) { "Ringing without a number (call log access: ${appContext.granted(Manifest.permission.READ_CALL_LOG)})" }
                }
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        // A system broadcast only, so the receiver need not be exported.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            unregister += { runCatching { appContext.unregisterReceiver(receiver) } }
        }.onFailure { VeyroLog.w(TAG) { "Phone state broadcast not registered: ${it.javaClass.simpleName}" } }

        // API 31+: the state comes from TelephonyCallback, which never carries the number. The
        // deprecated listener still does on these versions; it is used for the number only.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                unregister += listenLegacy(telephony, executor) { signal ->
                    if (signal.state == TelephonyCallState.RINGING && signal.number != null) trySend(signal)
                }
            }.onFailure { VeyroLog.d(TAG) { "Legacy call listener refused: ${it.javaClass.simpleName}" } }
        }

        awaitClose { unregister.forEach { runCatching { it() } } }
    }

    override suspend fun contactName(number: String): String? =
        withContext(dispatchers.io) { names.nameFor(number) }

    @SuppressLint("MissingPermission") // READ_CALL_LOG checked just below.
    override suspend fun callLog(limit: Int): List<PeerMessage.CallLogEntry> = withContext(dispatchers.io) {
        if (!appContext.granted(Manifest.permission.READ_CALL_LOG)) return@withContext emptyList()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
        val entries = ArrayList<PeerMessage.CallLogEntry>()
        try {
            appContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (entries.size < limit && c.moveToNext()) {
                    val type = when (c.getInt(5)) {
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "in"
                        CallLog.Calls.OUTGOING_TYPE -> "out"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                        else -> continue // Voicemail and anything newer: not a call the PC can show.
                    }
                    val number = c.getString(1).orEmpty()
                    entries += PeerMessage.CallLogEntry(
                        id = c.getLong(0),
                        number = number,
                        name = c.getString(2)?.takeIf { it.isNotBlank() } ?: names.nameFor(number),
                        ts = c.getLong(3),
                        durationSec = c.getLong(4),
                        type = type,
                    )
                }
            }
        } catch (e: SecurityException) {
            return@withContext emptyList()
        } catch (e: IllegalArgumentException) {
            return@withContext emptyList()
        }
        entries
    }

    /** `PhoneStateListener`: the call state on API 29-30, the caller's number on 31+. Returns the unregister action. */
    @Suppress("DEPRECATION")
    private fun listenLegacy(
        telephony: TelephonyManager,
        executor: Executor,
        onSignal: (CallSignal) -> Unit,
    ): () -> Unit {
        val listener = object : PhoneStateListener(executor) {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onSignal(CallSignal(map(state), phoneNumber?.takeIf { it.isNotBlank() }))
            }
        }
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        return { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    private fun map(state: Int): TelephonyCallState = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> TelephonyCallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> TelephonyCallState.OFFHOOK
        else -> TelephonyCallState.IDLE
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun callback(onState: (Int) -> Unit): TelephonyCallback =
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onState(state)
            }
    }
}

/**
 * [CallController] over `TelecomManager` (ADR-027). Audio always stays on the phone.
 *
 * `endCall` and `acceptRingingCall` are deprecated since API 29 but still the only way for a
 * non-dialer app; they need `ANSWER_PHONE_CALLS`. `silenceRinger` needs the default-dialer role
 * or `MODIFY_PHONE_STATE`; when refused, the ring stream is muted instead and unmuted once the
 * call ends.
 */
public class AndroidCallController(
    context: Context,
    private val dispatchers: AppDispatchers,
) : CallController {

    private companion object {
        const val TAG = "CallController"
    }

    private val appContext: Context = context.applicationContext
    private val telecom: TelecomManager? = appContext.getSystemService(TelecomManager::class.java)
    private val audio: AudioManager? = appContext.getSystemService(AudioManager::class.java)

    @Volatile private var ringMutedByUs = false

    @SuppressLint("MissingPermission") // ANSWER_PHONE_CALLS checked just below.
    override suspend fun answer() {
        if (!appContext.granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            VeyroLog.i(TAG) { "answer ignored: no ANSWER_PHONE_CALLS" }
            return
        }
        withContext(dispatchers.main) {
            try {
                @Suppress("DEPRECATION")
                telecom?.acceptRingingCall()
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "answer refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    @SuppressLint("MissingPermission") // ANSWER_PHONE_CALLS checked just below.
    override suspend fun end() {
        if (!appContext.granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            VeyroLog.i(TAG) { "end ignored: no ANSWER_PHONE_CALLS" }
            return
        }
        withContext(dispatchers.main) {
            try {
                @Suppress("DEPRECATION")
                telecom?.endCall()
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "end refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    @SuppressLint("MissingPermission") // Guarded by the SecurityException fallback.
    override suspend fun silence() {
        withContext(dispatchers.main) {
            try {
                telecom?.silenceRinger()
                return@withContext
            } catch (e: SecurityException) {
                // Not the default dialer: fall back to muting the ring stream.
            }
            try {
                audio?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                ringMutedByUs = true
            } catch (e: SecurityException) {
                // DND policy can refuse ring-stream changes.
                VeyroLog.w(TAG) { "silence refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    @SuppressLint("MissingPermission") // CALL_PHONE checked just below.
    override suspend fun dial(number: String) {
        if (!appContext.granted(Manifest.permission.CALL_PHONE)) {
            VeyroLog.i(TAG) { "dial ignored: no CALL_PHONE" }
            return
        }
        withContext(dispatchers.main) {
            try {
                telecom?.placeCall(Uri.fromParts("tel", number, null), Bundle())
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "dial refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    override fun restoreAfterCall() {
        if (!ringMutedByUs) return
        ringMutedByUs = false
        try {
            audio?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: SecurityException) {
            VeyroLog.w(TAG) { "ring unmute refused: ${e.javaClass.simpleName}" }
        }
    }
}
