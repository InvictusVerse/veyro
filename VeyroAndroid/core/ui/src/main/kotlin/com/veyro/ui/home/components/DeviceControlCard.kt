// Adapted from Sefirah-Android (https://github.com/shrimqy/Sefirah-Android), app/src/main/java/com/castle/sefirah/presentation/home/components/DeviceControlCard.kt, GPL-3.0.
// Modified for Veyro.
package com.veyro.ui.home.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyro.core.designsystem.icons.VeyroIcons

/** One button on the quick-action card. */
internal data class QuickAction(
    val name: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Row(s) of icon buttons with labels, five per row, collapsible beyond five.
 *
 * Veyro changes: the actions are Veyro's own (send files, receive, device settings, pair) built on
 * the phone, not PC-defined actions sent over the wire, so the custom-icon decoding and the
 * confirmation and timer dialogs are gone.
 */
@Composable
internal fun DeviceControlCard(
    actions: List<QuickAction>,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.animateContentSize()) {
            Column(
                modifier = Modifier.padding(top = 16.dp, bottom = if (actions.size > 5) 0.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val itemsToShow = if (expanded || actions.size <= 5) actions else actions.take(5)
                itemsToShow.chunked(5).forEach { rowActions ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        for (i in 0 until 5) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (i < rowActions.size) {
                                    val action = rowActions[i]
                                    DeviceControlButton(
                                        icon = action.icon,
                                        name = action.name,
                                        showLabel = showLabels,
                                        onClick = action.onClick,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (actions.size > 5) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = !expanded }
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (expanded) VeyroIcons.KeyboardArrowUp else VeyroIcons.KeyboardArrowDown,
                        contentDescription = if (expanded) "Show less" else "Show more",
                    )
                }
            }
        }
    }
}

@Composable
internal fun DeviceControlButton(
    name: String,
    icon: ImageVector,
    onClick: () -> Unit,
    showLabel: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = false, radius = 20.dp),
                    role = Role.Button,
                    onClickLabel = name,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = name,
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        }
        if (showLabel) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    ),
            )
        }
    }
}
