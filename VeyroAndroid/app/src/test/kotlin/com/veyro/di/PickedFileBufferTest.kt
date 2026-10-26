package com.veyro.di

import com.veyro.core.link.OutgoingFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PickedFileBufferTest {

    private fun testFile(name: String, size: Long = 100L): OutgoingFile = OutgoingFile(
        effectiveName = name,
        sizeBytes = size,
        lastModifiedEpochMillis = 1700000000L,
        openStream = { ByteArrayInputStream(ByteArray(size.toInt())) },
    )

    @Test
    fun `initially empty`() {
        val buffer = PickedFileBuffer()
        assertTrue(buffer.files.value.isEmpty())
    }

    @Test
    fun `add appends files to buffer`() {
        val buffer = PickedFileBuffer()
        val f1 = testFile("doc1.pdf", 500)
        val f2 = testFile("img2.png", 1200)

        buffer.add(listOf(f1))
        assertEquals(1, buffer.files.value.size)
        assertEquals("doc1.pdf", buffer.files.value[0].effectiveName)

        buffer.add(listOf(f2))
        assertEquals(2, buffer.files.value.size)
        assertEquals("doc1.pdf", buffer.files.value[0].effectiveName)
        assertEquals("img2.png", buffer.files.value[1].effectiveName)
    }

    @Test
    fun `removeAt removes correct element and ignores invalid index`() {
        val buffer = PickedFileBuffer()
        val f1 = testFile("a.txt")
        val f2 = testFile("b.txt")
        val f3 = testFile("c.txt")

        buffer.add(listOf(f1, f2, f3))
        assertEquals(3, buffer.files.value.size)

        // Out of bounds remove is a no-op
        buffer.removeAt(-1)
        buffer.removeAt(99)
        assertEquals(3, buffer.files.value.size)

        // Remove middle item
        buffer.removeAt(1)
        assertEquals(2, buffer.files.value.size)
        assertEquals("a.txt", buffer.files.value[0].effectiveName)
        assertEquals("c.txt", buffer.files.value[1].effectiveName)
    }

    @Test
    fun `clear resets files to empty`() {
        val buffer = PickedFileBuffer()
        buffer.add(listOf(testFile("a.txt"), testFile("b.txt")))
        assertEquals(2, buffer.files.value.size)

        buffer.clear()
        assertTrue(buffer.files.value.isEmpty())
    }
}
