package com.veyro.ui.components

import com.veyro.core.model.Platform

/** One connected device the "Send to" sheet offers. Names are user data: shown, never logged. */
public data class SendTarget(
    val id: String,
    val name: String,
    val platform: Platform,
    /** As the device's own status reports it; null when unknown. */
    val battery: Int? = null,
    val charging: Boolean? = null,
)

/** What the sheet is sending, for its one-line summary. */
public sealed interface SendContent {
    /** Shared files, by MIME type (null when the sharing app gave none). */
    public data class Files(val mimeTypes: List<String?>) : SendContent

    /** Shared text; a link when it is one http(s) URL. */
    public data class Text(val text: String) : SendContent

    /** A folder; [name] is null when it is picked after the device (Home). */
    public data class Folder(val name: String?) : SendContent

    /** Home: the files are picked after the device. */
    public data object FilesToPick : SendContent
}

/**
 * The sheet's summary line: "2 photos", "1 video", "3 files", "Link", "Text", "Folder Trip".
 * Pure, so it is tested on the JVM.
 */
public fun sendSummary(content: SendContent): String = when (content) {
    is SendContent.Files -> {
        val n = content.mimeTypes.size
        val types = content.mimeTypes
        when {
            n == 0 -> "Files"
            types.all { it?.startsWith("image/") == true } -> if (n == 1) "1 photo" else "$n photos"
            types.all { it?.startsWith("video/") == true } -> if (n == 1) "1 video" else "$n videos"
            types.all { it?.startsWith("image/") == true || it?.startsWith("video/") == true } ->
                "$n photos and videos"
            else -> if (n == 1) "1 file" else "$n files"
        }
    }
    is SendContent.Text -> if (isLink(content.text)) "Link" else "Text"
    is SendContent.Folder -> content.name?.takeIf { it.isNotBlank() }?.let { "Folder $it" } ?: "A folder, chosen next"
    SendContent.FilesToPick -> "Files, chosen next"
}

/** One http(s) URL and nothing else, as the PC's Open button would treat it. */
public fun isLink(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() }) return false
    val lower = t.lowercase()
    return (lower.startsWith("https://") || lower.startsWith("http://")) && t.length > lower.indexOf("://") + 3
}

/**
 * The order the sheet lists devices in: [preferredId] (Home's selected device) first, the rest
 * in the order given (most recently used first, from the caller), duplicates dropped.
 */
public fun orderSendTargets(targets: List<SendTarget>, preferredId: String?): List<SendTarget> {
    val unique = targets.distinctBy { it.id }
    val first = unique.firstOrNull { it.id == preferredId } ?: return unique
    return listOf(first) + unique.filter { it.id != preferredId }
}

/** "All connected (3)". */
public fun allConnectedLabel(count: Int): String = "All connected ($count)"

/** What TalkBack reads for one device row. */
public fun sendTargetDescription(target: SendTarget): String {
    val kind = if (target.platform == Platform.ANDROID) "phone" else "PC"
    val battery = target.battery?.let { b -> ", battery $b percent" + if (target.charging == true) ", charging" else "" }.orEmpty()
    return "${target.name}, $kind, connected$battery"
}
