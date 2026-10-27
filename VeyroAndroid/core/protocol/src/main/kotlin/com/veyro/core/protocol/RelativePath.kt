package com.veyro.core.protocol

/**
 * Validation of a path received from a peer, before it is allowed anywhere near the filesystem.
 *
 * This is a security control, not a convenience helper (REQ-T11,
 * ProjectInfo/04-PROTOCOL.md section 7). A sender is untrusted even when it is paired: pairing
 * proves *who* is sending, not that what they send is well-formed. One malformed path that
 * escapes the destination directory turns a file-transfer app into an arbitrary-file-write
 * primitive.
 *
 * It lives in `:core:protocol` - pure Kotlin, no Android - so that exactly one implementation is
 * shared by the Android client, is mirrored byte-for-byte by the Windows client, and is testable
 * without a device (ADR-009).
 *
 * The rules are deliberately **stricter than either OS requires**. Veyro validates against the
 * union of POSIX and Windows hazards regardless of which platform is receiving, because a file
 * received on Android may later be synced to Windows, and a name that is merely awkward on one
 * platform is a vulnerability on the other.
 */
public object RelativePath {

    /**
     * Device names that Windows resolves specially, in any directory, with or without an
     * extension. `CON.txt` opens the console, not a file.
     */
    private val WINDOWS_RESERVED: Set<String> = buildSet {
        addAll(listOf("con", "prn", "aux", "nul"))
        for (i in 1..9) {
            add("com$i")
            add("lpt$i")
        }
    }

    /** Characters Windows forbids in a filename, plus the separator we reserve for ourselves. */
    private const val FORBIDDEN_CHARS = "<>:\"\\|?*"

    public sealed interface Result {
        /** [normalized] is safe to resolve against the destination directory. */
        public data class Valid(val normalized: String, val segments: List<String>) : Result

        public data class Rejected(val reason: Reason, val detail: String) : Result
    }

    public enum class Reason {
        EMPTY,
        TOO_LONG,
        NOT_UTF8,
        CONTAINS_NUL,
        ABSOLUTE,
        DRIVE_LETTER,
        UNC_PATH,
        BACKSLASH,
        TRAVERSAL,
        CURRENT_DIR_SEGMENT,
        EMPTY_SEGMENT,
        RESERVED_NAME,
        TRAILING_DOT_OR_SPACE,
        FORBIDDEN_CHARACTER,
        CONTROL_CHARACTER,
        ALTERNATE_DATA_STREAM,
        TOO_MANY_SEGMENTS,
    }

    private const val MAX_SEGMENTS = 64

    /**
     * Validates [raw] as a relative path received from a peer.
     *
     * Returns a [Result] rather than throwing: a single bad path must fail one item, not the
     * whole transfer.
     */
    public fun validate(raw: String?): Result {
        if (raw.isNullOrEmpty()) {
            return Result.Rejected(Reason.EMPTY, "path is empty")
        }

        if (raw.toByteArray(Charsets.UTF_8).size > ProtocolConstants.MAX_RELATIVE_PATH_BYTES) {
            return Result.Rejected(Reason.TOO_LONG, "exceeds ${ProtocolConstants.MAX_RELATIVE_PATH_BYTES} bytes")
        }

        if (raw.contains('\u0000')) {
            // A NUL truncates the path in any C-based syscall layer, so "safe.txt\u0000/../../x"
            // can pass a naive string check and then open a different file entirely.
            return Result.Rejected(Reason.CONTAINS_NUL, "contains NUL")
        }

        // Unpaired surrogates survive a String but corrupt on encode, and some filesystems
        // normalise them differently, which breaks the hash-to-filename correspondence.
        if (raw.any { it.isSurrogate() } && !isWellFormedUtf16(raw)) {
            return Result.Rejected(Reason.NOT_UTF8, "malformed UTF-16 surrogate pair")
        }

        if (raw.contains('\\')) {
            // The wire format uses '/' only. Accepting '\' would mean two spellings of the same
            // path, and the validator would have to be correct for both.
            return Result.Rejected(Reason.BACKSLASH, "backslash is not a separator in VLP")
        }

        // Order matters here: "//server/share" is both UNC-shaped and absolute-shaped, and the
        // UNC diagnosis is the more specific one. Check the two-slash form first or every UNC
        // path gets reported as merely ABSOLUTE, which is true but less useful.
        if (raw.startsWith("//")) {
            return Result.Rejected(Reason.UNC_PATH, "UNC-style path")
        }

        if (raw.startsWith('/')) {
            return Result.Rejected(Reason.ABSOLUTE, "absolute path")
        }

        if (raw.length >= 2 && raw[1] == ':' && raw[0].isLetter()) {
            return Result.Rejected(Reason.DRIVE_LETTER, "drive-qualified path")
        }

        val segments = raw.split('/')

        if (segments.size > MAX_SEGMENTS) {
            return Result.Rejected(Reason.TOO_MANY_SEGMENTS, "${segments.size} segments")
        }

        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex

            if (segment.isEmpty()) {
                // A trailing slash is tolerated for directory entries; an empty segment anywhere
                // else means a doubled separator, which normalises differently per platform.
                return if (isLast) {
                    Result.Rejected(Reason.EMPTY_SEGMENT, "trailing separator")
                } else {
                    Result.Rejected(Reason.EMPTY_SEGMENT, "empty segment at $index")
                }
            }

            if (segment == "..") {
                return Result.Rejected(Reason.TRAVERSAL, "parent-directory segment")
            }

            if (segment == ".") {
                return Result.Rejected(Reason.CURRENT_DIR_SEGMENT, "current-directory segment")
            }

            segment.firstOrNull { it.code < 0x20 || it.code == 0x7F }?.let {
                return Result.Rejected(
                    Reason.CONTROL_CHARACTER,
                    "control character U+%04X".format(it.code),
                )
            }

            segment.firstOrNull { it in FORBIDDEN_CHARS }?.let {
                // ':' is both a Windows-forbidden character and the NTFS alternate-data-stream
                // marker, so report the more specific reason for it.
                return if (it == ':') {
                    Result.Rejected(Reason.ALTERNATE_DATA_STREAM, "NTFS stream separator")
                } else {
                    Result.Rejected(Reason.FORBIDDEN_CHARACTER, "character '$it'")
                }
            }

            if (segment.endsWith('.') || segment.endsWith(' ')) {
                // Windows silently strips these, so "evil. " and "evil" become the same file -
                // a way to overwrite a target whose name was supposed to be different.
                return Result.Rejected(Reason.TRAILING_DOT_OR_SPACE, "segment '$segment'")
            }

            val stem = segment.substringBefore('.').lowercase()
            if (stem in WINDOWS_RESERVED) {
                return Result.Rejected(Reason.RESERVED_NAME, "reserved device name '$stem'")
            }
        }

        return Result.Valid(normalized = segments.joinToString("/"), segments = segments)
    }

    /** Convenience for call sites that only need a yes/no. */
    public fun isValid(raw: String?): Boolean = validate(raw) is Result.Valid

    /**
     * Makes a name derived from an *outgoing* local file safe to put on the wire.
     *
     * Note the asymmetry: this is a sanitiser for data we produce, whereas [validate] is a
     * validator for data we receive. Never use this on received input - silently rewriting a
     * hostile path hides an attack instead of failing it.
     */
    public fun sanitizeOutgoingSegment(name: String): String {
        val cleaned = name
            .map { c -> if (c.code < 0x20 || c.code == 0x7F || c in FORBIDDEN_CHARS || c == '/') '_' else c }
            .joinToString("")
            .trimEnd('.', ' ')
            .ifEmpty { "unnamed" }
        val stem = cleaned.substringBefore('.').lowercase()
        return if (stem in WINDOWS_RESERVED) "_$cleaned" else cleaned
    }

    private fun isWellFormedUtf16(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                i += 2
            } else if (c.isLowSurrogate()) {
                return false
            } else {
                i++
            }
        }
        return true
    }
}
