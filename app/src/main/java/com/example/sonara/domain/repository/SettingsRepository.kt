package com.example.sonara.domain.repository

import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.DownloadLocationMode
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Domain boundary for user settings, theme preferences, and session checkpoints.
 */
interface SettingsRepository {
    fun getUserPreferences(): Flow<UserPreferences>

    // ─── Existing setters ─────────────────────────────────────────────────────
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setShowRomanized(show: Boolean)
    suspend fun setRestorePlaybackSession(enabled: Boolean)
    suspend fun setDefaultSearchMode(mode: SearchMode)
    suspend fun setReduceMotion(enabled: Boolean)
    suspend fun setStreamingQuality(quality: AudioQuality)
    suspend fun setDownloadQuality(quality: AudioQuality)
    suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean)
    suspend fun resetSettings()

    // ─── Phase 1 additions ────────────────────────────────────────────────────
    /** Set the application UI language. Independent of lyric/romanization language. */
    suspend fun setAppLanguage(language: AppLanguage)

    /**
     * Enable or disable the hardware Equalizer on the active ExoPlayer audio session.
     * This is a genuine Android audio-effects EQ — not a fake DSP.
     */
    suspend fun setEqualizerEnabled(enabled: Boolean)

    /**
     * Persist equalizer band gain levels (millibels, 5 bands indexed 0..4).
     * [gains] must contain exactly 5 Int values or the call is ignored.
     */
    suspend fun setEqualizerBandGains(gains: List<Int>)

    /**
     * When ON: playback stops unconditionally when Sonara is removed from Recents.
     * When OFF: existing behaviour (continue while playing, stop when idle).
     */
    suspend fun setStopMusicOnTaskClear(enabled: Boolean)

    /**
     * Set the preferred download container format.
     * [DownloadFileFormat.M4A] and [DownloadFileFormat.MP4] are persisted but NOT
     * currently deliverable — the download engine returns a FormatUnavailableException.
     */
    suspend fun setDownloadFileFormat(format: DownloadFileFormat)

    /**
     * Set download storage location mode.
     * [DownloadLocationMode.USER_SELECTED] requires SAF frontend wiring (Phase 2).
     */
    suspend fun setDownloadLocationMode(mode: DownloadLocationMode)

    /**
     * Persist a SAF content:// URI for [DownloadLocationMode.USER_SELECTED].
     * Passing null clears the stored URI and effectively resets to APP_PRIVATE.
     */
    suspend fun setDownloadLocationUri(uri: String?)

    // ─── Session checkpoint ───────────────────────────────────────────────────
    suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot)
    suspend fun getPlaybackSession(): PlaybackSessionSnapshot?
}

