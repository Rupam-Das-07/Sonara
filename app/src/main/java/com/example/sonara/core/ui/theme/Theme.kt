package com.example.sonara.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Authoritative Sonara Theme provider.
 * Enforces Petrol/Bone/Oxide brand identity and provides semantic design tokens.
 */
@Composable
fun SonaraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkSonaraColors else LightSonaraColors
    val typography = DefaultSonaraTypography
    val dimensions = DefaultSonaraDimensions
    val shapes = DefaultSonaraShapes
    val materials = if (darkTheme) DarkSonaraMaterials else LightSonaraMaterials

    CompositionLocalProvider(
        LocalSonaraColors provides colors,
        LocalSonaraTypography provides typography,
        LocalSonaraDimensions provides dimensions,
        LocalSonaraShapes provides shapes,
        LocalSonaraMaterials provides materials
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(),
            typography = typography.toMaterialTypography(),
            shapes = shapes.toMaterialShapes(),
            content = content
        )
    }
}

/**
 * Direct accessor for Sonara design tokens.
 */
object SonaraTheme {
    val colors: SonaraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSonaraColors.current

    val typography: SonaraTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalSonaraTypography.current

    val dimensions: SonaraDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalSonaraDimensions.current

    val shapes: SonaraShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalSonaraShapes.current

    val materials: SonaraMaterials
        @Composable
        @ReadOnlyComposable
        get() = LocalSonaraMaterials.current
}
