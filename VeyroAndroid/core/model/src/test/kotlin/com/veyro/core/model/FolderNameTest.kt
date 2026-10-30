package com.veyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADR-064: when a transfer reads as one folder. */
class FolderNameTest {

    private fun file(path: String) = TransferItem(0, path, 1, 0)
    private fun dir(path: String) = TransferItem(0, path, 0, 0, isDirectory = true)

    @Test
    fun `items under one top folder are that folder`() {
        assertEquals("Trip", listOf(file("Trip/a.jpg"), file("Trip/Day 2/b.jpg"), dir("Trip/Empty")).folderName())
    }

    @Test
    fun `loose files, a mix or two folders are not one folder`() {
        assertNull(listOf(file("a.jpg"), file("b.jpg")).folderName())
        assertNull(listOf(file("Trip/a.jpg"), file("b.jpg")).folderName())
        assertNull(listOf(file("Trip/a.jpg"), file("Work/b.jpg")).folderName())
        assertNull(listOf(file("Tripod.jpg"), file("Trip/a.jpg")).folderName())
        assertNull(emptyList<TransferItem>().folderName())
    }
}
