package com.veyro.core.network

/**
 * Resolved link details when a Wi-Fi Direct (P2P) group is established.
 */
public data class WifiDirectLinkInfo(
    public val groupOwnerAddress: String,
    public val localAddress: String,
    public val isGroupOwner: Boolean,
    public val networkName: String? = null,
    public val passphrase: String? = null,
    public val interfaceName: String? = null,
)

/**
 * Interface managing Wi-Fi Direct (WifiP2pManager) lifecycle, group negotiation,
 * and reachable IP address resolution.
 *
 * Designed to allow clean mocking and JVM unit testing of transport fallback logic.
 */
public interface WifiDirectManager {
    public val isSupported: Boolean
    public val isEnabled: Boolean
    public val isAvailable: Boolean get() = isSupported && isEnabled

    /**
     * Prepares a Wi-Fi Direct link for high-speed file transfer.
     *
     * @param isInitiator true if this device is initiating the group/connection.
     * @return [WifiDirectLinkInfo] if the P2P group and network link are established; null otherwise.
     */
    public suspend fun prepareP2pLink(isInitiator: Boolean): WifiDirectLinkInfo?

    /**
     * Prepares or joins a Wi-Fi Direct link with target network credentials.
     */
    public suspend fun prepareP2pLink(
        isInitiator: Boolean,
        targetSsid: String? = null,
        targetPassphrase: String? = null,
        targetPeerMac: String? = null,
    ): WifiDirectLinkInfo? = prepareP2pLink(isInitiator)

    /**
     * Tears down any active P2P group and releases resources.
     */
    public suspend fun closeP2pLink()
}
