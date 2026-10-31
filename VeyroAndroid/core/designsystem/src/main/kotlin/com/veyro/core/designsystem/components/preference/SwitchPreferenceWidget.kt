package com.veyro.core.designsystem.components.preference

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A settings row with a switch.
 *
 * Without [onContentClick] the whole row toggles. With it, the row is split once the switch is
 * on: tapping the text calls [onContentClick] (typically to open a detail screen) and only the
 * switch side toggles, with a divider between the two. While the switch is off, a tap anywhere
 * turns it on.
 *
 * Toggle targets are marked [Role.Switch] so TalkBack announces them as switches.
 *
 * @param checkedIcon shown instead of [icon] while checked, if [uncheckedIcon] is also given.
 * @param uncheckedIcon shown instead of [icon] while unchecked, if [checkedIcon] is also given.
 */
@Composable
public fun SwitchPreferenceWidget(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checkedIcon: ImageVector? = null,
    uncheckedIcon: ImageVector? = null,
    checked: Boolean = false,
    enabled: Boolean = true,
    onCheckedChanged: (Boolean) -> Unit,
    onContentClick: (() -> Unit)? = null,
) {
    val shownIcon = if (checkedIcon != null && uncheckedIcon != null) {
        if (checked) checkedIcon else uncheckedIcon
    } else {
        icon
    }

    if (onContentClick == null) {
        WholeRowSwitch(
            modifier = modifier,
            title = title,
            subtitle = subtitle,
            icon = shownIcon,
            checked = checked,
            enabled = enabled,
            onCheckedChanged = onCheckedChanged,
        )
    } else {
        SplitRowSwitch(
            modifier = modifier,
            title = title,
            subtitle = subtitle,
            icon = shownIcon,
            checked = checked,
            enabled = enabled,
            onCheckedChanged = onCheckedChanged,
            onContentClick = onContentClick,
        )
    }
}

/** Row where any tap flips the switch. */
@Composable
private fun WholeRowSwitch(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
) {
    TextPreferenceWidget(
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChanged,
        ),
        title = title,
        subtitle = subtitle,
        icon = icon,
        widget = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.padding(start = PreferenceMetrics.widgetGap),
            )
        },
        onPreferenceClick = null,
    )
}

/** Row with a separate text target and switch target; see [SwitchPreferenceWidget]. */
@Composable
private fun SplitRowSwitch(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChanged: (Boolean) -> Unit,
    onContentClick: () -> Unit,
) {
    // While off, the entire row is one big "turn on" target. While on, only the switch cell is a
    // toggle, so the text cell stays free to handle its own click.
    val turnOnEverywhere = if (checked) {
        Modifier
    } else {
        Modifier.toggleable(
            value = false,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { onCheckedChanged(true) },
        )
    }
    val turnOffFromSwitchCell = if (checked) {
        Modifier.toggleable(
            value = true,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { onCheckedChanged(false) },
        )
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LocalPreferenceMinHeight.current)
            .height(IntrinsicSize.Max)
            .then(turnOnEverywhere),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextPreferenceWidget(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            title = title,
            subtitle = subtitle,
            icon = icon,
            widget = null,
            onPreferenceClick = if (checked) onContentClick else null,
        )

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = DIVIDER_SIDE_GAP),
            contentAlignment = Alignment.Center,
        ) {
            VerticalDivider(
                modifier = Modifier.height(DIVIDER_LENGTH),
                color = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .then(turnOffFromSwitchCell),
            contentAlignment = Alignment.Center,
        ) {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.padding(horizontal = PreferenceMetrics.sideInset),
            )
        }
    }
}

private val DIVIDER_LENGTH: Dp = 32.dp
private val DIVIDER_SIDE_GAP: Dp = 8.dp
