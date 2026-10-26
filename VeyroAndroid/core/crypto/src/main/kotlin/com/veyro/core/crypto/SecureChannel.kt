package com.veyro.core.crypto

/**
 * The Noise handshake state machine and the transport cipher state that follows it.
 *
 * Implementation is deferred to roadmap phase P3, and it is gated on the conditions in ADR-004:
 * official Noise test vectors, negative tests, and independent review of the state machine. Do
 * not implement this from memory - work from the Noise specification and the vectors in
 * `protocol/testvectors/noise/`.
 */
public interface HandshakeEngine {

    /** Starts a `Noise_KK` handshake with an already-paired peer (REQ-S01). */
    public fun beginSession(peerStaticPublicKey: ByteArray, initiator: Boolean): Handshake

    /**
     * Starts a `Noise_XK` handshake for pairing.
     *
     * The initiator must already know [peerStaticPublicKey] out of band - from the scanned QR
     * code. That out-of-band step is the entire reason an active attacker on the LAN cannot
     * MITM the pairing (05-SECURITY.md, adversary A2/A5).
     */
    public fun beginPairing(peerStaticPublicKey: ByteArray, initiator: Boolean): Handshake
}

/** One in-progress handshake. Single-use; not thread-safe. */
public interface Handshake {
    public val isComplete: Boolean

    /** Produces the next handshake message, or null when there is nothing more to send. */
    public fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray?

    /** Consumes a handshake message from the peer. */
    public fun readMessage(message: ByteArray): ByteArray

    /** Only valid once [isComplete]. */
    public fun split(): TransportCiphers
}

/**
 * The two one-way cipher states produced by a completed handshake, plus the transcript-derived
 * values the rest of the protocol needs.
 */
public interface TransportCiphers {
    public val sessionId: ByteArray

    /** Six digits shown on both screens during pairing (04-PROTOCOL.md section 4.3). */
    public val shortAuthString: String

    /** Derived from the transcript; stored in the `TrustedPeer` record. */
    public val pairingSecret: ByteArray

    public fun encrypt(plaintext: ByteArray): ByteArray
    public fun decrypt(ciphertext: ByteArray): ByteArray
}