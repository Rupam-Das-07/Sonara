package com.example.sonara.data.repository

import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.UserPreferences
import com.example.sonara.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {

    private class FakeSettingsRepository : SettingsRepository {
        private val _prefs = MutableStateFlow(UserPreferences())
        private var snapshot: PlaybackSessionSnapshot? = null

        override fun getUserPreferences(): Flow<UserPreferences> = _prefs

        override suspend fun setThemeMode(mode: ThemeMode) { _prefs.value = _prefs.value.copy(themeMode = mode) }
        override suspend fun setShowRomanized(show: Boolean) { _prefs.value = _prefs.value.copy(showRomanized = show) }
        override suspend fun setRestorePlaybackSession(enabled: Boolean) { _prefs.value = _prefs.value.copy(restorePlaybackSession = enabled) }
        override suspend fun setDefaultSearchMode(mode: SearchMode) { _prefs.value = _prefs.value.copy(defaultSearchMode = mode) }
        override suspend fun setReduceMotion(enabled: Boolean) { _prefs.value = _prefs.value.copy(reduceMotion = enabled) }
        override suspend fun setStreamingQuality(quality: AudioQuality) { _prefs.value = _prefs.value.copy(streamingQuality = quality) }
        override suspend fun setDownloadQuality(quality: AudioQuality) { _prefs.value = _prefs.value.copy(downloadQuality = quality) }
        override suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) { _prefs.value = _prefs.value.copy(downloadOverWifiOnly = wifiOnly) }
        override suspend fun resetSettings() { _prefs.value = UserPreferences() }
        override suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) { this.snapshot = snapshot }
        override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? = snapshot

        // Phase 1 stubs
        override suspend fun setAppLanguage(language: com.example.sonara.domain.model.AppLanguage) { _prefs.value = _prefs.value.copy(appLanguage = language) }
        override suspend fun setEqualizerEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(equalizerEnabled = enabled) }
        override suspend fun setEqualizerBandGains(gains: List<Int>) { _prefs.value = _prefs.value.copy(equalizerBandGains = gains) }
        override suspend fun setStopMusicOnTaskClear(enabled: Boolean) { _prefs.value = _prefs.value.copy(stopMusicOnTaskClear = enabled) }
        override suspend fun setDownloadFileFormat(format: com.example.sonara.domain.model.DownloadFileFormat) { _prefs.value = _prefs.value.copy(downloadFileFormat = format) }
        override suspend fun setDownloadLocationMode(mode: com.example.sonara.domain.model.DownloadLocationMode) { _prefs.value = _prefs.value.copy(downloadLocationMode = mode) }
        override suspend fun setDownloadLocationUri(uri: String?) {}
    }

    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        repository = FakeSettingsRepository()
    }

    @Test
    fun defaultPreferencesHaveApprovedDefaults() = runTest {
        val prefs = repository.getUserPreferences().first()
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode)
        assertTrue(prefs.showRomanized)
        assertTrue(prefs.restorePlaybackSession)
        assertEquals(SearchMode.SONGS, prefs.defaultSearchMode)
        assertFalse(prefs.reduceMotion)
        assertEquals(AudioQuality.AUTO, prefs.streamingQuality)
        assertEquals(AudioQuality.VERY_HIGH, prefs.downloadQuality)
        assertFalse(prefs.downloadOverWifiOnly)
    }

    @Test
    fun setStreamingQualityUpdatesPreference() = runTest {
        repository.setStreamingQuality(AudioQuality.VERY_HIGH)
        assertEquals(AudioQuality.VERY_HIGH, repository.getUserPreferences().first().streamingQuality)

        repository.setStreamingQuality(AudioQuality.HIGH)
        assertEquals(AudioQuality.HIGH, repository.getUserPreferences().first().streamingQuality)
    }

    @Test
    fun setDownloadQualityAndWifiUpdatesPreference() = runTest {
        repository.setDownloadQuality(AudioQuality.HIGH)
        assertEquals(AudioQuality.HIGH, repository.getUserPreferences().first().downloadQuality)

        repository.setDownloadOverWifiOnly(true)
        assertTrue(repository.getUserPreferences().first().downloadOverWifiOnly)
    }

    @Test
    fun resetSettingsRestoresQualityDefaults() = runTest {
        repository.setStreamingQuality(AudioQuality.VERY_HIGH)
        repository.setDownloadQuality(AudioQuality.HIGH)
        repository.setDownloadOverWifiOnly(true)

        repository.resetSettings()

        val prefs = repository.getUserPreferences().first()
        assertEquals(AudioQuality.AUTO, prefs.streamingQuality)
        assertEquals(AudioQuality.VERY_HIGH, prefs.downloadQuality)
        assertFalse(prefs.downloadOverWifiOnly)
    }
}
