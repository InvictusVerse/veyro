package com.veyro.core.link.media

import com.veyro.core.protocol.AeadStreamException
import com.veyro.core.protocol.media.Vms1
import com.veyro.core.protocol.media.Vms1Channel
import com.veyro.core.protocol.media.Vms1Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The phone's VMS1 handshake over a real loopback socket, against a PC side written here the
 * way 04-PROTOCOL.md 7.5.2 describes it (the real one is Windows `Vms1Listener`).
 */
class Vms1ChannelTest {

    private val phoneId = "0123456789ABCDEFGHJKMNPQRS"
    private val pcId = "PCPCPCPCPCPCPCPCPCPCPCPCPC"
    private val sid = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val pool = Executors.newCachedThreadPool()

    /** A stand-in for the session exporter: both ends of one control session share it. */
    private fun exporter(secret: ByteArray): (String, ByteArray, Int) -> ByteArray = { label, ctx, len ->
        val a = MessageDigest.getInstance("SHA-256").digest(secret + label.toByteArray() + byteArrayOf(1) + ctx)
        val b = MessageDigest.getInstance("SHA-256").digest(secret + label.toByteArray() + byteArrayOf(2) + ctx)
        (a + b).copyOf(len)
    }

    private class PcResult(val channel: Vms1Channel, val auth: Vms1.Auth)

    private fun pcAccept(server: ServerSocket, export: (String, ByteArray, Int) -> ByteArray, expectedSid: ByteArray): Future<PcResult> = pool.submit<PcResult> {
        val socket = server.accept().apply { soTimeout = 10_000 }
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val preface = ByteArray(Vms1.PREFACE_SIZE)
        var n = 0
        while (n < preface.size) { val r = input.read(preface, n, preface.size - n); if (r < 0) throw IOException("eof"); n += r }
        val phone = Vms1.parsePreface(preface)
        if (!phone.sid.contentEquals(expectedSid)) { socket.close(); throw AeadStreamException("no such session") }
        val saltPc = ByteArray(16).also { SecureRandom().nextBytes(it) }
        output.write(Vms1.encodePreface(phone.source, phone.sid, saltPc))
        output.flush()
        val okm = export(Vms1.exportLabel(phone.source), Vms1.exportContext(phone.source, phone.sid, phone.salt, saltPc), Vms1.OKM_SIZE)
        val channel = Vms1Channel.fromOkm(input, output, okm, phone = false)
        val auth = (channel.reader.read() as Vms1Frame.Auth).auth
        channel.writer.writeAuthAck()
        PcResult(channel, auth)
    }

    @Test
    fun `phone and PC agree on keys, ids and the session, then media flows`() {
        val secret = ByteArray(32) { 7 }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val pc = pcAccept(server, exporter(secret), sid)
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                socket.soTimeout = 10_000
                socket.tcpNoDelay = true
                val phone = Vms1Channel.connectAsPhone(socket.getInputStream(), socket.getOutputStream(), Vms1.SOURCE_SCREEN, sid, phoneId, pcId, exporter(secret))
                val result = pc.get(10, TimeUnit.SECONDS)
                assertEquals(phoneId, result.auth.phoneId)
                assertEquals(pcId, result.auth.pcId)
                assertArrayEquals(sid, result.auth.sid)

                val config = Vms1.VideoConfig(Vms1.VIDEO_CODEC_H265, 1088, 1920, 0, 0, 1088, 1920, 90, false, 1)
                phone.writer.writeVideoConfig(config)
                phone.writer.writeAudioConfig(Vms1.AudioConfig(channels = 2, rate = 48_000))
                val au = ByteArray(70_000) { it.toByte() }
                phone.writer.writeAccessUnit(1_000L, au, 0, au.size, key = true, discontinuity = false)
                phone.writer.writeAudio(1_500L, ByteArray(960 * 4), 0, 960 * 4)
                assertEquals(config, (result.channel.reader.read() as Vms1Frame.VideoConfig).config)
                assertTrue(result.channel.reader.read() is Vms1Frame.AudioConfig)
                val first = result.channel.reader.read() as Vms1Frame.Video
                assertEquals(1_000L, first.ptsUs)
                assertEquals(70_000, first.auLength)
                val second = result.channel.reader.read() as Vms1Frame.Video
                assertEquals(Vms1.FLAG_END, second.flags)
                assertEquals(70_000 - Vms1.MAX_FRAGMENT, second.data.size)
                assertEquals(1_500L, (result.channel.reader.read() as Vms1Frame.Audio).ptsUs)
                phone.close()
                result.channel.close()
            }
        }
    }

    @Test
    fun `a phone that holds another session's keys is not acknowledged`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val pc = pcAccept(server, exporter(ByteArray(32) { 1 }), sid)
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                socket.soTimeout = 10_000
                val phoneSide = pool.submit<Any> {
                    Vms1Channel.connectAsPhone(socket.getInputStream(), socket.getOutputStream(), Vms1.SOURCE_SCREEN, sid, phoneId, pcId, exporter(ByteArray(32) { 2 }))
                }
                try {
                    pc.get(10, TimeUnit.SECONDS)
                    fail("the PC accepted AUTH under other keys")
                } catch (e: java.util.concurrent.ExecutionException) {
                    assertTrue(e.cause is AeadStreamException)
                }
                socket.close()
                try {
                    phoneSide.get(10, TimeUnit.SECONDS)
                    fail("the phone thought it was acknowledged")
                } catch (_: java.util.concurrent.ExecutionException) {
                }
            }
        }
    }

    @Test
    fun `a PC preface for another session stops the phone before any key is used`() {
        val secret = ByteArray(32) { 3 }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val other = ByteArray(16) { 9 }
            val pc = pool.submit<Unit> {
                server.accept().use { s ->
                    val buf = ByteArray(Vms1.PREFACE_SIZE)
                    var n = 0
                    while (n < buf.size) { val r = s.getInputStream().read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    s.getOutputStream().write(Vms1.encodePreface(Vms1.SOURCE_SCREEN, other, ByteArray(16)))
                    s.getOutputStream().flush()
                    Thread.sleep(200)
                }
            }
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                socket.soTimeout = 10_000
                try {
                    Vms1Channel.connectAsPhone(socket.getInputStream(), socket.getOutputStream(), Vms1.SOURCE_SCREEN, sid, phoneId, pcId, exporter(secret))
                    fail("connected to another session")
                } catch (_: AeadStreamException) {
                }
            }
            pc.get(10, TimeUnit.SECONDS)
        }
    }
}
