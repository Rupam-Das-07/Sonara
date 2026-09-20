package com.example.sonara.feature.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sonara.R
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.DownloadLocationMode
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode

@Composable
fun ThemeModeSelectorDialog(
    currentMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.setting_theme_mode),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.DARK to stringResource(R.string.theme_dark),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light)
                )
                for ((mode, label) in options) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = dimensions.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onSelect(mode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.secondaryText
                            )
                        )
                        Text(
                            text = label,
                            style = typography.body,
                            color = colors.primaryText
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = colors.accent, style = typography.buttonLabel)
            }
        }
    )
}

@Composable
fun DefaultSearchModeDialog(
    currentMode: SearchMode,
    onSelect: (SearchMode) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.setting_default_search_mode),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    SearchMode.SONGS to (stringResource(R.string.search_mode_songs) to "Official music catalog search"),
                    SearchMode.VIDEOS to (stringResource(R.string.search_mode_videos) to "YouTube video-to-audio search")
                )
                for ((mode, pair) in options) {
                    val (label, desc) = pair
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = dimensions.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onSelect(mode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.secondaryText
                            )
                        )
                        Column {
                            Text(
                                text = label,
                                style = typography.body,
                                color = colors.primaryText
                            )
                            Text(
                                text = desc,
                                style = typography.caption,
                                color = colors.secondaryText
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = colors.accent, style = typography.buttonLabel)
            }
        }
    )
}

@Composable
fun StreamingQualitySelectorDialog(
    currentQuality: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.setting_streaming_quality),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                for (quality in AudioQuality.values()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(quality) }
                            .padding(vertical = dimensions.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        RadioButton(
                            selected = currentQuality == quality,
                            onClick = { onSelect(quality) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.secondaryText
                            )
                        )
                        Column {
                            Text(
                                text = quality.label,
                                style = typography.body,
                                color = colors.primaryText
                            )
                            Text(
                                text = quality.description,
                                style = typography.caption,
                                color = colors.secondaryText
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = colors.accent, style = typography.buttonLabel)
            }
        }
    )
}

@Composable
fun DownloadQualitySelectorDialog(
    currentQuality: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.setting_download_quality),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                for (quality in AudioQuality.values()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(quality) }
                            .padding(vertical = dimensions.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        RadioButton(
                            selected = currentQuality == quality,
                            onClick = { onSelect(quality) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.secondaryText
                            )
                        )
                        Column {
                            Text(
                                text = quality.label,
                                style = typography.body,
                                color = colors.primaryText
                            )
                            Text(
                                text = quality.description,
                                style = typography.caption,
                                color = colors.secondaryText
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = colors.accent, style = typography.buttonLabel)
            }
        }
    )
}

@Composable
fun ClearDownloadsConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.dialog_clear_downloads_title),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_clear_downloads_message),
                style = typography.body,
                color = colors.secondaryText
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.action_clear), color = Color(0xFFE57373), style = typography.buttonLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = colors.secondaryText, style = typography.buttonLabel)
            }
        }
    )
}

@Composable
fun ClearHistoryConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.dialog_clear_history_title),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_clear_history_message),
                style = typography.body,
                color = colors.secondaryText
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.action_clear), color = Color(0xFFE57373), style = typography.buttonLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = colors.secondaryText, style = typography.buttonLabel)
            }
        }
    )
}

@Composable
fun ResetSettingsConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.dialog_reset_settings_title),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_reset_settings_message),
                style = typography.body,
                color = colors.secondaryText
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.action_reset), color = Color(0xFFE57373), style = typography.buttonLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = colors.secondaryText, style = typography.buttonLabel)
            }
        }
    )
}

@Composable
fun AppInfoDialog(
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.dialog_app_info_title),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.dialog_app_info_subtitle),
                    style = typography.display,
                    color = colors.accent
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dialog_app_info_message),
                    style = typography.body,
                    color = colors.secondaryText
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok), color = colors.accent, style = typography.buttonLabel)
            }
        }
    )
}

// ─── Phase 2 Dialogs ─────────────────────────────────────────────────────────

@Composable
fun AppLanguageSelectorDialog(
    currentLanguage: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val nationalLanguages = remember { AppLanguage.nationalLanguages() }
    val internationalLanguages = remember { AppLanguage.internationalLanguages() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.dialog_language_title),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // System Default Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(AppLanguage.SYSTEM_DEFAULT) }
                        .padding(vertical = dimensions.spaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                ) {
                    RadioButton(
                        selected = currentLanguage == AppLanguage.SYSTEM_DEFAULT,
                        onClick = { onSelect(AppLanguage.SYSTEM_DEFAULT) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = colors.accent,
                            unselectedColor = colors.secondaryText
                        )
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.language_system_default),
                            style = typography.body,
                            color = colors.primaryText
                        )
                        Text(
                            text = stringResource(R.string.dialog_language_system_desc),
                            style = typography.caption,
                            color = colors.secondaryText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(dimensions.spaceSm))

                // Section: Popular National
                Text(
                    text = stringResource(R.string.dialog_language_popular_national),
                    style = typography.caption.copy(color = colors.accent),
                    modifier = Modifier.padding(vertical = dimensions.spaceXs)
                )

                for (lang in nationalLanguages) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(lang) }
                            .padding(vertical = dimensions.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        RadioButton(
                            selected = currentLanguage == lang,
                            onClick = { onSelect(lang) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.secondaryText
                            )
                        )
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceXs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.nativeDisplayName,
                                    style = typography.body,
                                    color = colors.primaryText
                                )
                                if (lang.displayName != lang.nativeDisplayName) {
                                    Text(
                                        text = "(${lang.displayName})",
                                        style = typography.caption,
                                        color = colors.secondaryText
                                    )
                                }
                            }
                            if (lang.bcp47Tag.isNotEmpty()) {
                                Text(
                                    text = "BCP-47: ${lang.bcp47Tag}",
                                    style = typography.caption,
                                    color = colors.secondaryText
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(dimensions.spaceSm))

                // Section: International
                Text(
                    text = stringResource(R.string.dialog_language_international),
                    style = typography.caption.copy(color = colors.accent),
                    modifier = Modifier.padding(vertical = dimensions.spaceXs)
                )

                for (lang in internationalLanguages) {
                    val isSupported = lang.isSupported
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isSupported) {
                                if (isSupported) onSelect(lang)
                            }
                            .padding(vertical = dimensions.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        RadioButton(
                            selected = currentLanguage == lang,
                            onClick = { if (isSupported) onSelect(lang) },
                            enabled = isSupported,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.secondaryText,
                                disabledSelectedColor = colors.secondaryText.copy(alpha = 0.3f),
                                disabledUnselectedColor = colors.divider
                            )
                        )
                        Column {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceXs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lang.nativeDisplayName,
                                    style = typography.body,
                                    color = if (isSupported) colors.primaryText else colors.secondaryText.copy(alpha = 0.5f)
                                )
                                if (lang.displayName != lang.nativeDisplayName) {
                                    Text(
                                        text = "(${lang.displayName})",
                                        style = typography.caption,
                                        color = if (isSupported) colors.secondaryText else colors.secondaryText.copy(alpha = 0.4f)
                                    )
                                }
                            }
                            Text(
                                text = if (isSupported) "BCP-47: ${lang.bcp47Tag}"
                                else "BCP-47: ${lang.bcp47Tag} • " + stringResource(R.string.dialog_language_coming_soon),
                                style = typography.caption,
                                color = if (isSupported) colors.secondaryText else colors.secondaryText.copy(alpha = 0.4f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(dimensions.spaceMd))
                Text(
                    text = stringResource(R.string.dialog_language_info),
                    style = typography.caption,
                    color = colors.secondaryText.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = colors.accent, style = typography.buttonLabel)
            }
        }
    )
}

@Composable
fun EqualizerSettingsDialog(
    enabled: Boolean,
    bandGains: List<Int>,
    isSupported: Boolean = true,
    onToggleEnabled: (Boolean) -> Unit,
    onSetBandGain: (bandIndex: Int, gainMillibels: Int) -> Unit,
    onResetToFlat: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val bandLabels = remember {
        listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.setting_equalizer),
                    style = typography.cardTitle,
                    color = colors.primaryText
                )
                Switch(
                    checked = isSupported && enabled,
                    enabled = isSupported,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.background,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.secondaryText,
                        uncheckedTrackColor = colors.divider
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
            ) {
                Text(
                    text = if (!isSupported) stringResource(R.string.equalizer_unsupported_desc)
                    else if (enabled) stringResource(R.string.equalizer_dsp_active_desc)
                    else stringResource(R.string.equalizer_dsp_disabled_desc),
                    style = typography.caption,
                    color = colors.secondaryText
                )

                Spacer(modifier = Modifier.height(dimensions.spaceXs))

                // 5 Frequency Band Sliders
                for ((index, freqLabel) in bandLabels.withIndex()) {
                    val rawGain = bandGains.getOrElse(index) { 0 }
                    val gainDb = rawGain / 100 // Convert millibels to dB
                    val gainText = if (gainDb > 0) "+$gainDb dB" else "$gainDb dB"

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = freqLabel,
                                style = typography.body.copy(color = if (enabled) colors.primaryText else colors.secondaryText)
                            )
                            Text(
                                text = if (enabled) gainText else "Flat (0 dB)",
                                style = typography.caption.copy(color = if (enabled) colors.accent else colors.secondaryText)
                            )
                        }

                        Slider(
                            value = rawGain.toFloat(),
                            onValueChange = { newMilliBels ->
                                onSetBandGain(index, newMilliBels.toInt())
                            },
                            valueRange = -1500f..1500f,
                            steps = 29, // 100 mB increments (-15 to +15 dB)
                            enabled = isSupported && enabled,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.accent,
                                activeTrackColor = colors.accent,
                                inactiveTrackColor = colors.divider,
                                disabledThumbColor = colors.secondaryText.copy(alpha = 0.5f),
                                disabledActiveTrackColor = colors.divider,
                                disabledInactiveTrackColor = colors.divider.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onResetToFlat,
                    enabled = isSupported && enabled
                ) {
                    Text(
                        text = stringResource(R.string.equalizer_reset_flat),
                        color = if (isSupported && enabled) colors.secondaryText else colors.secondaryText.copy(alpha = 0.4f),
                        style = typography.buttonLabel
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done), color = colors.accent, style = typography.buttonLabel)
                }
            }
        }
    )
}

@Composable
fun DownloadFormatSelectorDialog(
    currentFormat: DownloadFileFormat,
    onSelect: (DownloadFileFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = stringResource(R.string.setting_download_format),
                style = typography.cardTitle,
                color = colors.primaryText
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val formats = listOf(
                    DownloadFileFormat.AUTO to ("Original / Auto" to "Best available audio format"),
                    DownloadFileFormat.WEBM to ("WebM Audio (.webm)" to "Opus audio"),
                    DownloadFileFormat.M4A to ("M4A Audio (.m4a)" to "AAC audio"),
                    DownloadFileFormat.MP4 to ("MP4 Audio (.mp4)" to "AAC audio")
                )

                for ((format, details) in formats) {
                    val (title, description) = details
                    val isAvailable = format.isCurrentlyAvailable
                    val isSelected = currentFormat == format

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isAvailable) {
                                if (isAvailable) onSelect(format)
                            }
                            .padding(vertical = dimensions.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { if (isAvailable) onSelect(format) },
                            enabled = isAvailable,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.secondaryText,
                                disabledSelectedColor = colors.secondaryText.copy(alpha = 0.3f),
                                disabledUnselectedColor = colors.divider
                            )
                        )
                        Column {
                            Text(
                                text = title,
                                style = typography.body,
                                color = if (isAvailable) colors.primaryText else colors.secondaryText.copy(alpha = 0.5f)
                            )
                            Text(
                                text = description,
                                style = typography.caption,
                                color = if (isAvailable) colors.secondaryText else colors.secondaryText.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = colors.accent, style = typography.buttonLabel)
            }
        }
    )
}
