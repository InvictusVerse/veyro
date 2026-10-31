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
 * Frame for any screen pushed on top of the main tabs: a top bar with a back arrow, the [title]
 * and optional [actions], above the [content]. Send, Receive, Pairing and the device screens use
 * this one frame instead of each building its own bar.
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
        topBar = { DetailTopBar(title = title, onNavigateBack = onNavigateBack, actions = actions) },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(VeyroIcons.ArrowBack, contentDescription = "Back")
            }
        },
        actions = actions,
    )
}
