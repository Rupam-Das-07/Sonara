package com.example.sonara.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPaletteDomainTest {

    @Test
    fun `all required color palette enum constants exist`() {
        val names = ColorPalette.entries.map { it.name }
        val required = listOf(
            "SONARA_CLASSIC",
            "OCEAN_BLUE",
            "ROSE",
            "FOREST_GREEN",
            "LAVENDER",
            "MIDNIGHT",
            "AMOLED_BLACK",
            "DYNAMIC_WALLPAPER"
        )
        for (req in required) {
            assertTrue("Palette $req must exist in ColorPalette enum", names.contains(req))
        }
        assertEquals(8, ColorPalette.entries.size)
    }

    @Test
    fun `SONARA_CLASSIC is the default palette in UserPreferences`() {
        val defaultPrefs = UserPreferences()
        assertEquals(ColorPalette.SONARA_CLASSIC, defaultPrefs.selectedPalette)
    }

    @Test
    fun `fromName resolves known enum names correctly`() {
        for (palette in ColorPalette.entries) {
            assertEquals(palette, ColorPalette.fromName(palette.name))
        }
    }

    @Test
    fun `fromName falls back safely to SONARA_CLASSIC on invalid or null input`() {
        assertEquals(ColorPalette.SONARA_CLASSIC, ColorPalette.fromName(null))
        assertEquals(ColorPalette.SONARA_CLASSIC, ColorPalette.fromName(""))
        assertEquals(ColorPalette.SONARA_CLASSIC, ColorPalette.fromName("unknown_palette"))
        assertEquals(ColorPalette.SONARA_CLASSIC, ColorPalette.fromName("ocean_blue"))
        assertEquals(ColorPalette.SONARA_CLASSIC, ColorPalette.fromName("12345"))
    }

    @Test
    fun `UserPreferences copy updates selectedPalette independently`() {
        val original = UserPreferences()
        val updated = original.copy(selectedPalette = ColorPalette.OCEAN_BLUE)

        assertEquals(ColorPalette.OCEAN_BLUE, updated.selectedPalette)
        assertEquals(ColorPalette.SONARA_CLASSIC, original.selectedPalette)
        assertEquals(original.themeMode, updated.themeMode)
    }
}
