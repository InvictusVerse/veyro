package com.veyro.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the path validator (REQ-T11).
 *
 * This is the file that stands between a hostile peer and an arbitrary file write, so it gets a
 * real corpus rather than a smoke test. Every rejection case asserts the *specific* reason, not
 * just "rejected", so a future refactor cannot accidentally reject things for the wrong cause
 * and still look green.
 */
class RelativePathTest {

    private fun reject(raw: String?): RelativePath.Result.Rejected {
        val result = RelativePath.validate(raw)
        assertTrue("expected rejection for <$raw> but got $result", result is RelativePath.Result.Rejected)
        return result as RelativePath.Result.Rejected
    }

    private fun accept(raw: String): RelativePath.Result.Valid {
        val result = RelativePath.validate(raw)
        assertTrue("expected <$raw> to be valid but got $result", result is RelativePath.Result.Valid)
        return result as RelativePath.Result.Valid
    }

    // ── Accepted ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `plain file name is accepted`() {
        assertEquals("photo.jpg", accept("photo.jpg").normalized)
    }

    @Test
    fun `nested path is accepted and keeps its segments`() {
        val valid = accept("Holiday/2026/DSC_0001.ARW")
        assertEquals(listOf("Holiday", "2026", "DSC_0001.ARW"), valid.segments)
    }

    @Test
    fun `unicode names are accepted`() {
        accept("Fotos/Grüße/日本語のファイル.txt")
        accept("emoji/🙂.png")
    }

    @Test
    fun `dot inside a name is fine`() {
        accept("archive.tar.gz")
        accept(".hidden")
        accept("folder.v2/file.txt")
    }

    @Test
    fun `name that merely starts with a reserved word is fine`() {
        // "console.log" is not "con".
        accept("console.log")
        accept("connection.json")
        accept("lpt10.txt")
    }

    // ── Traversal ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `parent directory segment is rejected`() {
        assertEquals(RelativePath.Reason.TRAVERSAL, reject("../secret").reason)
        assertEquals(RelativePath.Reason.TRAVERSAL, reject("a/../../etc/passwd").reason)
        assertEquals(RelativePath.Reason.TRAVERSAL, reject("a/b/..").reason)
    }

    @Test
    fun `dot dot inside a name is not traversal`() {
        // "..foo" and "foo..bar" are ordinary names; over-rejecting breaks real files.
        accept("..leading")
        accept("foo..bar")
    }

    @Test
    fun `current directory segment is rejected`() {
        assertEquals(RelativePath.Reason.CURRENT_DIR_SEGMENT, reject("./file").reason)
        assertEquals(RelativePath.Reason.CURRENT_DIR_SEGMENT, reject("a/./b").reason)
    }

    // ── Absolute and platform-qualified ────────────────────────────────────────────────────

    @Test
    fun `absolute posix path is rejected`() {
        assertEquals(RelativePath.Reason.ABSOLUTE, reject("/etc/passwd").reason)
    }

    @Test
    fun `drive letter is rejected`() {
        assertEquals(RelativePath.Reason.DRIVE_LETTER, reject("C:/Windows/System32/x.dll").reason)
        assertEquals(RelativePath.Reason.DRIVE_LETTER, reject("d:data.bin").reason)
    }

    @Test
    fun `unc path is rejected`() {
        assertEquals(RelativePath.Reason.UNC_PATH, reject("//server/share/file").reason)
    }

    @Test
    fun `backslash is rejected outright`() {
        assertEquals(RelativePath.Reason.BACKSLASH, reject("a\\b").reason)
        assertEquals(RelativePath.Reason.BACKSLASH, reject("..\\..\\windows").reason)
    }

    // ── Windows-specific hazards ───────────────────────────────────────────────────────────

    @Test
    fun `reserved device names are rejected with or without extension`() {
        for (name in listOf("CON", "con", "PRN", "aux", "NUL", "COM1", "lpt9")) {
            assertEquals(
                "expected $name to be reserved",
                RelativePath.Reason.RESERVED_NAME,
                reject(name).reason,
            )
            assertEquals(
                RelativePath.Reason.RESERVED_NAME,
                reject("$name.txt").reason,
            )
        }
    }

    @Test
    fun `reserved name nested in a directory is still rejected`() {
        assertEquals(RelativePath.Reason.RESERVED_NAME, reject("docs/nul.txt").reason)
    }

    @Test
    fun `trailing dot or space is rejected`() {
        assertEquals(RelativePath.Reason.TRAILING_DOT_OR_SPACE, reject("evil.").reason)
        assertEquals(RelativePath.Reason.TRAILING_DOT_OR_SPACE, reject("evil ").reason)
        assertEquals(RelativePath.Reason.TRAILING_DOT_OR_SPACE, reject("dir /file").reason)
    }

    @Test
    fun `ntfs alternate data stream is rejected`() {
        assertEquals(RelativePath.Reason.ALTERNATE_DATA_STREAM, reject("file.txt:evil").reason)
        assertEquals(RelativePath.Reason.ALTERNATE_DATA_STREAM, reject("docs/notes.txt:hidden").reason)
    }

    @Test
    fun `single letter followed by colon is classified as a drive letter not a stream`() {
        // "x:\$DATA" is ambiguous: it is both a drive-qualified path and an ADS reference.
        // The drive-letter check runs first, which is the right order - the more structural
        // rejection is the more informative one. Asserting the exact reason here pins that
        // ordering so a later refactor cannot silently flip it.
        assertEquals(RelativePath.Reason.DRIVE_LETTER, reject("x:\$DATA").reason)
    }

    @Test
    fun `windows forbidden characters are rejected`() {
        for (c in listOf('<', '>', '"', '|', '?', '*')) {
            assertEquals(
                "expected '$c' to be rejected",
                RelativePath.Reason.FORBIDDEN_CHARACTER,
                reject("bad${c}name").reason,
            )
        }
    }

    // ── Structural ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty and null are rejected`() {
        assertEquals(RelativePath.Reason.EMPTY, reject(null).reason)
        assertEquals(RelativePath.Reason.EMPTY, reject("").reason)
    }

    @Test
    fun `nul byte is rejected`() {
        assertEquals(RelativePath.Reason.CONTAINS_NUL, reject("safe.txt\u0000/../../x").reason)
    }

    @Test
    fun `control characters are rejected`() {
        assertEquals(RelativePath.Reason.CONTROL_CHARACTER, reject("new\nline.txt").reason)
        assertEquals(RelativePath.Reason.CONTROL_CHARACTER, reject("tab\there").reason)
        assertEquals(RelativePath.Reason.CONTROL_CHARACTER, reject("del\u007F").reason)
    }

    @Test
    fun `doubled separator is rejected`() {
        assertEquals(RelativePath.Reason.EMPTY_SEGMENT, reject("a//b").reason)
    }

    @Test
    fun `trailing separator is rejected`() {
        assertEquals(RelativePath.Reason.EMPTY_SEGMENT, reject("a/b/").reason)
    }

    @Test
    fun `over-long path is rejected`() {
        assertEquals(RelativePath.Reason.TOO_LONG, reject("a".repeat(2000)).reason)
    }

    @Test
    fun `excessively deep path is rejected`() {
        val deep = (1..100).joinToString("/") { "d$it" }
        assertEquals(RelativePath.Reason.TOO_MANY_SEGMENTS, reject(deep).reason)
    }

    @Test
    fun `lone surrogate is rejected`() {
        assertEquals(RelativePath.Reason.NOT_UTF8, reject("bad\uD800name").reason)
    }

    // ── Outgoing sanitiser ─────────────────────────────────────────────────────────────────

    @Test
    fun `outgoing sanitiser produces names the validator accepts`() {
        val hostile = listOf(
            "con", "nul.txt", "has/slash", "trailing. ", "pipe|char", "ctrl\u0001x", "",
        )
        for (name in hostile) {
            val safe = RelativePath.sanitizeOutgoingSegment(name)
            assertTrue(
                "sanitised '$name' -> '$safe' which is still invalid",
                RelativePath.isValid(safe),
            )
        }
    }

    @Test
    fun `outgoing sanitiser leaves good names untouched`() {
        assertEquals("photo.jpg", RelativePath.sanitizeOutgoingSegment("photo.jpg"))
        assertEquals("日本語.txt", RelativePath.sanitizeOutgoingSegment("日本語.txt"))
    }
}
