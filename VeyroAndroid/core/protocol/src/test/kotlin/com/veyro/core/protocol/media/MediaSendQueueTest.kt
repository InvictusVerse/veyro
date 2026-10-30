package com.veyro.core.protocol.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's send queue (04-PROTOCOL.md 7.5.6, ADR-066). */
class MediaSendQueueTest {

    private fun config(generation: Long = 1) = Vms1.VideoConfig(Vms1.VIDEO_CODEC_H264, 832, 1920, 0, 0, 832, 1920, 0, false, generation)

    private fun MediaSendQueue.frame(): MediaSendQueue.VideoItem.Frame = takeVideo(0) as MediaSendQueue.VideoItem.Frame

    @Test
    fun `a full queue drops, waits for a keyframe, and marks the next frame`() {
        val queue = MediaSendQueue(maxUnits = 3, maxBytes = 1_000_000)
        queue.offerConfig(config())
        assertTrue(queue.offerUnit(0, ByteArray(10), 10, key = true))
        assertTrue(queue.offerUnit(1, ByteArray(10), 10, key = false))
        assertTrue(queue.offerUnit(2, ByteArray(10), 10, key = false))
        assertFalse("a fourth unit does not fit", queue.offerUnit(3, ByteArray(10), 10, key = false))
        assertTrue(queue.takeVideo(0) is MediaSendQueue.VideoItem.Config)
        assertTrue(queue.frame().key)
        assertFalse("room again, but the decoder needs a keyframe first", queue.offerUnit(4, ByteArray(10), 10, key = false))
        assertTrue(queue.offerUnit(5, ByteArray(10), 10, key = true))
        assertEquals(2L, queue.droppedUnits)
        queue.frame()
        queue.frame()
        val next = queue.frame()
        assertEquals(5L, next.ptsUs)
        assertTrue("the receiver learns frames were dropped", next.discontinuity)
        assertTrue(queue.offerUnit(6, ByteArray(10), 10, key = false))
        assertFalse(queue.frame().discontinuity)
        assertNull(queue.takeVideo(0))
    }

    @Test
    fun `bytes count too, but one large unit always fits an empty queue`() {
        val queue = MediaSendQueue(maxUnits = 3, maxBytes = 100)
        assertTrue(queue.offerUnit(0, ByteArray(500), 500, key = true))
        assertFalse(queue.offerUnit(1, ByteArray(10), 10, key = false))
        queue.frame()
        assertTrue(queue.offerUnit(2, ByteArray(60), 60, key = true))
        assertFalse(queue.offerUnit(3, ByteArray(60), 60, key = true))
    }

    @Test
    fun `configs are never dropped`() {
        val queue = MediaSendQueue(maxUnits = 1, maxBytes = 100)
        assertTrue(queue.offerUnit(0, ByteArray(10), 10, key = true))
        queue.offerConfig(config(2))
        assertFalse(queue.offerUnit(1, ByteArray(10), 10, key = true))
        queue.frame()
        assertEquals(2L, (queue.takeVideo(0) as MediaSendQueue.VideoItem.Config).config.generation)
    }

    @Test
    fun `audio keeps at most 200 ms, oldest dropped`() {
        val queue = MediaSendQueue()
        val packet = 48_000 / 100 * 4 // 10 ms of 48 kHz stereo s16
        queue.offerAudioConfig(Vms1.AudioConfig(2, 48_000))
        for (i in 0 until 30) queue.offerAudio(i * 10_000L, ByteArray(packet), packet)
        assertTrue(queue.takeAudio(0) is MediaSendQueue.AudioItem.Config)
        val first = queue.takeAudio(0) as MediaSendQueue.AudioItem.Samples
        assertEquals("the oldest 10 packets went", 100_000L, first.ptsUs)
        var left = 1
        while (queue.takeAudio(0) != null) left++
        assertEquals(20, left)
    }

    @Test
    fun `close wakes the writers and drops everything`() {
        val queue = MediaSendQueue()
        queue.offerUnit(0, ByteArray(1), 1, key = true)
        queue.close()
        assertNull(queue.takeVideo(1_000))
        assertNull(queue.takeAudio(1_000))
        assertFalse(queue.offerUnit(1, ByteArray(1), 1, key = true))
    }
}
