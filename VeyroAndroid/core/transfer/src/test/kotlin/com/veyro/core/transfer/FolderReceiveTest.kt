package com.veyro.core.transfer

import com.veyro.core.model.TransferItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ADR-064: a received folder keeps its structure, never mixes with a copy, never escapes. */
class FolderReceiveTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun file(index: Int, path: String, size: Long = 3) = TransferItem(index, path, size, 0L)
    private fun dir(index: Int, path: String) = TransferItem(index, path, 0, 0L, isDirectory = true)

    /** Records what reached the storage; [taken] are the top folders already there. */
    private class Recording(private val taken: Set<String> = emptySet()) : TransferStoragePublisher {
        val published = mutableListOf<String>()
        val directories = mutableListOf<String>()
        override suspend fun publish(sourceFile: File, relativePath: String, mimeType: String?, sizeBytes: Long): StoragePublishResult {
            published += relativePath
            return StoragePublishResult.Success("file:///$relativePath", relativePath.substringAfterLast('/'), "x", sizeBytes)
        }
        override suspend fun folderExists(name: String): Boolean = name.lowercase() in taken
        override suspend fun createDirectory(relativePath: String): Boolean {
            directories += relativePath
            return true
        }
    }

    @Test
    fun `a free folder name is kept and its structure passes through`() = runTest {
        val storage = Recording()
        val folders = FolderReceive(storage)
        folders.prepare(listOf(file(0, "Trip/a.jpg"), file(1, "Trip/Day 2/b.jpg"), dir(2, "Trip/Empty")))

        assertEquals("Trip", folders.folderName)
        folders.publish(File("x"), "Trip/Day 2/b.jpg", null, 3)
        assertEquals(0, folders.createEmptyDirectories())
        assertEquals(listOf("Trip/Day 2/b.jpg"), storage.published)
        assertEquals(listOf("Trip/Empty"), storage.directories)
    }

    @Test
    fun `a taken top folder gets the next free number for every item`() = runTest {
        val storage = Recording(taken = setOf("trip", "trip (2)"))
        val folders = FolderReceive(storage)
        folders.prepare(listOf(file(0, "Trip/a.jpg"), dir(1, "Trip/Empty")))

        assertEquals("Trip (3)", folders.folderName)
        assertEquals("Trip (3)/a.jpg", folders.savedPath("Trip/a.jpg"))
        folders.createEmptyDirectories()
        assertEquals(listOf("Trip (3)/Empty"), storage.directories)
    }

    @Test
    fun `a folder named like a type folder is renamed and loose files are left alone`() = runTest {
        val storage = Recording(taken = setOf("photos"))
        val folders = FolderReceive(storage)
        folders.prepare(listOf(file(0, "Photos/a.jpg"), file(1, "loose.txt")))

        assertNull("a mix is not one folder", folders.folderName)
        assertEquals("Photos (2)/a.jpg", folders.savedPath("Photos/a.jpg"))
        assertEquals("loose.txt", folders.savedPath("loose.txt"))
    }

    @Test
    fun `hostile paths are refused, never rewritten`() = runTest {
        val storage = Recording()
        val folders = FolderReceive(storage)
        val hostile = listOf(
            "Trip/../escape.txt", // traversal
            "/etc/passwd", // absolute
            "C:/Windows/x.dll", // drive letter
            "//server/share/x", // UNC
            "Trip\\..\\x", // backslash
            "Trip/CON/a.txt", // reserved name
            "Trip/a.txt:stream", // alternate data stream
            "Trip/" + "n".repeat(1100), // too long
            (listOf("Trip") + List(70) { "d" }).joinToString("/"), // too deep
        )
        folders.prepare(hostile.mapIndexed { i, p -> file(i, p) } + dir(99, "Trip/../../outside"))

        for (path in hostile) {
            assertNull(path, folders.savedPath(path))
            assertTrue(folders.publish(File("x"), path, null, 3) is StoragePublishResult.Failed)
            assertNull(folders.openDirect(path, null, 3))
            assertFalse(folders.createDirectory(path))
        }
        assertEquals("a directory that fails validation is not made", 0, folders.createEmptyDirectories())
        assertTrue(storage.published.isEmpty())
        assertTrue(storage.directories.isEmpty())
    }

    @Test
    fun `two folders of one offer never share a saved name`() = runTest {
        val storage = Recording(taken = setOf("a"))
        val folders = FolderReceive(storage)
        folders.prepare(listOf(file(0, "A/x"), file(1, "A (2)/y")))

        assertEquals("A (3)/x", folders.savedPath("A/x"))
        assertEquals("A (2)/y", folders.savedPath("A (2)/y"))
    }

    @Test
    fun `the filesystem storage keeps folders inside its root`() = runTest {
        val root = tempDir.newFolder("dest")
        val storage = FilesystemStoragePublisher(root)
        val source = tempDir.newFile("src").apply { writeText("abc") }

        val result = storage.publish(source, "Trip/Day 2/a.txt", null, 3)

        assertTrue(result is StoragePublishResult.Success)
        assertEquals("abc", File(root, "Trip/Day 2/a.txt").readText())
        assertTrue(storage.folderExists("Trip"))
        assertTrue(storage.createDirectory("Trip/Empty"))
        assertTrue(File(root, "Trip/Empty").isDirectory)
        assertFalse(storage.createDirectory("Trip/../../outside"))
        assertFalse(File(root.parentFile, "outside").exists())
        assertTrue(storage.publish(tempDir.newFile("evil"), "../evil.txt", null, 0) is StoragePublishResult.Failed)
    }
}
