package com.veyro.core.model

import kotlinx.serialization.Serializable

/**
 * Observable transport network route used for raw TCP file transfers.
 *
 * [WIFI_DIRECT] represents high-speed peer-to-peer Wi-Fi Direct link.
 * [LAN_TCP] represents raw TCP sockets over the local Wi-Fi or hotspot network.
 */
@Serializable
public enum class TransferRoute {
    WIFI_DIRECT,
    LAN_TCP,
}
