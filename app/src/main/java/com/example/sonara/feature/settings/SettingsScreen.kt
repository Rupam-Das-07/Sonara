package com.example.sonara.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraDivider
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.feature.settings.components.AppInfoDialog
import com.example.sonara.feature.settings.components.AppLanguageSelectorDialog
import com.example.sonara.feature.settings.components.ClearDownloadsConfirmationDialog
import com.example.sonara.feature.settings.components.ClearHistoryConfirmationDialog
import com.example.sonara.feature.settings.components.DefaultSearchModeDialog
import com.example.sonara.feature.settings.components.DownloadFormatSelectorDialog
import com.example.sonara.feature.settings.components.DownloadQualitySelectorDialog
import com.example.sonara.feature.settings.components.EqualizerSettingsDialog
import com.example.sonara.feature.settings.components.ResetSettingsConfirmationDialog
import com.example.sonara.feature.settings.components.SettingsActionRow
import com.example.sonara.feature.settings.components.SettingsCategoryCard
import com.example.sonara.feature.settings.components.SettingsDestructiveRow
import com.example.sonara.feature.settings.components.SettingsSelectorRow
import com.example.sonara.feature.settings.components.SettingsToggleRow
import com.example.sonara.feature.settings.components.StreamingQualitySelectorDialog
import com.example.sonara.feature.settings.components.ThemeModeSelectorDialog

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onToggleCategory: (SettingsCategory) -> Unit,
    onOpenDialog: (SettingsDialog) -> Unit,
    onDismissDialog: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetAppLanguage: (AppLanguage) -> Unit,
    onSetShowRomanized: (Boolean) -> Unit,
    onSetRestorePlaybackSession: (Boolean) -> Unit,
    onSetDefaultSearchMode: (SearchMode) -> Unit,
    onSetReduceMotion: (Boolean) -> Unit,
    onSetEqualizerEnabled: (Boolean) -> Unit,
    onSetEqualizerBandGain: (bandIndex: Int, gainMillibels: Int) -> Unit,
    onResetEqualizerGains: () -> Unit,
    onSetStopMusicOnTaskClear: (Boolean) -> Unit,
    onRefreshBatteryOptimizationStatus: () -> Unit,
    onSetStreamingQuality: (AudioQuality) -> Unit,
    onSetDownloadQuality: (AudioQuality) -> Unit,
    onSetDownloadFileFormat: (DownloadFileFormat) -> Unit,
    onSetDownloadOverWifiOnly: (Boolean) -> Unit,
    onClearAllDownloads: () -> Unit,
    onClearImageCache: () -> Unit,
    onClearPlaybackHistory: () -> Unit,
    onResetSettings: () -> Unit,
    onClearFeedbackMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh battery optimization state on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshBatteryOptimizationStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.feedbackMessage) {
        state.feedbackMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            onClearFeedbackMessage()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimensions.spaceLg, vertical = dimensions.spaceMd),
            verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
        ) {
            // Header Statement
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = dimensions.spaceXs)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = typography.screenTitle,
                    color = colors.primaryText
                )
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    style = typography.caption,
                    color = colors.secondaryText
                )
            }

            // Category 1: Personalisation
            SettingsCategoryCard(
                title = stringResource(R.string.category_personalisation),
                icon = PhosphorIcons.Palette,
                isExpanded = SettingsCategory.PERSONALISATION in state.expandedCategories,
                onToggle = { onToggleCategory(SettingsCategory.PERSONALISATION) }
            ) {
                val themeLabel = when (state.preferences.themeMode) {
                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                }
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_theme_mode),
                    currentValue = themeLabel,
                    description = stringResource(R.string.setting_theme_mode_desc),
                    onClick = { onOpenDialog(SettingsDialog.ThemeModeSelector) }
                )
                SonaraDivider()
                val langLabel = if (state.preferences.appLanguage == AppLanguage.SYSTEM_DEFAULT) {
                    stringResource(R.string.language_system_default)
                } else {
                    "${state.preferences.appLanguage.nativeDisplayName} (${state.preferences.appLanguage.displayName})"
                }
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_app_language),
                    currentValue = langLabel,
                    description = stringResource(R.string.setting_app_language_desc),
                    onClick = { onOpenDialog(SettingsDialog.AppLanguageSelector) }
                )
                SonaraDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.setting_reduce_motion),
                    checked = state.preferences.reduceMotion,
                    description = stringResource(R.string.setting_reduce_motion_desc),
                    onCheckedChange = onSetReduceMotion
                )
            }

            // Category 2: Music & Playback
            SettingsCategoryCard(
                title = stringResource(R.string.category_music_playback),
                icon = PhosphorIcons.Sliders,
                isExpanded = SettingsCategory.MUSIC_PLAYBACK in state.expandedCategories,
                onToggle = { onToggleCategory(SettingsCategory.MUSIC_PLAYBACK) }
            ) {
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_streaming_quality),
                    currentValue = state.preferences.streamingQuality.label,
                    description = state.preferences.streamingQuality.description,
                    onClick = { onOpenDialog(SettingsDialog.StreamingQualitySelector) }
                )
                SonaraDivider()
                val eqLabel = if (!state.isEqualizerSupported) {
                    stringResource(R.string.equalizer_unsupported)
                } else if (state.preferences.equalizerEnabled) {
                    stringResource(R.string.equalizer_enabled_label)
                } else {
                    stringResource(R.string.equalizer_disabled_label)
                }
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_equalizer),
                    currentValue = eqLabel,
                    description = if (!state.isEqualizerSupported) {
                        stringResource(R.string.equalizer_unsupported_desc)
                    } else {
                        stringResource(R.string.setting_equalizer_desc)
                    },
                    enabled = state.isEqualizerSupported,
                    onClick = {
                        if (state.isEqualizerSupported) {
                            onOpenDialog(SettingsDialog.EqualizerSettings)
                        }
                    }
                )
                SonaraDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.setting_stop_on_task_clear),
                    checked = state.preferences.stopMusicOnTaskClear,
                    description = stringResource(R.string.setting_stop_on_task_clear_desc),
                    onCheckedChange = onSetStopMusicOnTaskClear
                )
                SonaraDivider()
                val batteryLabel = when (state.batteryOptimizationStatus) {
                    BatteryOptimizationStatus.UNRESTRICTED -> stringResource(R.string.battery_unrestricted)
                    BatteryOptimizationStatus.RESTRICTED -> stringResource(R.string.battery_optimized)
                    BatteryOptimizationStatus.UNKNOWN -> stringResource(R.string.battery_check)
                }
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_battery_optimization),
                    currentValue = batteryLabel,
                    description = stringResource(R.string.setting_battery_optimization_desc),
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Open Android Settings > Apps > Sonara > Battery", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
                SonaraDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.setting_restore_session),
                    checked = state.preferences.restorePlaybackSession,
                    description = stringResource(R.string.setting_restore_session_desc),
                    onCheckedChange = onSetRestorePlaybackSession
                )
                SonaraDivider()
                val searchModeLabel = when (state.preferences.defaultSearchMode) {
                    SearchMode.SONGS -> stringResource(R.string.search_mode_songs)
                    SearchMode.VIDEOS -> stringResource(R.string.search_mode_videos)
                }
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_default_search_mode),
                    currentValue = searchModeLabel,
                    description = stringResource(R.string.setting_default_search_mode_desc),
                    onClick = { onOpenDialog(SettingsDialog.DefaultSearchModeSelector) }
                )
            }

            // Category 3: Downloads & Offline Storage
            SettingsCategoryCard(
                title = stringResource(R.string.category_downloads),
                icon = PhosphorIcons.DownloadSimple,
                isExpanded = SettingsCategory.DOWNLOADS in state.expandedCategories,
                onToggle = { onToggleCategory(SettingsCategory.DOWNLOADS) }
            ) {
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_download_quality),
                    currentValue = state.preferences.downloadQuality.label,
                    description = state.preferences.downloadQuality.description,
                    onClick = { onOpenDialog(SettingsDialog.DownloadQualitySelector) }
                )
                SonaraDivider()
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_download_format),
                    currentValue = state.preferences.downloadFileFormat.displayLabel,
                    description = stringResource(R.string.setting_download_format_desc),
                    onClick = { onOpenDialog(SettingsDialog.DownloadFormatSelector) }
                )
                SonaraDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.setting_download_wifi_only),
                    checked = state.preferences.downloadOverWifiOnly,
                    description = stringResource(R.string.setting_download_wifi_only_desc),
                    onCheckedChange = onSetDownloadOverWifiOnly
                )
                SonaraDivider()
                SettingsDestructiveRow(
                    title = stringResource(R.string.setting_clear_downloads),
                    description = stringResource(R.string.setting_clear_downloads_desc),
                    onClick = { onOpenDialog(SettingsDialog.ClearDownloadsConfirmation) }
                )
            }

            // Category 4: Lyrics
            SettingsCategoryCard(
                title = stringResource(R.string.category_lyrics),
                icon = PhosphorIcons.TextAa,
                isExpanded = SettingsCategory.LYRICS in state.expandedCategories,
                onToggle = { onToggleCategory(SettingsCategory.LYRICS) }
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.setting_show_romanized),
                    checked = state.preferences.showRomanized,
                    description = stringResource(R.string.setting_show_romanized_desc),
                    onCheckedChange = onSetShowRomanized
                )
            }

            // Category 5: Data & Storage
            SettingsCategoryCard(
                title = stringResource(R.string.category_data_storage),
                icon = PhosphorIcons.Database,
                isExpanded = SettingsCategory.DATA_STORAGE in state.expandedCategories,
                onToggle = { onToggleCategory(SettingsCategory.DATA_STORAGE) }
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.setting_clear_image_cache),
                    description = stringResource(R.string.setting_clear_image_cache_desc),
                    isLoading = state.isClearingImageCache,
                    onClick = onClearImageCache
                )
                SonaraDivider()
                SettingsDestructiveRow(
                    title = stringResource(R.string.setting_clear_history),
                    description = stringResource(R.string.setting_clear_history_desc),
                    onClick = { onOpenDialog(SettingsDialog.ClearHistoryConfirmation) }
                )
                SonaraDivider()
                SettingsDestructiveRow(
                    title = stringResource(R.string.setting_reset_settings),
                    description = stringResource(R.string.setting_reset_settings_desc),
                    onClick = { onOpenDialog(SettingsDialog.ResetSettingsConfirmation) }
                )
            }

            // Category 6: About Sonara
            SettingsCategoryCard(
                title = stringResource(R.string.category_about),
                icon = PhosphorIcons.Info,
                isExpanded = SettingsCategory.APP_INFO in state.expandedCategories,
                onToggle = { onToggleCategory(SettingsCategory.APP_INFO) }
            ) {
                SettingsSelectorRow(
                    title = stringResource(R.string.setting_about_arch),
                    currentValue = stringResource(R.string.setting_about_arch_version),
                    description = stringResource(R.string.setting_about_arch_desc),
                    onClick = { onOpenDialog(SettingsDialog.AppInfo) }
                )
            }

            Spacer(modifier = Modifier.height(dimensions.spaceXl))
        }

        // Active Dialog Host
        when (state.activeDialog) {
            SettingsDialog.ThemeModeSelector -> {
                ThemeModeSelectorDialog(
                    currentMode = state.preferences.themeMode,
                    onSelect = onSetThemeMode,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.AppLanguageSelector -> {
                AppLanguageSelectorDialog(
                    currentLanguage = state.preferences.appLanguage,
                    onSelect = onSetAppLanguage,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.EqualizerSettings -> {
                EqualizerSettingsDialog(
                    enabled = state.preferences.equalizerEnabled,
                    bandGains = state.preferences.equalizerBandGains,
                    isSupported = state.isEqualizerSupported,
                    onToggleEnabled = onSetEqualizerEnabled,
                    onSetBandGain = onSetEqualizerBandGain,
                    onResetToFlat = onResetEqualizerGains,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.DownloadFormatSelector -> {
                DownloadFormatSelectorDialog(
                    currentFormat = state.preferences.downloadFileFormat,
                    onSelect = onSetDownloadFileFormat,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.DefaultSearchModeSelector -> {
                DefaultSearchModeDialog(
                    currentMode = state.preferences.defaultSearchMode,
                    onSelect = onSetDefaultSearchMode,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.StreamingQualitySelector -> {
                StreamingQualitySelectorDialog(
                    currentQuality = state.preferences.streamingQuality,
                    onSelect = onSetStreamingQuality,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.DownloadQualitySelector -> {
                DownloadQualitySelectorDialog(
                    currentQuality = state.preferences.downloadQuality,
                    onSelect = onSetDownloadQuality,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.ClearDownloadsConfirmation -> {
                ClearDownloadsConfirmationDialog(
                    onConfirm = onClearAllDownloads,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.ClearHistoryConfirmation -> {
                ClearHistoryConfirmationDialog(
                    onConfirm = onClearPlaybackHistory,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.ResetSettingsConfirmation -> {
                ResetSettingsConfirmationDialog(
                    onConfirm = onResetSettings,
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.AppInfo -> {
                AppInfoDialog(
                    onDismiss = onDismissDialog
                )
            }

            SettingsDialog.None -> Unit
        }
    }
}
