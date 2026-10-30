package com.veyro.ui.home

import com.veyro.core.link.PeerMessage
import com.veyro.core.link.folderSummary
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import com.veyro.core.model.DeviceId
import com.veyro.core.model.NetworkDiagnosis
import com.veyro.core.model.NetworkTransport
import com.veyro.core.model.PeerDevice
import com.veyro.core.model.Platform
import com.veyro.core.model.Transfer
import com.veyro.core.model.TransferDirection
import com.veyro.core.model.TransferState
import com.veyro.core.model.TrustState
import com.veyro.core.model.TrustedPeer
import com.veyro.core.model.folderName
import com.veyro.ui.format.FileKind
import com.veyro.ui.format.fileKindOf

/** Where a paired device stands right now, for the device cards (Home and Devices). */
public enum class ConnectionStatus {
    /** The live session is with this device. */
    CONNECTED,

    /** A connect attempt started from the card is running. */
    CONNECTING,

    /** Discovery currently sees it, so Connect can be tried. */
    NEARBY,

    /** Paired, but not seen on the network. */
    OFFLINE,
}

/**
 * One paired device as the cards show it: name, connection state and, for the connected PC, what
 * its own `devStatus` reports (battery ADR-026, Wi-Fi ADR-038).
 */
public data class PairedDeviceUi(
    val deviceId: DeviceId,
    val name: String,
    val platform: Platform,
    val status: ConnectionStatus,
    val battery: Int? = null,
    val charging: Boolean? = null,
    /** Wi-Fi bars 0..4, -1 when the PC is not on Wi-Fi; null when unknown or not connected. */
    val wifi: Int? = null,
    /** The last Connect from the card for this device failed. */
    val connectFailed: Boolean = false,
    val lastConnectedEpochMillis: Long = 0L,
    val pairedAtEpochMillis: Long = 0L,
    /**
     * The user disconnected it here (ADR-053): it stays disconnected, even when it tries to
     * connect by itself, until Connect is pressed or files are sent to it.
     */
    val disconnectedByUser: Boolean = false,
) {
    val isConnected: Boolean get() = status == ConnectionStatus.CONNECTED

    /**
     * Connect can only be tried for a device discovery currently sees, except one the user
     * disconnected: its Connect also lifts the block, so it can reconnect when it shows up.
     */
    val canToggleConnection: Boolean
        get() = status == ConnectionStatus.CONNECTED || status == ConnectionStatus.NEARBY || disconnectedByUser
}

/** The latest transfer for Home's "Recent" card. File names are user data: shown, never logged. */
public data class RecentTransferUi(
    val id: String,
    val fileName: String,
    /** Files beyond the first in the same transfer. */
    val moreCount: Int,
    val direction: TransferDirection,
    val peerName: String,
    /** Where the received file was saved; null for sent files (the bytes are on the PC). */
    val destinationUri: String?,
    val kind: FileKind,
    val finishedAtEpochMillis: Long,
    val succeeded: Boolean,
) {
    /** "Received from Office PC" / "Sent to Office PC". */
    val subtitle: String
        get() = if (direction == TransferDirection.RECEIVE) "Received from $peerName" else "Sent to $peerName"

    val canOpen: Boolean get() = destinationUri != null && succeeded
}

/**
 * Everything the hub renders.
 *
 * One immutable object rather than several separately-collected flows, so the screen can never show
 * an inconsistent combination - "no devices found" next to a stale "searching" spinner, say.
 */
public data class HomeUiState(
    val isSearching: Boolean = false,
    val devices: List<PeerDevice> = emptyList(),
    val transport: NetworkTransport = NetworkTransport.NONE,
    val hasUsableNetwork: Boolean = false,
    val isHotspotActive: Boolean = false,
    val isVpnActive: Boolean = false,
    val localAddresses: List<String> = emptyList(),
    val diagnosis: NetworkDiagnosis = NetworkDiagnosis.OK,

    /**
     * True once we have been searching long enough that "nothing found" is meaningful rather than
     * just early. Without this the empty state flashes an alarming diagnosis for the first second
     * of every visit.
     */
    val searchedLongEnough: Boolean = false,

    /** Device ids of the PCs with a live session (multiple PCs). */
    val connectedPeerIds: Set<DeviceId> = emptySet(),

    /** Devices the user disconnected here (ADR-053). */
    val userDisconnectedIds: Set<DeviceId> = emptySet(),

    /** Each connected PC's media sessions (ADR-026), by device id string. */
    val pcMediaByPeer: Map<String, PeerMessage.MediaState> = emptyMap(),

    /** Each connected PC's own status (battery, Wi-Fi), by device id string. */
    val pcStatuses: Map<String, PeerMessage.DeviceStatus> = emptyMap(),

    /** What each connected PC advertised in its hello, by device id string. */
    val peerCapabilities: Map<String, Capabilities> = emptyMap(),

    /** The last wallpaper each PC sent (JPEG), by device id string (ADR-038). */
    val pcWallpapers: Map<String, ByteArray> = emptyMap(),

    /** Paired devices from the trust store, revoked ones excluded. */
    val pairedPeers: List<TrustedPeer> = emptyList(),

    /** The PC the user picked in the switcher; the connected one (else the first) when null. */
    val selectedId: DeviceId? = null,

    /** Device a Connect from the card is running for. */
    val connectingId: DeviceId? = null,

    /** Device whose last Connect from the card failed. */
    val failedId: DeviceId? = null,

    /** Transfer history, newest first. */
    val history: List<Transfer> = emptyList(),

    /** A one-line confirmation for the snackbar ("Locking Office PC"); cleared once shown. */
    val message: String? = null,
) {
    /** The selected PC's media, which the media and volume cards show; null when it shares none. */
    val pcMedia: PeerMessage.MediaState?
        get() = selectedPc?.takeIf { it.isConnected }?.let { pcMediaByPeer[it.deviceId.value] }

    /** The PC session the media card shows: the playing one, else the first. */
    val pcMediaSession: PeerMessage.MediaSessionInfo?
        get() = pcMedia?.sessions?.let { sessions -> sessions.firstOrNull { it.playing } ?: sessions.firstOrNull() }

    /** Status line extras for [device], present only while that PC is connected. */
    public fun statusFor(device: PeerDevice): PeerMessage.DeviceStatus? =
        pcStatuses[device.deviceId.value]?.takeIf { device.deviceId in connectedPeerIds }

    val trustedDevices: List<PeerDevice>
        get() = devices.filter { it.trust == TrustState.TRUSTED }

    val otherDevices: List<PeerDevice>
        get() = devices.filterNot { it.trust == TrustState.TRUSTED }

    /** Show the diagnosis panel only when there is genuinely something wrong to report. */
    val shouldExplainEmptiness: Boolean
        get() = devices.isEmpty() && searchedLongEnough

    /** Paired devices for the cards, the connected one first. */
    val pairedDevices: List<PairedDeviceUi>
        get() = pairedPeers
            .map { peer ->
                val connected = peer.deviceId in connectedPeerIds
                val pcStatus = if (connected) pcStatuses[peer.deviceId.value] else null
                val status = when {
                    connected -> ConnectionStatus.CONNECTED
                    connectingId == peer.deviceId -> ConnectionStatus.CONNECTING
                    devices.any { it.deviceId == peer.deviceId && it.endpoints.isNotEmpty() } -> ConnectionStatus.NEARBY
                    else -> ConnectionStatus.OFFLINE
                }
                PairedDeviceUi(
                    deviceId = peer.deviceId,
                    name = peer.name,
                    platform = peer.platform,
                    status = status,
                    battery = if (connected) pcStatus?.battery else null,
                    charging = if (connected) pcStatus?.charging else null,
                    wifi = if (connected) pcStatus?.wifi else null,
                    connectFailed = failedId == peer.deviceId && !connected,
                    lastConnectedEpochMillis = peer.lastConnectedEpochMillis,
                    pairedAtEpochMillis = peer.pairedAtEpochMillis,
                    disconnectedByUser = !connected && peer.deviceId in userDisconnectedIds,
                )
            }
            .sortedBy { if (it.isConnected) 0 else 1 }

    /** Every connected PC's card, for "Send to" (multiple PCs). */
    val connectedDevices: List<PairedDeviceUi>
        get() = pairedDevices.filter { it.isConnected }

    /** The PC Home is about: the one picked in the switcher, else a connected one, else the first. */
    val selectedPc: PairedDeviceUi?
        get() {
            val all = pairedDevices
            return all.firstOrNull { it.deviceId == selectedId } ?: all.firstOrNull { it.isConnected } ?: all.firstOrNull()
        }

    /** The wallpaper to draw on the laptop, or null for the gradient fallback. */
    val selectedWallpaper: ByteArray?
        get() = selectedPc?.let { pcWallpapers[it.deviceId.value] }

    /**
     * The selected device is another phone (ADR-052): Home shows only what works between phones,
     * Send files and that phone's media, and none of the PC rows.
     */
    val selectedIsPhone: Boolean
        get() = selectedPc?.platform == Platform.ANDROID

    /** Lock PC needs the live session with that PC, and that PC must understand `pcLock`. */
    val canLockPc: Boolean
        get() = selectedPc?.let { canLock(it) } == true

    /** [canLockPc] for any paired device, for its card on Devices. */
    public fun canLock(device: PairedDeviceUi): Boolean =
        device.platform != Platform.ANDROID && device.isConnected &&
            Capability.DEVICE_STATUS in (peerCapabilities[device.deviceId.value] ?: Capabilities.NONE)

    /**
     * A connected phone that shares no media yet: Home says how to turn it on there. Null otherwise.
     * (A PC shares its media on its own; it never needs this hint.)
     */
    val mediaHintFor: PairedDeviceUi?
        get() = selectedPc?.takeIf { selectedIsPhone && it.isConnected && pcMedia == null }

    /** Send files asks "Send to" only when more than one PC is connected. */
    val sendNeedsChoice: Boolean
        get() = connectedDevices.size > 1

    /** Send files works for any paired PC: the coordinator dials it if it is not connected. */
    val canSendFiles: Boolean
        get() = selectedPc != null

    /** [canSendFolder] for the selected device, for Home's Send folder tile. */
    val canSendFolderToSelected: Boolean
        get() = selectedPc?.let { canSendFolder(it) } == true

    /**
     * Send folder (ADR-064) works like Send files, except that a connected device must say it
     * takes folders (FOLDER_TRANSFER). One not connected is tried; the send itself checks again.
     */
    public fun canSendFolder(device: PairedDeviceUi): Boolean =
        !device.isConnected || Capability.FOLDER_TRANSFER in (peerCapabilities[device.deviceId.value] ?: Capabilities.NONE)

    /** The newest finished transfer, for the Recent card. */
    val recent: RecentTransferUi?
        get() = history.firstOrNull { it.state.isTerminal }?.let { transfer ->
            val files = transfer.items.filterNot { it.isDirectory }
            val first = files.firstOrNull()?.fileName
            // A folder reads as one: "Photos folder, 120 files" (ADR-064).
            val folder = transfer.items.folderName()?.let { folderSummary(it, files.size) }
            RecentTransferUi(
                id = transfer.id,
                fileName = folder ?: first ?: if (transfer.direction == TransferDirection.RECEIVE) "Received files" else "Sent files",
                moreCount = if (folder != null) 0 else (files.size - 1).coerceAtLeast(0),
                direction = transfer.direction,
                peerName = transfer.peerDisplayName ?: "your device",
                destinationUri = transfer.destinationUri ?: files.firstOrNull()?.destinationUri,
                // ARCHIVE is drawn with the folder icon.
                kind = if (folder != null) FileKind.ARCHIVE else first?.let(::fileKindOf) ?: FileKind.OTHER,
                finishedAtEpochMillis = transfer.finishedAtEpochMillis ?: transfer.startedAtEpochMillis,
                succeeded = transfer.state == TransferState.COMPLETED,
            )
        }
}

/**
 * The line under the PC's status icons, Phone Link style: "Just now" while connected, else when
 * it was last seen. [now] is passed in so the wording is testable.
 */
public fun lastSeenLabel(device: PairedDeviceUi, now: Long): String = when (device.status) {
    ConnectionStatus.CONNECTED -> "Just now"
    ConnectionStatus.CONNECTING -> "Connecting..."
    ConnectionStatus.NEARBY, ConnectionStatus.OFFLINE -> if (device.disconnectedByUser) {
        "Disconnected. It stays off until you connect it."
    } else {
        val prefix = if (device.status == ConnectionStatus.NEARBY) "Nearby, last connected" else "Last connected"
        val at = device.lastConnectedEpochMillis
        if (at <= 0L) {
            if (device.status == ConnectionStatus.NEARBY) "Nearby, not connected yet" else "Not connected yet"
        } else {
            "$prefix ${relativeTime(at, now)}"
        }
    }
}

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
public fun relativeTime(at: Long, now: Long): String {
    val seconds = ((now - at) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        seconds < 2 * 86_400 -> "yesterday"
        else -> "${seconds / 86_400} days ago"
    }
}
