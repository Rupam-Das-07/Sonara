package com.example.sonara.data.repository

import com.example.sonara.data.local.preferences.UserPreferencesDataStore
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.DownloadLocationMode
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.UserPreferences
import com.example.sonara.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

class SettingsRepositoryImpl(
    private val preferencesDataStore: UserPreferencesDataStore
) : SettingsRepository {

    override fun getUserPreferences(): Flow<UserPreferences> =
        preferencesDataStore.userPreferences

    // ─── Existing setters ─────────────────────────────────────────────────────

    override suspend fun setThemeMode(mode: ThemeMode) =
        preferencesDataStore.setThemeMode(mode)

    override suspend fun setShowRomanized(show: Boolean) =
        preferencesDataStore.setShowRomanized(show)

    override suspend fun setRestorePlaybackSession(enabled: Boolean) =
        preferencesDataStore.setRestorePlaybackSession(enabled)

    override suspend fun setDefaultSearchMode(mode: SearchMode) =
        preferencesDataStore.setDefaultSearchMode(mode)

    override suspend fun setReduceMotion(enabled: Boolean) =
        preferencesDataStore.setReduceMotion(enabled)

    override suspend fun setStreamingQuality(quality: AudioQuality) =
        preferencesDataStore.setStreamingQuality(quality)

    override suspend fun setDownloadQuality(quality: AudioQuality) =
        preferencesDataStore.setDownloadQuality(quality)

    override suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) =
        preferencesDataStore.setDownloadOverWifiOnly(wifiOnly)

    override suspend fun resetSettings() =
        preferencesDataStore.resetSettings()

    // ─── Phase 1 additions ────────────────────────────────────────────────────

    override suspend fun setAppLanguage(language: AppLanguage) =
        preferencesDataStore.setAppLanguage(language)

    override suspend fun setEqualizerEnabled(enabled: Boolean) =
        preferencesDataStore.setEqualizerEnabled(enabled)

    override suspend fun setEqualizerBandGains(gains: List<Int>) =
        preferencesDataStore.setEqualizerBandGains(gains)

    override suspend fun setStopMusicOnTaskClear(enabled: Boolean) =
        preferencesDataStore.setStopMusicOnTaskClear(enabled)

    override suspend fun setDownloadFileFormat(format: DownloadFileFormat) =
        preferencesDataStore.setDownloadFileFormat(format)

    override suspend fun setDownloadLocationMode(mode: DownloadLocationMode) =
        preferencesDataStore.setDownloadLocationMode(mode)

    override suspend fun setDownloadLocationUri(uri: String?) =
        preferencesDataStore.setDownloadLocationUri(uri)

    // ─── Session checkpoint ───────────────────────────────────────────────────

    override suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) =
        preferencesDataStore.savePlaybackSession(snapshot)

    override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? =
        preferencesDataStore.getPlaybackSession()
}
