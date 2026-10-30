package com.veyro.core.link

import com.veyro.core.common.AppDispatchers
import com.veyro.core.model.TransferRoute
import com.veyro.core.protocol.tcp.TcpFrame
import com.veyro.core.protocol.tcp.Vtc3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * [TcpDataSender] fault handling on the VTC3 data socket (ADR-058, review issues 5 and 7), against
 * a receiver played on a raw socket.
 */
class TcpDataSenderFaultTest {

    private val dispatchers = object : AppDispatchers {
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
        override val main = Dispatchers.Default
    }

    /**
     * A receiver that accepts, answers stream-open and AuthAck, then runs [afterAuth] with the raw
     * output stream and the sealed writer.
     */
    private fun fakeReceiver(
        server: ServerSocket,
        tid: String,
        afterAuth: (BufferedOutputStream, com.veyro.core.protocol.tcp.Vtc3Channel) -> Unit,
    ): Thread = kotlin.concurrent.thread(name = "fake-vtc3-receiver") {
        server.accept().use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val channel = Vtc3.openAsReceiver(input, output, tid, TestLinkKeys.deriver(tid))
            channel.reader.readOrNull()
            channel.writer.write(TcpFrame.AuthAck(0L, tid, ok = true, reason = null))
            afterAuth(output, channel)
            runCatching { while (input.read() >= 0) Unit }
        }
    }

    @Test
    fun `a feedback record that fails authentication closes the data socket at once (review issue 5)`() = runBlocking {
        val tid = "tid-feedback-tamper"
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receiver = fakeReceiver(server, tid) { output, _ ->
            // An ItemResult record with a valid header (seq 0, 32 bytes) and garbage ciphertext:
            // its tag cannot match.
            val t = tid.toByteArray()
            val header = java.io.ByteArrayOutputStream().apply {
                write("VTC3".toByteArray())
                write(0x06)
                write(0); write(t.size)
                write(t)
                write(ByteArray(8))
                write(byteArrayOf(0, 0, 0, 32))
            }.toByteArray()
            output.write(header)
            output.write(ByteArray(32) { 0x5A })
            output.flush()
        }
        val sender = TcpDataSender(dispatchers)
        sender.connectAndAuthenticate("127.0.0.1", server.localPort, tid, "token", "sender-dev", TransferRoute.LAN_TCP, TestLinkKeys.deriver(tid))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fault = kotlinx.coroutines.CompletableDeferred<Throwable>()
        val reader = sender.startInboundReader(scope, onFault = { fault.complete(it) }) { }
        withTimeout(5_000) { reader.join() }
        // A typed failure reaches the transfer at once (TransferRunner fails the item with it).
        org.junit.Assert.assertTrue(withTimeout(5_000) { fault.await() } is com.veyro.core.protocol.AeadStreamException)
        // The fault is terminal: the socket is closed, so the next write fails at once.
        withTimeout(5_000) { while (sender.isConnected) delay(20) }
        assertFalse(sender.isConnected)
        assertThrows(IOException::class.java) {
            runBlocking { sender.sendItemBegin(tid, 0, 10L, 1, 256 * 1024) }
        }
        scope.cancel()
        sender.close()
        receiver.join(5_000)
        server.close()
    }

    @Test
    fun `close after closeSocketImmediately still wipes and drops the channel (review issue 7)`() = runBlocking {
        val tid = "tid-close-cleanup"
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val receiver = fakeReceiver(server, tid) { _, _ -> }
        val sender = TcpDataSender(dispatchers)
        sender.connectAndAuthenticate("127.0.0.1", server.localPort, tid, "token", "sender-dev", TransferRoute.LAN_TCP, TestLinkKeys.deriver(tid))
        sender.closeSocketImmediately()
        sender.close("send finished")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // The channel (and with it both framers) is gone: there is nothing left to read with.
        assertThrows(IOException::class.java) { sender.startInboundReader(scope) { } }
        scope.cancel()
        receiver.join(5_000)
        server.close()
    }
}
