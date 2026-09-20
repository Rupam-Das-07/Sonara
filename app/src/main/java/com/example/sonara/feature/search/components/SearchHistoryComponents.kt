package com.example.sonara.feature.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.SearchHistoryEntry
import com.example.sonara.domain.model.SearchSuggestion

/**
 * Renders a past search query previously submitted by the user.
 * Displays a Clock icon and an individual Delete action button.
 */
@Composable
fun SearchHistoryItemRow(
    entry: SearchHistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
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
            .padding(vertical = dimensions.spaceSm, horizontal = dimensions.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = PhosphorIcons.Clock,
                contentDescription = null,
                tint = colors.secondaryText,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = entry.query,
                style = typography.body,
                color = colors.primaryText,
                maxLines = 1
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = PhosphorIcons.X,
                contentDescription = stringResource(R.string.action_delete),
                tint = colors.secondaryText,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Renders a real-time related search / autocomplete suggestion.
 * Displays a MagnifyingGlass icon and NO delete button.
 */
@Composable
fun SearchSuggestionItemRow(
    suggestion: SearchSuggestion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
            .padding(vertical = dimensions.spaceSm, horizontal = dimensions.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        Icon(
            imageVector = PhosphorIcons.MagnifyingGlass,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = suggestion.query,
            style = typography.body,
            color = colors.primaryText,
            maxLines = 1
        )
    }
}

@Composable
fun ClearSearchHistoryConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.dialog_search_history_clear_title),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_search_history_clear_message),
                style = typography.body,
                color = colors.secondaryText
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.action_clear_all), color = Color(0xFFE57373), style = typography.buttonLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = colors.secondaryText, style = typography.buttonLabel)
            }
        }
    )
}
