package com.example.sonara.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.theme.SonaraTheme

enum class SonaraSurfaceTonalLevel {
    Canvas,
    Elevated,
    Secondary
}

/**
 * Semantic surface container implementing Sonara tonal hierarchy.
 */
@Composable
fun SonaraSurface(
    modifier: Modifier = Modifier,
    tonalLevel: SonaraSurfaceTonalLevel = SonaraSurfaceTonalLevel.Elevated,
    shape: Shape = RectangleShape,
    borderWidth: Dp = 0.dp,
    borderColor: Color = SonaraTheme.colors.divider,
    content: @Composable () -> Unit
) {
    val colors = SonaraTheme.colors

    val containerColor = when (tonalLevel) {
        SonaraSurfaceTonalLevel.Canvas -> colors.background
        SonaraSurfaceTonalLevel.Elevated -> colors.surface
        SonaraSurfaceTonalLevel.Secondary -> colors.surfaceVariant
    }

    val borderModifier = if (borderWidth > 0.dp) {
        Modifier.border(borderWidth, borderColor, shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(borderModifier)
    ) {
        content()
    }
}
