package com.veyro.core.link

import com.veyro.core.model.Capabilities
import com.veyro.core.model.Capability
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-056: phone-to-phone pairing. The phone that accepts sends pairOk, switches to the session
 * key and sends its Hello at once; the phone that dialled saves the pairing first and only then
 * switches. Its read loop used to read that Hello under the old key and drop it, so the dialling
 * phone never learnt what the other one supports ("No device connected" for shared text).
 */
public class PairingRekeyTest {

    private val bootstrap = FrameCrypto.sessionKeyFromBytes(ByteArray(32) { 1 })
    private val sessionSecret = ByteArray(32) { 2 }

    private fun pairingPair(): Pair<PeerConnection, PeerConnection> {
        val toAcceptor = Channel<Frame>(Channel.UNLIMITED)
        val toDialler = Channel<Frame>(Channel.UNLIMITED)
        val diallerWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = toDialler
            override val outgoing: SendChannel<Frame> = toAcceptor
        }
        val acceptorWs = object : TestWebSocketSession() {
            override val incoming: ReceiveChannel<Frame> = toAcceptor
            override val outgoing: SendChannel<Frame> = toDialler
        }
        fun session(ws: TestWebSocketSession, local: String, peer: String) = PeerConnection(
            ws = ws,
            initialKey = bootstrap,
            initialEncrypt = false,
            localDeviceId = local,
            tag = "Pairing-$local",
            dispatcher = Dispatchers.IO,
            peerDeviceId = peer,
            enableHeartbeat = false,
        )
        val dialler = session(diallerWs, "dialler", "acceptor").apply { start() }
        val acceptor = session(acceptorWs, "acceptor", "dialler").apply { start() }
        return dialler to acceptor
    }

    private val acceptorCaps = Capabilities.of(Capability.FILE_TRANSFER, Capability.CLIPBOARD, Capability.MEDIA_CONTROL)

    @Test
    fun `the Hello sent right after pairOk reaches a dialler that rekeys late`() = runBlocking {
        val (dialler, acceptor) = pairingPair()

        // The accepting phone, as finishInboundPairing does it: pairOk, rekey, Hello, no pause.
        acceptor.sendSealed(PeerMessage.PairOk("acceptor", "Main phone", "android", "c2VjcmV0", null))
        acceptor.rekey(FrameCrypto.sessionKeyFromBytes(sessionSecret))
        acceptor.send(PeerMessage.Hello("acceptor", "Main phone", "android", acceptorCaps.bits))

        // The dialling phone: takes the pairOk, saves the pairing (slow on an older phone), rekeys.
        val pairOk = withTimeout(5_000) { dialler.receiveHandshake(5_000) }
        assertTrue(pairOk?.payload is PeerMessage.PairOk)
        delay(300)
        dialler.rekey(FrameCrypto.sessionKeyFromBytes(sessionSecret))

        val learnt = withTimeoutOrNull(5_000) { dialler.peerCapabilities.first { it != Capabilities.NONE } }
        assertNotNull("the dialler learnt the acceptor's capabilities", learnt)
        assertEquals(acceptorCaps, learnt)
        assertTrue(Capability.CLIPBOARD in learnt!!)

        dialler.close()
        acceptor.close()
    }

    @Test
    fun `a session that never sees pairOk reads straight through`() = runBlocking {
        val (dialler, acceptor) = pairingPair()

        acceptor.send(PeerMessage.Hello("acceptor", "Main phone", "android", acceptorCaps.bits))

        val learnt = withTimeoutOrNull(5_000) { dialler.peerCapabilities.first { it != Capabilities.NONE } }
        assertEquals(acceptorCaps, learnt)
        dialler.close()
        acceptor.close()
    }
}
