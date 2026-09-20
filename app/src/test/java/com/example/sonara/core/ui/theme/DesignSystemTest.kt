package com.example.sonara.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying design system contracts from DESIGN_SYSTEM_ANDROID.md.
 */
class DesignSystemTest {

    @Test
    fun verifyDarkPaletteValues() {
        assertEquals(Color(0xFF071A1C), SonaraPalette.PetrolCanvas)
        assertEquals(Color(0xFF0F2426), SonaraPalette.PetrolElevatedSurface)
        assertEquals(Color(0xFF152F31), SonaraPalette.PetrolSecondarySurface)
        assertEquals(Color(0xFFE7E1D6), SonaraPalette.BonePrimaryText)
        assertEquals(Color(0xFFA8A297), SonaraPalette.BoneSecondaryText)
        assertEquals(Color(0xFF264043), SonaraPalette.PetrolBorder)
        assertEquals(Color(0xFFA25A3A), SonaraPalette.OxideAccent)
        assertEquals(Color(0xFF7C4A2F), SonaraPalette.OxideAccentSoft)
        assertTrue(DarkSonaraColors.isDark)
    }

    @Test
    fun verifyLightPaletteValues() {
        assertEquals(Color(0xFFF6F2EA), SonaraPalette.BoneCanvas)
        assertEquals(Color(0xFFECE6DA), SonaraPalette.BoneElevatedSurface)
        assertEquals(Color(0xFFE2D8CF), SonaraPalette.BoneSecondarySurface)
        assertEquals(Color(0xFF1B1F1E), SonaraPalette.DarkPrimaryText)
        assertEquals(Color(0xFF5B5A53), SonaraPalette.DarkSecondaryText)
        assertEquals(Color(0xFFD2CCC1), SonaraPalette.BoneBorder)
        assertEquals(Color(0xFFA25A3A), SonaraPalette.OxideAccentLight)
        assertEquals(Color(0xFF7C8862), SonaraPalette.AccentSoftLight)
        assertFalse(LightSonaraColors.isDark)
    }

    @Test
    fun verifyOnAccentContrastSafety() {
        // onAccent on Oxide (#A25A3A) must be contrast-safe Bone (#F6F2EA) or White (#FFFFFF)
        assertEquals(SonaraPalette.OnAccentBone, DarkSonaraColors.onAccent)
        assertEquals(SonaraPalette.OnAccentWhite, LightSonaraColors.onAccent)
    }

    @Test
    fun verify4dpSpacingScale() {
        val dims = DefaultSonaraDimensions
        assertEquals(4.dp, dims.spaceXs)
        assertEquals(8.dp, dims.spaceSm)
        assertEquals(12.dp, dims.spaceMd)
        assertEquals(16.dp, dims.spaceLg)
        assertEquals(20.dp, dims.spaceXl)
        assertEquals(24.dp, dims.space2Xl)
        assertEquals(32.dp, dims.space3Xl)
        assertEquals(40.dp, dims.space4Xl)
        assertEquals(48.dp, dims.space5Xl)
        assertEquals(64.dp, dims.space6Xl)
        assertEquals(48.dp, dims.minTouchTarget)
    }

    @Test
    fun verifyTypographyScale() {
        val typo = DefaultSonaraTypography
        assertEquals(32.sp, typo.display.fontSize)
        assertEquals(26.sp, typo.screenTitle.fontSize)
        assertEquals(20.sp, typo.sectionTitle.fontSize)
        assertEquals(16.sp, typo.cardTitle.fontSize)
        assertEquals(16.sp, typo.trackTitle.fontSize)
        assertEquals(14.sp, typo.artistMetadata.fontSize)
        assertEquals(15.sp, typo.body.fontSize)
        assertEquals(13.sp, typo.secondaryBody.fontSize)
        assertEquals(11.sp, typo.caption.fontSize)
        assertEquals(24.sp, typo.activeLyrics.fontSize)
        assertEquals(22.sp, typo.lyrics.fontSize)
        assertEquals(16.sp, typo.romanizedLyrics.fontSize)
    }

    @Test
    fun verifyMaterialColorSchemeDerivation() {
        val darkM3 = DarkSonaraColors.toMaterialColorScheme()
        assertEquals(DarkSonaraColors.accent, darkM3.primary)
        assertEquals(DarkSonaraColors.onAccent, darkM3.onPrimary)
        assertEquals(DarkSonaraColors.background, darkM3.background)
        assertEquals(DarkSonaraColors.surface, darkM3.surface)

        val lightM3 = LightSonaraColors.toMaterialColorScheme()
        assertEquals(LightSonaraColors.accent, lightM3.primary)
        assertEquals(LightSonaraColors.onAccent, lightM3.onPrimary)
        assertEquals(LightSonaraColors.background, lightM3.background)
        assertEquals(LightSonaraColors.surface, lightM3.surface)
    }
}
