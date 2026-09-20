package com.example.sonara.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 4dp-based spacing and dimension scale as frozen in DESIGN_SYSTEM_ANDROID.md §4.
 */
@Immutable
data class SonaraDimensions(
    val spaceXs: Dp = 4.dp,
    val spaceSm: Dp = 8.dp,
    val spaceMd: Dp = 12.dp,
    val spaceLg: Dp = 16.dp,
    val spaceXl: Dp = 20.dp,
    val space2Xl: Dp = 24.dp,
    val space3Xl: Dp = 32.dp,
    val space4Xl: Dp = 40.dp,
    val space5Xl: Dp = 48.dp,
    val space6Xl: Dp = 64.dp,
    val minTouchTarget: Dp = 48.dp,
    val iconSmall: Dp = 20.dp,
    val iconMedium: Dp = 24.dp,
    val iconLarge: Dp = 32.dp,
    val dividerThickness: Dp = 1.dp
)

val DefaultSonaraDimensions = SonaraDimensions()
val LocalSonaraDimensions = staticCompositionLocalOf { DefaultSonaraDimensions }
