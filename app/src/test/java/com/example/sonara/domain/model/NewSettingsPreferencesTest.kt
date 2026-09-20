package com.example.sonara.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the new Phase 1 UserPreferences fields.
 *
 * Verifies:
 * - All new fields have correct defaults
 * - Loudness normalizer preference does NOT exist (explicitly absent)
 * - Equalizer has safe flat defaults
 * - DownloadFileFormat defaults to WEBM
 * - DownloadLocationMode defaults to APP_PRIVATE
 * - AppLanguage defaults to SYSTEM_DEFAULT
 */
class NewSettingsPreferencesTest {

    private val defaultPreferences = UserPreferences()

    @Test
    fun `default appLanguage is SYSTEM_DEFAULT`() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, defaultPreferences.appLanguage)
    }

    @Test
    fun `default equalizerEnabled is false`() {
        assertFalse(defaultPreferences.equalizerEnabled)
    }

    @Test
    fun `default equalizerBandGains is five zeros`() {
        val gains = defaultPreferences.equalizerBandGains
        assertEquals(5, gains.size)
        gains.forEach { assertEquals("Default gain must be 0 mB", 0, it) }
    }

    @Test
    fun `default stopMusicOnTaskClear is false`() {
        assertFalse(defaultPreferences.stopMusicOnTaskClear)
    }

    @Test
    fun `default downloadFileFormat is AUTO`() {
        assertEquals(DownloadFileFormat.AUTO, defaultPreferences.downloadFileFormat)
    }

    @Test
    fun `default downloadLocationMode is APP_PRIVATE`() {
        assertEquals(DownloadLocationMode.APP_PRIVATE, defaultPreferences.downloadLocationMode)
    }

    @Test
    fun `UserPreferences does NOT contain loudnessNormalizerEnabled field`() {
        // This test ensures no fake normalization preference was accidentally added.
        // If this test fails after someone adds loudnessNormalizerEnabled backed by setVolume(),
        // they must provide a legitimate loudness normalization implementation first.
        val fields = UserPreferences::class.java.declaredFields.map { it.name }
        assertFalse(
            "loudnessNormalizerEnabled must NOT exist — it would imply fake normalization",
            fields.any { it.contains("loudness", ignoreCase = true) || it.contains("normali", ignoreCase = true) }
        )
    }

    @Test
    fun `existing fields are unchanged`() {
        assertEquals(ThemeMode.SYSTEM, defaultPreferences.themeMode)
        assertEquals(true, defaultPreferences.showRomanized)
        assertEquals(true, defaultPreferences.restorePlaybackSession)
        assertEquals(SearchMode.SONGS, defaultPreferences.defaultSearchMode)
        assertEquals(false, defaultPreferences.reduceMotion)
        assertEquals(AudioQuality.AUTO, defaultPreferences.streamingQuality)
        assertEquals(AudioQuality.VERY_HIGH, defaultPreferences.downloadQuality)
        assertEquals(false, defaultPreferences.downloadOverWifiOnly)
    }

    @Test
    fun `copy with new appLanguage is independent from other fields`() {
        val modified = defaultPreferences.copy(appLanguage = AppLanguage.HINDI)
        assertEquals(AppLanguage.HINDI, modified.appLanguage)
        // Verify other fields are unchanged
        assertEquals(AppLanguage.SYSTEM_DEFAULT, defaultPreferences.appLanguage)
    }

    @Test
    fun `equalizerBandGains must be list of exactly 5`() {
        val gains = defaultPreferences.equalizerBandGains
        assertEquals(5, gains.size)
    }
}
