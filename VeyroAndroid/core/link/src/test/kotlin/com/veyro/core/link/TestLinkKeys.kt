package com.veyro.core.link

import com.veyro.core.crypto.ConnectionKeySchedule
import com.veyro.core.crypto.EphemeralKey
import com.veyro.core.crypto.LinkKeys
import com.veyro.core.crypto.LinkRole
import com.veyro.core.protocol.tcp.Vtc3
import com.veyro.core.protocol.tcp.Vtc3Channel
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/**
 * Test fixtures for link protocol v3 (ADR-058).
 *
 * [sessionPair] runs the real [ConnectionKeySchedule.session] for both roles with two fresh
 * [EphemeralKey]s and fixed preamble/ack bytes, so tests get the same keys a real handshake would
 * give without a WebSocket handshake.
 */
internal object TestLinkKeys {

    val SECRET: ByteArray get() = ByteArray(32) { 42 }

    private val PREAMBLE = "{\"test\":\"preamble\"}".toByteArray()
    private val ACK = "{\"test\":\"ack\"}".toByteArray()

    private val FIXED_EXP_SECRET = ByteArray(32) { 0x5A }

    /** A VTC3 key deriver both ends of a direct data-socket test share (a fixed exporter secret). */
    fun deriver(transferId: String): (ByteArray, ByteArray) -> ByteArray =
        Vtc3.keyDeriver({ label, context, length -> ConnectionKeySchedule.export(FIXED_EXP_SECRET, label, context, length) }, transferId)

    /** (initiator keys, responder keys) for one fresh connection between [idI] and [idR]. */
    fun sessionPair(idI: String, idR: String, secret: ByteArray = SECRET): Pair<LinkKeys, LinkKeys> {
        val eI = EphemeralKey.generate()
        val eR = EphemeralKey.generate()
        val transcript = ConnectionKeySchedule.transcript(PREAMBLE, ACK)
        val dhI = eI.agree(eR.publicKey)
        val dhR = eR.agree(eI.publicKey)
        eI.wipe()
        eR.wipe()
        return ConnectionKeySchedule.session(LinkRole.INITIATOR, idI, idR, transcript, dhI, secret) to
            ConnectionKeySchedule.session(LinkRole.RESPONDER, idI, idR, transcript, dhR, secret)
    }
}

/**
 * One connection's keys handed out to its two ends: the first caller of [forLocal] is the
 * initiator. Lets a test wire two [PeerConnection]s with keys from one real key exchange.
 */
internal class TestKeyPairing(private val secret: ByteArray = TestLinkKeys.SECRET) {
    private var initiatorId: String? = null
    private var keys: Pair<LinkKeys, LinkKeys>? = null

    @Synchronized
    fun forLocal(localId: String, peerId: String): LinkKeys {
        val pair = keys ?: TestLinkKeys.sessionPair(localId, peerId, secret).also {
            keys = it
            initiatorId = localId
        }
        return if (localId == initiatorId) pair.first else pair.second
    }
}

/**
 * A v3 peer played by a test on raw frames (no read loop of its own): builds the preamble or the
 * ack with fresh `cn`/`epk`, derives this connection's keys from the exact bytes, and seals or
 * opens envelopes with them (04-PROTOCOL.md 4, 5).
 */
internal class V3TestPeer(val localId: String) {
    private val eph = EphemeralKey.generate()
    private val cn = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    private var preambleBytes: ByteArray? = null
    var keys: LinkKeys? = null
        private set
    private var sealer: SessionSealer? = null
    private var opener: SessionOpener? = null

    val publicEpk: ByteArray get() = eph.publicKey

    /** The dialler's preamble; its bytes are kept for the transcript. */
    fun preamble(
        name: String = "Office PC",
        platform: String = "windows",
        pair: String? = null,
        pk: ByteArray? = null,
        protocolVersion: Int = LINK_PROTOCOL_VERSION,
    ): Frame.Text {
        val bytes = PeerMessageJson.codec.encodeToString(
            LinkPreamble.serializer(),
            LinkPreamble(localId, name, platform, protocolVersion, pair, pk?.let(FrameCrypto::b64), FrameCrypto.b64(cn), FrameCrypto.b64(eph.publicKey)),
        ).toByteArray()
        preambleBytes = bytes
        return Frame.Text(true, bytes)
    }

    /** Dialler side: the ack arrived; returns it and, for a session ack, installs the session keys. */
    fun readAck(frame: Frame, peerId: String, secret: ByteArray?): LinkPreambleAck {
        val ackBytes = (frame as Frame.Text).data.copyOf()
        val ack = PeerMessageJson.codec.decodeFromString(LinkPreambleAck.serializer(), String(ackBytes))
        if (ack.mode == LinkPreambleAck.MODE_SESSION && secret != null) {
            val transcript = ConnectionKeySchedule.transcript(preambleBytes!!, ackBytes)
            val dh = eph.agree(FrameCrypto.decodeKey32(ack.epk)!!)
            install(ConnectionKeySchedule.session(LinkRole.INITIATOR, localId, peerId, transcript, dh, secret))
        }
        return ack
    }

    /** Responder side: answers [preamble] in session mode; returns the ack frame to deliver. */
    fun answerSession(preamble: Frame, secret: ByteArray): Frame.Text {
        val p = (preamble as Frame.Text).data.copyOf()
        val parsed = PeerMessageJson.codec.decodeFromString(LinkPreamble.serializer(), String(p))
        val ackBytes = PeerMessageJson.codec.encodeToString(
            LinkPreambleAck.serializer(),
            LinkPreambleAck(LinkPreambleAck.MODE_SESSION, cn = FrameCrypto.b64(cn), epk = FrameCrypto.b64(eph.publicKey)),
        ).toByteArray()
        val transcript = ConnectionKeySchedule.transcript(p, ackBytes)
        val dh = eph.agree(FrameCrypto.decodeKey32(parsed.epk)!!)
        install(ConnectionKeySchedule.session(LinkRole.RESPONDER, parsed.id, localId, transcript, dh, secret))
        return Frame.Text(true, ackBytes)
    }

    fun install(linkKeys: LinkKeys) {
        keys = linkKeys
        val initiator = linkKeys.role == LinkRole.INITIATOR
        sealer = SessionSealer(linkKeys.sendKey, linkKeys.localId, if (initiator) FrameCrypto.DIR_I2R else FrameCrypto.DIR_R2I)
        opener = SessionOpener(linkKeys.recvKey, linkKeys.peerId, if (initiator) FrameCrypto.DIR_R2I else FrameCrypto.DIR_I2R)
    }

    /** The next sealed frame this side sends. */
    fun seal(message: PeerMessage): Frame.Text {
        val s = sealer!!
        val envelope = LinkEnvelope(
            msgId = java.util.UUID.randomUUID().toString(),
            from = localId,
            seq = s.nextCounter + 1,
            ts = System.currentTimeMillis(),
            payload = message,
        )
        return Frame.Text(s.seal(PeerMessageJson.codec.encodeToString(LinkEnvelope.serializer(), envelope)))
    }

    /** Opens the next frame from the peer; throws if it does not authenticate. */
    fun open(frame: Frame): LinkEnvelope =
        PeerMessageJson.codec.decodeFromString(LinkEnvelope.serializer(), opener!!.open((frame as Frame.Text).readText()))
}

/**
 * Starts nothing: both connections must already be started. Exchanges a `hello` each way, waits
 * until both are established and admits both, which is what `ConnectionManager` does before a
 * session carries anything else (04-PROTOCOL.md 4.4).
 */
internal suspend fun establishPair(a: PeerConnection, b: PeerConnection) {
    coroutineScope {
        launch { a.send(PeerMessage.Hello(a.localDeviceId, "A", "android")) }
        launch { b.send(PeerMessage.Hello(b.localDeviceId, "B", "android")) }
    }
    check(a.awaitEstablished() && b.awaitEstablished()) { "test pair did not establish" }
    a.admit()
    b.admit()
}

/**
 * Plays the other side of a VTC3 data socket in tests (04-PROTOCOL.md 7.3), with the exporter of
 * a session that shares the real session's keys.
 */
internal object Vtc3TestPeer {
    fun asSender(input: InputStream, output: OutputStream, transferId: String, exporter: PeerConnection): Vtc3Channel =
        Vtc3.openAsSender(input, output, transferId, Vtc3.keyDeriver(exporter::exportKey, transferId))

    fun asReceiver(input: InputStream, output: OutputStream, transferId: String, exporter: PeerConnection): Vtc3Channel =
        Vtc3.openAsReceiver(input, output, transferId, Vtc3.keyDeriver(exporter::exportKey, transferId))
}
