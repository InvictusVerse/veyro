package com.veyro.core.crypto

import com.veyro.core.common.Bytes
import java.security.SecureRandom

/**
 * The Noise handshake patterns Veyro uses.
 *
 * ```
 * Noise_KK:                        Noise_XK:
 *   -> s                             <- s
 *   <- s                             ...
 *   ...                              -> e, es
 *   -> e, es, ss                     <- e, ee
 *   <- e, ee, se                     -> s, se
 * ```
 *
 * **KK** is the normal case: both peers already know each other's static key from pairing, so one
 * round trip gives mutual authentication with forward secrecy and neither side ever transmits its
 * identity. **XK** is used for pairing, where the initiator learned the responder's static key out
 * of band from the QR code and sends its own encrypted - which is precisely why an active attacker
 * on the LAN cannot MITM the pairing.
 *
 * ## State machine shape
 *
 * Deliberately one small class with an explicit message index rather than a chain of lambdas. A
 * handshake is a sequence with exactly one legal next step, and making that index visible is what
 * lets [writeMessage] and [readMessage] refuse to be called out of order. A reordered or replayed
 * message must fail, not silently advance.
 *
 * ## ADR-004 status: UNPROVEN
 *
 * This is first-party code over vetted primitives. It has **not** been validated against the
 * official Noise test vectors, which is a gate that must close before 1.0. The tests that exist
 * cover the properties checkable without published vectors - both sides derive the same keys and
 * the same SAS, tampering fails, replay fails, a wrong static key fails, out-of-order fails. That
 * is necessary and not sufficient, and `12-TESTING.md` section 3 records it as such.
 */
public class NoiseHandshake private constructor(
    private val pattern: Pattern,
    private val isInitiator: Boolean,
    private val localStaticPrivate: ByteArray,
    private val localStaticPublic: ByteArray,
    private val remoteStaticPublic: ByteArray?,
    private val random: SecureRandom,
) : Handshake {

    public enum class Pattern(internal val protocolName: String) {
        /** Both static keys known. The paired-session case. */
        KK(NoiseConstants.PROTOCOL_NAME_KK),

        /** Responder's static known to the initiator only. The pairing case. */
        XK(NoiseConstants.PROTOCOL_NAME_XK),
    }

    private val symmetric = SymmetricState(pattern.protocolName)

    private var localEphemeralPrivate: ByteArray? = null
    private var localEphemeralPublic: ByteArray? = null
    private var remoteEphemeralPublic: ByteArray? = null
    private var learnedRemoteStatic: ByteArray? = remoteStaticPublic?.copyOf()

    private var messageIndex = 0
    private var complete = false
    private var ciphers: Pair<CipherState, CipherState>? = null

    override val isComplete: Boolean get() = complete

    /** Available after completion; for XK this is what the initiator's static key turned out to be. */
    public val remoteStaticKey: ByteArray? get() = learnedRemoteStatic?.copyOf()

    public companion object {
        /** Total messages in each pattern. Both are two-message handshakes. */
        private const val MESSAGE_COUNT = 2

        /**
         * Starts a handshake.
         *
         * @param prologue mixed into the transcript before anything else, so protocol version and
         * mode cannot be tampered with (04-PROTOCOL.md section 4.1). A mismatched prologue makes
         * the handshake fail rather than succeed with different parameters, which is what closes
         * the downgrade path.
         */
        public fun start(
            pattern: Pattern,
            isInitiator: Boolean,
            localStaticPrivate: ByteArray,
            remoteStaticPublic: ByteArray?,
            prologue: ByteArray,
            random: SecureRandom = SecureRandom(),
        ): NoiseHandshake {
            require(localStaticPrivate.size == X25519.KEY_SIZE) { "static private key must be 32 bytes" }
            if (pattern == Pattern.KK) {
                requireNotNull(remoteStaticPublic) { "KK requires the peer's static public key" }
            }
            if (pattern == Pattern.XK && isInitiator) {
                requireNotNull(remoteStaticPublic) {
                    "XK initiator must know the responder's static key out of band"
                }
            }
            remoteStaticPublic?.let {
                require(it.size == X25519.KEY_SIZE) { "static public key must be 32 bytes" }
            }

            val handshake = NoiseHandshake(
                pattern = pattern,
                isInitiator = isInitiator,
                localStaticPrivate = localStaticPrivate.copyOf(),
                localStaticPublic = X25519.publicKeyOf(localStaticPrivate),
                remoteStaticPublic = remoteStaticPublic,
                random = random,
            )
            handshake.initialize(prologue)
            return handshake
        }
    }

    /**
     * Mixes the prologue and the pre-message static keys into the transcript.
     *
     * Pre-message ordering is initiator-static then responder-static, from each party's own point
     * of view. Getting this the wrong way round yields two transcripts that differ, so the
     * handshake fails cleanly rather than succeeding insecurely - but it would fail against every
     * other implementation too, which is exactly what the test vectors would catch.
     */
    private fun initialize(prologue: ByteArray) {
        symmetric.mixHash(prologue)

        when (pattern) {
            Pattern.KK -> {
                // -> s, <- s : both statics are pre-messages.
                if (isInitiator) {
                    symmetric.mixHash(localStaticPublic)
                    symmetric.mixHash(remoteStaticPublic!!)
                } else {
                    symmetric.mixHash(remoteStaticPublic!!)
                    symmetric.mixHash(localStaticPublic)
                }
            }

            Pattern.XK -> {
                // <- s : only the responder's static is a pre-message.
                if (isInitiator) {
                    symmetric.mixHash(remoteStaticPublic!!)
                } else {
                    symmetric.mixHash(localStaticPublic)
                }
            }
        }
    }

    override fun writeMessage(payload: ByteArray): ByteArray? {
        check(!complete) { "handshake already complete" }
        val ourTurn = (messageIndex % 2 == 0) == isInitiator
        check(ourTurn) { "not this side's turn to write (message $messageIndex)" }

        val out = when (pattern) {
            Pattern.KK -> if (messageIndex == 0) writeKkFirst(payload) else writeKkSecond(payload)
            Pattern.XK -> if (messageIndex == 0) writeXkFirst(payload) else writeXkSecond(payload)
        }
        advance()
        return out
    }

    override fun readMessage(message: ByteArray): ByteArray {
        check(!complete) { "handshake already complete" }
        val ourTurn = (messageIndex % 2 == 0) != isInitiator
        check(ourTurn) { "not this side's turn to read (message $messageIndex)" }

        val out = when (pattern) {
            Pattern.KK -> if (messageIndex == 0) readKkFirst(message) else readKkSecond(message)
            Pattern.XK -> if (messageIndex == 0) readXkFirst(message) else readXkSecond(message)
        }
        advance()
        return out
    }

    // ── KK ─────────────────────────────────────────────────────────────────────────────────
    // -> e, es, ss

    private fun writeKkFirst(payload: ByteArray): ByteArray {
        val e = generateEphemeral()
        symmetric.mixHash(e.second)
        symmetric.mixKey(X25519.agree(e.first, remoteStaticPublic!!))        // es
        symmetric.mixKey(X25519.agree(localStaticPrivate, remoteStaticPublic)) // ss
        return e.second + symmetric.encryptAndHash(payload)
    }

    private fun readKkFirst(message: ByteArray): ByteArray {
        val re = takeEphemeral(message)
        symmetric.mixHash(re)
        remoteEphemeralPublic = re
        symmetric.mixKey(X25519.agree(localStaticPrivate, re))                 // es
        symmetric.mixKey(X25519.agree(localStaticPrivate, remoteStaticPublic!!)) // ss
        return symmetric.decryptAndHash(message.copyOfRange(X25519.KEY_SIZE, message.size))
    }

    // <- e, ee, se

    private fun writeKkSecond(payload: ByteArray): ByteArray {
        val e = generateEphemeral()
        symmetric.mixHash(e.second)
        symmetric.mixKey(X25519.agree(e.first, remoteEphemeralPublic!!))   // ee
        symmetric.mixKey(X25519.agree(e.first, remoteStaticPublic!!))      // se
        return e.second + symmetric.encryptAndHash(payload)
    }

    private fun readKkSecond(message: ByteArray): ByteArray {
        val re = takeEphemeral(message)
        symmetric.mixHash(re)
        remoteEphemeralPublic = re
        symmetric.mixKey(X25519.agree(localEphemeralPrivate!!, re))        // ee
        symmetric.mixKey(X25519.agree(localStaticPrivate, re))             // se
        return symmetric.decryptAndHash(message.copyOfRange(X25519.KEY_SIZE, message.size))
    }

    // ── XK ─────────────────────────────────────────────────────────────────────────────────
    // -> e, es

    private fun writeXkFirst(payload: ByteArray): ByteArray {
        val e = generateEphemeral()
        symmetric.mixHash(e.second)
        symmetric.mixKey(X25519.agree(e.first, remoteStaticPublic!!))      // es
        return e.second + symmetric.encryptAndHash(payload)
    }

    private fun readXkFirst(message: ByteArray): ByteArray {
        val re = takeEphemeral(message)
        symmetric.mixHash(re)
        remoteEphemeralPublic = re
        symmetric.mixKey(X25519.agree(localStaticPrivate, re))             // es
        return symmetric.decryptAndHash(message.copyOfRange(X25519.KEY_SIZE, message.size))
    }

    // <- e, ee   /   -> s, se  (the third message is folded into completion below)

    private fun writeXkSecond(payload: ByteArray): ByteArray {
        val e = generateEphemeral()
        symmetric.mixHash(e.second)
        symmetric.mixKey(X25519.agree(e.first, remoteEphemeralPublic!!))   // ee
        return e.second + symmetric.encryptAndHash(payload)
    }

    private fun readXkSecond(message: ByteArray): ByteArray {
        val re = takeEphemeral(message)
        symmetric.mixHash(re)
        remoteEphemeralPublic = re
        symmetric.mixKey(X25519.agree(localEphemeralPrivate!!, re))        // ee
        return symmetric.decryptAndHash(message.copyOfRange(X25519.KEY_SIZE, message.size))
    }

    // ── shared ─────────────────────────────────────────────────────────────────────────────

    private fun advance() {
        messageIndex++
        if (messageIndex >= MESSAGE_COUNT) {
            complete = true
            ciphers = symmetric.split()
        }
    }

    private fun generateEphemeral(): Pair<ByteArray, ByteArray> {
        val priv = X25519.generatePrivateKey(random)
        val pub = X25519.publicKeyOf(priv)
        localEphemeralPrivate = priv
        localEphemeralPublic = pub
        return priv to pub
    }

    private fun takeEphemeral(message: ByteArray): ByteArray {
        // A message shorter than one public key plus a tag cannot be well-formed. Checking before
        // slicing is what keeps this a total function on hostile input.
        require(message.size >= X25519.KEY_SIZE + NoiseConstants.TAG_LEN) {
            "handshake message too short: ${message.size} bytes"
        }
        return message.copyOfRange(0, X25519.KEY_SIZE)
    }

    override fun split(): TransportCiphers {
        check(complete) { "handshake is not complete" }
        val (first, second) = ciphers!!
        // The initiator writes with the first cipher and reads with the second; the responder is
        // the mirror image. Swapping these is the classic way to get a handshake that works only
        // against itself.
        val send = if (isInitiator) first else second
        val receive = if (isInitiator) second else first
        return NoiseTransportCiphers(
            handshakeHash = symmetric.handshakeHash(),
            sendCipher = send,
            receiveCipher = receive,
        )
    }

    /** Wipes ephemeral material. Call after a failed or abandoned handshake. */
    public fun destroy() {
        Bytes.wipe(localEphemeralPrivate, localStaticPrivate)
        localEphemeralPrivate = null
        symmetric.clear()
    }
}

/** The transport phase: two one-way ciphers plus the transcript-derived values. */
internal class NoiseTransportCiphers(
    private val handshakeHash: ByteArray,
    private val sendCipher: CipherState,
    private val receiveCipher: CipherState,
) : TransportCiphers {

    override val sessionId: ByteArray = VlpKdf.sessionId(handshakeHash)

    override val shortAuthString: String = VlpKdf.shortAuthString(handshakeHash)

    override val pairingSecret: ByteArray = VlpKdf.pairingSecret(handshakeHash)

    override fun encrypt(plaintext: ByteArray): ByteArray =
        sendCipher.encryptWithAd(ByteArray(0), plaintext)

    override fun decrypt(ciphertext: ByteArray): ByteArray =
        receiveCipher.decryptWithAd(ByteArray(0), ciphertext)

    /** The raw transcript hash. Exposed for diagnostics and for binding higher-level state. */
    internal fun transcript(): ByteArray = handshakeHash.copyOf()
}

/** Default [HandshakeEngine] backed by [NoiseHandshake]. */
public class NoiseHandshakeEngine(
    private val staticPrivateKey: () -> ByteArray,
    private val random: SecureRandom = SecureRandom(),
) : HandshakeEngine {

    override fun beginSession(peerStaticPublicKey: ByteArray, initiator: Boolean): Handshake =
        NoiseHandshake.start(
            pattern = NoiseHandshake.Pattern.KK,
            isInitiator = initiator,
            localStaticPrivate = staticPrivateKey(),
            remoteStaticPublic = peerStaticPublicKey,
            prologue = VlpPrologue.session(),
            random = random,
        )

    override fun beginPairing(peerStaticPublicKey: ByteArray, initiator: Boolean): Handshake =
        NoiseHandshake.start(
            pattern = NoiseHandshake.Pattern.XK,
            isInitiator = initiator,
            localStaticPrivate = staticPrivateKey(),
            remoteStaticPublic = peerStaticPublicKey,
            prologue = VlpPrologue.pairing(),
            random = random,
        )
}

/**
 * The prologue bytes.
 *
 * Mixed into the transcript before anything else, so the protocol version and the mode are bound
 * to the handshake. An attacker who rewrites either gets a transcript mismatch and a failed
 * handshake, rather than a successful one with weaker parameters (04-PROTOCOL.md section 4.1).
 */
public object VlpPrologue {

    public fun session(): ByteArray = build(com.veyro.core.protocol.Vlp.MODE_SESSION)

    public fun pairing(): ByteArray = build(com.veyro.core.protocol.Vlp.MODE_PAIRING)

    private fun build(mode: Byte): ByteArray {
        val prefix = com.veyro.core.protocol.Vlp.PROLOGUE_PREFIX.toByteArray(Charsets.US_ASCII)
        return prefix + byteArrayOf(com.veyro.core.protocol.Vlp.VERSION.toByte(), mode)
    }
}
