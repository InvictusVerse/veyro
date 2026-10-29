package com.veyro.di

import com.veyro.core.common.AppDispatchers
import com.veyro.core.common.VeyroLog
import com.veyro.core.crypto.IdentityManager
import com.veyro.core.data.TrustStore
import com.veyro.core.link.ConnectionManager
import com.veyro.core.model.TrustedPeer
import kotlinx.coroutines.CoroutineScope

/**
 * Builds [ConnectionManager] and supplies it with identity, trust and naming.
 *
 * Lives in `:app` because it is the only layer allowed to know about every module at once.
 * `:core:link` declares what it needs as constructor lambdas, so it never holds the
 * `IdentityManager` or the `TrustStore` (it uses `:core:crypto` only for the pure confirm-pairing
 * derivations, ADR-031).
 */
internal object ConnectionWiring {

    private const val TAG = "ConnectionWiring"

    fun create(
        dispatchers: AppDispatchers,
        scope: CoroutineScope,
        identityManager: IdentityManager,
        trustStore: TrustStore,
        deviceName: suspend () -> String,
        /** ADR-052: one small file holding the listening port of the previous run. */
        listenPortFile: java.io.File? = null,
    ): ConnectionManager = ConnectionManager(
        dispatchers = dispatchers,
        scope = scope,
        localDeviceId = { identityManager.ensureIdentity().deviceId.value },
        localDeviceName = deviceName,
        localPublicKey = { identityManager.ensureIdentity().staticPublicKey },
        trustedPeer = { raw ->
            val id = com.veyro.core.model.DeviceId.parseOrNull(raw)
            if (id == null) null else trustStore.find(id)?.takeUnless { it.revoked }
        },
        saveTrustedPeer = { peer: TrustedPeer ->
            // Trust is only ever created here, after a completed pairing exchange.
            // Do not swallow exceptions: persistence failure must abort the pairing exchange.
            trustStore.put(peer)
        },
        // Confirm pairing (ADR-031): DH plus HKDF inside the identity; the private key stays there.
        deriveSharedKey = { peerPk, salt, info -> identityManager.deriveSharedKey(peerPk, salt, info) },
        // ADR-042: removing a device on either side removes it on both.
        forgetTrustedPeer = { raw ->
            com.veyro.core.model.DeviceId.parseOrNull(raw)?.let { trustStore.remove(it) }
        },
        listenPortStore = listenPortFile?.let(::FileListenPortStore) ?: ConnectionManager.ListenPortStore.NONE,
    )

    /** The port as decimal text. A missing or unreadable file means "no preference". */
    private class FileListenPortStore(private val file: java.io.File) : ConnectionManager.ListenPortStore {
        override fun load(): Int = runCatching { file.readText().trim().toInt() }.getOrDefault(0)

        override fun save(port: Int) {
            if (port <= 0 || load() == port) return
            runCatching { file.writeText(port.toString()) }
                .onFailure { VeyroLog.w(TAG) { "Listening port not saved: ${it.javaClass.simpleName}" } }
        }
    }
}

/** Legacy alias for backwards compatibility. */
internal typealias LinkWiring = ConnectionWiring
