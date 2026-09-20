package com.example.sonara.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.DownloadLocationMode
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sonara_preferences")

/**
 * Preferences DataStore wrapper for app settings, streaming/download quality, and session checkpoints.
 *
 * All Phase 1 additions use safe defaults that are backward-compatible:
 * - Existing users on earlier versions will receive the default value for any new key.
 * - No migrations or re-creates are needed; DataStore handles missing keys gracefully.
 */
class UserPreferencesDataStore(private val context: Context) {

    companion object {
        // ─── Existing keys ────────────────────────────────────────────────────────
        private val KEY_THEME_MODE           = stringPreferencesKey("theme_mode")
        private val KEY_SHOW_ROMANIZED       = booleanPreferencesKey("show_romanized")
        private val KEY_RESTORE_SESSION      = booleanPreferencesKey("restore_session")
        private val KEY_DEFAULT_SEARCH_MODE  = stringPreferencesKey("default_search_mode")
        private val KEY_REDUCE_MOTION        = booleanPreferencesKey("reduce_motion")
        private val KEY_STREAMING_QUALITY    = stringPreferencesKey("streaming_quality")
        private val KEY_DOWNLOAD_QUALITY     = stringPreferencesKey("download_quality")
        private val KEY_DOWNLOAD_WIFI_ONLY   = booleanPreferencesKey("download_wifi_only")
        private val KEY_LAST_TRACK_ID        = stringPreferencesKey("last_track_id")
        private val KEY_LAST_POSITION_MS     = longPreferencesKey("last_position_ms")
        private val KEY_LAST_QUEUE_IDS       = stringPreferencesKey("last_queue_ids")

        // ─── Phase 1 additions ────────────────────────────────────────────────────
        private val KEY_APP_LANGUAGE            = stringPreferencesKey("app_language")
        private val KEY_EQUALIZER_ENABLED       = booleanPreferencesKey("equalizer_enabled")
        /**
         * Persisted as a comma-separated list of 5 integers (millibels) e.g. "0,0,200,-100,0".
         * Using a single String key avoids creating 5 separate DataStore keys.
         */
        private val KEY_EQUALIZER_BAND_GAINS    = stringPreferencesKey("equalizer_band_gains")
        private val KEY_STOP_MUSIC_ON_TASK_CLEAR = booleanPreferencesKey("stop_music_on_task_clear")
        private val KEY_DOWNLOAD_FILE_FORMAT    = stringPreferencesKey("download_file_format")
        private val KEY_DOWNLOAD_LOCATION_MODE  = stringPreferencesKey("download_location_mode")
        /**
         * Stores the SAF content:// URI string when the user selects a custom directory.
         * Null (absent) means APP_PRIVATE mode is active.
         */
        private val KEY_DOWNLOAD_LOCATION_URI   = stringPreferencesKey("download_location_uri")

        private const val DEFAULT_BAND_GAINS = "0,0,0,0,0"
        private const val BAND_COUNT = 5
    }

    val userPreferences: Flow<UserPreferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            // ─── Existing fields ──────────────────────────────────────────────────
            val themeMode = safeEnum(preferences[KEY_THEME_MODE], ThemeMode.SYSTEM) { ThemeMode.valueOf(it) }
            val showRomanized = preferences[KEY_SHOW_ROMANIZED] ?: true
            val restorePlaybackSession = preferences[KEY_RESTORE_SESSION] ?: true
            val defaultSearchMode = safeEnum(preferences[KEY_DEFAULT_SEARCH_MODE], SearchMode.SONGS) { SearchMode.valueOf(it) }
            val reduceMotion = preferences[KEY_REDUCE_MOTION] ?: false
            val streamingQuality = safeEnum(preferences[KEY_STREAMING_QUALITY], AudioQuality.AUTO) { AudioQuality.valueOf(it) }
            val downloadQuality = safeEnum(preferences[KEY_DOWNLOAD_QUALITY], AudioQuality.VERY_HIGH) { AudioQuality.valueOf(it) }
            val downloadOverWifiOnly = preferences[KEY_DOWNLOAD_WIFI_ONLY] ?: false

            // ─── Phase 1 fields ───────────────────────────────────────────────────
            val appLanguage = AppLanguage.fromName(preferences[KEY_APP_LANGUAGE] ?: AppLanguage.SYSTEM_DEFAULT.name)
            val equalizerEnabled = preferences[KEY_EQUALIZER_ENABLED] ?: false
            val equalizerBandGains = parseBandGains(preferences[KEY_EQUALIZER_BAND_GAINS] ?: DEFAULT_BAND_GAINS)
            val stopMusicOnTaskClear = preferences[KEY_STOP_MUSIC_ON_TASK_CLEAR] ?: false
            val downloadFileFormat = DownloadFileFormat.fromName(preferences[KEY_DOWNLOAD_FILE_FORMAT] ?: DownloadFileFormat.AUTO.name)
            val downloadLocationMode = DownloadLocationMode.fromName(preferences[KEY_DOWNLOAD_LOCATION_MODE] ?: DownloadLocationMode.APP_PRIVATE.name)

            UserPreferences(
                themeMode = themeMode,
                showRomanized = showRomanized,
                restorePlaybackSession = restorePlaybackSession,
                defaultSearchMode = defaultSearchMode,
                reduceMotion = reduceMotion,
                streamingQuality = streamingQuality,
                downloadQuality = downloadQuality,
                downloadOverWifiOnly = downloadOverWifiOnly,
                appLanguage = appLanguage,
                equalizerEnabled = equalizerEnabled,
                equalizerBandGains = equalizerBandGains,
                stopMusicOnTaskClear = stopMusicOnTaskClear,
                downloadFileFormat = downloadFileFormat,
                downloadLocationMode = downloadLocationMode
            )
        }

    // ─── Existing setters ─────────────────────────────────────────────────────

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setShowRomanized(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_ROMANIZED] = show }
    }

    suspend fun setRestorePlaybackSession(enabled: Boolean) {
        context.dataStore.edit { it[KEY_RESTORE_SESSION] = enabled }
    }

    suspend fun setDefaultSearchMode(mode: SearchMode) {
        context.dataStore.edit { it[KEY_DEFAULT_SEARCH_MODE] = mode.name }
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        context.dataStore.edit { it[KEY_REDUCE_MOTION] = enabled }
    }

    suspend fun setStreamingQuality(quality: AudioQuality) {
        context.dataStore.edit { it[KEY_STREAMING_QUALITY] = quality.name }
    }

    suspend fun setDownloadQuality(quality: AudioQuality) {
        context.dataStore.edit { it[KEY_DOWNLOAD_QUALITY] = quality.name }
    }

    suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { it[KEY_DOWNLOAD_WIFI_ONLY] = wifiOnly }
    }

    suspend fun resetSettings() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_THEME_MODE)
            preferences.remove(KEY_SHOW_ROMANIZED)
            preferences.remove(KEY_RESTORE_SESSION)
            preferences.remove(KEY_DEFAULT_SEARCH_MODE)
            preferences.remove(KEY_REDUCE_MOTION)
            preferences.remove(KEY_STREAMING_QUALITY)
            preferences.remove(KEY_DOWNLOAD_QUALITY)
            preferences.remove(KEY_DOWNLOAD_WIFI_ONLY)
            // Phase 1 fields — also reset to defaults
            preferences.remove(KEY_APP_LANGUAGE)
            preferences.remove(KEY_EQUALIZER_ENABLED)
            preferences.remove(KEY_EQUALIZER_BAND_GAINS)
            preferences.remove(KEY_STOP_MUSIC_ON_TASK_CLEAR)
            preferences.remove(KEY_DOWNLOAD_FILE_FORMAT)
            preferences.remove(KEY_DOWNLOAD_LOCATION_MODE)
            preferences.remove(KEY_DOWNLOAD_LOCATION_URI)
        }
    }

    // ─── Phase 1 setters ──────────────────────────────────────────────────────

    suspend fun setAppLanguage(language: AppLanguage) {
        context.dataStore.edit { it[KEY_APP_LANGUAGE] = language.name }
    }

    suspend fun setEqualizerEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_EQUALIZER_ENABLED] = enabled }
    }

    /**
     * Persists band gains only when exactly [BAND_COUNT] values are supplied.
     * Silently ignores invalid lists to prevent corrupting equalizer state.
     */
    suspend fun setEqualizerBandGains(gains: List<Int>) {
        if (gains.size != BAND_COUNT) return
        context.dataStore.edit { it[KEY_EQUALIZER_BAND_GAINS] = gains.joinToString(",") }
    }

    suspend fun setStopMusicOnTaskClear(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STOP_MUSIC_ON_TASK_CLEAR] = enabled }
    }

    suspend fun setDownloadFileFormat(format: DownloadFileFormat) {
        context.dataStore.edit { it[KEY_DOWNLOAD_FILE_FORMAT] = format.name }
    }

    suspend fun setDownloadLocationMode(mode: DownloadLocationMode) {
        context.dataStore.edit { it[KEY_DOWNLOAD_LOCATION_MODE] = mode.name }
    }

    suspend fun setDownloadLocationUri(uri: String?) {
        context.dataStore.edit { preferences ->
            if (uri != null) {
                preferences[KEY_DOWNLOAD_LOCATION_URI] = uri
            } else {
                preferences.remove(KEY_DOWNLOAD_LOCATION_URI)
            }
        }
    }

    /** Read the currently stored SAF URI, or null if none. */
    suspend fun getDownloadLocationUri(): String? {
        val preferences = context.dataStore.data.firstOrNull() ?: return null
        return preferences[KEY_DOWNLOAD_LOCATION_URI]
    }

    // ─── Session checkpoint ───────────────────────────────────────────────────

    suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) {
        context.dataStore.edit { preferences ->
            if (snapshot.lastTrackId != null) {
                preferences[KEY_LAST_TRACK_ID] = snapshot.lastTrackId
            } else {
                preferences.remove(KEY_LAST_TRACK_ID)
            }
            preferences[KEY_LAST_POSITION_MS] = snapshot.lastPositionMs
            preferences[KEY_LAST_QUEUE_IDS] = snapshot.queueTrackIds.joinToString(",")
        }
    }

    suspend fun getPlaybackSession(): PlaybackSessionSnapshot? {
        val preferences = context.dataStore.data.firstOrNull() ?: return null
        val trackId = preferences[KEY_LAST_TRACK_ID]
        val positionMs = preferences[KEY_LAST_POSITION_MS] ?: 0L
        val queueIdsString = preferences[KEY_LAST_QUEUE_IDS] ?: ""
        val queueIds = if (queueIdsString.isNotEmpty()) queueIdsString.split(",") else emptyList()

        if (trackId == null && queueIds.isEmpty()) return null

        return PlaybackSessionSnapshot(
            lastTrackId = trackId,
            lastPositionMs = positionMs,
            queueTrackIds = queueIds
        )
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private inline fun <T> safeEnum(stored: String?, default: T, parse: (String) -> T): T {
        if (stored == null) return default
        return try { parse(stored) } catch (e: IllegalArgumentException) { default }
    }

    /**
     * Parse a comma-separated band-gains string into a List<Int>.
     * Falls back to all-zeros if parsing fails.
     */
    private fun parseBandGains(raw: String): List<Int> {
        return try {
            val parts = raw.split(",")
            if (parts.size == BAND_COUNT) {
                parts.map { it.trim().toInt() }
            } else {
                List(BAND_COUNT) { 0 }
            }
        } catch (e: NumberFormatException) {
            List(BAND_COUNT) { 0 }
        }
    }
}
