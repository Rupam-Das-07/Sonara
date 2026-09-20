package com.example.sonara.core.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * Universal quiet divider separator.
 */
@Composable
fun SonaraDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = SonaraTheme.dimensions.dividerThickness,
    color: Color = SonaraTheme.colors.divider
) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = thickness,
        color = color
    )
}
