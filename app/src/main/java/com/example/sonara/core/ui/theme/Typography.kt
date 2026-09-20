package com.example.sonara.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.sonara.R

/**
 * Modern Bold Native Android Typography System for Sonara (Outfit + Manrope).
 *
 * Configured with explicit FontVariation.weight settings on variable fonts
 * to guarantee true bold, semibold, and medium rendering on Android hardware.
 */
@OptIn(ExperimentalTextApi::class)
val OutfitFontFamily = FontFamily(
    Font(
        R.font.outfit,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.outfit,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        R.font.outfit,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        R.font.outfit,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
    Font(
        R.font.outfit,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800))
    )
)

@OptIn(ExperimentalTextApi::class)
val ManropeFontFamily = FontFamily(
    Font(
        R.font.manrope,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.manrope,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        R.font.manrope,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        R.font.manrope,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    ),
    Font(
        R.font.manrope,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800))
    )
)

// Backward compatibility aliases
val ClashDisplayFontFamily = ManropeFontFamily
val SatoshiFontFamily = OutfitFontFamily
val OpenSansFontFamily = OutfitFontFamily
val InterFontFamily = ManropeFontFamily
val NotoSansFontFamily = OutfitFontFamily

@Immutable
data class SonaraTypography(
    /** Major editorial statement / artist profile header — Manrope ExtraBold (800) */
    val display: TextStyle = TextStyle(
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    /** Journal Masthead — Manrope ExtraBold (800) */
    val journalMasthead: TextStyle = TextStyle(
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp
    ),
    /** Screen Titles — Manrope ExtraBold (800) */
    val screenTitle: TextStyle = TextStyle(
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp
    ),
    /** Contextual greeting ("Good morning") — Cursive Bold (700) */
    val greeting: TextStyle = TextStyle(
        fontFamily = FontFamily.Cursive,
        fontWeight = FontWeight.Bold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    /** Main section headings — Outfit Bold (700) */
    val sectionTitle: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    /** Card & playlist headers — Outfit Bold (700) */
    val cardTitle: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    /** Track titles — Outfit SemiBold (600) */
    val trackTitle: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    /** Artist metadata & subtitles — Outfit Medium (500) */
    val artistMetadata: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    /** Body text — Outfit Medium (500) */
    val body: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    /** Secondary body & editorial descriptions — Outfit Medium (500) */
    val secondaryBody: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    ),
    /** Badges, track counts & sub-captions — Outfit SemiBold (600) */
    val caption: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp
    ),
    /** Bottom navigation labels — Outfit SemiBold (600) */
    val navigationLabel: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.sp
    ),
    /** Buttons & primary controls — Outfit SemiBold (600) */
    val buttonLabel: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    /** Playback timing & durations — Outfit SemiBold (600) */
    val playbackTiming: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    val journalStamp: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 1.2.sp
    ),
    val lyrics: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    val activeLyrics: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    val romanizedLyrics: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )
) {
    /**
     * Maps SonaraTypography to Material 3 Typography for component compatibility.
     */
    fun toMaterialTypography(): Typography {
        return Typography(
            displayLarge = display,
            headlineLarge = screenTitle,
            headlineMedium = sectionTitle,
            titleLarge = cardTitle,
            titleMedium = trackTitle,
            titleSmall = artistMetadata,
            bodyLarge = body,
            bodyMedium = secondaryBody,
            bodySmall = caption,
            labelLarge = buttonLabel,
            labelMedium = navigationLabel,
            labelSmall = playbackTiming
        )
    }
}

val DefaultSonaraTypography = SonaraTypography()
val LocalSonaraTypography = staticCompositionLocalOf { DefaultSonaraTypography }
