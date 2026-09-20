package com.example.sonara.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.theme.SonaraTheme

enum class SonaraIconButtonVariant {
    Standard,
    Filled,
    Accent,
    Ghost  // Transparent background, subordinate opacity -- matches Web Desktop .btn-ghost (opacity: 0.65)
}

/**
 * Universal, accessible Icon Button.
 * Enforces minimum 48dp touch bounds via M3 minimumInteractiveComponentSize
 * while preserving exact Sonara visual geometry.
 */
@Composable
fun SonaraIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: SonaraIconButtonVariant = SonaraIconButtonVariant.Standard,
    shape: Shape = SonaraTheme.shapes.circular,
    size: Dp = 40.dp, // Visual size, strictly divorced from 48dp interaction size
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit
) {
    val colors = SonaraTheme.colors

    val backgroundColor = when (variant) {
        SonaraIconButtonVariant.Standard -> Color.Transparent
        SonaraIconButtonVariant.Ghost    -> Color.Transparent
        SonaraIconButtonVariant.Filled   -> colors.surfaceVariant
        SonaraIconButtonVariant.Accent   -> colors.accent
    }

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize() // M3 Accessibility foundation (48dp touch target)
            .defaultMinSize(minWidth = size, minHeight = size) // Visual bounds
            .clip(shape)
            .background(backgroundColor)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
                this.role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
