package com.veyro.core.transfer

import java.io.File

/**
 * Chooses the on-disk destination for one incoming item (REQ-T12).
 *
 * This is an interface so that policy can be tested without the filesystem and so that the
 * settings that feed it live in `:core:data` without `:core:transfer` ever depending on
 * `:core:data`.
 */
public interface TransferDestinationResolver {
    public suspend fun resolve(request: DestinationRequest): DestinationResolution
}

public data class DestinationRequest(
    val relativePath: String,
    /** Directory the user chose for saves, or null for the app-private default. */
    val preferredDir: File?,
    val exists: Boolean,
    val isDirectory: Boolean,
)

public sealed interface DestinationResolution {
    /** Write to this file. The transfer engine will create parent directories. */
    public data class Resolved(val file: File) : DestinationResolution

    /** Reject this item; optionally tell the sender why. */
    public data class Rejected(val reason: String) : DestinationResolution
}

public enum class CollisionPolicy {
    /** Prompt the user per collision. The noisiest, but also the safest. */
    ASK,

    /** `photo (1).jpg`, `photo (2).jpg`, ... - the default (REQ-T12). */
    RENAME,

    /** Overwrite the existing file. Requires explicit confirmation higher up. */
    OVERWRITE,

    /** Skip the item and move on. */
    SKIP,
}