package com.veyro.ui.components

import com.veyro.ui.format.FileKind
import com.veyro.ui.format.destinationLabel
import com.veyro.ui.format.fileKindOf
import com.veyro.ui.format.formatCountdown
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The pure parts of the incoming-file prompt: type icons, destination wording, countdown. */
class IncomingFilesSheetModelTest {

    @Test
    fun `file kinds come from the extension, case-insensitively`() {
        assertEquals(FileKind.IMAGE, fileKindOf("IMG_2041.JPG"))
        assertEquals(FileKind.IMAGE, fileKindOf("photo.heic"))
        assertEquals(FileKind.VIDEO, fileKindOf("clip.mp4"))
        assertEquals(FileKind.AUDIO, fileKindOf("voice.m4a"))
        assertEquals(FileKind.DOCUMENT, fileKindOf("report-final.pdf"))
        assertEquals(FileKind.ARCHIVE, fileKindOf("backup.tar.gz"))
        assertEquals(FileKind.OTHER, fileKindOf("README"))
        assertEquals(FileKind.OTHER, fileKindOf("data.bin"))
    }

    @Test
    fun `destination follows the media store folders the publisher uses`() {
        assertEquals("Pictures/Veyro", destinationLabel(listOf("a.jpg", "b.png"), null))
        assertEquals("Download/Veyro", destinationLabel(listOf("a.pdf"), null))
        assertEquals("Pictures/Veyro and Download/Veyro", destinationLabel(listOf("a.jpg", "b.zip"), null))
        assertEquals(
            "Pictures/Veyro, Movies/Veyro and Music/Veyro",
            destinationLabel(listOf("a.jpg", "b.mp4", "c.mp3"), null),
        )
        assertEquals("Download/Veyro", destinationLabel(emptyList(), null))
        assertEquals("Inbox", destinationLabel(listOf("a.jpg"), File("/storage/emulated/0/Inbox")))
    }

    @Test
    fun `countdown reads as minutes and seconds and speaks only at a few moments`() {
        assertEquals("1:00", formatCountdown(60))
        assertEquals("0:42", formatCountdown(42))
        assertEquals("0:00", formatCountdown(-3))
        val spoken = (60L downTo 0L).map { countdownAnnouncement(it) }.distinct()
        assertEquals(4, spoken.size)
    }
}
