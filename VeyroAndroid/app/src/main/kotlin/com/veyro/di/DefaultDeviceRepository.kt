package com.veyro.di

import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.DiscoverySubscription
import com.veyro.core.discovery.DiscoveryManager
import com.veyro.core.discovery.DiscoveryReason
import com.veyro.core.discovery.DiscoveryRequest
import com.veyro.core.model.PeerDevice
import com.veyro.core.protocol.Vlp
import kotlinx.coroutines.flow.Flow

/**
 * Adapts [DiscoveryManager] to the simpler [DeviceRepository] the UI collects.
 *
 * Lives in `:app` rather than `:core:data` because it is wiring, not data access: it decides how long
 * a window lives and whether unpaired peers are listed. Keeping that policy in the composition root
 * means no screen can accidentally open an unbounded pairing window.
 *
 * ## Unpaired peers are always listed, and that is a deliberate correction
 *
 * This originally passed `includeUnpaired = forPairing`, so the hub hid every device that was not
 * already in the trust store. The result was a dead end: on first run nothing is paired, so the hub
 * showed nothing - and the pairing screen, the only place that would have shown unpaired devices,
 * never started discovery at all. There was no way for a user to get from "installed" to "paired".
 *
 * Listing an unpaired peer is safe, and conflating two different things is what caused the bug.
 * Discovery produces *candidates*; it grants nothing (REQ-D07). A device appearing in a list cannot
 * send or receive anything - the `Noise_KK` handshake still requires a trust record, and
 * `TransportManager` refuses an unpaired peer outright. The protocol's "hidden unless open mode"
 * rule is about **not broadcasting our own name** to strangers (REQ-D06), which is a separate
 * control and is still enforced in the presence record.
 *
 * So: show everything, trust nothing.
 */
internal class DefaultDeviceRepository(
    private val discovery: DiscoveryManager,
) : DeviceRepository {

    override val devices: Flow<List<PeerDevice>> = discovery.peers

    override fun observeNearby(forPairing: Boolean): DiscoverySubscription {
        val handle = discovery.openWindow(
            DiscoveryRequest(
                reason = if (forPairing) DiscoveryReason.PAIRING else DiscoveryReason.FOREGROUND,
                // Only the pairing window is capped. An unbounded pairing window would leave the
                // device accepting unpaired handshakes indefinitely (04-PROTOCOL.md section 4.6).
                timeoutMillis = if (forPairing) Vlp.PAIRING_WINDOW_MILLIS else null,
                // Always true. See the class note: hiding unpaired peers made pairing impossible.
                includeUnpaired = true,
            ),
        )
        return object : DiscoverySubscription {
            override fun close() = handle.close()
        }
    }
}
