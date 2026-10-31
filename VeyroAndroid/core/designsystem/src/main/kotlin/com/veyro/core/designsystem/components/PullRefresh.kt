package com.veyro.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Adds pull-to-refresh to [content], which must contain a vertically scrollable child.
 *
 * @param refreshing true while a refresh is running; drives the spinner.
 * @param enabled false turns the gesture off, for example while a dialog is open.
 * @param onRefresh called once when the user completes the pull gesture.
 * @param indicatorPadding moves the spinner away from the top edge, for example below a top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun PullRefresh(
    refreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier.pullToRefresh(
            state = pullState,
            isRefreshing = refreshing,
            enabled = enabled,
            onRefresh = onRefresh,
        ),
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            state = pullState,
            isRefreshing = refreshing,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(indicatorPadding),
            containerColor = colors.surfaceVariant,
            color = colors.onSurfaceVariant,
        )
    }
}
