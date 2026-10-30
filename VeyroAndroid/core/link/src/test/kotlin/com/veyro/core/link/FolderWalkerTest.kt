package com.veyro.core.link

import com.veyro.core.protocol.RelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** ADR-064: a picked folder becomes the items of one transfer. */
class FolderWalkerTest {

    /** A tree in memory: directory id to its entries; file id to its bytes. */
    private class FakeTree : DocumentTree {
        val dirs = HashMap<String, MutableList<DocumentEntry>>()
        val data = HashMap<String, ByteArray>()
        val unreadable = HashSet<String>()

        fun dir(parent: String?, id: String, name: String = id): String {
            dirs.getOrPut(id) { mutableListOf() }
            parent?.let { dirs.getValue(it) += DocumentEntry(id, name, isDirectory = true, sizeBytes = null, lastModifiedEpochMillis = 1L) }
            return id
        }

        fun file(parent: String, id: String, name: String, bytes: ByteArray = id.toByteArray(), size: Long? = bytes.size.toLong(), virtual: Boolean = false) {
            data[id] = bytes
            dirs.getValue(parent) += DocumentEntry(id, name, isDirectory = false, sizeBytes = size, lastModifiedEpochMillis = 2L, isVirtual = virtual)
        }

        override fun children(documentId: String): List<DocumentEntry> {
            if (documentId in unreadable) throw SecurityException("no access")
            return dirs[documentId] ?: throw IOException("not a directory")
        }

        override fun open(documentId: String): InputStream = ByteArrayInputStream(data.getValue(documentId))
    }

    private fun FolderWalk.paths() = items.map { (it.relativePath ?: "") + if (it.isDirectory) "/" else "" }

    @Test
    fun `nested files keep their paths under the folder name and empty directories become items`() {
        val tree = FakeTree()
        val root = tree.dir(null, "root")
        tree.file(root, "a", "a.txt")
        val day = tree.dir(root, "day", "Day 2")
        tree.file(day, "b", "b.jpg")
        tree.dir(root, "empty", "Empty")
        val deep = tree.dir(day, "deep", "deep")
        tree.dir(deep, "deeper", "deeper")

        val walk = FolderWalker.walk(tree, root, "Trip")

        assertEquals("Trip", walk.name)
        // Files first, then the empty directories.
        assertEquals(listOf("Trip/a.txt", "Trip/Day 2/b.jpg", "Trip/Day 2/deep/deeper/", "Trip/Empty/"), walk.paths())
        assertEquals(2, walk.fileCount)
        assertEquals(2, walk.emptyDirectoryCount)
        assertEquals(0, walk.skipped)
        assertEquals("a".length + "b".length.toLong(), walk.totalBytes)
        assertTrue("folder files are streamed, never staged", walk.items.filterNot { it.isDirectory }.all { it.streamed })
        assertEquals("b", String(walk.items[1].openStream().readBytes()))
    }

    @Test
    fun `unreadable, virtual and unsized entries are left out and counted`() {
        val tree = FakeTree()
        val root = tree.dir(null, "root")
        tree.file(root, "ok", "ok.txt")
        tree.file(root, "doc", "Notes", virtual = true)
        tree.file(root, "nosize", "stream.bin", size = null)
        val locked = tree.dir(root, "locked", "Locked")
        tree.unreadable += locked

        val walk = FolderWalker.walk(tree, root, "Mixed")

        assertEquals(listOf("Mixed/ok.txt"), walk.paths())
        assertEquals(3, walk.skipped)
    }

    @Test
    fun `names are sanitised and names equal ignoring case are made distinct`() {
        val tree = FakeTree()
        val root = tree.dir(null, "root")
        tree.file(root, "1", "Photo.JPG")
        tree.file(root, "2", "photo.jpg")
        tree.file(root, "3", "a:b?.txt")
        tree.file(root, "4", "CON.txt")
        tree.file(root, "5", "trailing. ")

        val walk = FolderWalker.walk(tree, root, "bad|name.")

        val paths = walk.paths()
        assertEquals("bad_name", walk.name)
        assertTrue(paths.toString(), "bad_name/Photo.JPG" in paths || "bad_name/photo.jpg" in paths)
        assertTrue(paths.toString(), paths.any { it == "bad_name/photo (2).jpg" || it == "bad_name/Photo (2).JPG" })
        assertTrue("bad_name/a_b_.txt" in paths)
        assertTrue("bad_name/_CON.txt" in paths)
        assertTrue("bad_name/trailing" in paths)
        assertTrue("every path passes the receiver's rules", paths.all { RelativePath.isValid(it.removeSuffix("/")) })
    }

    @Test
    fun `paths the receiver would reject are left out instead of sent`() {
        val tree = FakeTree()
        val root = tree.dir(null, "root")
        // 70 levels: past the 64-segment limit (04-PROTOCOL.md section 8).
        var parent = root
        repeat(70) { level -> parent = tree.dir(parent, "d$level", "d") }
        tree.file(parent, "deepfile", "x.txt")
        tree.file(root, "long", "n".repeat(1100) + ".txt")
        tree.file(root, "fine", "fine.txt")

        val walk = FolderWalker.walk(tree, root, "Top")

        assertEquals(listOf("Top/fine.txt"), walk.paths().filterNot { it.endsWith("/") })
        assertEquals(1, walk.fileCount)
        assertTrue("the too-long name and the too-deep entry are counted", walk.skipped >= 2)
        assertTrue(walk.paths().all { RelativePath.isValid(it.removeSuffix("/")) })
    }

    @Test
    fun `a folder past the item limit is refused whole`() {
        val tree = FakeTree()
        val root = tree.dir(null, "root")
        repeat(FolderWalker.MAX_ITEMS + 1) { tree.file(root, "f$it", "f$it.txt") }

        val walk = FolderWalker.walk(tree, root, "Huge")

        assertTrue(walk.tooLarge)
        assertTrue("nothing is sent", walk.items.isEmpty())
        assertEquals(0, walk.fileCount)
    }

    @Test
    fun `a directory listed inside itself is walked once`() {
        val tree = FakeTree()
        val root = tree.dir(null, "root")
        tree.file(root, "a", "a.txt")
        tree.dirs.getValue(root) += DocumentEntry(root, "loop", isDirectory = true, sizeBytes = null, lastModifiedEpochMillis = 0L)

        val walk = FolderWalker.walk(tree, root, "Loop")

        assertEquals(listOf("Loop/a.txt"), walk.paths())
    }

    @Test
    fun `an empty or unreadable folder has nothing to send`() {
        val empty = FakeTree().apply { dir(null, "root") }
        assertEquals(0, FolderWalker.walk(empty, "root", "Empty").fileCount)

        val locked = FakeTree().apply { dir(null, "root"); unreadable += "root" }
        val walk = FolderWalker.walk(locked, "root", "Locked")
        assertEquals(0, walk.fileCount)
        assertEquals(1, walk.skipped)
        assertFalse(walk.tooLarge)
    }

    @Test
    fun `folder wording`() {
        assertEquals("Photos folder, 120 files", folderSummary("Photos", 120))
        assertEquals("Photos folder, 1 file", folderSummary("Photos", 1))
    }
}
