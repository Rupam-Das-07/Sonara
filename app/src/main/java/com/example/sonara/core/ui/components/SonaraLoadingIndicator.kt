package com.example.sonara.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * Universal contextual loading indicator.
 */
@Composable
fun SonaraLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    color: Color = SonaraTheme.colors.accent,
    trackColor: Color = SonaraTheme.colors.surfaceVariant,
    strokeWidth: Dp = 3.dp
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(size),
            color = color,
            trackColor = trackColor,
            strokeWidth = strokeWidth
        )
    }
}
