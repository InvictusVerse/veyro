package com.veyro.core.discovery

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.DiscoverySource
import com.veyro.core.model.Endpoint
import com.veyro.core.model.TrustState
import com.veyro.core.network.InterfaceClassifier
import com.veyro.core.network.LocalAddressProvider
import com.veyro.core.protocol.BeaconCodec
import com.veyro.core.protocol.PresenceRecord
import com.veyro.core.protocol.ProtocolConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * UDP beacon discovery: multicast to 239.255.42.99:47653 plus a directed broadcast per interface.
 *
 * This is the fallback that makes the hotspot scenarios work (04-PROTOCOL.md section 3.2). It
 * exists because mDNS, despite being the correct mechanism, is silently broken on a meaningful
 * fraction of access points and hotspot drivers - and "silently" is the problem. Running both
 * concurrently means a failure in one is invisible to the user instead of fatal.
 *
 * Both are sent because the failure modes are not symmetric: some APs drop multicast and forward
 * directed broadcast, some do the reverse.
 *
 * ## Rate and lifetime are part of the contract
 *
 *  - At most one announce per second, and **only while a window is open**. NFR-02 requires zero
 *    packets when idle, which is a product promise, not a tuning preference.
 *  - A `QUERY` is answered with a **unicast** `ANNOUNCE` to the sender, never a broadcast.
 *    Answering with a broadcast would turn one device joining a busy network into a reply storm
 *    from every other device - a battery problem and a trivially abusable amplifier.
 *
 * ## Why a directed broadcast rather than 255.255.255.255
 *
 * The all-ones address is not routed off the primary interface, so on a multi-homed machine - or a
 * phone running a hotspot while on Wi-Fi - it would miss the interface that matters. The
 * per-subnet broadcast address reaches each network the device is actually on.
 */
public class UdpBeaconImpl(
    private val dispatchers: AppDispatchers,
    private val addressProvider: LocalAddressProvider,
    private val scope: CoroutineScope,
    private val recordProvider: suspend (port: Int) -> PresenceRecord?,
    private val selfDeviceId: () -> String?,
) : UdpBeacon {

    private companion object {
        const val TAG = "UdpBeacon"
        const val RECEIVE_TIMEOUT_MILLIS = 1_000
        const val RECEIVE_BUFFER_BYTES = 2048
    }

    private val mutex = Mutex()
    private var socket: MulticastSocket? = null
    private var receiveJob: Job? = null
    private var announceJob: Job? = null
    private var advertisedPort: Int = 0

    private val _advertisements = MutableSharedFlow<PeerAdvertisement>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val advertisements: Flow<PeerAdvertisement> = _advertisements.asSharedFlow()

    @Volatile
    override var isRunning: Boolean = false
        private set

    override suspend fun start(port: Int): Unit = mutex.withLock {
        if (isRunning) return
        advertisedPort = port

        val opened = withContext(dispatchers.io) {
            try {
                MulticastSocket(ProtocolConstants.UDP_DISCOVERY_PORT).apply {
                    reuseAddress = true
                    soTimeout = RECEIVE_TIMEOUT_MILLIS
                    // Do not loop our own multicast back: we would otherwise discover ourselves.
                    loopbackMode = true
                    joinGroupOnUsableInterfaces(this)
                }
            } catch (t: Throwable) {
                VeyroLog.w(TAG, t) { "Could not open the beacon socket on ${ProtocolConstants.UDP_DISCOVERY_PORT}" }
                null
            }
        } ?: return

        socket = opened
        isRunning = true

        receiveJob = scope.launch(dispatchers.io) { receiveLoop(opened) }
        announceJob = scope.launch(dispatchers.io) { announceLoop() }
        VeyroLog.i(TAG) { "Beacon started, advertising TCP port $port" }
    }

    override suspend fun stop(): Unit = mutex.withLock {
        isRunning = false
        // Cancel before closing so the receive loop does not log a spurious error on a socket that
        // was closed out from under it.
        announceJob?.cancelAndJoin()
        receiveJob?.cancelAndJoin()
        announceJob = null
        receiveJob = null
        withContext(dispatchers.io) {
            runCatching { socket?.close() }
        }
        socket = null
        VeyroLog.i(TAG) { "Beacon stopped; no further packets will be sent" }
    }

    override suspend fun sendQuery() {
        val current = socket ?: return
        val record = recordProvider(advertisedPort)
        val frame = if (record != null) {
            BeaconCodec.encode(BeaconCodec.Type.QUERY, record)
        } else {
            BeaconCodec.encodeBareQuery()
        }
        sendEverywhere(current, frame)
    }

    // ── loops ──────────────────────────────────────────────────────────────────────────────

    private suspend fun receiveLoop(socket: MulticastSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (scope.isActive && isRunning) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                // Expected: the timeout exists so this loop stays cancellable.
                continue
            } catch (t: Throwable) {
                if (isRunning) VeyroLog.d(TAG) { "Receive ended: ${t.message}" }
                return
            }
            handlePacket(socket, packet)
        }
    }

    private suspend fun handlePacket(socket: MulticastSocket, packet: DatagramPacket) {
        // Honour the received length, not the buffer size: using the buffer size would parse
        // uninitialised tail bytes as payload.
        when (val decoded = BeaconCodec.decode(packet.data, packet.length)) {
            is BeaconCodec.Result.Invalid -> {
                // The normal case on a shared multicast group: most packets are not ours. Logging
                // at debug only, or a noisy network would flood the recorder.
                return
            }

            is BeaconCodec.Result.Query -> {
                // Unicast reply to the sender. See the class note on reply storms.
                val record = recordProvider(advertisedPort) ?: return
                val reply = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record)
                runCatching {
                    socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                }.onFailure { VeyroLog.d(TAG) { "Could not answer a query: ${it.message}" } }

                decoded.record?.let { emit(it, packet.address) }
            }

            is BeaconCodec.Result.Ok -> emit(decoded.record, packet.address)
        }
    }

    private fun emit(record: PresenceRecord, from: InetAddress) {
        // Ignore our own announcements, which arrive on the directed-broadcast path even with
        // multicast loopback disabled.
        if (record.deviceId.value == selfDeviceId()) return

        val host = from.hostAddress ?: return
        _advertisements.tryEmit(
            PeerAdvertisement(
                deviceIdRaw = record.deviceId.value,
                name = record.name,
                platformRaw = record.platform.toWire(),
                version = record.version,
                capabilityBits = record.capabilities.bits,
                // The source address is authoritative for where to connect; the record's port says
                // which port. An address *inside* the payload would be attacker-chosen and could
                // be used to point us at a third party.
                endpoints = listOf(Endpoint(host, record.port)),
                trustHint = TrustState.UNKNOWN,
                source = DiscoverySource.UDP_BEACON,
                observedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun announceLoop() {
        while (scope.isActive && isRunning) {
            val current = socket ?: return
            val record = recordProvider(advertisedPort)
            if (record != null) {
                val frame = BeaconCodec.encode(BeaconCodec.Type.ANNOUNCE, record)
                sendEverywhere(current, frame)
            }
            delay(ProtocolConstants.UDP_BEACON_INTERVAL_MILLIS)
        }
    }

    /**
     * Sends one frame to the multicast group and to each usable subnet''s broadcast address.
     *
     * `suspend`, not blocking: resolving the broadcast addresses needs [addressProvider], which is
     * itself suspending. Wrapping that in `runBlocking` from inside an IO coroutine is a real
     * deadlock risk on a dispatcher with a bounded thread pool, not just a style problem.
     */
    private suspend fun sendEverywhere(socket: DatagramSocket, frame: ByteArray) {
        val broadcasts = addressProvider.localAddresses()
            .filter { InterfaceClassifier.isAdvertisable(it.kind) }
            .mapNotNull { it.broadcastAddress }
            .distinct()

        withContext(dispatchers.io) {
            runCatching {
                socket.send(
                    DatagramPacket(
                        frame,
                        frame.size,
                        InetAddress.getByName(ProtocolConstants.UDP_MULTICAST_GROUP),
                        ProtocolConstants.UDP_DISCOVERY_PORT,
                    ),
                )
            }.onFailure { VeyroLog.d(TAG) { "Multicast send failed: ${it.message}" } }

            for (broadcast in broadcasts) {
                runCatching {
                    socket.send(
                        DatagramPacket(
                            frame,
                            frame.size,
                            InetAddress.getByName(broadcast),
                            ProtocolConstants.UDP_DISCOVERY_PORT,
                        ),
                    )
                }.onFailure { VeyroLog.d(TAG) { "Broadcast to $broadcast failed: ${it.message}" } }
            }
        }
    }

    /**
     * Joins the multicast group on every interface worth listening on.
     *
     * Joining on the default interface only is a real bug on a multi-homed device: a phone running
     * a hotspot while also on Wi-Fi has two relevant interfaces, and the hotspot one is usually
     * not the default.
     */
    private fun joinGroupOnUsableInterfaces(socket: MulticastSocket) {
        val group = InetSocketAddress(
            InetAddress.getByName(ProtocolConstants.UDP_MULTICAST_GROUP),
            ProtocolConstants.UDP_DISCOVERY_PORT,
        )
        var joined = 0
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull()
            .orEmpty()

        for (nif in interfaces) {
            val usable = runCatching {
                nif.isUp && !nif.isLoopback && nif.supportsMulticast()
            }.getOrDefault(false)
            if (!usable) continue
            if (!InterfaceClassifier.isAdvertisable(InterfaceClassifier.classify(nif.name ?: ""))) {
                continue
            }
            if (runCatching { socket.joinGroup(group, nif); true }.getOrDefault(false)) joined++
        }

        if (joined == 0) {
            // Fall back to the default interface. Better than nothing, and the diagnosis layer
            // will report MULTICAST_BLOCKED if it turns out nothing arrives.
            runCatching { socket.joinGroup(InetAddress.getByName(ProtocolConstants.UDP_MULTICAST_GROUP)) }
            VeyroLog.d(TAG) { "Joined the multicast group on the default interface only" }
        } else {
            VeyroLog.d(TAG) { "Joined the multicast group on $joined interface(s)" }
        }
    }
}
