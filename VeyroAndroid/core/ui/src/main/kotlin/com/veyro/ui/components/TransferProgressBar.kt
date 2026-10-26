package com.veyro.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.components.VeyroProgressBar
import com.veyro.core.designsystem.theme.VeyroMetricSubStyle
import com.veyro.core.designsystem.theme.VeyroTextSecondary
import com.veyro.core.model.TransferProgress

/**
 * Transfer progress bar component.
 *
 * Re-exported from the designsystem component for use in the `:core:ui` module.
 */
@Composable
public fun TransferProgressBar(
    progress: TransferProgress,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = progress.currentItemName,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        VeyroProgressBar(
            progress = progress.fraction,
            showShimmer = true,
            modifier = Modifier.height(8.dp),
        )
        Text(
            text = "${progress.bytesTransferred / 1024} / ${progress.totalBytes / 1024} KiB" +
                (progress.etaSeconds?.let { "  ·  ${it}s left" } ?: ""),
            style = VeyroMetricSubStyle,
            color = VeyroTextSecondary,
        )
    }
}
