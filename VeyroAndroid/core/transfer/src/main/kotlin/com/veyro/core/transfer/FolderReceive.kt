package com.veyro.core.transfer

import com.veyro.core.model.TransferItem
import com.veyro.core.model.folderName
import com.veyro.core.protocol.RelativePath
import java.io.File

/**
 * One receive's view of the storage, for folder transfers (ADR-064).
 *
 * A received folder keeps its structure under the received-files location. When the name of its
 * top folder is already taken there (an earlier copy of the same folder, a file, or one of the
 * type folders such as "Photos"), the whole folder is saved as "Photos (2)", "Photos (3)", ... so
 * two copies never mix and nothing is overwritten.
 *
 * Every path is validated again here (REQ-T11). A path that fails is refused, never rewritten
 * into one that passes: only the first segment of a path that is already valid is renamed.
 *
 * Made per offer; [prepare] runs once the offer is accepted, before any item is opened.
 */
public class FolderReceive(private val base: TransferStoragePublisher) : TransferStoragePublisher {

    private companion object {
        /** "Photos (2)" up to "Photos (99)"; past that the folder goes into the first name. */
        const val MAX_SUFFIX = 99
    }

    /** Top folder of the offer, lowercase, to the name it is saved under. */
    private val tops = HashMap<String, String>()
    private val emptyDirectories = ArrayList<String>()

    /** The saved name of the folder when the offer is one folder ("Photos (2)"); else null. */
    public var folderName: String? = null
        private set

    /** Empty directories of the offer that passed validation, in offer order. */
    public val directories: List<String> get() = emptyDirectories

    public suspend fun prepare(items: List<TransferItem>) {
        tops.clear()
        emptyDirectories.clear()
        folderName = null
        // Lowercase key to the spelling the sender used. Case-insensitive, because Windows and
        // Android shared storage both treat "Photos" and "photos" as one folder.
        val offered = LinkedHashMap<String, String>()
        for (item in items) {
            val valid = RelativePath.validate(item.relativePath) as? RelativePath.Result.Valid ?: continue
            if (item.isDirectory) emptyDirectories += valid.normalized
            if (item.isDirectory || valid.segments.size >= 2) offered.putIfAbsent(valid.segments[0].lowercase(), valid.segments[0])
        }
        val chosen = HashSet<String>()
        for ((key, top) in offered) {
            var name = top
            for (n in 1..MAX_SUFFIX) {
                val candidate = if (n == 1) top else "$top ($n)"
                if (!RelativePath.isValid(candidate)) break
                val lower = candidate.lowercase()
                // Never the name another top folder of this same offer arrives under.
                if (lower in chosen || (lower != key && lower in offered)) continue
                if (exists(candidate)) continue
                name = candidate
                break
            }
            chosen += name.lowercase()
            tops[key] = name
        }
        folderName = items.folderName()?.let { tops[it.lowercase()] }
    }

    /** A storage that cannot answer counts as free: the folder then merges, it is never lost. */
    private suspend fun exists(name: String): Boolean = try {
        base.folderExists(name)
    } catch (c: kotlin.coroutines.cancellation.CancellationException) {
        throw c
    } catch (_: Exception) {
        false
    }

    /** [relativePath] as it is saved, or null when it fails validation. */
    public fun savedPath(relativePath: String): String? {
        val valid = RelativePath.validate(relativePath) as? RelativePath.Result.Valid ?: return null
        val saved = tops[valid.segments[0].lowercase()] ?: return valid.normalized
        val renamed = (listOf(saved) + valid.segments.drop(1)).joinToString("/")
        // A rename that pushes a path past the limits keeps the original name instead.
        return renamed.takeIf { RelativePath.isValid(it) } ?: valid.normalized
    }

    /**
     * Creates the folder's empty directories after its files arrived. Returns how many could not
     * be created (storage that cannot hold an empty directory, or a failure).
     */
    public suspend fun createEmptyDirectories(): Int = emptyDirectories.count { !createDirectory(it) }

    override suspend fun publish(sourceFile: File, relativePath: String, mimeType: String?, sizeBytes: Long): StoragePublishResult {
        val path = savedPath(relativePath) ?: return StoragePublishResult.Failed("PATH_REJECTED")
        return base.publish(sourceFile, path, mimeType, sizeBytes)
    }

    override suspend fun openDirect(relativePath: String, mimeType: String?, sizeBytes: Long): DirectReceiveTarget? {
        val path = savedPath(relativePath) ?: return null
        return base.openDirect(path, mimeType, sizeBytes)
    }

    override suspend fun folderExists(name: String): Boolean = base.folderExists(name)

    override suspend fun createDirectory(relativePath: String): Boolean {
        val path = savedPath(relativePath) ?: return false
        return try {
            base.createDirectory(path)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (_: Exception) {
            false
        }
    }
}
