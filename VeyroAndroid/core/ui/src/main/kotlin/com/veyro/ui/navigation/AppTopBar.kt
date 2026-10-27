// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), core/presentation/src/main/java/sefirah/presentation/components/AppTopBar.kt, GPL-3.0.
// Modified for Veyro.
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
 * The tab screens' top bar: the tab's name, and on Devices a search action that turns the title
 * into a search field.
 *
 * Veyro changes: the query is hoisted (the host owns it, so it survives tab switches) and is
 * cleared when the search closes; search is offered only where there is a list to filter.
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
    var active by rememberSaveable { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        title = {
            if (!active || !searchEnabled) {
                Text(text = title)
            } else {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onSearch = { active = false },
                    active = active,
                    onActiveChange = {
                        active = it
                        if (!it) onSearchQueryChange("")
                    },
                    placeholder = { Text(text = searchPlaceholder) },
                    leadingIcon = {
                        IconButton(onClick = {
                            onSearchQueryChange("")
                            active = false
                        }) {
                            Icon(VeyroIcons.ArrowBack, contentDescription = "Close search")
                        }
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(VeyroIcons.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                )
            }
        },
        actions = {
            if (!active && searchEnabled) {
                IconButton(onClick = { active = true }) {
                    Icon(VeyroIcons.Search, contentDescription = "Search")
                }
            }
        },
    )
}
