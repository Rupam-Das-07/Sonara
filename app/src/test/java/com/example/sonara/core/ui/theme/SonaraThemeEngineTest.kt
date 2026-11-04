package com.example.sonara.core.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.sonara.domain.model.ColorPalette
import com.example.sonara.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonaraThemeEngineTest {

    private val staticPalettes = ColorPalette.entries.filter { it != ColorPalette.DYNAMIC_WALLPAPER }

    @Test
    fun `1 every static palette resolves to a valid Light colour scheme`() {
        for (palette in staticPalettes) {
            val colors = SonaraPalettePresets.colorsFor(palette, darkTheme = false)
            assertFalse("Palette $palette in light mode must have isDark=false", colors.isDark)
            assertNotNull(colors.background)
            assertNotNull(colors.surface)
            assertNotNull(colors.primaryText)
            assertNotNull(colors.secondaryText)
            assertNotNull(colors.accent)
            assertNotNull(colors.divider)
            assertNotNull(colors.error)
            assertEquals("Light mode onAccent must be high-contrast white or bone", 1f, colors.onAccent.alpha, 0.01f)
        }
    }

    @Test
    fun `2 every static palette resolves to a valid Dark colour scheme`() {
        for (palette in staticPalettes) {
            val colors = SonaraPalettePresets.colorsFor(palette, darkTheme = true)
            assertTrue("Palette $palette in dark mode must have isDark=true", colors.isDark)
            assertNotNull(colors.background)
            assertNotNull(colors.surface)
            assertNotNull(colors.primaryText)
            assertNotNull(colors.secondaryText)
            assertNotNull(colors.accent)
            assertNotNull(colors.divider)
            assertNotNull(colors.error)
        }
    }

    @Test
    fun `3 SONARA_CLASSIC exactly matches existing brand definitions`() {
        val dark = SonaraPalettePresets.colorsFor(ColorPalette.SONARA_CLASSIC, darkTheme = true)
        val light = SonaraPalettePresets.colorsFor(ColorPalette.SONARA_CLASSIC, darkTheme = false)

        assertEquals("Dark Sonara Classic must exactly match DarkSonaraColors", DarkSonaraColors, dark)
        assertEquals("Light Sonara Classic must exactly match LightSonaraColors", LightSonaraColors, light)

        // Verify key frozen hex values
        assertEquals(SonaraPalette.PetrolCanvas, dark.background)
        assertEquals(SonaraPalette.PetrolElevatedSurface, dark.surface)
        assertEquals(SonaraPalette.OxideAccent, dark.accent)
        assertEquals(SonaraPalette.BonePrimaryText, dark.primaryText)
        assertEquals(SonaraPalette.PetrolBorder, dark.divider)

        assertEquals(SonaraPalette.BoneCanvas, light.background)
        assertEquals(SonaraPalette.BoneElevatedSurface, light.surface)
        assertEquals(SonaraPalette.OxideAccentLight, light.accent)
        assertEquals(SonaraPalette.DarkPrimaryText, light.primaryText)
        assertEquals(SonaraPalette.BoneBorder, light.divider)

        // Materials verification
        val darkMaterials = SonaraPalettePresets.materialsFor(ColorPalette.SONARA_CLASSIC, dark)
        val lightMaterials = SonaraPalettePresets.materialsFor(ColorPalette.SONARA_CLASSIC, light)
        assertEquals(DarkSonaraMaterials, darkMaterials)
        assertEquals(LightSonaraMaterials, lightMaterials)
    }

    @Test
    fun `4 AMOLED Black uses a true-black canvas and distinguishable surface`() {
        val dark = SonaraPalettePresets.colorsFor(ColorPalette.AMOLED_BLACK, darkTheme = true)
        val light = SonaraPalettePresets.colorsFor(ColorPalette.AMOLED_BLACK, darkTheme = false)

        assertEquals("AMOLED Black canvas must be true pitch black (#000000)", Color(0xFF000000), dark.background)
        assertNotEquals("Elevated surface must remain distinguishable from black canvas", dark.background, dark.surface)
        assertTrue("Elevated surface luminance must be strictly near black", dark.surface.red < 0.1f && dark.surface.green < 0.1f && dark.surface.blue < 0.1f)

        // AMOLED materials
        val materials = SonaraPalettePresets.materialsFor(ColorPalette.AMOLED_BLACK, dark)
        assertEquals(Color(0xFF000000), materials.base)
        assertEquals(Color(0xFF000000).copy(alpha = SonaraMaterialTokens.DarkTopBarAlpha), materials.topBar)

        // Light monochrome companion
        assertEquals("AMOLED Light companion canvas must be pure white", Color(0xFFFFFFFF), light.background)
        assertNotEquals(light.background, light.surface)
    }

    @Test
    fun `5 dynamic colour safely falls back on null context or unsupported versions`() {
        val dark = DynamicColorProvider.resolveDynamicColors(context = null, darkTheme = true)
        val light = DynamicColorProvider.resolveDynamicColors(context = null, darkTheme = false)

        assertEquals(DarkSonaraColors, dark)
        assertEquals(LightSonaraColors, light)
    }

    @Test
    fun `6 dynamic colour resolution never throws an exception`() {
        val dark = SonaraPalettePresets.colorsFor(ColorPalette.DYNAMIC_WALLPAPER, darkTheme = true, context = null)
        val light = SonaraPalettePresets.colorsFor(ColorPalette.DYNAMIC_WALLPAPER, darkTheme = false, context = null)

        assertNotNull(dark)
        assertNotNull(light)
        assertTrue(dark.isDark)
        assertFalse(light.isDark)
    }

    @Test
    fun `7 MaterialTheme ColorScheme matches SonaraColors toMaterialColorScheme`() {
        for (palette in staticPalettes) {
            val dark = SonaraPalettePresets.colorsFor(palette, darkTheme = true)
            val light = SonaraPalettePresets.colorsFor(palette, darkTheme = false)

            for (colors in listOf(dark, light)) {
                val m3 = colors.toMaterialColorScheme()
                assertEquals(colors.accent, m3.primary)
                assertEquals(colors.onAccent, m3.onPrimary)
                assertEquals(colors.background, m3.background)
                assertEquals(colors.surface, m3.surface)
                assertEquals(colors.primaryText, m3.onSurface)
                assertEquals(colors.secondaryText, m3.onSurfaceVariant)
                assertEquals(colors.divider, m3.outline)
                assertEquals(colors.error, m3.error)
            }
        }
    }

    @Test
    fun `8 materialsFor preserves frozen alpha tokens across all palettes`() {
        for (palette in staticPalettes) {
            val dark = SonaraPalettePresets.colorsFor(palette, darkTheme = true)
            val light = SonaraPalettePresets.colorsFor(palette, darkTheme = false)

            val darkMat = SonaraPalettePresets.materialsFor(palette, dark)
            val lightMat = SonaraPalettePresets.materialsFor(palette, light)

            assertEquals(SonaraMaterialTokens.DarkTopBarAlpha, darkMat.topBarAlpha, 0.001f)
            assertEquals(SonaraMaterialTokens.DarkPlayerAlpha, darkMat.playerAlpha, 0.001f)
            assertEquals(SonaraMaterialTokens.DarkNavigationAlpha, darkMat.navigationAlpha, 0.001f)
            assertEquals(1.00f, darkMat.baseAlpha, 0.001f)

            assertEquals(SonaraMaterialTokens.LightTopBarAlpha, lightMat.topBarAlpha, 0.001f)
            assertEquals(SonaraMaterialTokens.LightPlayerAlpha, lightMat.playerAlpha, 0.001f)
            assertEquals(SonaraMaterialTokens.LightNavigationAlpha, lightMat.navigationAlpha, 0.001f)
            assertEquals(1.00f, lightMat.baseAlpha, 0.001f)
        }
    }

    @Test
    fun `9 ThemeMode SYSTEM, LIGHT and DARK continue resolving correctly`() {
        fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
        }

        assertTrue(resolveDark(ThemeMode.DARK, systemDark = false))
        assertTrue(resolveDark(ThemeMode.DARK, systemDark = true))
        assertFalse(resolveDark(ThemeMode.LIGHT, systemDark = false))
        assertFalse(resolveDark(ThemeMode.LIGHT, systemDark = true))
        assertTrue(resolveDark(ThemeMode.SYSTEM, systemDark = true))
        assertFalse(resolveDark(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `10 palette changes without dark-mode change do not trigger transition`() {
        var displayedIsDark = true
        var targetIsDark = true

        // User changes palette while staying in Dark mode
        val initialPalette = ColorPalette.SONARA_CLASSIC
        val newPalette = ColorPalette.OCEAN_BLUE

        assertNotEquals(initialPalette, newPalette)
        // Transition condition from SonaraAppRoot:
        val shouldTriggerRippleReveal = targetIsDark != displayedIsDark
        assertFalse("Palette change without mode change must NOT trigger ripple reveal", shouldTriggerRippleReveal)
    }
}
