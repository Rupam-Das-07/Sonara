package com.example.sonara.core.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

/**
 * Provides Android 12+ (API 31+) wallpaper-derived Material You color schemes,
 * mapped cleanly into Sonara's semantic [SonaraColors] contract.
 *
 * Safely falls back to [DarkSonaraColors] / [LightSonaraColors] (SONARA_CLASSIC)
 * whenever dynamic color is unavailable, unsupported, or if resolution fails.
 */
object DynamicColorProvider {

    /**
     * Checks whether dynamic wallpaper coloring is supported by the platform (Android S+ / API 31+).
     */
    fun isDynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Resolves [SonaraColors] from wallpaper-derived Material 3 dynamic color scheme.
     * Safely falls back to Sonara Classic on unsupported versions, null context, or exceptions.
     */
    fun resolveDynamicColors(context: Context?, darkTheme: Boolean): SonaraColors {
        if (context == null || !isDynamicColorAvailable()) {
            return if (darkTheme) DarkSonaraColors else LightSonaraColors
        }

        return try {
            val scheme = if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }

            SonaraColors(
                background = scheme.background,
                surface = scheme.surface,
                surfaceVariant = scheme.surfaceVariant,
                primaryText = scheme.onSurface,
                secondaryText = scheme.onSurfaceVariant,
                disabledText = scheme.onSurface.copy(alpha = 0.38f),
                divider = scheme.outlineVariant,
                accent = scheme.primary,
                accentSoft = scheme.secondary,
                accentContainer = scheme.primaryContainer,
                onAccent = scheme.onPrimary,
                playbackActive = scheme.primary,
                playbackBuffering = scheme.primary.copy(alpha = 0.65f),
                playbackUnavailable = scheme.error,
                success = if (darkTheme) SonaraPalette.SuccessDark else SonaraPalette.SuccessLight,
                warning = if (darkTheme) SonaraPalette.WarningDark else SonaraPalette.WarningLight,
                error = scheme.error,
                isDark = darkTheme
            )
        } catch (_: Exception) {
            if (darkTheme) DarkSonaraColors else LightSonaraColors
        }
    }
}
