package com.veyro.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.theme.VeyroCyan
import com.veyro.core.designsystem.theme.VeyroCyanDark
import com.veyro.core.designsystem.theme.VeyroCyanGlow
import com.veyro.core.designsystem.theme.VeyroExtras
import com.veyro.core.designsystem.theme.VeyroMetricSubStyle
import com.veyro.core.designsystem.theme.VeyroTeal
import com.veyro.core.designsystem.theme.VeyroTextSecondary

private val ProgressTrackHeight = 8.dp
private val ProgressTrackRadius = RoundedCornerShape(4.dp)

/**
 * Futuristic linear progress bar with a cyan gradient fill and animated shimmer.
 *
 * @param progress  0f..1f
 * @param showShimmer  If true, a traveling highlight sweeps across the fill to suggest activity.
 */
@Composable
public fun VeyroProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    showShimmer: Boolean = true,
    height: Dp = ProgressTrackHeight,
    fillColor: Brush = Brush.horizontalGradient(listOf(VeyroCyan, VeyroCyanGlow)),
) {
    val shimmerOffset by animateShimmer(showShimmer)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(ProgressTrackRadius)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Fill
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height)
                .clip(ProgressTrackRadius)
                .background(fillColor),
        )

        // Shimmer sweep
        if (showShimmer && progress > 0.05f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(height)
                    .clip(ProgressTrackRadius)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.35f),
                                Color.Transparent,
                            ),
                            startX = shimmerOffset,
                            endX = shimmerOffset + 80f,
                        ),
                    ),
            )
        }
    }
}

/**
 * Transfer progress display — filename + bar + byte counter + speed.
 */
@Composable
public fun VeyroTransferProgress(
    currentItemName: String,
    bytesTransferred: Long,
    totalBytes: Long,
    bytesPerSecond: Long,
    modifier: Modifier = Modifier,
) {
    val fraction = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = currentItemName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        VeyroProgressBar(
            progress = fraction,
            showShimmer = true,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatBytesUI(bytesTransferred),
                style = VeyroMetricSubStyle,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = " / ${formatBytesUI(totalBytes)}",
                style = VeyroMetricSubStyle,
                color = VeyroTextSecondary,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (bytesPerSecond > 0) {
                Text(
                    text = "${formatBytesUI(bytesPerSecond)}/s",
                    style = VeyroMetricSubStyle,
                    color = VeyroTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun animateShimmer(enabled: Boolean): androidx.compose.runtime.State<Float> {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    return infiniteTransition.animateFloat(
        initialValue = -160f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
}

/** Formats bytes for UI — avoids division inside the composable for simplicity. */
private fun formatBytesUI(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
        else -> "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
    }
}
