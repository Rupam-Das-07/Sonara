package com.example.sonara.feature.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.theme.SonaraTheme

@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { onCheckedChange(!checked) }
            )
            .padding(vertical = dimensions.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = dimensions.spaceMd)) {
            Text(
                text = title,
                style = typography.body,
                color = colors.primaryText
            )
            if (description != null) {
                Text(
                    text = description,
                    style = typography.caption,
                    color = colors.secondaryText
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.secondaryText,
                uncheckedTrackColor = colors.divider
            )
        )
    }
}

@Composable
fun SettingsSelectorRow(
    title: String,
    currentValue: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            )
            .padding(vertical = dimensions.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = dimensions.spaceMd)) {
            Text(
                text = title,
                style = typography.body,
                color = if (enabled) colors.primaryText else colors.secondaryText.copy(alpha = 0.5f)
            )
            if (description != null) {
                Text(
                    text = description,
                    style = typography.caption,
                    color = if (enabled) colors.secondaryText else colors.secondaryText.copy(alpha = 0.5f)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensions.spaceXs)
        ) {
            Text(
                text = currentValue,
                style = typography.secondaryBody,
                color = if (enabled) colors.accent else colors.secondaryText.copy(alpha = 0.5f)
            )
            if (enabled) {
                Icon(
                    imageVector = PhosphorIcons.CaretRight,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    isLoading: Boolean = false
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                enabled = !isLoading,
                onClick = onClick
            )
            .padding(vertical = dimensions.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = dimensions.spaceMd)) {
            Text(
                text = title,
                style = typography.body,
                color = colors.primaryText
            )
            if (description != null) {
                Text(
                    text = description,
                    style = typography.caption,
                    color = colors.secondaryText
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = colors.accent,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = PhosphorIcons.CaretRight,
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun SettingsDestructiveRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick
            )
            .padding(vertical = dimensions.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = dimensions.spaceMd)) {
            Text(
                text = title,
                style = typography.body,
                color = colors.accent
            )
            if (description != null) {
                Text(
                    text = description,
                    style = typography.caption,
                    color = colors.secondaryText
                )
            }
        }

        Icon(
            imageVector = PhosphorIcons.CaretRight,
            contentDescription = null,
            tint = colors.accent.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
    }
}
