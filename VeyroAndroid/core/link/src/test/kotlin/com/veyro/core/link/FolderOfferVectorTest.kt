package com.veyro.core.link

import com.veyro.core.model.folderName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "offer of a folder" in `protocol/testvectors/link/control-messages.json` (ADR-064). The Windows
 * suite round-trips the same vector; this checks Android reads it the same way and writes it back
 * with `isDir` on every item.
 */
class FolderOfferVectorTest {

    private val vector =
        """{"t":"offer","transferId":"tx-2","items":[{"index":0,"relPath":"Trip/a.jpg","size":1024,"mtime":1760000000000,"isDir":false},""" +
            """{"index":1,"relPath":"Trip/Day 2/b.jpg","size":2048,"mtime":1760000000000,"isDir":false},""" +
            """{"index":2,"relPath":"Trip/Empty","size":0,"mtime":1760000000000,"isDir":true}],""" +
            """"totalBytes":3072,"senderName":"Test PC","preferredRoutes":["LAN_TCP"]}"""

    @Test
    fun `vector - offer of a folder`() {
        val offer = PeerMessageJson.codec.decodeFromString<PeerMessage>(vector) as PeerMessage.TransferOffer

        assertEquals(listOf("Trip/a.jpg", "Trip/Day 2/b.jpg", "Trip/Empty"), offer.items.map { it.relPath })
        assertEquals(listOf(false, false, true), offer.items.map { it.isDir })
        assertEquals(3072L, offer.totalBytes)
        assertEquals("Trip", offer.items.map { it.toDomain() }.folderName())

        val text = PeerMessageJson.codec.encodeToString<PeerMessage>(offer)
        val encoded = PeerMessageJson.codec.parseToJsonElement(text) as JsonObject
        val items = encoded.getValue("items") as JsonArray
        assertEquals(listOf(false, false, true), items.map { (it as JsonObject).getValue("isDir").jsonPrimitive.boolean })
    }
}
