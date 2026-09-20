package com.example.sonara.feature.settings

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.DownloadLocationMode
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DownloadRepository
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.playback.player.EqualizerManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for centralized Sonara application settings, streaming/download quality, and maintenance.
 *
 * Phase 1 additions: AppLanguage, Equalizer, StopMusicOnTaskClear,
 * DownloadFileFormat, DownloadLocationMode, Battery Optimization detection.
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val streamResolver: StreamResolverPort,
    private val downloadRepository: DownloadRepository? = null,
    private val appContext: Context? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            isEqualizerSupported = EqualizerManager.isHardwareSupported()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.getUserPreferences().collect { prefs ->
                _uiState.update { it.copy(preferences = prefs) }
            }
        }
    }

    fun toggleCategory(category: SettingsCategory) {
        _uiState.update { state ->
            val newExpanded = if (category in state.expandedCategories) {
                state.expandedCategories - category
            } else {
                state.expandedCategories + category
            }
            state.copy(expandedCategories = newExpanded)
        }
    }

    fun openDialog(dialog: SettingsDialog) {
        _uiState.update { it.copy(activeDialog = dialog) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(activeDialog = SettingsDialog.None) }
    }

    // ─── Existing setters ─────────────────────────────────────────────────────

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
            dismissDialog()
        }
    }

    fun setShowRomanized(show: Boolean) {
        viewModelScope.launch { settingsRepository.setShowRomanized(show) }
    }

    fun setRestorePlaybackSession(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRestorePlaybackSession(enabled) }
    }

    fun setDefaultSearchMode(mode: SearchMode) {
        viewModelScope.launch {
            settingsRepository.setDefaultSearchMode(mode)
            dismissDialog()
        }
    }

    fun setReduceMotion(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setReduceMotion(enabled) }
    }

    fun setStreamingQuality(quality: AudioQuality) {
        viewModelScope.launch {
            settingsRepository.setStreamingQuality(quality)
            dismissDialog()
        }
    }

    fun setDownloadQuality(quality: AudioQuality) {
        viewModelScope.launch {
            settingsRepository.setDownloadQuality(quality)
            dismissDialog()
        }
    }

    fun setDownloadOverWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch { settingsRepository.setDownloadOverWifiOnly(wifiOnly) }
    }

    // ─── Phase 1 setters ──────────────────────────────────────────────────────

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.setAppLanguage(language)
            dismissDialog()
        }
    }

    /**
     * Enable or disable the hardware Equalizer on the active ExoPlayer audio session.
     * SonaraPlaybackService observes this preference reactively and applies it immediately.
     */
    fun setEqualizerEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEqualizerEnabled(enabled) }
    }

    /**
     * Set equalizer band gains (millibels, 5 bands indexed 0..4).
     * [gains] must contain exactly 5 values; otherwise silently ignored.
     */
    fun setEqualizerBandGains(gains: List<Int>) {
        if (gains.size != 5) return
        viewModelScope.launch { settingsRepository.setEqualizerBandGains(gains) }
    }

    /**
     * Update a single band's gain level in millibels (-1500..1500).
     */
    fun setEqualizerBandGain(bandIndex: Int, gainMillibels: Int) {
        if (bandIndex !in 0..4) return
        val currentGains = _uiState.value.preferences.equalizerBandGains.toMutableList()
        while (currentGains.size < 5) currentGains.add(0)
        currentGains[bandIndex] = gainMillibels.coerceIn(-1500, 1500)
        viewModelScope.launch { settingsRepository.setEqualizerBandGains(currentGains) }
    }

    /**
     * Reset all equalizer band gains to flat (0 mB / 0 dB).
     */
    fun resetEqualizerGains() {
        viewModelScope.launch { settingsRepository.setEqualizerBandGains(listOf(0, 0, 0, 0, 0)) }
    }

    /**
     * Toggle stop-on-task-clear behaviour.
     * ON  → SonaraPlaybackService.onTaskRemoved() stops playback unconditionally.
     * OFF → Existing behaviour (continue if playing, stop if idle).
     */
    fun setStopMusicOnTaskClear(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setStopMusicOnTaskClear(enabled) }
    }

    /**
     * Set download file format preference.
     * If format.isCurrentlyAvailable is false, the preference is persisted but
     * DownloadEngine will raise FormatUnavailableException at download time.
     * No files are renamed or corrupted.
     */
    fun setDownloadFileFormat(format: DownloadFileFormat) {
        viewModelScope.launch {
            settingsRepository.setDownloadFileFormat(format)
            dismissDialog()
        }
    }

    /**
     * Set download location mode.
     * USER_SELECTED requires SAF picker wiring in the frontend phase.
     */
    fun setDownloadLocationMode(mode: DownloadLocationMode) {
        viewModelScope.launch {
            settingsRepository.setDownloadLocationMode(mode)
            dismissDialog()
        }
    }

    /**
     * Set download location URI after successful SAF folder selection.
     */
    fun setDownloadLocationUri(uri: String?) {
        viewModelScope.launch {
            settingsRepository.setDownloadLocationUri(uri)
            if (uri != null) {
                settingsRepository.setDownloadLocationMode(DownloadLocationMode.USER_SELECTED)
            }
        }
    }

    /**
     * Detect and update the battery optimization status for Sonara.
     *
     * DETECTION ONLY — uses PowerManager.isIgnoringBatteryOptimizations() (API 23+).
     * No special permissions required for detection.
     *
     * Sonara cannot directly disable battery optimization. Opening the system
     * settings requires an Activity-level intent (frontend phase).
     * Call this from the Settings screen's onResume equivalent to keep state fresh.
     */
    fun refreshBatteryOptimizationStatus() {
        val ctx = appContext ?: return
        viewModelScope.launch {
            val status = withContext(ioDispatcher) { detectBatteryOptimizationStatus(ctx) }
            _uiState.update { it.copy(batteryOptimizationStatus = status) }
        }
    }

    // ─── Maintenance actions ──────────────────────────────────────────────────

    fun clearAllDownloads() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingDownloads = true) }
            try {
                withContext(ioDispatcher) { downloadRepository?.removeAllDownloads() }
                _uiState.update { it.copy(isClearingDownloads = false, feedbackMessage = "Offline downloads cleared") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isClearingDownloads = false, feedbackMessage = "Failed to clear downloads: ${e.message}") }
            }
        }
    }

    fun clearImageCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingImageCache = true, isClearingCache = true) }
            try {
                withContext(ioDispatcher) {
                    appContext?.let { ctx ->
                        val imageLoader = Coil.imageLoader(ctx)
                        imageLoader.memoryCache?.clear()
                        imageLoader.diskCache?.clear()
                    }
                }
                _uiState.update { it.copy(isClearingImageCache = false, isClearingCache = false, feedbackMessage = "Image cache cleared successfully") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isClearingImageCache = false, isClearingCache = false, feedbackMessage = "Failed to clear image cache: ${e.message}") }
            }
        }
    }

    fun clearStreamCache() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingStreamCache = true, isClearingCache = true) }
            try {
                withContext(ioDispatcher) { streamResolver.clearCache() }
                _uiState.update { it.copy(isClearingStreamCache = false, isClearingCache = false, feedbackMessage = "Stream URL cache cleared") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isClearingStreamCache = false, isClearingCache = false, feedbackMessage = "Failed to clear stream cache: ${e.message}") }
            }
        }
    }

    fun clearPlaybackHistory() {
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { historyRepository.clearHistory() }
                _uiState.update { it.copy(activeDialog = SettingsDialog.None, feedbackMessage = "Playback history cleared") }
            } catch (e: Exception) {
                _uiState.update { it.copy(activeDialog = SettingsDialog.None, feedbackMessage = "Failed to clear playback history: ${e.message}") }
            }
        }
    }

    fun resetSettings() {
        viewModelScope.launch {
            try {
                settingsRepository.resetSettings()
                _uiState.update { it.copy(activeDialog = SettingsDialog.None, feedbackMessage = "Settings restored to defaults") }
            } catch (e: Exception) {
                _uiState.update { it.copy(activeDialog = SettingsDialog.None, feedbackMessage = "Failed to reset settings: ${e.message}") }
            }
        }
    }

    fun clearFeedbackMessage() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    // ─── Internal utilities ───────────────────────────────────────────────────

    private fun detectBatteryOptimizationStatus(context: Context): BatteryOptimizationStatus {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return BatteryOptimizationStatus.UNKNOWN
            val isIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName)
            if (isIgnoring) BatteryOptimizationStatus.UNRESTRICTED else BatteryOptimizationStatus.RESTRICTED
        } catch (e: Exception) {
            BatteryOptimizationStatus.UNKNOWN
        }
    }
}
