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
 * `:core:link` declares what it needs as constructor lambdas, so it depends on neither
 * `:core:data` nor `:core:crypto`.
 */
internal object ConnectionWiring {

    private const val TAG = "ConnectionWiring"

    fun create(
        dispatchers: AppDispatchers,
        scope: CoroutineScope,
        identityManager: IdentityManager,
        trustStore: TrustStore,
        deviceName: suspend () -> String,
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
    )
}

/** Legacy alias for backwards compatibility. */
internal typealias LinkWiring = ConnectionWiring
