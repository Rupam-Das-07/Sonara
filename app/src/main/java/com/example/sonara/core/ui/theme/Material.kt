package com.example.sonara.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Sonara's frozen semantic material tokens for Point 3: Moderate (02).
 *
 * Implements subtle, physical material depth through restrained transparency on
 * persistent chrome surfaces sitting above the scrolling content layer.
 *
 * FROZEN RULES:
 *  - NO BLUR: Absolutely forbidden. Controlled alpha + tonal foundation only.
 *  - NO ARTWORK TINT: Album art must NEVER recolor chrome surfaces.
 *  - NO OXIDE GLASS TINT: Oxide remains strictly a semantic/action color.
 *  - Persistent chrome only: Top Bar, MiniPlayer, Bottom Navigation, Navigation Rail.
 *  - Everything else (Home background, cards, lists, dialogs, sheets) remains 100% opaque.
 */
object SonaraMaterialTokens {
    // Dark Mode Alphas: High-legibility restrained material (TopBar 0.90f, Player 0.95f, Nav 0.90f)
    const val DarkTopBarAlpha = 0.90f
    const val DarkPlayerAlpha = 0.95f
    const val DarkNavigationAlpha = 0.90f
    const val DarkBaseAlpha = 1.00f

    // Light Mode Alphas (Frozen)
    const val LightTopBarAlpha = 0.92f
    const val LightPlayerAlpha = 0.90f
    const val LightNavigationAlpha = 0.92f
    const val LightBaseAlpha = 1.00f
}

/**
 * Semantic material surface contract for Sonara Android.
 * Provides the four approved persistent material surfaces plus the opaque foundation.
 */
@Immutable
data class SonaraMaterials(
    val topBar: Color,
    val player: Color,
    val navigation: Color,
    val base: Color,
    val topBarAlpha: Float,
    val playerAlpha: Float,
    val navigationAlpha: Float,
    val baseAlpha: Float = 1.00f
)

/**
 * Dark Mode: Petrol-derived material foundation (#071A1C).
 */
val DarkSonaraMaterials = SonaraMaterials(
    topBar = SonaraPalette.PetrolCanvas.copy(alpha = SonaraMaterialTokens.DarkTopBarAlpha),
    player = Color(0xFF09191B).copy(alpha = SonaraMaterialTokens.DarkPlayerAlpha),
    navigation = SonaraPalette.PetrolElevatedSurface.copy(alpha = SonaraMaterialTokens.DarkNavigationAlpha),
    base = SonaraPalette.PetrolCanvas,
    topBarAlpha = SonaraMaterialTokens.DarkTopBarAlpha,
    playerAlpha = SonaraMaterialTokens.DarkPlayerAlpha,
    navigationAlpha = SonaraMaterialTokens.DarkNavigationAlpha,
    baseAlpha = SonaraMaterialTokens.DarkBaseAlpha
)

/**
 * Light Mode: Bone-derived material foundation (#F6F2EA).
 */
val LightSonaraMaterials = SonaraMaterials(
    topBar = SonaraPalette.BoneCanvas.copy(alpha = SonaraMaterialTokens.LightTopBarAlpha),
    player = SonaraPalette.BoneElevatedSurface.copy(alpha = SonaraMaterialTokens.LightPlayerAlpha),
    navigation = SonaraPalette.BoneElevatedSurface.copy(alpha = SonaraMaterialTokens.LightNavigationAlpha),
    base = SonaraPalette.BoneCanvas,
    topBarAlpha = SonaraMaterialTokens.LightTopBarAlpha,
    playerAlpha = SonaraMaterialTokens.LightPlayerAlpha,
    navigationAlpha = SonaraMaterialTokens.LightNavigationAlpha,
    baseAlpha = SonaraMaterialTokens.LightBaseAlpha
)

val LocalSonaraMaterials = staticCompositionLocalOf { DarkSonaraMaterials }