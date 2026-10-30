package com.veyro.core.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Loads the shared vector files under `protocol/testvectors` (ADR-058, FR-T2). */
internal object V3Vectors {
    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "protocol/testvectors/link/connection-schedule-v3.json").isFile) dir = dir.parentFile
        File(requireNotNull(dir) { "protocol/testvectors not found above user.dir" }, "protocol/testvectors")
    }

    fun load(relative: String): JsonObject = Json.parseToJsonElement(File(root, relative).readText()).jsonObject

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
        ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.bytes(key: String): ByteArray = V3Vectors.unhex(str(key))
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.num(key: String): Long = getValue(key).jsonPrimitive.long
