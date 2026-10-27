// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), core/presentation/src/main/java/sefirah/presentation/components/SearchBar.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** A pill text field for the top bar; Back closes it. Veyro change: internal, no Surface wrapper. */
@Composable
internal fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val isFocused = interactionSource.collectIsFocusedAsState().value
    val shouldClearFocus = !active && isFocused

    LaunchedEffect(active) {
        if (shouldClearFocus) {
            focusManager.clearFocus()
        } else if (active) {
            focusRequester.requestFocus()
        }
    }

    BackHandler(enabled = active) {
        onActiveChange(false)
    }

    TextField(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onActiveChange(true) },
        value = query,
        onValueChange = onQueryChange,
        placeholder = placeholder,
        leadingIcon = leadingIcon?.let { leading ->
            { Box(Modifier.offset(x = 4.dp)) { leading() } }
        },
        trailingIcon = trailingIcon?.let { trailing ->
            { Box(Modifier.offset(x = (-4).dp)) { trailing() } }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        textStyle = MaterialTheme.typography.bodySmall,
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        shape = MaterialTheme.shapes.extraLarge,
        interactionSource = interactionSource,
    )
}
