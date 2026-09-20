package com.example.sonara.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Raw Sonara brand color palette as frozen in DESIGN_SYSTEM_ANDROID.md §2.1.
 */
object SonaraPalette {
    // Dark Theme (Petrol Foundation)
    val PetrolCanvas = Color(0xFF071A1C)
    val PetrolElevatedSurface = Color(0xFF0F2426)
    val PetrolSecondarySurface = Color(0xFF152F31)
    val BonePrimaryText = Color(0xFFE7E1D6)
    val BoneSecondaryText = Color(0xFFA8A297)
    val PetrolBorder = Color(0xFF264043)
    val OxideAccent = Color(0xFFA25A3A)
    val OxideAccentSoft = Color(0xFF7C4A2F)
    val OxideAccentContainerDark = Color(0xFF2E1C15)

    // Light Theme (Bone Foundation)
    val BoneCanvas = Color(0xFFF6F2EA)
    val BoneElevatedSurface = Color(0xFFECE6DA)
    val BoneSecondarySurface = Color(0xFFE2D8CF)
    val DarkPrimaryText = Color(0xFF1B1F1E)
    val DarkSecondaryText = Color(0xFF5B5A53)
    val BoneBorder = Color(0xFFD2CCC1)
    val OxideAccentLight = Color(0xFFA25A3A)
    val AccentSoftLight = Color(0xFF7C8862)
    val OxideAccentContainerLight = Color(0xFFEBD8CF)

    // Shared Contrast-Safe Foreground Tokens on Oxide (#A25A3A)
    val OnAccentBone = Color(0xFFF6F2EA)
    val OnAccentWhite = Color(0xFFFFFFFF)

    // Semantic Status Colors (DESIGN_SYSTEM_ANDROID.md §2.2)
    val SuccessDark = Color(0xFF3D7A5A)
    val SuccessLight = Color(0xFF2E6B4B)
    val WarningDark = Color(0xFFC4842E)
    val WarningLight = Color(0xFF9C6318)
    val ErrorDark = Color(0xFFC04D44)
    val ErrorLight = Color(0xFFA63A32)
}

/**
 * Semantic color contract for Sonara Android.
 * Single source of truth for all UI theming.
 */
@Immutable
data class SonaraColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val disabledText: Color,
    val divider: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val playbackActive: Color,
    val playbackBuffering: Color,
    val playbackUnavailable: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val isDark: Boolean
) {
    /**
     * Derives Material 3 ColorScheme directly from SonaraColors,
     * ensuring a single authoritative source of truth.
     */
    fun toMaterialColorScheme(): ColorScheme {
        return if (isDark) {
            darkColorScheme(
                primary = accent,
                onPrimary = onAccent,
                primaryContainer = accentContainer,
                onPrimaryContainer = onAccent,
                secondary = accentSoft,
                onSecondary = onAccent,
                background = background,
                onBackground = primaryText,
                surface = surface,
                onSurface = primaryText,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = secondaryText,
                outline = divider,
                outlineVariant = divider,
                error = error,
                onError = SonaraPalette.OnAccentBone
            )
        } else {
            lightColorScheme(
                primary = accent,
                onPrimary = onAccent,
                primaryContainer = accentContainer,
                onPrimaryContainer = SonaraPalette.DarkPrimaryText,
                secondary = accentSoft,
                onSecondary = onAccent,
                background = background,
                onBackground = primaryText,
                surface = surface,
                onSurface = primaryText,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = secondaryText,
                outline = divider,
                outlineVariant = divider,
                error = error,
                onError = SonaraPalette.OnAccentWhite
            )
        }
    }
}

val DarkSonaraColors = SonaraColors(
    background = SonaraPalette.PetrolCanvas,
    surface = SonaraPalette.PetrolElevatedSurface,
    surfaceVariant = SonaraPalette.PetrolSecondarySurface,
    primaryText = SonaraPalette.BonePrimaryText,
    secondaryText = SonaraPalette.BoneSecondaryText,
    disabledText = SonaraPalette.BonePrimaryText.copy(alpha = 0.38f),
    divider = SonaraPalette.PetrolBorder,
    accent = SonaraPalette.OxideAccent,
    accentSoft = SonaraPalette.OxideAccentSoft,
    accentContainer = SonaraPalette.OxideAccentContainerDark,
    onAccent = SonaraPalette.OnAccentBone,
    playbackActive = SonaraPalette.OxideAccent,
    playbackBuffering = SonaraPalette.OxideAccent.copy(alpha = 0.65f),
    playbackUnavailable = SonaraPalette.ErrorDark,
    success = SonaraPalette.SuccessDark,
    warning = SonaraPalette.WarningDark,
    error = SonaraPalette.ErrorDark,
    isDark = true
)

val LightSonaraColors = SonaraColors(
    background = SonaraPalette.BoneCanvas,
    surface = SonaraPalette.BoneElevatedSurface,
    surfaceVariant = SonaraPalette.BoneSecondarySurface,
    primaryText = SonaraPalette.DarkPrimaryText,
    secondaryText = SonaraPalette.DarkSecondaryText,
    disabledText = SonaraPalette.DarkPrimaryText.copy(alpha = 0.38f),
    divider = SonaraPalette.BoneBorder,
    accent = SonaraPalette.OxideAccentLight,
    accentSoft = SonaraPalette.AccentSoftLight,
    accentContainer = SonaraPalette.OxideAccentContainerLight,
    onAccent = SonaraPalette.OnAccentWhite,
    playbackActive = SonaraPalette.OxideAccentLight,
    playbackBuffering = SonaraPalette.OxideAccentLight.copy(alpha = 0.65f),
    playbackUnavailable = SonaraPalette.ErrorLight,
    success = SonaraPalette.SuccessLight,
    warning = SonaraPalette.WarningLight,
    error = SonaraPalette.ErrorLight,
    isDark = false
)

val LocalSonaraColors = compositionLocalOf { DarkSonaraColors }
