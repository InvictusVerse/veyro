package com.veyro.di

import android.content.Context
import android.os.Build
import com.veyro.core.common.AndroidLogSink
import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.CompositeLogSink
import com.veyro.core.common.DefaultAppDispatchers
import com.veyro.core.common.Diagnostics
import com.veyro.core.common.LogLevel
import com.veyro.core.common.RecordingLogSink
import com.veyro.core.common.VeyroLog
import com.veyro.core.crypto.DefaultIdentityManager
import com.veyro.core.crypto.HandshakeEngine
import com.veyro.core.crypto.NoiseHandshakeEngine
import com.veyro.core.crypto.IdentityManager
import com.veyro.core.crypto.IdentityUnavailableException
import com.veyro.core.crypto.LocalKeys
import com.veyro.core.crypto.SeedStore
import com.veyro.core.crypto.VlpKdf
import com.veyro.core.crypto.keystoreSeedStore
import com.veyro.core.data.DeviceRepository
import com.veyro.core.data.FileSettingsStore
import com.veyro.core.data.FileTransferHistoryStore
import com.veyro.core.data.FileTrustStore
import com.veyro.core.data.SettingsStore
import com.veyro.core.data.TransferHistoryStore
import com.veyro.core.data.TrustStore
import com.veyro.core.discovery.DiscoveryManager
import com.veyro.core.discovery.DiscoveryManagerImpl
import com.veyro.core.discovery.DiscoveryReason
import com.veyro.core.discovery.DiscoveryRequest
import com.veyro.core.discovery.MulticastLockHolder
import com.veyro.core.discovery.NsdMdnsAdvertiser
import com.veyro.core.discovery.NsdMdnsBrowser
import com.veyro.core.discovery.UdpBeaconImpl
import com.veyro.core.model.DeviceId
import com.veyro.core.model.Endpoint
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import com.veyro.core.link.ConnectionManager
import com.veyro.core.link.TransferCoordinator
import com.veyro.core.link.TransferRunner
import com.veyro.core.transfer.TransferStoragePublisher
import com.veyro.storage.AndroidMediaStorePublisher
import com.veyro.core.model.Capabilities
import com.veyro.core.model.Platform
import com.veyro.core.model.fingerprint
import com.veyro.core.network.AndroidNetworkMonitor
import com.veyro.core.network.AndroidWifiDirectManager
import com.veyro.core.network.DefaultLocalAddressProvider
import com.veyro.core.network.InterfaceClassifier
import com.veyro.core.network.LocalAddressProvider
import com.veyro.core.network.NetworkMonitor
import com.veyro.core.network.WifiDirectManager
import com.veyro.core.protocol.PresenceRecord
import com.veyro.core.protocol.Vlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/**
 * The object graph, written by hand (ADR-005).
 *
 * There is no DI framework. The graph is small, process-scoped and almost entirely singletons,
 * which is the case where a framework buys the least and costs the most.
 *
 * Rules for editing this file:
 *  - Everything is `by lazy`, so nothing is constructed until something needs it. That keeps cold
 *    start fast (NFR-09) and - more importantly here - means **no socket is opened and no packet
 *    is sent merely because the app launched** (NFR-02).
 *  - Construction order is expressed by reference, not an `init()` sequence.
 *  - Nothing here may hold an Activity or a View.
 */
public class VeyroContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    public val dispatchers: AppDispatchers by lazy { DefaultAppDispatchers() }

    /**
     * Process-lifetime scope for long-running components.
     *
     * `SupervisorJob` so one failing collector - a discovery mechanism that throws on an odd
     * network, say - cannot cancel the others and take the whole app's background work with it.
     */
    private val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + dispatchers.default)
    }

    /** In-memory log tail, so the debug screen works without a laptop attached. */
    public val logRecorder: RecordingLogSink by lazy { RecordingLogSink() }

    /**
     * Hand-off between the file picker and the send screen.
     *
     * Process-scoped on purpose: only an Activity can launch a picker, and the result can arrive
     * after a configuration change. Keeping the selection here means a rotation mid-pick does not
     * silently lose it.
     */
    public val pickedFiles: PickedFileBuffer by lazy { PickedFileBuffer() }

    // ── P1 — identity and local storage ────────────────────────────────────────────────────

    private val storageDir: File by lazy { appContext.filesDir }

    public val seedStore: SeedStore by lazy { keystoreSeedStore(appContext) }

    public val identityManager: IdentityManager by lazy { DefaultIdentityManager(seedStore) }

    /**
     * The trust store is authenticated with a key derived from the identity seed.
     *
     * That binding is the mechanism behind REQ-I10: after an identity reset every derived key
     * changes, so the trust file stops authenticating and reads as empty. "Reset my identity"
     * therefore really does mean "all pairings are gone", enforced by cryptography rather than by
     * remembering to delete a second file.
     */
    public val trustStore: TrustStore by lazy {
        val macKey = runBlocking {
            try {
                identityManager.deriveLocalKey(LocalKeys.TRUST_STORE_MAC)
            } catch (e: IdentityUnavailableException) {
                VeyroLog.e("Container", e) { "No identity; trust store will be unreadable" }
                ByteArray(32)
            }
        }
        FileTrustStore(storageDir, macKey, dispatchers)
    }

    public val settingsStore: SettingsStore by lazy {
        FileSettingsStore(storageDir, defaultDeviceName = defaultDeviceName())
    }

    // ── P2 — network and discovery ─────────────────────────────────────────────────────────

    public val localAddressProvider: LocalAddressProvider by lazy {
        DefaultLocalAddressProvider(dispatchers)
    }

    public val networkMonitor: NetworkMonitor by lazy {
        AndroidNetworkMonitor(appContext, dispatchers, localAddressProvider)
    }

    public val discoveryManager: DiscoveryManager by lazy {
        DiscoveryManagerImpl(
            dispatchers = dispatchers,
            scope = appScope,
            advertiser = NsdMdnsAdvertiser(appContext, dispatchers),
            browser = NsdMdnsBrowser(appContext, dispatchers) {
                runCatching { runBlocking { identityManager.currentIdentity()?.deviceId?.value } }
                    .getOrNull()
            },
            beacon = UdpBeaconImpl(
                dispatchers = dispatchers,
                addressProvider = localAddressProvider,
                scope = appScope,
                recordProvider = { port -> buildPresenceRecord(port, includeName = false) },
                selfDeviceId = {
                    runCatching { runBlocking { identityManager.currentIdentity()?.deviceId?.value } }
                        .getOrNull()
                },
            ),
            multicastLock = MulticastLockHolder(appContext),
            trustedPeers = { runCatching { trustStore.snapshot() }.getOrDefault(emptyList()) },
            presenceRecord = { port, includeName -> buildPresenceRecord(port, includeName) },
            listeningPort = { connectionManager.startListening() },
        )
    }

    public val deviceRepository: DeviceRepository by lazy {
        DefaultDeviceRepository(discoveryManager)
    }

    /**
     * The connection layer: pairing and device-to-device peer connections.
     *
     * Ported from the working Invox ecosystem code. It handles WebSocket transport, preamble
     * exchange, PIN pairing, and authenticated session management.
     */
    public val connectionManager: ConnectionManager by lazy {
        ConnectionWiring.create(
            dispatchers = dispatchers,
            scope = appScope,
            identityManager = identityManager,
            trustStore = trustStore,
            deviceName = { runCatching { settingsStore.deviceName.first() }.getOrDefault("Veyro device") },
        )
    }

    /** Legacy alias for callers expecting linkManager. */
    public val linkManager: ConnectionManager get() = connectionManager

    // ── Transfer, over peer connections ────────────────────────────────────────────────────

    public val historyStore: TransferHistoryStore by lazy {
        FileTransferHistoryStore(storageDir)
    }

    public val storagePublisher: TransferStoragePublisher by lazy {
        AndroidMediaStorePublisher(
            context = appContext,
            customDestinationDir = { runCatching { settingsStore.destinationDir.first() }.getOrNull() },
        )
    }

    public val wifiDirectManager: WifiDirectManager by lazy {
        AndroidWifiDirectManager(appContext, dispatchers)
    }

    private val transferRunner: TransferRunner by lazy {
        TransferRunner(dispatchers, wifiDirectManager, localAddressProvider)
    }

    /**
     * The one surface the Send and Receive screens use.
     *
     * Backed by [connectionManager] (connection + session) and [transferRunner] (chunk streaming).
     */
    private val lastKnownEndpoints = ConcurrentHashMap<DeviceId, Pair<List<Endpoint>, Long>>()

    public val transferCoordinator: TransferCoordinator by lazy {
        TransferCoordinator(
            connectionManager = connectionManager,
            runner = transferRunner,
            dispatchers = dispatchers,
            // Cache, not files: staged copies are reproducible and the OS may reclaim them.
            stagingDir = File(appContext.cacheDir, "staging"),
            destinationProvider = { destinationDirectory() },
            localName = { runCatching { settingsStore.deviceName.first() }.getOrDefault("Veyro device") },
            endpointsFor = { id ->
                val current = discoveryManager.peers.value.firstOrNull { it.deviceId == id }?.endpoints.orEmpty()
                if (current.isNotEmpty()) {
                    lastKnownEndpoints[id] = current to System.currentTimeMillis()
                    current
                } else {
                    val cached = lastKnownEndpoints[id]
                    if (cached != null && (System.currentTimeMillis() - cached.second) < LAST_KNOWN_ENDPOINT_TTL_MS) {
                        cached.first
                    } else {
                        emptyList()
                    }
                }
            },
            autoAcceptFor = { deviceId ->
                // REQ-T03: per-device, default off, never for an unpaired device. `find` returning
                // null for an unknown peer is what enforces the last part.
                trustStore.find(deviceId)?.takeUnless { it.revoked }?.autoAcceptTransfers == true
            },
            discoverEndpoints = { id, timeoutMs ->
                val handle = discoveryManager.openWindow(
                    DiscoveryRequest(
                        reason = DiscoveryReason.TRANSFER,
                        timeoutMillis = timeoutMs + 2_000L,
                        includeUnpaired = false,
                    ),
                )
                try {
                    withTimeoutOrNull(timeoutMs) {
                        discoveryManager.peers
                            .mapNotNull { peers ->
                                peers.firstOrNull { it.deviceId == id }?.endpoints?.takeIf { it.isNotEmpty() }
                            }
                            .first()
                    }?.also { fresh ->
                        lastKnownEndpoints[id] = fresh to System.currentTimeMillis()
                    }.orEmpty()
                } finally {
                    handle.close()
                }
            },
            storagePublisher = storagePublisher,
            onTransferCompleted = { transfer ->
                historyStore.append(transfer)
            },
        )
    }

    private companion object {
        const val LAST_KNOWN_ENDPOINT_TTL_MS = 60_000L
    }

    /**
     * Where received files land.
     *
     * Defaults to the app-private Downloads folder rather than the shared one. That is deliberate:
     * writing to shared storage needs either a SAF tree the user granted or a permission, and
     * neither should be required before the first transfer works. The user can point it elsewhere
     * from Settings.
     */
    public suspend fun destinationDirectory(): File {
        val chosen = runCatching { settingsStore.destinationDir.first() }.getOrNull()
        val dir = chosen ?: File(appContext.cacheDir, "staging_receive")
        dir.mkdirs()
        return dir
    }

    /**
     * Builds the record both discovery mechanisms advertise.
     *
     * Note what is and is not included. The display name is present only when [includeName] is
     * true - that is "open mode", used while pairing. Outside pairing the name is withheld so a
     * stranger on a public network cannot harvest it (REQ-D06), and recognition relies on the
     * rotating presence token instead.
     */
    private suspend fun buildPresenceRecord(port: Int, includeName: Boolean): PresenceRecord? {
        val identity = runCatching { identityManager.ensureIdentity() }.getOrNull() ?: return null
        if (port !in 1..65535) return null

        // The key published here must be the one the handshake authenticates with, which is the
        // *derived* Noise static key (LocalKeys.NOISE_STATIC), not the raw identity public key.
        // Publishing the wrong one would make every handshake fail with a key mismatch while
        // everything else looked correct - the worst kind of bug to track down.
        val noisePublicKey = runCatching {
            com.veyro.core.crypto.X25519.publicKeyOf(
                identityManager.deriveLocalKey(LocalKeys.NOISE_STATIC, 32),
            )
        }.getOrNull() ?: return null

        val publicKeyHash = MessageDigest.getInstance("SHA-256").run {
            update("veyro-pk-v1".toByteArray(Charsets.US_ASCII))
            update(noisePublicKey)
            digest()
        }.copyOf(PresenceRecord.HASH_LENGTH)

        // In open mode the token is random per window, so it carries no linkable information. In
        // private mode it would be HMAC-derived per peer; that path arrives with pairing in P3.
        val token = ByteArray(PresenceRecord.TOKEN_LENGTH).also {
            java.security.SecureRandom().nextBytes(it)
        }

        val name = if (includeName) {
            runCatching { settingsStore.deviceName.first() }.getOrNull()
        } else {
            null
        }

        return PresenceRecord(
            version = Vlp.VERSION,
            deviceId = identity.deviceId,
            platform = Platform.ANDROID,
            capabilities = Capabilities.ANDROID_CURRENT,
            publicKeyHash = publicKeyHash,
            presenceToken = token,
            name = name,
            port = port,
            // Published only in open mode. Outside pairing there is nothing to gain from
            // broadcasting it, and withholding it keeps the record minimal (REQ-D06).
            publicKey = if (includeName) noisePublicKey else null,
        )
    }

    // ── Debug ──────────────────────────────────────────────────────────────────────────────

    /** Installs logging. Called once from `VeyroApplication.onCreate`. */
    public fun installLogging(isDebuggable: Boolean) {
        VeyroLog.minLevel = if (isDebuggable) LogLevel.DEBUG else LogLevel.WARN
        VeyroLog.install(CompositeLogSink(AndroidLogSink, logRecorder))
    }

    /**
     * Collects the facts worth knowing when something does not work.
     *
     * Nothing secret appears here - this snapshot is built to be pasted into a bug report, so the
     * seed, pairing secrets and session keys must never reach it (AGENTS.md).
     */
    public suspend fun diagnostics(): Diagnostics {
        val identity = runCatching { identityManager.currentIdentity() }.getOrNull()
        val peers = runCatching { trustStore.snapshot() }.getOrDefault(emptyList())
        val network = runCatching { networkMonitor.currentStatus() }.getOrNull()
        val addresses = runCatching { localAddressProvider.localAddresses() }.getOrDefault(emptyList())
        val window = discoveryManager.windowState.value

        return Diagnostics.builder()
            .section("App") {
                fact("Package", appContext.packageName)
                fact("Protocol version", Vlp.VERSION.toString())
            }
            .section("Device") {
                fact("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                fact("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            }
            .section("Identity") {
                if (identity == null) {
                    problem("State", "not loaded")
                } else {
                    fact("Device ID", identity.deviceId.value)
                    fact("Fingerprint", identity.deviceId.fingerprint())
                }
            }
            .section("Network") {
                if (network == null) {
                    problem("State", "unknown")
                } else {
                    fact("Transport", network.transport.name)
                    fact("Usable", network.hasUsableNetwork.toString(), isProblem = !network.hasUsableNetwork)
                    fact("Hotspot active", network.isHotspotActive.toString())
                    fact("VPN active", network.isVpnActive.toString(), isProblem = network.isVpnActive)
                    fact("Diagnosis", network.diagnosis.name, isProblem = network.diagnosis.isNetworkProblem)
                }
            }
            .section("Interfaces") {
                if (addresses.isEmpty()) {
                    problem("Usable addresses", "none")
                } else {
                    for (a in addresses) {
                        val advertisable = InterfaceClassifier.isAdvertisable(a.kind)
                        fact(
                            "${a.interfaceName} (${a.kind})",
                            "${a.address}/${a.prefixLength}" +
                                (if (advertisable) "" else "  not advertised") +
                                (if (a.isLinkLocal) "  link-local" else ""),
                            isProblem = a.isLinkLocal,
                        )
                    }
                }
            }
            .section("Discovery") {
                fact("Window", if (window.isOpen) "open (${window.reason})" else "closed")
                fact(
                    "Listening port",
                    if (connectionManager.listeningPort != 0) connectionManager.listeningPort.toString() else "not listening",
                )
                fact(
                    "Multicast lock",
                    (discoveryManager as? DiscoveryManagerImpl)?.isMulticastLockHeld()?.toString() ?: "unknown",
                )
                fact("Peers visible", discoveryManager.peers.value.size.toString())
            }
            .section("Storage") {
                fact("Files dir", storageDir.absolutePath)
                fact("Identity file", File(storageDir, "identity.bin").existsLabel())
                fact("Trust file", File(storageDir, "trust.cbor").existsLabel())
                fact("Settings file", File(storageDir, "settings.json").existsLabel())
                fact("Free space", "${storageDir.usableSpace / (1024 * 1024)} MiB")
            }
            .section("Trust") {
                fact("Paired devices", peers.count { !it.revoked }.toString())
                fact("Revoked", peers.count { it.revoked }.toString())
                fact("Auto-accept enabled", peers.count { it.autoAcceptTransfers }.toString())
            }
            .build()
    }

    private fun File.existsLabel(): String =
        if (exists()) "present (${length()} bytes)" else "absent"

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        return when {
            model.isEmpty() -> "Android device"
            // Many devices already include the manufacturer in MODEL; avoid "Samsung Samsung S24".
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isEmpty() -> model
            else -> "$manufacturer $model"
        }
    }
}
