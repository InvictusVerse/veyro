package com.veyro.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.veyro.R
import com.veyro.VeyroApplication

/**
 * The 4x1 home-screen widget (ADR-062): the selected device's name and connection state, with
 * Send files, Send clipboard and, for a PC, Lock PC. No `updatePeriodMillis`: the app pushes a new
 * [RemoteViews] whenever its connection flows change ([com.veyro.di.VeyroContainer.refreshWidgets]),
 * and Android asks through [onUpdate] when a widget is added or after a reboot.
 *
 * Colours come from `res/values*` widget colours: Material 3 Veyro colours with light and dark
 * variants, and the system's dynamic palette on Android 12+.
 */
public class VeyroWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        (context.applicationContext as VeyroApplication).container.refreshWidgets { pending.finish() }
    }

    override fun onEnabled(context: Context) {
        (context.applicationContext as VeyroApplication).container.refreshWidgets()
    }

    public companion object {
        private const val REQUEST_OPEN = 2001
        private const val REQUEST_SEND = 2002
        private const val REQUEST_CLIP = 2003
        private const val REQUEST_LOCK = 2004

        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 97

        /** Whether any Veyro widget is placed; nothing is built for widgets otherwise. */
        public fun hasWidgets(context: Context): Boolean = runCatching {
            AppWidgetManager.getInstance(context)
                ?.getAppWidgetIds(ComponentName(context, VeyroWidgetProvider::class.java))
                ?.isNotEmpty() == true
        }.getOrDefault(false)

        /** Draws [spec] on every placed widget. */
        public fun render(context: Context, spec: WidgetSpec) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, VeyroWidgetProvider::class.java)) }
                .getOrNull()
            if (ids == null || ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_veyro).apply {
                setTextViewText(R.id.widget_title, spec.title)
                setTextViewText(R.id.widget_status, spec.status)
                setTextColor(
                    R.id.widget_status,
                    context.getColor(if (spec.connected) R.color.veyro_widget_connected else R.color.veyro_widget_on_surface_variant),
                )
                setImageViewResource(
                    R.id.widget_icon,
                    if (spec.isPc) com.veyro.ui.R.drawable.ic_veyro_desktop else com.veyro.ui.R.drawable.ic_veyro_smartphone,
                )
                setOnClickPendingIntent(R.id.widget_info, activity(context, REQUEST_OPEN, QuickIntents.openApp(context)))

                val actions = if (spec.showActions) View.VISIBLE else View.GONE
                setViewVisibility(R.id.widget_send_files, actions)
                setViewVisibility(R.id.widget_send_clipboard, actions)
                setOnClickPendingIntent(
                    R.id.widget_send_files,
                    activity(context, REQUEST_SEND, QuickIntents.sendFiles(context, spec.sendTargetId)),
                )
                button(R.id.widget_send_clipboard, spec.clipboardEnabled)
                setOnClickPendingIntent(
                    R.id.widget_send_clipboard,
                    if (spec.clipboardEnabled) activity(context, REQUEST_CLIP, QuickIntents.sendClipboard(context)) else null,
                )

                setViewVisibility(R.id.widget_lock, if (spec.showLock) View.VISIBLE else View.GONE)
                button(R.id.widget_lock, spec.lockEnabled)
                val lockId = spec.lockTargetId
                setOnClickPendingIntent(
                    R.id.widget_lock,
                    if (spec.lockEnabled && lockId != null) lockIntent(context, lockId) else null,
                )
            }
            runCatching { manager.updateAppWidget(ids, views) }
        }

        private fun RemoteViews.button(id: Int, enabled: Boolean) {
            setBoolean(id, "setEnabled", enabled)
            setInt(id, "setImageAlpha", if (enabled) ALPHA_ENABLED else ALPHA_DISABLED)
        }

        private fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun lockIntent(context: Context, deviceId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_LOCK,
                Intent(context, QuickActionReceiver::class.java)
                    .setAction(QuickActionReceiver.ACTION_LOCK_PC)
                    .putExtra(QuickActionReceiver.EXTRA_DEVICE_ID, deviceId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/**
 * The widget's Lock PC (ADR-062). Not exported: only Veyro's own PendingIntent reaches it, and the
 * device id is checked again by `lockPc`, which acts only on a live session that supports it.
 */
public class QuickActionReceiver : BroadcastReceiver() {

    public companion object {
        public const val ACTION_LOCK_PC: String = "com.veyro.action.QUICK_LOCK_PC"
        public const val EXTRA_DEVICE_ID: String = "com.veyro.extra.DEVICE_ID"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOCK_PC) return
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        val app = context.applicationContext as VeyroApplication
        val pending = goAsync()
        app.container.lockPcFromShortcut(deviceId, name = null) { message ->
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            pending.finish()
        }
    }
}
