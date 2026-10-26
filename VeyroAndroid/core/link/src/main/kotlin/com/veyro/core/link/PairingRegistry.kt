package com.veyro.core.link

import com.veyro.core.common.VeyroLog
import javax.crypto.SecretKey

/**
 * In-memory registry of open pairing windows.
 *
 * ## Provenance
 *
 * A port of `services/ecosystem/pairing/PairingManager.kt` from the author's Invox code, kept close
 * to the original because it is small, complete and correct.
 *
 * ## What it is defending against
 *
 * A 6-digit PIN is only 20 bits. Three things together make that acceptable, and removing any one
 * of them breaks it:
 *
 *  1. **PBKDF2 at 120k iterations** makes each guess expensive for an offline attacker who captured
 *     the exchange.
 *  2. **[MAX_ATTEMPTS] verification failures burn the window**, so an online attacker gets five
 *     tries, not a million.
 *  3. **[PIN_TTL_MS] expiry** bounds how long the target even exists.
 *
 * ## The PIN is never logged
 *
 * logcat is readable over ADB and by any privileged process on the device. Logging the PIN would
 * hand an observer the exact secret the handshake is protected by, so every log line here refers to
 * the device name instead. This is the one rule in this file that must not be relaxed for
 * debugging - use the fingerprint if you need to correlate.
 */
public class PairingRegistry {

    public companion object {
        private const val TAG = "PairingRegistry"

        /** How long a PIN stays valid. */
        public const val PIN_TTL_MS: Long = 60_000L

        /** Verification failures before the window is burned. */
        public const val MAX_ATTEMPTS: Int = 5
    }

    public data class Window(
        val peerDeviceId: String,
        val peerDeviceName: String,
        val pin: String,
        val bootstrapKey: SecretKey,
        val expiresAt: Long,
        var attempts: Int = 0,
    )

    private val windows = HashMap<String, Window>()

    /**
     * Opens a window for an inbound pairing attempt and mints the PIN the user will read aloud.
     *
     * The salt order is **(initiator, target)** - here the peer is the initiator and we are the
     * target. The initiator must derive with the same order via [deriveInitiatorKey], or both sides
     * produce different keys and every frame fails its MAC, which is indistinguishable from a wrong
     * PIN.
     */
    @Synchronized
    public fun openInbound(
        peerDeviceId: String,
        peerDeviceName: String,
        localDeviceId: String,
    ): Window {
        val pin = FrameCrypto.newPin()
        val window = Window(
            peerDeviceId = peerDeviceId,
            peerDeviceName = peerDeviceName,
            pin = pin,
            bootstrapKey = FrameCrypto.deriveBootstrapKey(pin, peerDeviceId, localDeviceId),
            expiresAt = System.currentTimeMillis() + PIN_TTL_MS,
        )
        windows[peerDeviceId] = window
        // Deliberately does not include the PIN. See the class note.
        VeyroLog.i(TAG) { "Pairing window opened for $peerDeviceName" }
        return window
    }

    /** The window for a peer, or null if it never existed, expired, or was burned. */
    @Synchronized
    public fun lookup(peerDeviceId: String): Window? {
        val window = windows[peerDeviceId] ?: return null
        if (System.currentTimeMillis() > window.expiresAt) {
            windows.remove(peerDeviceId)
            VeyroLog.i(TAG) { "Pairing window expired" }
            return null
        }
        if (window.attempts >= MAX_ATTEMPTS) {
            windows.remove(peerDeviceId)
            VeyroLog.w(TAG) { "Pairing window burned after $MAX_ATTEMPTS failed attempts" }
            return null
        }
        return window
    }

    @Synchronized
    public fun recordAttempt(peerDeviceId: String) {
        windows[peerDeviceId]?.let { it.attempts += 1 }
    }

    @Synchronized
    public fun close(peerDeviceId: String) {
        windows.remove(peerDeviceId)
    }

    @Synchronized
    public fun closeAll(): Unit = windows.clear()

    @Synchronized
    public fun hasOpenWindow(): Boolean {
        val now = System.currentTimeMillis()
        return windows.values.any { now <= it.expiresAt && it.attempts < MAX_ATTEMPTS }
    }

    /**
     * The initiator's side: derive the same bootstrap key from the PIN the user typed.
     *
     * Must be called with **(ourId, peerId)** because we are the initiator and the salt convention
     * is (initiator, target).
     */
    public fun deriveInitiatorKey(
        pin: String,
        localDeviceId: String,
        peerDeviceId: String,
    ): SecretKey = FrameCrypto.deriveBootstrapKey(pin, localDeviceId, peerDeviceId)
}

/** Legacy alias for backwards compatibility. */
public typealias PairingWindowRegistry = PairingRegistry
