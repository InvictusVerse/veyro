package com.veyro.core.companion

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.roundToInt

/**
 * [MediaSource] and [MediaCommandExecutor] backed by `MediaSessionManager` (ADR-026).
 *
 * Reading other apps' sessions needs the notification-listener grant: the session list is asked
 * for on behalf of [VeyroNotificationListener]. Without the grant, or before the listener binds,
 * [available] is false and nothing is read. Commands re-resolve the session id against the live
 * controllers, so a stale id does nothing.
 *
 * Track metadata is user data: nothing here logs it.
 */
public class AndroidMediaSource(
    context: Context,
    private val dispatchers: AppDispatchers,
) : MediaSource, MediaCommandExecutor {

    private companion object {
        const val TAG = "MediaSource"
        const val ART_PX = 160
        const val MAX_ART_BYTES = 24 * 1024
        val JPEG_QUALITIES = intArrayOf(85, 70, 50)

        /** Sent by AudioService on any stream volume change. Not in the public SDK constants. */
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    }

    private val appContext: Context = context.applicationContext
    private val listenerComponent = ComponentName(appContext, VeyroNotificationListener::class.java)
    private val sessionManager: MediaSessionManager? =
        appContext.getSystemService(MediaSessionManager::class.java)
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val packageManager: PackageManager = appContext.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Last encoded artwork per session id, so an unchanged bitmap is not recompressed. */
    private data class ArtEntry(val bitmapKey: Int, val encoded: String?)
    private val artCache = HashMap<String, ArtEntry>()

    override val available: Flow<Boolean> =
        ListenerBridge.listener.map { it != null }.distinctUntilChanged()

    override val changes: Flow<Unit> = callbackFlow {
        val manager = sessionManager
        // Keyed by session token (which has value equality), so each session gets one callback
        // however many controller objects the system hands out for it.
        val callbacks = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

        fun track(controllers: List<MediaController>?) {
            val live = controllers.orEmpty().associateBy { it.sessionToken }
            // Drop callbacks for sessions that went away.
            val gone = callbacks.keys - live.keys
            for (key in gone) {
                callbacks.remove(key)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
            }
            for ((key, controller) in live) {
                if (key in callbacks) continue
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) { trySend(Unit) }
                    override fun onMetadataChanged(metadata: MediaMetadata?) { trySend(Unit) }
                    override fun onSessionDestroyed() { trySend(Unit) }
                }
                controller.registerCallback(callback, mainHandler)
                callbacks[key] = controller to callback
            }
            trySend(Unit)
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { track(it) }
        val volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { trySend(Unit) }
        }

        var registeredSessions = false
        if (manager != null) {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, mainHandler)
                registeredSessions = true
                track(manager.getActiveSessions(listenerComponent))
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "Media sessions unavailable: ${e.javaClass.simpleName}" }
            }
        }
        val filter = IntentFilter(ACTION_VOLUME_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(volumeReceiver, filter)
        }

        awaitClose {
            if (registeredSessions) manager?.removeOnActiveSessionsChangedListener(sessionsListener)
            for ((controller, callback) in callbacks.values) controller.unregisterCallback(callback)
            callbacks.clear()
            runCatching { appContext.unregisterReceiver(volumeReceiver) }
        }
    }
        // Registration and every callback then run on the main thread, so [callbacks] needs no lock.
        .flowOn(dispatchers.main)

    override suspend fun snapshot(): PeerMessage.MediaState = withContext(dispatchers.default) {
        val sessions = if (ListenerBridge.listener.value == null) {
            emptyList()
        } else {
            controllers().map { toInfo(it) }
        }
        synchronized(artCache) { artCache.keys.retainAll(sessions.map { it.id }.toSet()) }
        val audio = audioManager
        PeerMessage.MediaState(
            sessions = sessions,
            volume = audio?.let { volumePercent(it) },
            muted = audio?.isStreamMute(AudioManager.STREAM_MUSIC),
        )
    }

    override suspend fun transport(id: String, action: String, positionMs: Long?) {
        withContext(dispatchers.main) {
            val controller = controllers().firstOrNull { idOf(it) == id } ?: return@withContext
            val controls = controller.transportControls
            when (action) {
                PeerMessage.MediaCommand.PLAY -> controls.play()
                PeerMessage.MediaCommand.PAUSE -> controls.pause()
                PeerMessage.MediaCommand.TOGGLE ->
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                PeerMessage.MediaCommand.NEXT -> controls.skipToNext()
                PeerMessage.MediaCommand.PREV -> controls.skipToPrevious()
                PeerMessage.MediaCommand.SEEK -> positionMs?.let { controls.seekTo(it) }
                else -> Unit
            }
        }
    }

    override suspend fun setVolume(percent: Int) {
        val audio = audioManager ?: return
        withContext(dispatchers.main) {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val index = (percent.coerceIn(0, 100) * max / 100f).roundToInt()
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "Volume change refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    override suspend fun setMuted(muted: Boolean) {
        val audio = audioManager ?: return
        withContext(dispatchers.main) {
            val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            try {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            } catch (e: SecurityException) {
                VeyroLog.w(TAG) { "Mute change refused: ${e.javaClass.simpleName}" }
            }
        }
    }

    private fun controllers(): List<MediaController> = try {
        sessionManager?.getActiveSessions(listenerComponent).orEmpty()
    } catch (e: SecurityException) {
        // Access revoked between the bind and this call.
        emptyList()
    }

    private fun volumePercent(audio: AudioManager): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt().coerceIn(0, 100)
    }

    private fun toInfo(controller: MediaController): PeerMessage.MediaSessionInfo {
        val id = idOf(controller)
        val metadata = controller.metadata
        val state = controller.playbackState
        val actions = state?.actions ?: 0L
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }

        // PlaybackState times are on the elapsed-realtime clock; the wire wants epoch ms.
        val sampledAt = state?.lastPositionUpdateTime?.takeIf { it > 0 }?.let { updated ->
            System.currentTimeMillis() - (SystemClock.elapsedRealtime() - updated)
        }
        val position = state?.position?.takeIf { it >= 0 }

        return PeerMessage.MediaSessionInfo(
            id = id,
            app = labelOf(controller.packageName),
            title = metadata?.text(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata?.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata?.text(MediaMetadata.METADATA_KEY_ALBUM),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = position,
            durationMs = duration,
            ts = if (position != null) sampledAt ?: System.currentTimeMillis() else null,
            canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L && duration != null,
            art = artOf(id, metadata),
        )
    }

    /** Stable for the controller's lifetime: package plus the session token's identity. */
    private fun idOf(controller: MediaController): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${controller.packageName}|${controller.sessionToken.hashCode()}".toByteArray(Charsets.UTF_8))
        return digest.copyOf(8).joinToString("") { "%02x".format(it) }
    }

    private fun MediaMetadata.text(key: String): String? =
        getText(key)?.toString()?.takeIf { it.isNotBlank() }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun artOf(id: String, metadata: MediaMetadata?): String? {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        val key = System.identityHashCode(bitmap) * 31 + bitmap.generationId
        synchronized(artCache) { artCache[id]?.takeIf { it.bitmapKey == key }?.let { return it.encoded } }
        val encoded = encodeArt(bitmap)
        synchronized(artCache) { artCache[id] = ArtEntry(key, encoded) }
        return encoded
    }

    /** JPEG at most [ART_PX] square and [MAX_ART_BYTES], base64; null if it cannot fit. */
    private fun encodeArt(source: Bitmap): String? = try {
        val scale = minOf(1f, ART_PX.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(source, width, height, true) else source
        var result: ByteArray? = null
        for (quality in JPEG_QUALITIES) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= MAX_ART_BYTES) {
                result = out.toByteArray()
                break
            }
        }
        if (scaled !== source) scaled.recycle()
        result?.let { Base64.getEncoder().encodeToString(it) }
    } catch (_: RuntimeException) {
        // A recycled or odd-config bitmap from another app must not break the snapshot.
        null
    }
}
