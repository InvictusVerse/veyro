package com.veyro.quick

import com.veyro.core.model.Platform
import com.veyro.core.model.TrustedPeer
import com.veyro.ui.clipboard.SendClipOutcome

/** One paired device as the Quick Settings tiles and the widget see it (ADR-062). */
public data class QuickDevice(
    val deviceId: String,
    val name: String,
    val isPc: Boolean,
    val connected: Boolean,
    /** Connected, a PC, and it advertised `pcLock` support (DEVICE_STATUS). */
    val canLock: Boolean,
    /** The user disconnected it here (ADR-053). */
    val disconnectedByUser: Boolean = false,
)

/**
 * Everything a tile or the widget shows, built from the app's connection flows. [devices] are the
 * paired devices, connected first, then most recently used.
 */
public data class QuickSnapshot(
    val devices: List<QuickDevice>,
    /** The device selected on Home (the connection layer's active one), if any. */
    val selectedId: String? = null,
    /** Android 17's local network permission is missing (ADR-061): nothing can connect. */
    val localNetworkBlocked: Boolean = false,
) {
    val connected: List<QuickDevice> get() = devices.filter { it.connected }

    /** The device the widget is about and Send files goes to: Home's choice when connected. */
    val primary: QuickDevice?
        get() = connected.firstOrNull { it.deviceId == selectedId }
            ?: connected.firstOrNull()
            ?: devices.firstOrNull { it.deviceId == selectedId }
            ?: devices.firstOrNull()

    /** The PC Lock PC locks: the primary one if it can be locked, else the first that can. */
    val lockTarget: QuickDevice?
        get() = primary?.takeIf { it.canLock } ?: connected.firstOrNull { it.canLock }

    public companion object {
        public val EMPTY: QuickSnapshot = QuickSnapshot(emptyList())

        /**
         * Builds the snapshot from the trust store and the live sessions. [lockable] is the ids
         * whose session advertised DEVICE_STATUS; a phone is never lockable (ADR-052).
         */
        public fun of(
            peers: List<TrustedPeer>,
            connectedIds: Set<String>,
            lockable: Set<String>,
            selectedId: String?,
            disconnectedByUser: Set<String> = emptySet(),
            localNetworkBlocked: Boolean = false,
        ): QuickSnapshot {
            val devices = peers
                .filterNot { it.revoked }
                .sortedWith(
                    compareByDescending<TrustedPeer> { it.deviceId.value in connectedIds }
                        .thenByDescending { maxOf(it.lastConnectedEpochMillis, it.pairedAtEpochMillis) },
                )
                .map { peer ->
                    val id = peer.deviceId.value
                    val connected = id in connectedIds
                    val isPc = peer.platform != Platform.ANDROID
                    QuickDevice(
                        deviceId = id,
                        name = peer.name,
                        isPc = isPc,
                        connected = connected,
                        canLock = isPc && connected && id in lockable,
                        disconnectedByUser = !connected && id in disconnectedByUser,
                    )
                }
            return QuickSnapshot(devices, selectedId?.takeIf { s -> devices.any { it.deviceId == s } }, localNetworkBlocked)
        }
    }
}

/** How a tile looks; maps to `Tile.STATE_*` in the service. */
public enum class TileLook { ACTIVE, INACTIVE, UNAVAILABLE }

public data class TileSpec(val label: String, val subtitle: String, val look: TileLook)

/** What tapping a tile does, decided before the service acts. */
public enum class TileClick {
    /** Nothing to do (the tile is unavailable). */
    NONE,

    /** Open Veyro, which explains the problem (local network permission missing). */
    OPEN_APP,

    /** Do the tile's own action. */
    ACT,
}

/** The widget's content for one snapshot (ADR-062). */
public data class WidgetSpec(
    val title: String,
    val status: String,
    val connected: Boolean,
    /** Draw a PC rather than a phone. */
    val isPc: Boolean,
    /** Show the Send files and Send clipboard buttons (something is paired). */
    val showActions: Boolean,
    val clipboardEnabled: Boolean,
    /** Lock PC is shown only for a PC, and works only while it can be locked. */
    val showLock: Boolean,
    val lockEnabled: Boolean,
    /** Where Send files goes; null lets the app choose (nothing paired). */
    val sendTargetId: String?,
    val lockTargetId: String?,
)

/**
 * What each Quick Settings tile and the home-screen widget shows for a [QuickSnapshot]
 * (ADR-062). Plain Kotlin, JVM-tested; the services only copy these values into `Tile` and
 * `RemoteViews`.
 */
public object QuickActions {

    private const val BLOCKED = "Allow Nearby devices"
    private const val NOT_CONNECTED = "Not connected"
    private const val NOTHING_PAIRED = "No device paired"

    public fun sendClipboardTile(s: QuickSnapshot): TileSpec {
        val connected = s.connected
        return when {
            s.localNetworkBlocked -> TileSpec("Send clipboard", BLOCKED, TileLook.INACTIVE)
            connected.isEmpty() -> TileSpec("Send clipboard", if (s.devices.isEmpty()) NOTHING_PAIRED else NOT_CONNECTED, TileLook.UNAVAILABLE)
            connected.size == 1 -> TileSpec("Send clipboard", "To ${connected.single().name}", TileLook.ACTIVE)
            else -> TileSpec("Send clipboard", "To ${connected.size} devices", TileLook.ACTIVE)
        }
    }

    /** Send files works offline too: the transfer dials the device (ADR-038). */
    public fun sendFilesTile(s: QuickSnapshot): TileSpec {
        val target = s.primary
        return when {
            s.localNetworkBlocked -> TileSpec("Send files", BLOCKED, TileLook.INACTIVE)
            target == null -> TileSpec("Send files", NOTHING_PAIRED, TileLook.UNAVAILABLE)
            target.connected -> TileSpec("Send files", "To ${target.name}", TileLook.ACTIVE)
            else -> TileSpec("Send files", "To ${target.name}", TileLook.INACTIVE)
        }
    }

    public fun lockPcTile(s: QuickSnapshot): TileSpec {
        val target = s.lockTarget
        return when {
            s.localNetworkBlocked -> TileSpec("Lock PC", BLOCKED, TileLook.INACTIVE)
            target != null -> TileSpec("Lock PC", target.name, TileLook.ACTIVE)
            s.devices.none { it.isPc } -> TileSpec("Lock PC", "No PC paired", TileLook.UNAVAILABLE)
            s.connected.any { it.isPc } -> TileSpec("Lock PC", "Not supported by this PC", TileLook.UNAVAILABLE)
            else -> TileSpec("Lock PC", "No PC connected", TileLook.UNAVAILABLE)
        }
    }

    /** Unavailable tiles are not clickable; a blocked one opens Veyro to explain. */
    public fun click(spec: TileSpec, s: QuickSnapshot): TileClick = when {
        spec.look == TileLook.UNAVAILABLE -> TileClick.NONE
        s.localNetworkBlocked -> TileClick.OPEN_APP
        else -> TileClick.ACT
    }

    public fun widget(s: QuickSnapshot): WidgetSpec {
        val device = s.primary
        if (device == null) {
            return WidgetSpec(
                title = "Veyro",
                status = "Open Veyro to pair a device",
                connected = false,
                isPc = true,
                showActions = false,
                clipboardEnabled = false,
                showLock = false,
                lockEnabled = false,
                sendTargetId = null,
                lockTargetId = null,
            )
        }
        val status = when {
            s.localNetworkBlocked -> "Allow Nearby devices in Veyro"
            device.connected -> "Connected"
            device.disconnectedByUser -> "Disconnected"
            else -> NOT_CONNECTED
        }
        return WidgetSpec(
            title = device.name,
            status = status,
            connected = device.connected,
            isPc = device.isPc,
            showActions = true,
            clipboardEnabled = !s.localNetworkBlocked && s.connected.isNotEmpty(),
            showLock = device.isPc,
            lockEnabled = !s.localNetworkBlocked && device.canLock,
            sendTargetId = device.deviceId,
            lockTargetId = device.deviceId.takeIf { device.canLock },
        )
    }

    /** The toast after the Send clipboard tile or widget button; the same wording as the Clipboard screen. */
    public fun clipboardToast(outcome: SendClipOutcome, s: QuickSnapshot): String = when (outcome) {
        SendClipOutcome.SENT -> s.connected.singleOrNull()?.let { "Clipboard sent to ${it.name}" } ?: "Clipboard sent to your devices"
        SendClipOutcome.EMPTY -> "Nothing copied on this phone"
        SendClipOutcome.TOO_LARGE -> "Too large to send"
        SendClipOutcome.NO_PC -> "No device connected"
    }

    public fun lockToast(sent: Boolean, name: String): String = if (sent) "Locking $name" else "Could not reach $name"
}
