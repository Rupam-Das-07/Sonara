package com.example.sonara.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.theme.SonaraTheme

enum class SonaraButtonVariant {
    Primary,
    Secondary,
    Text
}

/**
 * Universal, feature-agnostic Sonara Button.
 * Wraps M3 Buttons to inherit interaction states, semantics, and accessibility (48dp hit area),
 * while explicitly controlling the visual geometry, typography, and color to preserve Sonara identity.
 */
@Composable
fun SonaraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SonaraButtonVariant = SonaraButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = SonaraTheme.shapes.medium
) {
    val colors = SonaraTheme.colors
    val dimensions = SonaraTheme.dimensions

    val contentPadding = PaddingValues(
        horizontal = dimensions.spaceLg,
        vertical = dimensions.spaceMd
    )

    // Visual size constraint only. M3 buttons automatically apply minimumInteractiveComponentSize()
    // underneath to guarantee the 48dp touch target without visually inflating the button.
    val minSizeModifier = modifier.defaultMinSize(
        minWidth = 80.dp,
        minHeight = 40.dp // Visual height
    )

    when (variant) {
        SonaraButtonVariant.Primary -> {
            Button(
                onClick = onClick,
                modifier = minSizeModifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                    disabledContainerColor = colors.accent.copy(alpha = 0.38f),
                    disabledContentColor = colors.onAccent.copy(alpha = 0.5f)
                ),
                contentPadding = contentPadding
            ) {
                ButtonContent(
                    text = text,
                    textStyle = SonaraTheme.typography.buttonLabel,
                    textColor = colors.onAccent,
                    loading = loading,
                    loadingColor = colors.onAccent,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon
                )
            }
        }
        SonaraButtonVariant.Secondary -> {
            OutlinedButton(
                onClick = onClick,
                modifier = minSizeModifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface,
                    contentColor = colors.primaryText,
                    disabledContainerColor = colors.surface.copy(alpha = 0.38f),
                    disabledContentColor = colors.disabledText
                ),
                border = null,
                contentPadding = contentPadding
            ) {
                ButtonContent(
                    text = text,
                    textStyle = SonaraTheme.typography.buttonLabel,
                    textColor = colors.primaryText,
                    loading = loading,
                    loadingColor = colors.accent,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon
                )
            }
        }
        SonaraButtonVariant.Text -> {
            TextButton(
                onClick = onClick,
                modifier = minSizeModifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.accent,
                    disabledContentColor = colors.disabledText
                ),
                contentPadding = contentPadding
            ) {
                ButtonContent(
                    text = text,
                    textStyle = SonaraTheme.typography.buttonLabel,
                    textColor = colors.accent,
                    loading = loading,
                    loadingColor = colors.accent,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon
                )
            }
        }
    }
}

@Composable
private fun ButtonContent(
    text: String,
    textStyle: TextStyle,
    textColor: Color,
    loading: Boolean,
    loadingColor: Color,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: (@Composable () -> Unit)?
) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = loadingColor,
            strokeWidth = 2.dp
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                it()
                Spacer(modifier = Modifier.width(SonaraTheme.dimensions.spaceSm))
            }
            Text(
                text = text,
                style = textStyle,
                color = textColor
            )
            trailingIcon?.let {
                Spacer(modifier = Modifier.width(SonaraTheme.dimensions.spaceSm))
                it()
            }
        }
    }
}
