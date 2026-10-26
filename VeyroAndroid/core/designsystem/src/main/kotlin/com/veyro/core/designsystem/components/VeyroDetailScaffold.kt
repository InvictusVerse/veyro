// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/devices/MediaSessionSettingsScreen.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.core.designsystem.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * The frame every pushed screen uses: a [TopAppBar] with a back arrow and the title, as on
 * Sefirah's device sub-screens. Veyro change: extracted so Send, Receive, Pairing and the device
 * screens share it instead of each declaring its own bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun VeyroDetailScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(VeyroIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
            )
        },
        content = content,
    )
}
