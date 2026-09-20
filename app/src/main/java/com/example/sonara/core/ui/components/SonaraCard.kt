package com.example.sonara.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * Universal content card with subtle tonal fill and divider border.
 */
@Composable
fun SonaraCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = SonaraTheme.shapes.medium,
    containerColor: Color = SonaraTheme.colors.surface,
    borderColor: Color = SonaraTheme.colors.divider,
    borderWidth: Dp = SonaraTheme.dimensions.dividerThickness,
    contentPadding: Dp = SonaraTheme.dimensions.spaceLg,
    content: @Composable () -> Unit
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Card(
        modifier = modifier.then(clickableModifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        border = if (borderWidth > 0.dp) BorderStroke(borderWidth, borderColor) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
