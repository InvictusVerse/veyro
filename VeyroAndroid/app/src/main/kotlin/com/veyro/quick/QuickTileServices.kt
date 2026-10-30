package com.veyro.quick

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.veyro.VeyroApplication
import com.veyro.di.VeyroContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shared half of Veyro's Quick Settings tiles (ADR-062). While the shade shows a tile, it
 * follows [VeyroContainer.quickSnapshot] (pushed from the connection flows, no polling) and copies
 * what [QuickActions] decides into the tile. Not an active tile: Android binds it only while the
 * shade is open, so it costs nothing otherwise.
 */
public abstract class QuickTileService : TileService() {

    private var listening: CoroutineScope? = null
    private var snapshot: QuickSnapshot = QuickSnapshot.EMPTY

    protected val container: VeyroContainer get() = (application as VeyroApplication).container

    /** What this tile shows for [s]. */
    protected abstract fun spec(s: QuickSnapshot): TileSpec

    /** The tile's own action, after any unlock. */
    protected abstract fun act(s: QuickSnapshot)

    /** Request codes keep each tile's PendingIntent separate. */
    protected abstract val requestCode: Int

    /** Whether the action needs the phone unlocked first (reading the clipboard, picking files). */
    protected open val needsUnlock: Boolean = true

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        val scope = CoroutineScope(SupervisorJob() + container.dispatchers.main)
        listening = scope
        scope.launch {
            // The first read builds the trust store; keep it off the main thread.
            val snapshots = withContext(container.dispatchers.default) { container.quickSnapshot }
            snapshots.flowOn(container.dispatchers.default).collect {
                snapshot = it
                render()
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        listening?.cancel()
        listening = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val s = snapshot
        when (QuickActions.click(spec(s), s)) {
            TileClick.NONE -> Unit
            TileClick.OPEN_APP -> launch(QuickIntents.openApp(this))
            TileClick.ACT -> if (needsUnlock && isLocked) unlockAndRun { act(snapshot) } else act(s)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val spec = spec(snapshot)
        tile.label = spec.label
        tile.subtitle = spec.subtitle
        tile.state = when (spec.look) {
            TileLook.ACTIVE -> Tile.STATE_ACTIVE
            TileLook.INACTIVE -> Tile.STATE_INACTIVE
            TileLook.UNAVAILABLE -> Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }

    /** Opens an activity and collapses the shade; the PendingIntent form is required on API 34+. */
    protected fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * "Send clipboard": Android 10+ lets only the app in front read the clipboard, so the tile opens
 * [SendClipboardActivity], which reads it once it has focus, sends it the way the Clipboard
 * screen's Send clipboard does, and closes.
 */
public class SendClipboardTileService : QuickTileService() {
    override val requestCode: Int = 3001
    override fun spec(s: QuickSnapshot): TileSpec = QuickActions.sendClipboardTile(s)
    override fun act(s: QuickSnapshot) {
        launch(QuickIntents.sendClipboard(this))
    }
}

/** "Send files": the same system picker Home opens, for the device Home has selected. */
public class SendFilesTileService : QuickTileService() {
    override val requestCode: Int = 3002
    override fun spec(s: QuickSnapshot): TileSpec = QuickActions.sendFilesTile(s)
    override fun act(s: QuickSnapshot) {
        launch(QuickIntents.sendFiles(this, s.primary?.deviceId))
    }
}

/**
 * "Lock PC": `pcLock` over the selected PC's live session (ADR-038). Allowed from the lock
 * screen: it can only lock the user's own PC, which is never harmful.
 */
public class LockPcTileService : QuickTileService() {
    override val requestCode: Int = 3003
    override val needsUnlock: Boolean = false
    override fun spec(s: QuickSnapshot): TileSpec = QuickActions.lockPcTile(s)
    override fun act(s: QuickSnapshot) {
        val target = s.lockTarget ?: return
        container.lockPcFromShortcut(target.deviceId, target.name) { message ->
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
