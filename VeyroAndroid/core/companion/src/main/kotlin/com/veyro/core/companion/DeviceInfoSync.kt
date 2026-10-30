package com.veyro.core.companion

import com.veyro.core.common.VeyroLog
import com.veyro.core.link.PeerMessage
import com.veyro.core.model.Capability
import com.veyro.core.model.DeviceNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Live device names and the clipboard-receive preference (ADR-059, 04-PROTOCOL.md 6.7).
 *
 * Per live link:
 *  - **Out**: to a peer that advertises [Capability.DEVICE_INFO], this phone's `deviceInfo`
 *    (its name from Settings and whether "Auto-fetch clipboard" is on) right after the hello and
 *    again whenever either changes. No reconnect is needed for a rename or a toggle.
 *  - **In**: the name the peer announces for itself (its hello, helloAck or `deviceInfo`, already
 *    validated by the connection) is stored with [storeAnnouncedName] whenever it differs. A peer
 *    that was offline during a rename therefore catches up from its next hello. The user's local
 *    nickname for the peer is a separate field and is not touched.
 *
 * Names are user data: never logged.
 */
public class DeviceInfoSync(
    private val links: Flow<List<CompanionLink>>,
    /** This phone's name as Settings holds it. */
    private val localName: Flow<String>,
    /** "Auto-fetch clipboard" (ADR-043): whether this phone keeps a PC's automatic copies. */
    private val clipReceive: Flow<Boolean>,
    /** Stores a peer's validated own name; the store ignores unknown devices and unchanged names. */
    private val storeAnnouncedName: suspend (peerDeviceId: String, name: String) -> Unit,
    private val scope: CoroutineScope,
) {

    private companion object {
        const val TAG = "DeviceInfo"
    }

    private var job: Job? = null

    /** Starts observing. Idempotent; returns the running job. */
    @Synchronized
    public fun start(): Job = job?.takeIf { it.isActive } ?: scope.launch { run() }.also { job = it }

    private suspend fun run() {
        links.forEachLink { link ->
            try {
                coroutineScope {
                    launch { storeNames(link) }
                    link.peerCapabilities
                        .map { Capability.DEVICE_INFO in it }
                        .distinctUntilChanged()
                        .collectLatest { capable -> if (capable) announce(link) }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                VeyroLog.w(TAG) { "Device info session ended: ${t.javaClass.simpleName}" }
            }
        }
    }

    private suspend fun storeNames(link: CompanionLink) {
        val peerId = link.peerDeviceId ?: return
        link.peerAnnouncedName
            .filterNotNull()
            .distinctUntilChanged()
            .collect { announced ->
                val name = DeviceNames.clean(announced) ?: return@collect
                runCatching { storeAnnouncedName(peerId, name) }
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { VeyroLog.w(TAG) { "Peer name not stored: ${it.javaClass.simpleName}" } }
            }
    }

    /** Sends the current snapshot, then every change, until the link goes or loses the bit. */
    private suspend fun announce(link: CompanionLink) {
        combine(localName, clipReceive) { name, receive -> PeerMessage.DeviceInfo(name = DeviceNames.clean(name), clipReceive = receive) }
            .distinctUntilChanged()
            .collect { info ->
                runCatching { link.send(info) }
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { VeyroLog.w(TAG) { "Device info not sent: ${it.javaClass.simpleName}" } }
            }
    }
}
