package com.example.sonara.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * Universal calm empty state presentation.
 */
@Composable
fun SonaraEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(dimensions.space2Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon?.let {
            it()
            Spacer(modifier = Modifier.height(dimensions.spaceLg))
        }
        Text(
            text = title,
            style = typography.sectionTitle,
            color = colors.primaryText,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(dimensions.spaceSm))
        Text(
            text = message,
            style = typography.body,
            color = colors.secondaryText,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(dimensions.spaceXl))
            SonaraButton(
                text = actionLabel,
                onClick = onAction,
                variant = SonaraButtonVariant.Primary
            )
        }
    }
}
