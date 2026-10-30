package com.veyro.core.link.media

import com.veyro.core.link.PeerMessageJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Finds and parses a file under `protocol/testvectors`, walking up from the module directory. */
internal object MediaVectors {

    fun load(relative: String): JsonObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val file = File(dir, "protocol/testvectors/$relative")
            if (file.isFile) return PeerMessageJson.codec.parseToJsonElement(file.readText()).jsonObject
            dir = dir.parentFile
        }
        error("protocol/testvectors/$relative not found above ${System.getProperty("user.dir")}")
    }

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i -> text.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
