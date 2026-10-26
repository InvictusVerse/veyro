package com.veyro.core.service

/** Builds and updates the notifications the foreground services show. No business logic. */
public interface NotificationDelegate {
    public fun showDiscoverable()
    public fun hideDiscoverable()
    public fun showTransferProgress(transferredBytes: Long, totalBytes: Long)
    public fun showTransferComplete(fileName: String)
    public fun showIncomingOffer(peerName: String, fileCount: Int, totalBytes: Long)
}