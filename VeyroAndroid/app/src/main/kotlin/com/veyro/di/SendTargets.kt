package com.veyro.di

import com.veyro.core.model.TrustedPeer

/**
 * Who a Send files goes to (multiple PCs). Pure, so the choice is JVM-tested.
 *
 * Home asks "Send to" when more than one PC is connected and passes the chosen ids, every
 * connected id for "All connected"; with one connected PC it passes that one. The notification's
 * Send files passes its PC. An empty list means "the selected connected PC, else the most
 * recently used paired PC", the behaviour from before several PCs could be connected.
 */
internal object SendTargets {

    /** The paired, unrevoked peers to send to, in the order asked for, each at most once. */
    fun resolve(paired: List<TrustedPeer>, deviceIds: List<String>, selectedConnected: String?): List<TrustedPeer> {
        val peers = paired.filterNot { it.revoked }
        if (deviceIds.isNotEmpty()) {
            return deviceIds.distinct().mapNotNull { id -> peers.firstOrNull { it.deviceId.value == id } }
        }
        val fallback = peers.firstOrNull { it.deviceId.value == selectedConnected }
            ?: peers.maxWithOrNull(compareBy({ it.lastConnectedEpochMillis }, { it.pairedAtEpochMillis }))
        return listOfNotNull(fallback)
    }

    /**
     * Resolves the targets and sends to each one after the other, so the transfer banner (one
     * transfer at a time) shows every PC in turn. A failure for one PC does not stop the others.
     */
    suspend fun sendToEach(
        paired: List<TrustedPeer>,
        deviceIds: List<String>,
        selectedConnected: String?,
        send: suspend (TrustedPeer) -> Unit,
    ): Int {
        val targets = resolve(paired, deviceIds, selectedConnected)
        for (target in targets) send(target)
        return targets.size
    }
}
