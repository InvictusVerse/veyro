package com.veyro.core.transfer

import com.veyro.core.protocol.RelativePath
import java.io.File

/**
 * Default [TransferDestinationResolver]: rename on collision, reject anything that escapes.
 *
 * Two jobs, and the second is a security control rather than a convenience:
 *
 *  1. **Collision policy** (REQ-T12). Default `RENAME`, producing `photo (1).jpg`. Overwriting is
 *     opt-in because the failure mode of a wrong default is destroying a file the user already
 *     had, which is not recoverable.
 *
 *  2. **Containment.** After canonicalisation the resolved path must still be inside the
 *     destination directory. `RelativePath` already rejects `..` and absolute forms, so this is
 *     defence in depth - but it is the check that would catch a future validator bug, and a
 *     symlink inside the destination pointing outward is not something string validation can see.
 */
public class DefaultDestinationResolver(
    private val collisionPolicy: CollisionPolicy = CollisionPolicy.RENAME,
) : TransferDestinationResolver {

    override suspend fun resolve(request: DestinationRequest): DestinationResolution {
        val base = request.preferredDir ?: return DestinationResolution.Rejected("no destination set")

        val relative = when (val check = RelativePath.validate(request.relativePath)) {
            is RelativePath.Result.Valid -> check.normalized
            is RelativePath.Result.Rejected ->
                return DestinationResolution.Rejected("rejected path: ${check.reason}")
        }

        val target = File(base, relative)
        val canonicalBase = runCatching { base.canonicalFile }.getOrElse { base.absoluteFile }
        val canonicalTarget = runCatching { target.canonicalFile }.getOrElse { target.absoluteFile }

        if (!canonicalTarget.path.startsWith(canonicalBase.path)) {
            // Should be unreachable given the validator, which is why it is worth having: if this
            // ever fires, the validator has a hole and this stopped it becoming a write outside
            // the user's chosen folder.
            return DestinationResolution.Rejected("resolved path escapes the destination")
        }

        if (!request.exists || request.isDirectory) {
            return DestinationResolution.Resolved(canonicalTarget)
        }

        return when (collisionPolicy) {
            CollisionPolicy.OVERWRITE -> DestinationResolution.Resolved(canonicalTarget)
            CollisionPolicy.SKIP -> DestinationResolution.Rejected("already exists")
            CollisionPolicy.ASK -> DestinationResolution.Rejected("collision: needs a decision")
            CollisionPolicy.RENAME -> DestinationResolution.Resolved(nextFreeName(canonicalTarget))
        }
    }

    /** `photo.jpg` -> `photo (1).jpg` -> `photo (2).jpg`, stopping at the first free name. */
    private fun nextFreeName(target: File): File {
        val parent = target.parentFile ?: return target
        val name = target.name
        val dot = name.lastIndexOf('.')
        // A leading dot means a hidden file, not an extension: ".env" has no stem/extension split.
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""

        for (n in 1..9999) {
            val candidate = File(parent, "$stem ($n)$extension")
            if (!candidate.exists()) return candidate
        }
        // Give up on renaming rather than looping forever; the caller surfaces this as a failure.
        return File(parent, "$stem (${System.currentTimeMillis()})$extension")
    }
}