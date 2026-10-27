package com.veyro.di

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.veyro.MainActivity
import com.veyro.R
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.TrustedPeer

/**
 * Direct Share (ADR-043): one sharing shortcut per paired PC, so "Laptop" shows in the share
 * sheet's top row next to recent chats, instead of Veyro somewhere down the app list. Android ranks
 * the row by use; [reportUsed] tells it which PC was picked. Shortcut ids carry only the device id.
 */
internal object ShareShortcuts {
    private const val TAG = "ShareShortcuts"
    private const val CATEGORY = "com.veyro.category.SEND_TO_PC"
    private const val PREFIX = "pc-"
    private const val MAX = 4

    /** Replaces the shortcuts with [peers], most recently used first. */
    fun publish(context: Context, peers: List<TrustedPeer>) {
        runCatching {
            val shortcuts = peers.take(minOf(MAX, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))).mapIndexed { rank, peer ->
                ShortcutInfoCompat.Builder(context, PREFIX + peer.deviceId.value)
                    .setShortLabel(peer.name.ifBlank { "PC" }.take(24))
                    .setLongLabel("Send to ${peer.name.ifBlank { "PC" }}".take(40))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    // Required, and what a launcher long-press opens; sharing goes to the share-target.
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                    .setCategories(setOf(CATEGORY))
                    .setLongLived(true)
                    .setRank(rank)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }.onFailure { VeyroLog.w(TAG) { "Share shortcuts not published: ${it.javaClass.simpleName}" } }
    }

    /** The PC picked in the Direct Share row, or null when Veyro itself was picked. */
    fun targetOf(intent: Intent?): String? =
        intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun reportUsed(context: Context, deviceId: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, PREFIX + deviceId) }
    }
}
