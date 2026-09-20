package com.example.sonara.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * Universal recoverable error communication banner.
 */
@Composable
fun SonaraErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    val shapes = SonaraTheme.shapes

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.small)
            .background(colors.surface)
            .border(dimensions.dividerThickness, colors.error, shapes.small)
            .padding(dimensions.spaceLg)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = typography.cardTitle,
                color = colors.error
            )
            Spacer(modifier = Modifier.height(dimensions.spaceXs))
        }
        Text(
            text = message,
            style = typography.secondaryBody,
            color = colors.primaryText
        )
        if (retryLabel != null && onRetry != null) {
            Spacer(modifier = Modifier.height(dimensions.spaceMd))
            SonaraButton(
                text = retryLabel,
                onClick = onRetry,
                variant = SonaraButtonVariant.Text
            )
        }
    }
}
