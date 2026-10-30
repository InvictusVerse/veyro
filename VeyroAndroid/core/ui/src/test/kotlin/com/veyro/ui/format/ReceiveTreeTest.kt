package com.veyro.ui.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-072: the pure rules for receiving into the folder chosen in Settings. */
class ReceiveTreeTest {

    @Test
    fun `a loose file goes into its type folder inside the tree`() {
        assertEquals(listOf("Photos"), ReceiveTree.dirSegments("a.jpg", "image/jpeg"))
        assertEquals(listOf("Videos"), ReceiveTree.dirSegments("clip.mp4", null))
        assertEquals(listOf("Apps"), ReceiveTree.dirSegments("app.apk", null))
        assertEquals(listOf("Other"), ReceiveTree.dirSegments("notes.xyz", null))
    }

    @Test
    fun `an item of a received folder keeps the folder structure`() {
        assertEquals(listOf("Trip", "Day 1"), ReceiveTree.dirSegments("Trip/Day 1/a.jpg", "image/jpeg"))
        assertEquals(listOf("Trip"), ReceiveTree.dirSegments("Trip/notes.txt", null))
        assertEquals(listOf("Trip", "Empty"), ReceiveTree.segments("Trip/Empty"))
    }

    @Test
    fun `paths that could leave the tree are rejected`() {
        for (bad in listOf("../a.jpg", "Trip/../../a.jpg", "/etc/passwd", "Trip/./a.jpg", "a\\b.jpg", "", "Trip//a.jpg")) {
            assertNull("must reject '$bad'", ReceiveTree.dirSegments(bad, null))
            assertNull("must reject '$bad'", ReceiveTree.segments(bad))
        }
    }

    @Test
    fun `a clash picks the next free name and never the taken one`() {
        assertEquals("a.jpg", ReceiveTree.freeName("a.jpg", listOf("b.jpg")))
        assertEquals("a (2).jpg", ReceiveTree.freeName("a.jpg", listOf("a.jpg")))
        assertEquals("a (3).jpg", ReceiveTree.freeName("a.jpg", listOf("A.JPG", "a (2).jpg")))
        assertEquals("archive.tar (2).gz", ReceiveTree.freeName("archive.tar.gz", listOf("archive.tar.gz")))
        assertEquals("README (2)", ReceiveTree.freeName("README", listOf("readme")))
        assertEquals(".env (2)", ReceiveTree.freeName(".env", listOf(".env")))
    }

    @Test
    fun `the part name is the final name plus the suffix`() {
        assertEquals("a.jpg.veyropart", ReceiveTree.partName("a.jpg"))
    }

    @Test
    fun `the fallback decision`() {
        assertEquals(ReceiveTree.Target.DEFAULT, ReceiveTree.target(null, granted = false, reachable = false))
        assertEquals(ReceiveTree.Target.DEFAULT, ReceiveTree.target("", granted = true, reachable = true))
        assertEquals(ReceiveTree.Target.CHOSEN, ReceiveTree.target("content://x/tree/1", granted = true, reachable = true))
        assertEquals(ReceiveTree.Target.FALLBACK, ReceiveTree.target("content://x/tree/1", granted = false, reachable = true))
        assertEquals(ReceiveTree.Target.FALLBACK, ReceiveTree.target("content://x/tree/1", granted = true, reachable = false))
    }

    @Test
    fun `unknown free space never blocks`() {
        assertTrue(ReceiveTree.hasRoom(null, 10))
        assertTrue(ReceiveTree.hasRoom(-1, 10))
        assertTrue(ReceiveTree.hasRoom(10, 10))
        assertFalse(ReceiveTree.hasRoom(9, 10))
    }

    @Test
    fun `labels follow the chosen folder`() {
        assertEquals("Download/Veyro (default)", ReceiveTree.label(null))
        assertEquals("Inbox", ReceiveTree.label("Inbox"))
        assertTrue(ReceiveTree.receivedRowText(null).contains("Download/Veyro"))
        assertTrue(ReceiveTree.receivedRowText(ChosenReceiveFolder("content://x", "Inbox")).contains("in Inbox,"))
        assertEquals("Inbox (Photos and Videos)", destinationLabel(listOf("a.jpg", "b.mp4"), "Inbox"))
    }
}
