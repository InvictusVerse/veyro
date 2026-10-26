package com.veyro.di

import com.veyro.core.link.OutgoingFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The hand-off between the file picker and the send screen.
 *
 * Needed because only an Activity can launch an `ActivityResultContract`, but the screen that wants
 * the result is a Composable behind a ViewModel. Rather than passing an Activity reference down (or
 * having the ViewModel know about `content://` URIs), the Activity writes here and the ViewModel
 * observes.
 *
 * Process-scoped and in the container, so it survives configuration changes: a picker that returns
 * after a rotation must not drop the user's selection.
 *
 * The buffer is **not** cleared automatically on read. The send screen owns when a selection is
 * finished with, because the user may pick, look, pick more, and only then send.
 */
public class PickedFileBuffer {

    private val _files = MutableStateFlow<List<OutgoingFile>>(emptyList())
    public val files: StateFlow<List<OutgoingFile>> = _files.asStateFlow()

    /** Appends, rather than replaces: picking twice should add to the selection. */
    public fun add(files: List<OutgoingFile>) {
        if (files.isEmpty()) return
        _files.value = _files.value + files
    }

    public fun removeAt(index: Int) {
        val current = _files.value
        if (index !in current.indices) return
        _files.value = current.filterIndexed { i, _ -> i != index }
    }

    public fun clear() {
        _files.value = emptyList()
    }
}
