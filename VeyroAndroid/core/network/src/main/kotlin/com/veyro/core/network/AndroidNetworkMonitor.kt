package com.veyro.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.Endpoint
import com.veyro.core.model.NetworkDiagnosis
import com.veyro.core.model.NetworkStatus
import com.veyro.core.model.NetworkTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * [NetworkMonitor] over `ConnectivityManager.NetworkCallback`.
 *
 * Callback-driven, not polled. That is a requirement rather than a preference: NFR-01/NFR-03 say an
 * idle Veyro does no work, and a polling loop is exactly the thing that quietly drains a battery.
 * `registerDefaultNetworkCallback` costs nothing while nothing changes.
 */
public class AndroidNetworkMonitor(
    context: Context,
    private val dispatchers: AppDispatchers,
    private val addressProvider: LocalAddressProvider,
) : NetworkMonitor {

    private companion object {
        const val TAG = "Network"
    }

    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(ConnectivityManager::class.java)

    override val status: Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                trySend(Unit)
            }
        }

        // Emit once up front so a collector is not left waiting for a change that may never come.
        trySend(Unit)

        try {
            connectivity?.registerDefaultNetworkCallback(callback)
        } catch (t: Throwable) {
            VeyroLog.w(TAG, t) { "Could not register the network callback" }
        }

        awaitClose {
            runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }
        // The callback fires several times for one physical change (available, then capabilities).
        // Conflating and de-duplicating stops that becoming several discovery restarts.
        .conflate()
        .let { trigger ->
            kotlinx.coroutines.flow.flow {
                trigger.collect {
                    emit(currentStatus())
                }
            }
        }
        .distinctUntilChanged()

    override suspend fun currentStatus(): NetworkStatus = withContext(dispatchers.io) {
        val capabilities = runCatching {
            connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()

        val addresses = addressProvider.localAddresses()
        val advertisable = addresses.filter { InterfaceClassifier.isAdvertisable(it.kind) }

        val hasVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
            addresses.any { it.kind == InterfaceKind.VPN }

        // A soft-AP interface being up is the only reliable signal that this device is hosting a
        // hotspot; there is no supported API to ask.
        val hotspotActive = addresses.any { it.kind == InterfaceKind.HOTSPOT }

        val transport = when {
            hotspotActive -> NetworkTransport.HOTSPOT
            capabilities == null -> if (advertisable.isEmpty()) {
                NetworkTransport.NONE
            } else {
                // No active default network but a usable interface exists. This happens on a
                // hotspot with no upstream: there is no "internet", but peers are reachable.
                NetworkTransport.WIFI
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                NetworkTransport.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                NetworkTransport.CELLULAR
            else -> if (advertisable.isEmpty()) NetworkTransport.NONE else NetworkTransport.WIFI
        }

        val observations = NetworkDiagnoser.Observations(
            transport = transport,
            hasVpn = hasVpn,
            localAddresses = advertisable.map { it.address },
        )

        NetworkStatus(
            hasUsableNetwork = advertisable.isNotEmpty() &&
                transport != NetworkTransport.NONE &&
                transport != NetworkTransport.CELLULAR,
            transport = transport,
            isHotspotActive = hotspotActive,
            isVpnActive = hasVpn,
            localAddresses = advertisable.map { it.address },
            // Without a peer to probe, the only conclusions available are about the local side.
            diagnosis = if (advertisable.isEmpty()) {
                NetworkDiagnoser.diagnose(observations)
            } else {
                NetworkDiagnosis.OK
            },
        )
    }

    /**
     * Races a TCP connect against every advertised endpoint and returns the first that answers
     * (REQ-D09).
     *
     * Racing rather than trying in order is what makes a multi-homed peer work without the user
     * choosing an address: a PC on both Wi-Fi and Ethernet advertises both, and only one may be on
     * the phone's subnet. Trying sequentially would mean waiting out a full timeout on the wrong
     * one first.
     *
     * The distinction between *refused* and *timed out* is preserved, because it is the evidence
     * that separates a firewall from AP isolation.
     */
    override suspend fun probe(endpoints: List<Endpoint>, timeoutMillis: Long): ProbeResult {
        if (endpoints.isEmpty()) {
            return ProbeResult(null, NetworkDiagnosis.SUBNET_MISMATCH)
        }

        val status = currentStatus()
        if (!status.hasUsableNetwork) {
            return ProbeResult(null, NetworkDiagnoser.diagnose(
                NetworkDiagnoser.Observations(
                    transport = status.transport,
                    hasVpn = status.isVpnActive,
                    localAddresses = status.localAddresses,
                ),
            ))
        }

        val outcomes = withContext(dispatchers.io) {
            coroutineScope {
                endpoints.map { endpoint ->
                    async { tryConnect(endpoint, timeoutMillis) }
                }.awaitAll()
            }
        }

        outcomes.firstOrNull { it.reachable }?.let {
            return ProbeResult(it.endpoint, NetworkDiagnosis.OK)
        }

        val observations = NetworkDiagnoser.Observations(
            transport = status.transport,
            hasVpn = status.isVpnActive,
            localAddresses = status.localAddresses,
            // We only probe endpoints we learned from discovery, so by definition we saw the peer.
            seenViaMdns = true,
            tcpConnectSucceeded = false,
            tcpRefused = outcomes.any { it.refused },
            peerAddresses = endpoints.map { it.host },
            localPrefixLength = addressProvider.localAddresses()
                .firstOrNull { InterfaceClassifier.isAdvertisable(it.kind) }
                ?.prefixLength ?: 24,
        )
        return ProbeResult(null, NetworkDiagnoser.diagnose(observations))
    }

    private data class Outcome(
        val endpoint: Endpoint,
        val reachable: Boolean,
        val refused: Boolean,
    )

    private fun tryConnect(endpoint: Endpoint, timeoutMillis: Long): Outcome {
        Socket().use { socket ->
            return try {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMillis.toInt())
                Outcome(endpoint, reachable = true, refused = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.ConnectException) {
                // "Connection refused" carries real information: the host answered. A timeout does
                // not. Collapsing them into one boolean would lose the ability to tell a firewall
                // from AP isolation, which is the most common hotspot complaint.
                Outcome(endpoint, reachable = false, refused = true)
            } catch (t: Throwable) {
                Outcome(endpoint, reachable = false, refused = false)
            }
        }
    }
}

/**
 * Enumerates local IPv4 addresses, classified and ordered.
 *
 * `NetworkInterface.isVirtual` is unreliable - it returns false for VirtualBox, VMware and WSL
 * adapters - so [InterfaceClassifier] does the real work by name. See its documentation for why
 * getting this wrong produces a peer that is visible and permanently unreachable.
 */
public class DefaultLocalAddressProvider(
    private val dispatchers: AppDispatchers,
) : LocalAddressProvider {

    private companion object {
        const val TAG = "Addresses"
    }

    override suspend fun localAddresses(): List<LocalAddress> = withContext(dispatchers.io) {
        val result = mutableListOf<LocalAddress>()

        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (t: Throwable) {
            VeyroLog.w(TAG, t) { "Could not enumerate network interfaces" }
            return@withContext emptyList()
        }

        for (nif in interfaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            if (!up) continue
            val loopback = runCatching { nif.isLoopback }.getOrDefault(false)
            if (loopback) continue

            val kind = InterfaceClassifier.classify(
                name = nif.name ?: continue,
                isLoopback = loopback,
                isVirtual = runCatching { nif.isVirtual }.getOrDefault(false),
            )

            for (ifAddress in runCatching { nif.interfaceAddresses }.getOrDefault(emptyList())) {
                val address = ifAddress.address ?: continue
                // IPv4 only. IPv6 link-local would need scope-id handling on both platforms and is
                // not needed for any supported topology; adding it is a protocol change.
                if (address !is java.net.Inet4Address) continue
                val host = address.hostAddress ?: continue
                if (Ipv4.isLoopback(host)) continue

                result += LocalAddress(
                    address = host,
                    interfaceName = nif.name,
                    kind = kind,
                    prefixLength = ifAddress.networkPrefixLength.toInt(),
                    broadcastAddress = Ipv4.broadcastAddress(host, ifAddress.networkPrefixLength.toInt()),
                    isLinkLocal = Ipv4.isLinkLocal(host),
                )
            }
        }

        result.sortedBy { InterfaceClassifier.preference(it.kind) }
    }
}
