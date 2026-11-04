package com.example.sonara.core.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.example.sonara.domain.model.ColorPalette

/**
 * Centralized registry of Sonara's eight curated color palettes.
 *
 * Each preset defines coordinated Light and Dark [SonaraColors] and [SonaraMaterials]
 * that adhere to WCAG 2.1 AA contrast requirements and preserve semantic status tokens.
 */
object SonaraPalettePresets {

    // ─── 1. Ocean Blue (Marine & Cyan) ──────────────────────────────────────────

    val DarkOceanBlueColors = SonaraColors(
        background = Color(0xFF0A1624),
        surface = Color(0xFF102235),
        surfaceVariant = Color(0xFF183049),
        primaryText = Color(0xFFF0F6FC),
        secondaryText = Color(0xFF94A3B8),
        disabledText = Color(0xFFF0F6FC).copy(alpha = 0.38f),
        divider = Color(0xFF1E3A5F),
        accent = Color(0xFF38BDF8),
        accentSoft = Color(0xFF7DD3FC),
        accentContainer = Color(0xFF0C3B5E),
        onAccent = Color(0xFF082F49),
        playbackActive = Color(0xFF38BDF8),
        playbackBuffering = Color(0xFF38BDF8).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorDark,
        success = SonaraPalette.SuccessDark,
        warning = SonaraPalette.WarningDark,
        error = SonaraPalette.ErrorDark,
        isDark = true
    )

    val LightOceanBlueColors = SonaraColors(
        background = Color(0xFFF0F7FF),
        surface = Color(0xFFE0EFFE),
        surfaceVariant = Color(0xFFBAE6FD),
        primaryText = Color(0xFF0F172A),
        secondaryText = Color(0xFF475569),
        disabledText = Color(0xFF0F172A).copy(alpha = 0.38f),
        divider = Color(0xFFBFDBFE),
        accent = Color(0xFF0284C7),
        accentSoft = Color(0xFF38BDF8),
        accentContainer = Color(0xFFE0F2FE),
        onAccent = Color(0xFFFFFFFF),
        playbackActive = Color(0xFF0284C7),
        playbackBuffering = Color(0xFF0284C7).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorLight,
        success = SonaraPalette.SuccessLight,
        warning = SonaraPalette.WarningLight,
        error = SonaraPalette.ErrorLight,
        isDark = false
    )

    // ─── 2. Rose (Crimson & Coral Bloom) ────────────────────────────────────────

    val DarkRoseColors = SonaraColors(
        background = Color(0xFF1A0B12),
        surface = Color(0xFF28121D),
        surfaceVariant = Color(0xFF381928),
        primaryText = Color(0xFFFFF1F2),
        secondaryText = Color(0xFFBEA7AE),
        disabledText = Color(0xFFFFF1F2).copy(alpha = 0.38f),
        divider = Color(0xFF4A1E33),
        accent = Color(0xFFFB7185),
        accentSoft = Color(0xFFFDA4AF),
        accentContainer = Color(0xFF5C1528),
        onAccent = Color(0xFF4C0519),
        playbackActive = Color(0xFFFB7185),
        playbackBuffering = Color(0xFFFB7185).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorDark,
        success = SonaraPalette.SuccessDark,
        warning = SonaraPalette.WarningDark,
        error = SonaraPalette.ErrorDark,
        isDark = true
    )

    val LightRoseColors = SonaraColors(
        background = Color(0xFFFFF5F6),
        surface = Color(0xFFFFE4E8),
        surfaceVariant = Color(0xFFFECDD6),
        primaryText = Color(0xFF1E0810),
        secondaryText = Color(0xFF6C4C54),
        disabledText = Color(0xFF1E0810).copy(alpha = 0.38f),
        divider = Color(0xFFFBCFE8),
        accent = Color(0xFFE11D48),
        accentSoft = Color(0xFFFB7185),
        accentContainer = Color(0xFFFFE4E6),
        onAccent = Color(0xFFFFFFFF),
        playbackActive = Color(0xFFE11D48),
        playbackBuffering = Color(0xFFE11D48).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorLight,
        success = SonaraPalette.SuccessLight,
        warning = SonaraPalette.WarningLight,
        error = SonaraPalette.ErrorLight,
        isDark = false
    )

    // ─── 3. Forest Green (Pine & Sage) ──────────────────────────────────────────

    val DarkForestGreenColors = SonaraColors(
        background = Color(0xFF0A1710),
        surface = Color(0xFF11241A),
        surfaceVariant = Color(0xFF1A3326),
        primaryText = Color(0xFFF0FDF4),
        secondaryText = Color(0xFF8FA899),
        disabledText = Color(0xFFF0FDF4).copy(alpha = 0.38f),
        divider = Color(0xFF1F4733),
        accent = Color(0xFF34D399),
        accentSoft = Color(0xFF6EE7B7),
        accentContainer = Color(0xFF104429),
        onAccent = Color(0xFF022C17),
        playbackActive = Color(0xFF34D399),
        playbackBuffering = Color(0xFF34D399).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorDark,
        success = SonaraPalette.SuccessDark,
        warning = SonaraPalette.WarningDark,
        error = SonaraPalette.ErrorDark,
        isDark = true
    )

    val LightForestGreenColors = SonaraColors(
        background = Color(0xFFF4FAF5),
        surface = Color(0xFFE5F5E8),
        surfaceVariant = Color(0xFFD0EAD5),
        primaryText = Color(0xFF062312),
        secondaryText = Color(0xFF486351),
        disabledText = Color(0xFF062312).copy(alpha = 0.38f),
        divider = Color(0xFFBEE3C5),
        accent = Color(0xFF059669),
        accentSoft = Color(0xFF34D399),
        accentContainer = Color(0xFFD1FAE5),
        onAccent = Color(0xFFFFFFFF),
        playbackActive = Color(0xFF059669),
        playbackBuffering = Color(0xFF059669).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorLight,
        success = SonaraPalette.SuccessLight,
        warning = SonaraPalette.WarningLight,
        error = SonaraPalette.ErrorLight,
        isDark = false
    )

    // ─── 4. Lavender (Amethyst & Lilac) ─────────────────────────────────────────

    val DarkLavenderColors = SonaraColors(
        background = Color(0xFF120E1E),
        surface = Color(0xFF1C162E),
        surfaceVariant = Color(0xFF282040),
        primaryText = Color(0xFFF5F3FF),
        secondaryText = Color(0xFFA19BAF),
        disabledText = Color(0xFFF5F3FF).copy(alpha = 0.38f),
        divider = Color(0xFF3B2F5C),
        accent = Color(0xFFA78BFA),
        accentSoft = Color(0xFFC4B5FD),
        accentContainer = Color(0xFF3B246B),
        onAccent = Color(0xFF2E1065),
        playbackActive = Color(0xFFA78BFA),
        playbackBuffering = Color(0xFFA78BFA).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorDark,
        success = SonaraPalette.SuccessDark,
        warning = SonaraPalette.WarningDark,
        error = SonaraPalette.ErrorDark,
        isDark = true
    )

    val LightLavenderColors = SonaraColors(
        background = Color(0xFFF8F6FF),
        surface = Color(0xFFEFEBFD),
        surfaceVariant = Color(0xFFE0D8FC),
        primaryText = Color(0xFF160B29),
        secondaryText = Color(0xFF594F6E),
        disabledText = Color(0xFF160B29).copy(alpha = 0.38f),
        divider = Color(0xFFDDD6FE),
        accent = Color(0xFF7C3AED),
        accentSoft = Color(0xFFA78BFA),
        accentContainer = Color(0xFFEDE9FE),
        onAccent = Color(0xFFFFFFFF),
        playbackActive = Color(0xFF7C3AED),
        playbackBuffering = Color(0xFF7C3AED).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorLight,
        success = SonaraPalette.SuccessLight,
        warning = SonaraPalette.WarningLight,
        error = SonaraPalette.ErrorLight,
        isDark = false
    )

    // ─── 5. Midnight (Indigo & Obsidian) ────────────────────────────────────────

    val DarkMidnightColors = SonaraColors(
        background = Color(0xFF0B0F19),
        surface = Color(0xFF13192B),
        surfaceVariant = Color(0xFF1C253E),
        primaryText = Color(0xFFEEF2FF),
        secondaryText = Color(0xFF8E98B4),
        disabledText = Color(0xFFEEF2FF).copy(alpha = 0.38f),
        divider = Color(0xFF232E4E),
        accent = Color(0xFF818CF8),
        accentSoft = Color(0xFFA5B4FC),
        accentContainer = Color(0xFF1E2559),
        onAccent = Color(0xFF1E1B4B),
        playbackActive = Color(0xFF818CF8),
        playbackBuffering = Color(0xFF818CF8).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorDark,
        success = SonaraPalette.SuccessDark,
        warning = SonaraPalette.WarningDark,
        error = SonaraPalette.ErrorDark,
        isDark = true
    )

    val LightMidnightColors = SonaraColors(
        background = Color(0xFFF5F7FF),
        surface = Color(0xFFE8ECFD),
        surfaceVariant = Color(0xFFD8E0FA),
        primaryText = Color(0xFF0A0D1B),
        secondaryText = Color(0xFF4A526E),
        disabledText = Color(0xFF0A0D1B).copy(alpha = 0.38f),
        divider = Color(0xFFC7D2FE),
        accent = Color(0xFF4338CA),
        accentSoft = Color(0xFF6366F1),
        accentContainer = Color(0xFFE0E7FF),
        onAccent = Color(0xFFFFFFFF),
        playbackActive = Color(0xFF4338CA),
        playbackBuffering = Color(0xFF4338CA).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorLight,
        success = SonaraPalette.SuccessLight,
        warning = SonaraPalette.WarningLight,
        error = SonaraPalette.ErrorLight,
        isDark = false
    )

    // ─── 6. AMOLED Black (True OLED Black Canvas) ───────────────────────────────

    val DarkAmoledBlackColors = SonaraColors(
        background = Color(0xFF000000), // True 100% black
        surface = Color(0xFF0D0D0D),
        surfaceVariant = Color(0xFF171717),
        primaryText = Color(0xFFF5F5F5),
        secondaryText = Color(0xFFA0A0A0),
        disabledText = Color(0xFFF5F5F5).copy(alpha = 0.38f),
        divider = Color(0xFF262626),
        accent = Color(0xFF38BDF8),
        accentSoft = Color(0xFF7DD3FC),
        accentContainer = Color(0xFF1A1A1A),
        onAccent = Color(0xFF000000),
        playbackActive = Color(0xFF38BDF8),
        playbackBuffering = Color(0xFF38BDF8).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorDark,
        success = SonaraPalette.SuccessDark,
        warning = SonaraPalette.WarningDark,
        error = SonaraPalette.ErrorDark,
        isDark = true
    )

    val LightAmoledBlackColors = SonaraColors(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFFE5E5E5),
        primaryText = Color(0xFF171717),
        secondaryText = Color(0xFF525252),
        disabledText = Color(0xFF171717).copy(alpha = 0.38f),
        divider = Color(0xFFD4D4D4),
        accent = Color(0xFF0284C7),
        accentSoft = Color(0xFF38BDF8),
        accentContainer = Color(0xFFE0F2FE),
        onAccent = Color(0xFFFFFFFF),
        playbackActive = Color(0xFF0284C7),
        playbackBuffering = Color(0xFF0284C7).copy(alpha = 0.65f),
        playbackUnavailable = SonaraPalette.ErrorLight,
        success = SonaraPalette.SuccessLight,
        warning = SonaraPalette.WarningLight,
        error = SonaraPalette.ErrorLight,
        isDark = false
    )

    // ─── Resolvers ─────────────────────────────────────────────────────────────

    /**
     * Resolves the authoritative [SonaraColors] for a given palette and light/dark mode.
     */
    fun colorsFor(
        palette: ColorPalette,
        darkTheme: Boolean,
        context: Context? = null
    ): SonaraColors {
        return when (palette) {
            ColorPalette.SONARA_CLASSIC -> if (darkTheme) DarkSonaraColors else LightSonaraColors
            ColorPalette.OCEAN_BLUE -> if (darkTheme) DarkOceanBlueColors else LightOceanBlueColors
            ColorPalette.ROSE -> if (darkTheme) DarkRoseColors else LightRoseColors
            ColorPalette.FOREST_GREEN -> if (darkTheme) DarkForestGreenColors else LightForestGreenColors
            ColorPalette.LAVENDER -> if (darkTheme) DarkLavenderColors else LightLavenderColors
            ColorPalette.MIDNIGHT -> if (darkTheme) DarkMidnightColors else LightMidnightColors
            ColorPalette.AMOLED_BLACK -> if (darkTheme) DarkAmoledBlackColors else LightAmoledBlackColors
            ColorPalette.DYNAMIC_WALLPAPER -> DynamicColorProvider.resolveDynamicColors(context, darkTheme)
        }
    }

    /**
     * Resolves the authoritative [SonaraMaterials] for persistent chrome surfaces.
     * Preserves strict restrained alphas and tonal foundation rules.
     */
    fun materialsFor(palette: ColorPalette, colors: SonaraColors): SonaraMaterials {
        return when {
            palette == ColorPalette.SONARA_CLASSIC && colors.isDark -> DarkSonaraMaterials
            palette == ColorPalette.SONARA_CLASSIC && !colors.isDark -> LightSonaraMaterials
            palette == ColorPalette.AMOLED_BLACK && colors.isDark -> SonaraMaterials(
                topBar = Color(0xFF000000).copy(alpha = SonaraMaterialTokens.DarkTopBarAlpha),
                player = Color(0xFF0A0A0A).copy(alpha = SonaraMaterialTokens.DarkPlayerAlpha),
                navigation = Color(0xFF0A0A0A).copy(alpha = SonaraMaterialTokens.DarkNavigationAlpha),
                base = Color(0xFF000000),
                topBarAlpha = SonaraMaterialTokens.DarkTopBarAlpha,
                playerAlpha = SonaraMaterialTokens.DarkPlayerAlpha,
                navigationAlpha = SonaraMaterialTokens.DarkNavigationAlpha,
                baseAlpha = SonaraMaterialTokens.DarkBaseAlpha
            )
            colors.isDark -> SonaraMaterials(
                topBar = colors.background.copy(alpha = SonaraMaterialTokens.DarkTopBarAlpha),
                player = colors.surface.copy(alpha = SonaraMaterialTokens.DarkPlayerAlpha),
                navigation = colors.surface.copy(alpha = SonaraMaterialTokens.DarkNavigationAlpha),
                base = colors.background,
                topBarAlpha = SonaraMaterialTokens.DarkTopBarAlpha,
                playerAlpha = SonaraMaterialTokens.DarkPlayerAlpha,
                navigationAlpha = SonaraMaterialTokens.DarkNavigationAlpha,
                baseAlpha = SonaraMaterialTokens.DarkBaseAlpha
            )
            else -> SonaraMaterials(
                topBar = colors.background.copy(alpha = SonaraMaterialTokens.LightTopBarAlpha),
                player = colors.surface.copy(alpha = SonaraMaterialTokens.LightPlayerAlpha),
                navigation = colors.surface.copy(alpha = SonaraMaterialTokens.LightNavigationAlpha),
                base = colors.background,
                topBarAlpha = SonaraMaterialTokens.LightTopBarAlpha,
                playerAlpha = SonaraMaterialTokens.LightPlayerAlpha,
                navigationAlpha = SonaraMaterialTokens.LightNavigationAlpha,
                baseAlpha = SonaraMaterialTokens.LightBaseAlpha
            )
        }
    }
}
