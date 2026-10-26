package com.veyro.core.companion

import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.veyro.core.link.LinkMessage
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Decides which notifications are mirrored and turns them into `notif` messages (04-PROTOCOL 6.1).
 *
 * Skipped: this app's own notifications, ongoing events, foreground-service notifications,
 * local-only ones, group summaries (their children are mirrored instead), media-player
 * notifications, and anything without a title. What is left is the set a person would want to see
 * on a PC.
 */
internal class NotificationMapper(context: Context) {

    private companion object {
        const val ICON_PX = 96
        const val MAX_ICON_BYTES = 16 * 1024
        const val APP_CACHE_SIZE = 64

        const val SKIPPED_FLAGS = Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_FOREGROUND_SERVICE or
            Notification.FLAG_LOCAL_ONLY or
            Notification.FLAG_GROUP_SUMMARY

        // Keys of the bundles inside EXTRA_MESSAGES, as written by Notification.MessagingStyle.
        const val MESSAGE_KEY_TEXT = "text"
        const val MESSAGE_KEY_SENDER = "sender"
        const val MESSAGE_KEY_SENDER_PERSON = "sender_person"
    }

    private data class AppInfo(val label: String, val iconPng: String?)

    private val packageManager: PackageManager = context.packageManager
    private val ownPackage: String = context.packageName

    private val mediaTemplates = setOf(
        Notification.MediaStyle::class.java.name,
        Notification.DecoratedMediaCustomViewStyle::class.java.name,
    )

    // Label and icon per package; rendering an icon on every post would be wasted work.
    private val appCache = object : LinkedHashMap<String, AppInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AppInfo>?): Boolean =
            size > APP_CACHE_SIZE
    }

    fun isMirrorable(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == ownPackage) return false
        val notification = sbn.notification ?: return false
        if (notification.flags and SKIPPED_FLAGS != 0) return false
        val extras = notification.extras ?: return false
        if (extras.getString(Notification.EXTRA_TEMPLATE) in mediaTemplates) return false
        return !titleOf(extras).isNullOrBlank()
    }

    /** Full wire form, or null when [sbn] is not mirrorable. */
    fun toWire(sbn: StatusBarNotification, kind: String): LinkMessage.Notification? {
        if (!isMirrorable(sbn)) return null
        val notification = sbn.notification
        val extras = notification.extras
        val app = appInfo(sbn.packageName)

        val actions = mutableListOf<LinkMessage.NotificationActionInfo>()
        var canReply = false
        notification.actions?.forEachIndexed { index, action ->
            if (action.remoteInputs.isNullOrEmpty()) {
                // Original index kept: notifAction names this index in the phone's own array.
                val label = action.title?.toString()
                if (!label.isNullOrBlank()) actions += LinkMessage.NotificationActionInfo(index, label)
            } else {
                canReply = true
            }
        }

        return LinkMessage.Notification(
            key = sbn.key,
            kind = kind,
            pkg = sbn.packageName,
            app = app.label,
            title = titleOf(extras),
            // Big text first: it is the full body, where EXTRA_TEXT is often the collapsed line.
            text = extras.text(Notification.EXTRA_BIG_TEXT)
                ?: extras.text(Notification.EXTRA_TEXT)
                ?: extras.text(Notification.EXTRA_SUB_TEXT),
            messages = messagesOf(extras),
            ts = notification.`when`.takeIf { it > 0 } ?: sbn.postTime,
            group = sbn.groupKey,
            actions = actions.takeIf { it.isNotEmpty() },
            canReply = canReply,
            iconPng = app.iconPng,
        )
    }

    private fun titleOf(extras: Bundle): String? =
        extras.text(Notification.EXTRA_TITLE) ?: extras.text(Notification.EXTRA_TITLE_BIG)

    private fun Bundle.text(key: String): String? =
        getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }

    @Suppress("DEPRECATION") // The typed overloads are API 33+; minSdk is 29.
    private fun messagesOf(extras: Bundle): List<LinkMessage.NotificationLine>? {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val lines = bundles.mapNotNull { parcelable ->
            val bundle = parcelable as? Bundle ?: return@mapNotNull null
            val text = bundle.getCharSequence(MESSAGE_KEY_TEXT)?.toString() ?: return@mapNotNull null
            val sender = bundle.getCharSequence(MESSAGE_KEY_SENDER)?.toString()
                ?: bundle.getParcelable<Person>(MESSAGE_KEY_SENDER_PERSON)?.name?.toString()
            LinkMessage.NotificationLine(sender = sender, text = text)
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    private fun appInfo(pkg: String): AppInfo = synchronized(appCache) {
        appCache.getOrPut(pkg) { AppInfo(label = labelOf(pkg), iconPng = iconOf(pkg)) }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    /** App icon as base64 PNG, at most [ICON_PX] square and [MAX_ICON_BYTES], else null. */
    private fun iconOf(pkg: String): String? = try {
        val drawable = packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
            .takeIf { it.size <= MAX_ICON_BYTES }
            ?.let { Base64.getEncoder().encodeToString(it) }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        // A broken third-party drawable must not stop the notification itself being mirrored.
        null
    }
}
