package com.veyro.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.veyro.core.designsystem.icons.VeyroIcons

/**
 * Top bar of a tab. It shows the tab's name; where [searchEnabled] is set it also offers a search
 * action that swaps the name for a [SearchField].
 *
 * The text typed is owned by the caller ([searchQuery]), so it is not lost when the bar leaves
 * composition on another tab. Closing the field with its arrow or the Back gesture clears it;
 * the keyboard's search key closes the field but keeps the filter applied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    title: String,
    searchEnabled: Boolean,
    searchPlaceholder: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val fieldVisible = searchOpen && searchEnabled

    val closeAndClear: () -> Unit = {
        onSearchQueryChange("")
        searchOpen = false
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        title = {
            if (fieldVisible) {
                SearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSubmit = { searchOpen = false },
                    onClose = closeAndClear,
                    placeholder = searchPlaceholder,
                    leadingIcon = {
                        IconButton(onClick = closeAndClear) {
                            Icon(VeyroIcons.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    trailingIcon = if (searchQuery.isEmpty()) null else {
                        {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(VeyroIcons.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                )
            } else {
                Text(text = title)
            }
        },
        actions = {
            if (searchEnabled && !searchOpen) {
                IconButton(onClick = { searchOpen = true }) {
                    Icon(VeyroIcons.Search, contentDescription = "Search")
                }
            }
        },
    )
}
