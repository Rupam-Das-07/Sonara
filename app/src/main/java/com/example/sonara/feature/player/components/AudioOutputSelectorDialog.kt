package com.example.sonara.feature.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.theme.SonaraPalette
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.AudioDeviceType
import com.example.sonara.domain.model.AudioOutputDevice
import com.example.sonara.domain.model.AudioOutputState

/**
 * Native contextual dialog for inspecting and switching live Android audio output routes.
 *
 * Implements:
 * - Aggressive minimalism & Quiet Editorial hierarchy
 * - Mineral Petrol / Bone Canvas identity
 * - Dynamic device enumeration from OS (Bluetooth, Wired, USB, Phone Speaker)
 * - Immediate route switching without playback interruption
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputSelectorDialog(
    state: AudioOutputState,
    onSelectDevice: (AudioOutputDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val isDark = colors.isDark

    val dialogBg = if (isDark) Color(0xFF0D2326) else SonaraPalette.BoneElevatedSurface
    val dialogBorder = if (isDark) Color(0xFF264043) else SonaraPalette.BoneBorder

    BasicAlertDialog(
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(SonaraPalette.OxideAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getDeviceIcon(state.activeDevice.type),
                                contentDescription = null,
                                tint = SonaraPalette.OxideAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.audio_output_title),
                                style = typography.sectionTitle.copy(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = colors.primaryText
                            )
                            Text(
                                text = stringResource(
                                    R.string.audio_output_current_output,
                                    getLocalizedDeviceName(state.activeDevice)
                                ),
                                style = typography.caption.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Available Output Devices List
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(state.availableDevices, key = { "${it.id}_${it.type}" }) { device ->
                        AudioOutputDeviceRow(
                            device = device,
                            isSelected = device.isCurrent,
                            onClick = {
                                onSelectDevice(device)
                                onDismiss()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.action_close),
                            color = SonaraPalette.OxideAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioOutputDeviceRow(
    device: AudioOutputDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val isDark = colors.isDark

    val rowBg = when {
        isSelected && isDark -> Color(0xFF133235)
        isSelected && !isDark -> SonaraPalette.BoneSecondarySurface
        else -> Color.Transparent
    }
    val rowBorder = when {
        isSelected -> SonaraPalette.OxideAccent.copy(alpha = 0.50f)
        isDark -> Color.White.copy(alpha = 0.06f)
        else -> Color.Black.copy(alpha = 0.05f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(rowBg)
            .border(1.dp, rowBorder, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Device Type Icon
            Icon(
                imageVector = getDeviceIcon(device.type),
                contentDescription = null,
                tint = if (isSelected) SonaraPalette.OxideAccent else colors.secondaryText,
                modifier = Modifier.size(20.dp)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Device Name
                Text(
                    text = getLocalizedDeviceName(device),
                    style = typography.body.copy(
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (isSelected) colors.primaryText else colors.primaryText.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Subtitle / Hardware Category
                Text(
                    text = getLocalizedDeviceSubtitle(device.type),
                    style = typography.caption.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    color = if (isSelected) SonaraPalette.OxideAccent.copy(alpha = 0.90f) else colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Active indicator radio dot
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(
                    width = if (isSelected) 2.dp else 1.5.dp,
                    color = if (isSelected) SonaraPalette.OxideAccent else colors.secondaryText.copy(alpha = 0.40f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SonaraPalette.OxideAccent)
                )
            }
        }
    }
}

/**
 * Returns the appropriate Phosphor icon for a given audio device hardware type.
 */
fun getDeviceIcon(type: AudioDeviceType): ImageVector {
    return when (type) {
        AudioDeviceType.PHONE_SPEAKER -> PhosphorIcons.DeviceMobile
        AudioDeviceType.BLUETOOTH_HEADPHONES -> PhosphorIcons.Headphones
        AudioDeviceType.BLUETOOTH_TWS -> PhosphorIcons.Earbuds
        AudioDeviceType.BLUETOOTH_SPEAKER -> PhosphorIcons.SpeakerHigh
        AudioDeviceType.WIRED_HEADPHONES -> PhosphorIcons.Headphones
        AudioDeviceType.USB_AUDIO -> PhosphorIcons.Headphones
        AudioDeviceType.OTHER -> PhosphorIcons.SpeakerHigh
    }
}

@Composable
fun getLocalizedDeviceName(device: AudioOutputDevice): String {
    return if (device.type == AudioDeviceType.PHONE_SPEAKER ||
        device.name.equals("Speakers", ignoreCase = true) ||
        device.name.equals("This Phone", ignoreCase = true) ||
        device.name.isBlank()
    ) {
        stringResource(R.string.audio_output_speakers)
    } else if (device.name.equals("Bluetooth Headphones", ignoreCase = true)) {
        stringResource(R.string.audio_output_bluetooth_headphones)
    } else if (device.name.equals("Bluetooth Earbuds", ignoreCase = true)) {
        stringResource(R.string.audio_output_bluetooth_earbuds)
    } else if (device.name.equals("Bluetooth Speaker", ignoreCase = true)) {
        stringResource(R.string.audio_output_bluetooth_speaker)
    } else if (device.name.equals("Wired Headphones", ignoreCase = true)) {
        stringResource(R.string.audio_output_wired_headphones)
    } else if (device.name.equals("USB Audio", ignoreCase = true)) {
        stringResource(R.string.audio_output_usb_audio)
    } else {
        device.name
    }
}

@Composable
fun getLocalizedDeviceSubtitle(type: AudioDeviceType): String {
    return when (type) {
        AudioDeviceType.PHONE_SPEAKER -> stringResource(R.string.audio_output_phone_speaker)
        AudioDeviceType.BLUETOOTH_HEADPHONES -> stringResource(R.string.audio_output_bluetooth_headphones)
        AudioDeviceType.BLUETOOTH_TWS -> stringResource(R.string.audio_output_bluetooth_earbuds)
        AudioDeviceType.BLUETOOTH_SPEAKER -> stringResource(R.string.audio_output_bluetooth_speaker)
        AudioDeviceType.WIRED_HEADPHONES -> stringResource(R.string.audio_output_wired_headphones)
        AudioDeviceType.USB_AUDIO -> stringResource(R.string.audio_output_usb_audio)
        AudioDeviceType.OTHER -> stringResource(R.string.audio_output_title)
    }
}
