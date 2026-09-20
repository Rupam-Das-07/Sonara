package com.example.sonara.feature.settings

import com.example.sonara.domain.model.UserPreferences

enum class SettingsCategory {
    PERSONALISATION,
    MUSIC_PLAYBACK,
    DOWNLOADS,
    LYRICS,
    DATA_STORAGE,
    APP_INFO
}

sealed interface SettingsDialog {
    data object None : SettingsDialog
    data object ThemeModeSelector : SettingsDialog
    data object DefaultSearchModeSelector : SettingsDialog
    data object StreamingQualitySelector : SettingsDialog
    data object DownloadQualitySelector : SettingsDialog
    data object ClearDownloadsConfirmation : SettingsDialog
    data object ClearHistoryConfirmation : SettingsDialog
    data object ResetSettingsConfirmation : SettingsDialog
    data object AppInfo : SettingsDialog

    // ─── Phase 1 additions ────────────────────────────────────────────────────
    data object AppLanguageSelector : SettingsDialog
    data object EqualizerSettings : SettingsDialog
    data object DownloadFormatSelector : SettingsDialog
}

/**
 * Battery optimization state.
 *
 * UNKNOWN: Not yet checked (initial state).
 * UNRESTRICTED: Sonara is on the battery optimization ignore-list — background
 *   playback and background operations will not be throttled.
 * RESTRICTED: Sonara IS subject to battery optimization — the system may kill
 *   background services under Doze. The user can add Sonara to the ignore-list
 *   via Android Settings > Apps > Sonara > Battery > Unrestricted.
 *   The frontend can open this with ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *   (requires REQUEST_INSTALL_PACKAGES permission declaration) or with
 *   ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (opens the general list).
 *
 * NOTE: Sonara cannot directly disable battery optimization without requesting
 *   the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (a special-use permission).
 *   This state is DETECTION ONLY. The frontend phase must wire the system intent.
 */
enum class BatteryOptimizationStatus {
    UNKNOWN,
    UNRESTRICTED,  // App is on the ignore list (good for background playback)
    RESTRICTED     // App is subject to optimization (may affect background service)
}

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val expandedCategories: Set<SettingsCategory> = setOf(
        SettingsCategory.PERSONALISATION,
        SettingsCategory.MUSIC_PLAYBACK,
        SettingsCategory.DOWNLOADS
    ),
    val activeDialog: SettingsDialog = SettingsDialog.None,
    val isClearingImageCache: Boolean = false,
    val isClearingStreamCache: Boolean = false,
    val isClearingDownloads: Boolean = false,
    val isClearingCache: Boolean = false,
    val feedbackMessage: String? = null,

    // ─── Phase 1 additions ────────────────────────────────────────────────────
    /** Current detected battery optimization status. Refreshed when the screen resumes. */
    val batteryOptimizationStatus: BatteryOptimizationStatus = BatteryOptimizationStatus.UNKNOWN,
    /** Whether hardware equalizer DSP effect is supported by the device. */
    val isEqualizerSupported: Boolean = true
)
