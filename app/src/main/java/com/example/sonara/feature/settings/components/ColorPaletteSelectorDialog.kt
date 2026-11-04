package com.example.sonara.feature.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sonara.R
import com.example.sonara.core.ui.theme.DynamicColorProvider
import com.example.sonara.core.ui.theme.SonaraColors
import com.example.sonara.core.ui.theme.SonaraPalettePresets
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.ColorPalette

/**
 * Returns the localized string resource for a given [ColorPalette] display name.
 */
fun getPaletteDisplayNameRes(palette: ColorPalette): Int {
    return when (palette) {
        ColorPalette.SONARA_CLASSIC -> R.string.palette_sonara_classic
        ColorPalette.OCEAN_BLUE -> R.string.palette_ocean_blue
        ColorPalette.ROSE -> R.string.palette_rose
        ColorPalette.FOREST_GREEN -> R.string.palette_forest_green
        ColorPalette.LAVENDER -> R.string.palette_lavender
        ColorPalette.MIDNIGHT -> R.string.palette_midnight
        ColorPalette.AMOLED_BLACK -> R.string.palette_amoled_black
        ColorPalette.DYNAMIC_WALLPAPER -> R.string.palette_dynamic_wallpaper
    }
}

/**
 * Returns the localized string resource for a given [ColorPalette] short description.
 */
fun getPaletteDescriptionRes(palette: ColorPalette): Int {
    return when (palette) {
        ColorPalette.SONARA_CLASSIC -> R.string.palette_sonara_classic_desc
        ColorPalette.OCEAN_BLUE -> R.string.palette_ocean_blue_desc
        ColorPalette.ROSE -> R.string.palette_rose_desc
        ColorPalette.FOREST_GREEN -> R.string.palette_forest_green_desc
        ColorPalette.LAVENDER -> R.string.palette_lavender_desc
        ColorPalette.MIDNIGHT -> R.string.palette_midnight_desc
        ColorPalette.AMOLED_BLACK -> R.string.palette_amoled_black_desc
        ColorPalette.DYNAMIC_WALLPAPER -> R.string.palette_dynamic_wallpaper_desc
    }
}

/**
 * Modal selection dialog displaying all eight curated Sonara colour palettes.
 *
 * Each option renders a live preview card illustrating the coordinated:
 * [Background / Canvas | Surface | Accent | Primary Text] tokens.
 *
 * Previews respect the application's currently resolved Dark/Light mode and update
 * immediately when the system theme changes.
 */
@Composable
fun ColorPaletteSelectorDialog(
    currentPalette: ColorPalette,
    onSelect: (ColorPalette) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = stringResource(R.string.setting_color_palette),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ColorPalette.entries, key = { it.name }) { palette ->
                    PalettePreviewCard(
                        palette = palette,
                        isSelected = currentPalette == palette,
                        onSelect = { onSelect(palette) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_close),
                    color = colors.accent,
                    style = typography.buttonLabel
                )
            }
        }
    )
}

/**
 * Interactive preview card for an individual [ColorPalette].
 * Renders actual resolved palette colours via [SonaraPalettePresets.colorsFor].
 */
@Composable
fun PalettePreviewCard(
    palette: ColorPalette,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val isDark = colors.isDark

    val isDynamic = palette == ColorPalette.DYNAMIC_WALLPAPER
    val isDynamicSupported = isDynamic && DynamicColorProvider.isDynamicColorAvailable()

    // Resolve candidate colors for the active theme mode (Dark vs Light)
    val previewColors = remember(palette, isDark, context) {
        SonaraPalettePresets.colorsFor(palette, isDark, context)
    }

    val fallbackColors = remember(isDark, context) {
        SonaraPalettePresets.colorsFor(ColorPalette.SONARA_CLASSIC, isDark, context)
    }
    val isMonetActive = isDynamicSupported && (previewColors != fallbackColors)

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) colors.surfaceVariant else colors.surface,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) colors.accent else colors.divider
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .wrapContentHeight()
            .semantics {
                role = Role.RadioButton
                this.selected = isSelected
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Coordinated 4-colour swatch pill
            PaletteSwatchPill(colors = previewColors)

            // Palette info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(getPaletteDisplayNameRes(palette)),
                        style = typography.body.copy(
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = colors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (isDynamic && isMonetActive) {
                        Text(
                            text = stringResource(R.string.palette_dynamic_wallpaper_monet_active),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.accent,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.accentContainer)
                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
                        )
                    }
                }

                if (isDynamic && !isDynamicSupported) {
                    Text(
                        text = stringResource(R.string.palette_dynamic_wallpaper_unsupported),
                        style = typography.caption.copy(fontSize = 11.sp),
                        color = colors.warning,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = stringResource(getPaletteDescriptionRes(palette)),
                        style = typography.caption.copy(fontSize = 11.5.sp),
                        color = colors.secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Radio indicator
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = colors.accent,
                    unselectedColor = colors.secondaryText
                )
            )
        }
    }
}

/**
 * Compact horizontal swatch pill illustrating the coordinated:
 * [Background / Canvas | Surface | Accent | Primary Text] tokens.
 */
@Composable
fun PaletteSwatchPill(
    colors: SonaraColors,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(pillShape)
            .border(0.75.dp, SonaraTheme.colors.divider, pillShape)
    ) {
        // Canvas / Background
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.background)
        )
        // Surface
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.surface)
        )
        // Accent
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.accent)
        )
        // Primary Text
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(colors.primaryText)
        )
    }
}
