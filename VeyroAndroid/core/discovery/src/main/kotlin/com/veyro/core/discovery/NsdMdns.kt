package com.veyro.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.model.DiscoverySource
import com.veyro.core.model.Endpoint
import com.veyro.core.model.TrustState
import com.veyro.core.protocol.PresenceCodec
import com.veyro.core.protocol.PresenceRecord
import com.veyro.core.protocol.ProtocolConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds a Wi-Fi `MulticastLock` for the lifetime of a discovery window.
 *
 * **This is not optional.** Without a held multicast lock, many OEM ROMs drop inbound multicast
 * once the device dozes, and mDNS discovery fails with **no error at all** - the browse callback
 * simply never fires. It is the single most likely cause of "it can't find my PC" and it is
 * invisible in logs, which is why it gets its own named component rather than two lines inside the
 * browser.
 *
 * Requires `CHANGE_WIFI_MULTICAST_STATE`, declared in the app manifest.
 *
 * Reference-counting is off deliberately: ownership is explicit and the lock is released when the
 * window closes. A reference-counted lock that someone forgets to release is a battery drain that
 * only shows up in user reports (NFR-03).
 */
public class MulticastLockHolder(context: Context) {

    private companion object {
        const val TAG = "MulticastLock"
        const val LOCK_TAG = "veyro-discovery"
    }

    private val wifiManager =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    fun acquire() {
        if (lock?.isHeld == true) return
        lock = try {
            wifiManager?.createMulticastLock(LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            VeyroLog.w(TAG, t) { "Could not acquire the multicast lock; mDNS may be unreliable" }
            null
        }
        if (lock?.isHeld == true) {
            VeyroLog.d(TAG) { "Multicast lock acquired" }
        }
    }

    @Synchronized
    fun release() {
        try {
            lock?.takeIf { it.isHeld }?.release()
            VeyroLog.d(TAG) { "Multicast lock released" }
        } catch (t: Throwable) {
            VeyroLog.w(TAG, t) { "Could not release the multicast lock" }
        } finally {
            lock = null
        }
    }

    @Synchronized
    fun isHeld(): Boolean = lock?.isHeld == true
}

/**
 * mDNS advertiser for `_veyro._tcp` (REQ-D03).
 *
 * The service instance name is derived from the `deviceId` prefix rather than the user's device
 * name, so a stranger browsing a public network sees an opaque instance rather than
 * "Rahul's Laptop" (REQ-D06). The display name travels in the TXT record and is omitted entirely
 * in private mode.
 */
public class NsdMdnsAdvertiser(
    context: Context,
    private val dispatchers: AppDispatchers,
) : MdnsAdvertiser {

    private companion object {
        const val TAG = "MdnsAdvertiser"
    }

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val mutex = Mutex()
    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    override var isRunning: Boolean = false
        private set

    override suspend fun start(port: Int, deviceId: String, displayName: String?) {
        start(port, deviceId, displayName, null)
    }

    /** Full form: the record carries everything a browser needs to pre-filter. */
    public suspend fun start(
        port: Int,
        deviceId: String,
        displayName: String?,
        record: PresenceRecord?,
    ): Unit = mutex.withLock {
        if (isRunning) return
        val manager = nsd ?: run {
            VeyroLog.w(TAG) { "NsdManager unavailable; mDNS advertising disabled" }
            return
        }

        val info = NsdServiceInfo().apply {
            // Opaque instance name: the display name must not leak to passive observers.
            serviceName = "veyro-${deviceId.take(8)}"
            serviceType = ProtocolConstants.MDNS_SERVICE_TYPE
            this.port = port

            if (record != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for ((key, value) in PresenceCodec.encodeToPairs(record)) {
                    setAttribute(key, value)
                }
                getBestLocalIp()?.let { ip ->
                    setAttribute("ip", ip)
                }
            }
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                VeyroLog.i(TAG) { "Advertising as ${info.serviceName} on port $port" }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                VeyroLog.w(TAG) { "Registration failed with code $errorCode" }
                isRunning = false
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                VeyroLog.d(TAG) { "Advertising stopped" }
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                VeyroLog.w(TAG) { "Unregistration failed with code $errorCode" }
            }
        }

        withContext(dispatchers.io) {
            runCatching {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            }.onFailure { VeyroLog.w(TAG, it) { "registerService threw" } }
        }
        listener = registration
        isRunning = true
    }

    override suspend fun stop(): Unit = mutex.withLock {
        val manager = nsd ?: return
        listener?.let { l ->
            runCatching { manager.unregisterService(l) }
                .onFailure { VeyroLog.w(TAG, it) { "unregisterService threw" } }
        }
        listener = null
        isRunning = false
    }

    private fun getBestLocalIp(): String? {
        return try {
            var fallbackIp: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                val name = intf.name.lowercase()
                // Skip VM adapters, cellular, and dummy interfaces that LAN peers cannot reach
                if (name.contains("vbox") || name.contains("vmnet") || name.contains("wsl") ||
                    name.contains("rmnet") || name.contains("ccmni") || name.contains("dummy")) continue

                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        // Prefer Wi-Fi or local Hotspot AP
                        if (name.contains("wlan") || name.contains("ap") || name.contains("swlan")) {
                            return ip
                        } else if (fallbackIp == null) {
                            fallbackIp = ip
                        }
                    }
                }
            }
            fallbackIp
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * mDNS browser for `_veyro._tcp`.
 *
 * ## The resolve queue
 *
 * `NsdManager.resolveService` must not be called concurrently. Issuing a second resolve while one
 * is in flight wedges both, and the platform gives no error - discovery just stops producing
 * results. On a network with several peers this is easy to hit, so resolves are serialised through
 * a queue with a single in-flight flag.
 *
 * ## Address preference
 *
 * When the resolved record carries an address in its TXT data, that is preferred over
 * `NsdServiceInfo.host`. The advertiser chose which interface it meant; the resolved host can be a
 * link-local or VPN address that will never be reachable, producing a peer that is visible and
 * permanently unconnectable - exactly what REQ-N06 exists to prevent.
 */
public class NsdMdnsBrowser(
    context: Context,
    private val dispatchers: AppDispatchers,
    private val selfDeviceId: () -> String?,
) : MdnsBrowser {

    private companion object {
        const val TAG = "MdnsBrowser"
    }

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val mutex = Mutex()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    private val _advertisements = MutableSharedFlow<PeerAdvertisement>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val advertisements: Flow<PeerAdvertisement> = _advertisements.asSharedFlow()

    private val _lostServices = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val lostServices: Flow<String> = _lostServices.asSharedFlow()

    @Volatile
    override var isRunning: Boolean = false
        private set

    override suspend fun start(): Unit = mutex.withLock {
        if (isRunning) return
        val manager = nsd ?: run {
            VeyroLog.w(TAG) { "NsdManager unavailable; mDNS browsing disabled" }
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                VeyroLog.d(TAG) { "Browsing $serviceType" }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                // Do not resolve our own advertisement: it wastes a resolve slot and would show
                // the user their own device in the nearby list.
                val self = selfDeviceId()
                if (self != null && info.serviceName?.endsWith(self.take(8)) == true) return
                resolveQueue.add(info)
                pumpResolveQueue()
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName
                VeyroLog.i(TAG) { "Service lost: $name" }
                if (name != null) {
                    _lostServices.tryEmit(name)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                VeyroLog.d(TAG) { "Browsing stopped" }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                VeyroLog.w(TAG) { "Browse start failed with code $errorCode" }
                isRunning = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                VeyroLog.w(TAG) { "Browse stop failed with code $errorCode" }
            }
        }

        withContext(dispatchers.io) {
            runCatching {
                manager.discoverServices(
                    ProtocolConstants.MDNS_SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
            }.onFailure { VeyroLog.w(TAG, it) { "discoverServices threw" } }
        }
        discoveryListener = listener
        isRunning = true
    }

    override suspend fun stop(): Unit = mutex.withLock {
        val manager = nsd ?: return
        discoveryListener?.let { l ->
            runCatching { manager.stopServiceDiscovery(l) }
                .onFailure { VeyroLog.w(TAG, it) { "stopServiceDiscovery threw" } }
        }
        discoveryListener = null
        resolveQueue.clear()
        resolving.set(false)
        isRunning = false
    }

    /**
     * Drains the resolve queue one entry at a time.
     *
     * The flag is the whole point: `NsdManager` will not resolve two services at once, and doing so
     * wedges both with no error.
     */
    private fun pumpResolveQueue() {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        val manager = nsd ?: run { resolving.set(false); return }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                handleResolved(resolved)
                resolving.set(false)
                // Continue draining. Both callbacks must do this or the queue stalls forever.
                pumpResolveQueue()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                VeyroLog.d(TAG) { "Resolve failed for ${info.serviceName}, code $errorCode" }
                resolving.set(false)
                pumpResolveQueue()
            }
        }

        runCatching { manager.resolveService(next, resolveListener) }
            .onFailure {
                VeyroLog.w(TAG, it) { "resolveService threw" }
                resolving.set(false)
                pumpResolveQueue()
            }
    }

    private fun handleResolved(resolved: NsdServiceInfo) {
        val pairs = resolved.attributes?.mapNotNull { (key, value) ->
            val text = value?.toString(Charsets.UTF_8) ?: return@mapNotNull null
            key to text
        }.orEmpty()

        if (pairs.isEmpty()) {
            VeyroLog.d(TAG) { "Resolved ${resolved.serviceName} with no TXT data; ignoring" }
            return
        }

        when (val decoded = PresenceCodec.decodeFromPairs(pairs)) {
            is PresenceCodec.Result.Invalid -> {
                // Normal on a shared network: anything can publish a service. Not an error.
                VeyroLog.d(TAG) { "Ignoring a record: ${decoded.reason}" }
            }

            is PresenceCodec.Result.Ok -> {
                val record = decoded.record
                // Prefer the advertiser's own IPv4 address from TXT, then the resolved host.
                val host = pairs.firstOrNull { it.first == "ip" }?.second
                    ?: resolved.host?.hostAddress
                if (host == null) {
                    VeyroLog.d(TAG) { "Resolved ${resolved.serviceName} with no address; ignoring" }
                    return
                }
                val port = if (record.port in 1..65535) record.port else resolved.port
                if (port !in 1..65535) return

                _advertisements.tryEmit(
                    PeerAdvertisement(
                        deviceIdRaw = record.deviceId.value,
                        name = record.name,
                        platformRaw = record.platform.toWire(),
                        version = record.version,
                        capabilityBits = record.capabilities.bits,
                        endpoints = listOf(Endpoint(host, port)),
                        // Discovery never decides trust. The DiscoveryManager overlays the trust
                        // store; until then everything is UNKNOWN (REQ-D07).
                        trustHint = TrustState.UNKNOWN,
                        source = DiscoverySource.MDNS,
                        observedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
}
