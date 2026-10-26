package com.veyro.core.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Android implementation of [WifiDirectManager] backed by [WifiP2pManager].
 *
 * Implements a proper asynchronous P2P lifecycle state machine:
 *
 * ```
 * IDLE -> CLEANING_IF_NEEDED -> GROUP_REQUESTED -> GROUP_INFO_READY
 *      -> WAITING_FOR_PEER -> TCP_READY -> WIFI_DIRECT_READY
 * ```
 *
 * Key design decisions:
 * - [cleanStaleGroup] retries BUSY with bounded backoff rather than swallowing it.
 * - [prepareReceiverGroup] polls for `groupFormed=true` after `createGroup` succeeds,
 *   because the ActionListener callback only means the request was accepted, not that the
 *   group is fully established.
 * - Group credentials (SSID/passphrase) are extracted only after `groupFormed=true`.
 * - An epoch counter prevents stale callbacks from corrupting newer transfer attempts.
 */
public class AndroidWifiDirectManager(
    private val context: Context,
    private val dispatchers: AppDispatchers,
) : WifiDirectManager {

    private companion object {
        const val TAG = "Veyro.WifiDirect"

        // Total time budget for receiver group creation including polling
        const val GROUP_FORMATION_TIMEOUT_MS = 15_000L

        // Total time budget for sender connect + group join
        const val CONNECT_TIMEOUT_MS = 15_000L

        // Polling interval when waiting for groupFormed=true
        const val POLL_INTERVAL_MS = 400L

        // Max attempts to poll for group formation after createGroup onSuccess
        const val MAX_GROUP_POLL_ATTEMPTS = 20 // 20 * 400ms = 8s

        // Stale group cleanup
        const val CLEANUP_RETRY_MAX = 3
        const val CLEANUP_RETRY_DELAY_MS = 600L
    }

    private val p2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private val channel: WifiP2pManager.Channel? by lazy {
        p2pManager?.initialize(context, context.mainLooper, null)
    }

    private val groupCreatedByUs = AtomicBoolean(false)

    /** Monotonic epoch counter to detect stale callbacks from old transfer attempts. */
    private val epoch = AtomicInteger(0)

    override val isSupported: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && p2pManager != null

    override val isEnabled: Boolean
        get() = isSupported && checkAndLogPermissions()

    private fun checkAndLogPermissions(): Boolean {
        val isTiramisu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val nearbyGranted = if (isTiramisu) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        val hasPerms = if (isTiramisu) nearbyGranted else locationGranted

        if (!hasPerms) {
            val denied = if (isTiramisu) {
                if (!nearbyGranted) listOf("NEARBY_WIFI_DEVICES") else emptyList()
            } else {
                if (!locationGranted) listOf("ACCESS_FINE_LOCATION") else emptyList()
            }
            VeyroLog.w(TAG) {
                "[p2p:perms] Denied required permissions: $denied (SDK=${Build.VERSION.SDK_INT}, isSupported=$isSupported)"
            }
        } else {
            VeyroLog.d(TAG) {
                "[p2p:perms] All required Wi-Fi Direct permissions granted (SDK=${Build.VERSION.SDK_INT})"
            }
        }
        return hasPerms
    }

    public fun p2pFailureReason(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR (0) - System failure or internal driver error"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (1) - P2P is unsupported or Wi-Fi radio is toggled off"
        WifiP2pManager.BUSY -> "BUSY (2) - Framework is busy with another P2P operation or group"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS (3) - No service discovery requests posted"
        else -> "UNKNOWN_CODE ($reason)"
    }

    override suspend fun prepareP2pLink(isInitiator: Boolean): WifiDirectLinkInfo? =
        prepareP2pLink(isInitiator, null, null, null)

    override suspend fun prepareP2pLink(
        isInitiator: Boolean,
        targetSsid: String?,
        targetPassphrase: String?,
        targetPeerMac: String?,
    ): WifiDirectLinkInfo? = withContext(dispatchers.io) {
        val currentEpoch = epoch.incrementAndGet()

        if (!isSupported) {
            VeyroLog.w(TAG) { "[p2p:fallback] Hardware or OS does not support Wi-Fi Direct (FEATURE_WIFI_DIRECT=false or WifiP2pManager missing)" }
            return@withContext null
        }
        if (!checkAndLogPermissions()) {
            VeyroLog.w(TAG) { "[p2p:fallback] Missing runtime permissions for Wi-Fi Direct; falling back to LAN TCP" }
            return@withContext null
        }
        val mgr = p2pManager ?: return@withContext null
        val ch = channel ?: return@withContext null

        VeyroLog.i(TAG) {
            "[p2p:prepare] epoch=$currentEpoch isInitiator=$isInitiator targetSsid=${targetSsid?.let { "'$it'" } ?: "null"} targetPeerMac=${targetPeerMac ?: "null"}"
        }

        // Phase: CLEANING_IF_NEEDED
        // Clean up any stale or lingering group before initiating a fresh link.
        // This uses bounded retries for BUSY instead of swallowing it.
        val cleanResult = cleanStaleGroup(mgr, ch)
        if (!cleanResult) {
            VeyroLog.w(TAG) {
                "[p2p:fallback] epoch=$currentEpoch Stale group cleanup failed after retries; attempting group creation anyway"
            }
        }

        // Epoch check: if another prepareP2pLink was called while we cleaned, abort
        if (epoch.get() != currentEpoch) {
            VeyroLog.w(TAG) { "[p2p:stale] epoch=$currentEpoch superseded by epoch=${epoch.get()}, aborting" }
            return@withContext null
        }

        val linkInfo: WifiDirectLinkInfo? = if (isInitiator) {
            // SENDER / CLIENT: Join the receiver's P2P group
            prepareSenderLink(mgr, ch, currentEpoch, targetSsid, targetPassphrase, targetPeerMac)
        } else {
            // RECEIVER / SERVER: Create Autonomous Group Owner
            prepareReceiverGroup(mgr, ch, currentEpoch)
        }

        if (linkInfo == null) {
            VeyroLog.w(TAG) {
                "[p2p:fallback] epoch=$currentEpoch Wi-Fi Direct setup could not establish a valid P2P connection; falling back to LAN TCP"
            }
            return@withContext null
        }

        VeyroLog.i(TAG) {
            "[p2p:endpoints] epoch=$currentEpoch P2P ready! Role=${if (linkInfo.isGroupOwner) "Group Owner (GO)" else "P2P Client"} | GroupOwnerAddress=${linkInfo.groupOwnerAddress} | LocalAddress=${linkInfo.localAddress} | Network=${linkInfo.networkName} | Interface=${linkInfo.interfaceName}"
        }

        linkInfo
    }

    /**
     * Receiver creates an autonomous Group Owner and waits for the group to actually form.
     *
     * The critical fix: `createGroup()` ActionListener.onSuccess only means the request was
     * accepted by the framework. The actual group formation happens asynchronously and is
     * signaled by `requestConnectionInfo()` returning `groupFormed=true`.
     *
     * This method polls with bounded retries until group formation is confirmed.
     */
    private suspend fun prepareReceiverGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        currentEpoch: Int,
    ): WifiDirectLinkInfo? = withTimeoutOrNull(GROUP_FORMATION_TIMEOUT_MS) {
        VeyroLog.i(TAG) { "[p2p:create-group] epoch=$currentEpoch Receiver creating autonomous Wi-Fi Direct group..." }

        // Phase: GROUP_REQUESTED
        val createSuccess = requestCreateGroup(mgr, ch)
        if (!createSuccess) {
            VeyroLog.w(TAG) { "[p2p:create-group] epoch=$currentEpoch createGroup request was rejected by the framework" }
            return@withTimeoutOrNull null
        }

        groupCreatedByUs.set(true)
        VeyroLog.i(TAG) { "[p2p:create-group] epoch=$currentEpoch createGroup request accepted, polling for group formation..." }

        // Phase: Polling for GROUP_INFO_READY
        // The group is forming asynchronously. Poll requestConnectionInfo until groupFormed=true.
        var connectionInfo: WifiP2pInfo? = null
        for (attempt in 1..MAX_GROUP_POLL_ATTEMPTS) {
            if (epoch.get() != currentEpoch) {
                VeyroLog.w(TAG) { "[p2p:stale] epoch=$currentEpoch superseded during group poll at attempt $attempt" }
                return@withTimeoutOrNull null
            }

            delay(POLL_INTERVAL_MS)
            val info = queryConnectionInfo(mgr, ch)
            VeyroLog.d(TAG) {
                "[p2p:group-poll] epoch=$currentEpoch attempt=$attempt groupFormed=${info?.groupFormed}, isGroupOwner=${info?.isGroupOwner}, goAddress=${info?.groupOwnerAddress?.hostAddress}"
            }

            if (info != null && info.groupFormed) {
                connectionInfo = info
                VeyroLog.i(TAG) {
                    "[p2p:group-formed] epoch=$currentEpoch Group formed after $attempt poll(s)! isGroupOwner=${info.isGroupOwner}, goAddress=${info.groupOwnerAddress?.hostAddress}"
                }
                break
            }
        }

        if (connectionInfo == null || !connectionInfo.groupFormed) {
            VeyroLog.w(TAG) {
                "[p2p:create-group] epoch=$currentEpoch Group formation timed out after $MAX_GROUP_POLL_ATTEMPTS polls (${MAX_GROUP_POLL_ATTEMPTS * POLL_INTERVAL_MS}ms)"
            }
            // Clean up the half-formed group
            runCatching { removeGroupOnce(mgr, ch) }
            groupCreatedByUs.set(false)
            return@withTimeoutOrNull null
        }

        // Phase: GROUP_INFO_READY - extract credentials
        val group = queryGroupInfo(mgr, ch)
        val networkName = group?.networkName
        val passphrase = group?.passphrase
        val intfName = group?.`interface`

        if (networkName.isNullOrBlank()) {
            VeyroLog.w(TAG) {
                "[p2p:create-group] epoch=$currentEpoch Group formed but networkName is null/blank — cannot bootstrap peer"
            }
            runCatching { removeGroupOnce(mgr, ch) }
            groupCreatedByUs.set(false)
            return@withTimeoutOrNull null
        }

        val goAddress = connectionInfo.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
        val localAddress = if (connectionInfo.isGroupOwner) {
            goAddress
        } else {
            resolveLocalP2pAddress() ?: goAddress
        }

        VeyroLog.i(TAG) {
            "[p2p:endpoints] epoch=$currentEpoch Receiver Group Info: networkName='$networkName', passphrase=${if (passphrase != null) "[REDACTED]" else "null"}, interface='$intfName', goAddress='$goAddress', localAddress='$localAddress'"
        }

        WifiDirectLinkInfo(
            groupOwnerAddress = goAddress,
            localAddress = localAddress,
            isGroupOwner = connectionInfo.isGroupOwner,
            networkName = networkName,
            passphrase = passphrase,
            interfaceName = intfName,
        )
    }

    /**
     * Sender joins the receiver's P2P group using bootstrap credentials.
     *
     * Strategy A (API 29+): Use WifiP2pConfig.Builder with SSID + passphrase
     * Strategy B (fallback): Peer discovery + MAC-based connect
     */
    private suspend fun prepareSenderLink(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        currentEpoch: Int,
        targetSsid: String?,
        targetPassphrase: String?,
        targetPeerMac: String?,
    ): WifiDirectLinkInfo? = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        // Strategy A: Connect directly using networkName + passphrase on API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !targetSsid.isNullOrBlank() && !targetPassphrase.isNullOrBlank()) {
            VeyroLog.i(TAG) {
                "[p2p:peer-select] epoch=$currentEpoch Sender joining receiver P2P group via WifiP2pConfig.Builder (networkName='$targetSsid')..."
            }

            val config = WifiP2pConfig.Builder()
                .setNetworkName(targetSsid)
                .setPassphrase(targetPassphrase)
                .build()

            val connectSuccess = suspendCancellableCoroutine<Boolean> { cont ->
                mgr.connect(
                    ch,
                    config,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            VeyroLog.i(TAG) { "[p2p:connect-status] epoch=$currentEpoch P2P connect request accepted by Android framework" }
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onFailure(reason: Int) {
                            VeyroLog.w(TAG) { "[p2p:connect-status] epoch=$currentEpoch P2P connect request failed: ${p2pFailureReason(reason)}" }
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
            }

            if (connectSuccess) {
                // Poll connection info until groupFormed is true and IP is assigned
                for (attempt in 1..MAX_GROUP_POLL_ATTEMPTS) {
                    if (epoch.get() != currentEpoch) {
                        VeyroLog.w(TAG) { "[p2p:stale] epoch=$currentEpoch superseded during sender poll at attempt $attempt" }
                        return@withTimeoutOrNull null
                    }
                    delay(POLL_INTERVAL_MS)
                    val connInfo = queryConnectionInfo(mgr, ch)
                    VeyroLog.d(TAG) {
                        "[p2p:sender-poll] epoch=$currentEpoch attempt=$attempt groupFormed=${connInfo?.groupFormed}, isGroupOwner=${connInfo?.isGroupOwner}, goAddress=${connInfo?.groupOwnerAddress?.hostAddress}"
                    }
                    if (connInfo != null && connInfo.groupFormed) {
                        val goAddress = connInfo.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                        val localAddress = resolveLocalP2pAddress() ?: goAddress
                        VeyroLog.i(TAG) {
                            "[p2p:role] epoch=$currentEpoch Sender connected as P2P Client after $attempt poll(s)! isGroupOwner=${connInfo.isGroupOwner}, groupOwner=$goAddress, local=$localAddress"
                        }
                        return@withTimeoutOrNull WifiDirectLinkInfo(
                            groupOwnerAddress = goAddress,
                            localAddress = localAddress,
                            isGroupOwner = connInfo.isGroupOwner,
                            networkName = targetSsid,
                        )
                    }
                }
                VeyroLog.w(TAG) { "[p2p:connect-status] epoch=$currentEpoch Sender group join timed out after $MAX_GROUP_POLL_ATTEMPTS polls" }
            }
        }

        // Strategy B: Fall back to Peer Discovery and connection by MAC address / peer list
        VeyroLog.i(TAG) { "[p2p:discovery-start] epoch=$currentEpoch Starting peer discovery via discoverPeers()..." }

        val discoveryStarted = suspendCancellableCoroutine<Boolean> { cont ->
            mgr.discoverPeers(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        VeyroLog.i(TAG) { "[p2p:discovery-start] epoch=$currentEpoch Peer discovery started successfully" }
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onFailure(reason: Int) {
                        VeyroLog.w(TAG) { "[p2p:discovery-start] epoch=$currentEpoch Peer discovery failed: ${p2pFailureReason(reason)}" }
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }

        if (!discoveryStarted) return@withTimeoutOrNull null

        // Wait briefly for peers to populate
        delay(1500)
        val peers = queryPeers(mgr, ch)
        val peerList = peers?.deviceList?.toList().orEmpty()
        VeyroLog.i(TAG) {
            "[p2p:discovery-result] epoch=$currentEpoch Discovered ${peerList.size} peer(s): [${peerList.joinToString { "${it.deviceName} (${it.deviceAddress})" }}]"
        }

        val targetDevice = if (!targetPeerMac.isNullOrBlank()) {
            peerList.firstOrNull { it.deviceAddress.equals(targetPeerMac, ignoreCase = true) }
        } else {
            peerList.firstOrNull()
        }

        if (targetDevice != null) {
            VeyroLog.i(TAG) {
                "[p2p:peer-select] epoch=$currentEpoch Selected peer ${targetDevice.deviceName} (${targetDevice.deviceAddress}), requesting connection..."
            }
            val config = WifiP2pConfig().apply {
                deviceAddress = targetDevice.deviceAddress
            }
            val connectSuccess = suspendCancellableCoroutine<Boolean> { cont ->
                mgr.connect(
                    ch,
                    config,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            VeyroLog.i(TAG) { "[p2p:connect-status] epoch=$currentEpoch P2P connect request to ${targetDevice.deviceAddress} accepted" }
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onFailure(reason: Int) {
                            VeyroLog.w(TAG) { "[p2p:connect-status] epoch=$currentEpoch P2P connect request to ${targetDevice.deviceAddress} failed: ${p2pFailureReason(reason)}" }
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
            }

            if (connectSuccess) {
                for (attempt in 1..MAX_GROUP_POLL_ATTEMPTS) {
                    delay(POLL_INTERVAL_MS)
                    val connInfo = queryConnectionInfo(mgr, ch)
                    if (connInfo != null && connInfo.groupFormed) {
                        val goAddress = connInfo.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                        val localAddress = resolveLocalP2pAddress() ?: goAddress
                        VeyroLog.i(TAG) {
                            "[p2p:role] epoch=$currentEpoch Sender connected via MAC after $attempt poll(s)! isGroupOwner=${connInfo.isGroupOwner}, groupOwner=$goAddress, local=$localAddress"
                        }
                        return@withTimeoutOrNull WifiDirectLinkInfo(
                            groupOwnerAddress = goAddress,
                            localAddress = localAddress,
                            isGroupOwner = connInfo.isGroupOwner,
                        )
                    }
                }
            }
        }

        // If direct join or peer connect did not complete, check if group already exists
        val existing = queryConnectionInfo(mgr, ch)
        if (existing != null && existing.groupFormed) {
            val goAddress = existing.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            val localAddress = resolveLocalP2pAddress() ?: goAddress
            VeyroLog.i(TAG) { "[p2p:role] epoch=$currentEpoch Existing P2P connection found: isGroupOwner=${existing.isGroupOwner}, go=$goAddress, local=$localAddress" }
            return@withTimeoutOrNull WifiDirectLinkInfo(
                groupOwnerAddress = goAddress,
                localAddress = localAddress,
                isGroupOwner = existing.isGroupOwner,
            )
        }

        null
    }

    /**
     * Cleans up any stale P2P group with bounded retries for BUSY.
     *
     * Returns true if cleanup succeeded or no group existed; false if cleanup failed
     * after all retries (caller should proceed cautiously).
     */
    private suspend fun cleanStaleGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): Boolean {
        for (attempt in 1..CLEANUP_RETRY_MAX) {
            val result = removeGroupOnce(mgr, ch)
            when (result) {
                RemoveResult.SUCCESS -> {
                    VeyroLog.d(TAG) { "[p2p:clean] Previous P2P group cleaned (attempt $attempt)" }
                    return true
                }
                RemoveResult.NO_GROUP -> {
                    VeyroLog.d(TAG) { "[p2p:clean] No active group to clean (attempt $attempt)" }
                    return true
                }
                RemoveResult.BUSY -> {
                    if (attempt < CLEANUP_RETRY_MAX) {
                        VeyroLog.w(TAG) {
                            "[p2p:clean] removeGroup returned BUSY (attempt $attempt/$CLEANUP_RETRY_MAX), retrying in ${CLEANUP_RETRY_DELAY_MS}ms..."
                        }
                        delay(CLEANUP_RETRY_DELAY_MS)
                    } else {
                        VeyroLog.w(TAG) {
                            "[p2p:clean] removeGroup still BUSY after $CLEANUP_RETRY_MAX attempts"
                        }
                    }
                }
                RemoveResult.FAILED -> {
                    VeyroLog.w(TAG) { "[p2p:clean] removeGroup failed (attempt $attempt)" }
                    return false
                }
            }
        }
        return false
    }

    private enum class RemoveResult { SUCCESS, NO_GROUP, BUSY, FAILED }

    private suspend fun removeGroupOnce(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): RemoveResult =
        suspendCancellableCoroutine { cont ->
            mgr.removeGroup(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(RemoveResult.SUCCESS)
                    }

                    override fun onFailure(reason: Int) {
                        val result = when (reason) {
                            WifiP2pManager.BUSY -> RemoveResult.BUSY
                            // ERROR with no active group is typical — not a real failure
                            WifiP2pManager.ERROR -> RemoveResult.NO_GROUP
                            else -> RemoveResult.FAILED
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                },
            )
        }

    /**
     * Issues createGroup and returns whether the request was accepted (not whether the group is formed).
     */
    private suspend fun requestCreateGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): Boolean =
        suspendCancellableCoroutine { cont ->
            mgr.createGroup(
                ch,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        VeyroLog.i(TAG) { "[p2p:create-group] createGroup request accepted by framework" }
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onFailure(reason: Int) {
                        VeyroLog.w(TAG) { "[p2p:create-group] createGroup request failed: ${p2pFailureReason(reason)}" }
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }

    private suspend fun queryConnectionInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pInfo? =
        suspendCancellableCoroutine { cont ->
            mgr.requestConnectionInfo(ch) { info ->
                if (cont.isActive) cont.resume(info)
            }
        }

    private suspend fun queryGroupInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { cont ->
            mgr.requestGroupInfo(ch) { group ->
                if (cont.isActive) cont.resume(group)
            }
        }

    private suspend fun queryPeers(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pDeviceList? =
        suspendCancellableCoroutine { cont ->
            mgr.requestPeers(ch) { peers ->
                if (cont.isActive) cont.resume(peers)
            }
        }

    override suspend fun closeP2pLink(): Unit = withContext(dispatchers.io) {
        val mgr = p2pManager ?: return@withContext
        val ch = channel ?: return@withContext
        if (groupCreatedByUs.compareAndSet(true, false)) {
            VeyroLog.i(TAG) { "[p2p:clean] Tearing down Wi-Fi Direct link..." }
            val result = removeGroupOnce(mgr, ch)
            VeyroLog.i(TAG) { "[p2p:clean] Group teardown result: $result" }
        }
    }

    private fun resolveLocalP2pAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.contains("p2p") || name.startsWith("p2p") || name.contains("wlan1")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            VeyroLog.d(TAG) { "Error resolving local P2P interface address: ${t.message}" }
        }
        return null
    }
}
