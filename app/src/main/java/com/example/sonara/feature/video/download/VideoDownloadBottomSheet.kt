package com.example.sonara.feature.video.download

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.components.SonaraLoadingIndicator
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.data.download.DownloadProgressTracker
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.download.DownloadPreselectionReason
import com.example.sonara.domain.model.video.download.DownloadPreselectionResult
import com.example.sonara.domain.model.video.download.VideoDownloadOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDownloadBottomSheet(
    track: Track,
    availableOptions: List<VideoDownloadOption>,
    selectedOption: VideoDownloadOption?,
    preselectionResult: DownloadPreselectionResult?,
    isLoadingOptions: Boolean,
    onSelectOption: (VideoDownloadOption) -> Unit,
    onConfirmDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val maxSheetHeight = configuration.screenHeightDp.dp * 0.75f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = colors.secondaryText.copy(alpha = 0.40f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // 1. HEADER (Title & Track preview)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(track.artworkUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Download Video",
                        style = typography.cardTitle.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = colors.primaryText
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.title,
                        style = typography.caption.copy(
                            fontSize = 13.sp,
                            color = colors.secondaryText
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Telemetry and Preselection Status Banner
            if (preselectionResult != null) {
                val explanation = preselectionResult.explanation
                if (!explanation.isNullOrBlank()) {
                    val isFallback = preselectionResult.reason != DownloadPreselectionReason.EXACT_MATCH
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isFallback) colors.accent.copy(alpha = 0.08f) else colors.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (isFallback) colors.accent.copy(alpha = 0.25f) else colors.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isFallback) PhosphorIcons.WarningCircle else PhosphorIcons.CheckCircle,
                                contentDescription = null,
                                tint = if (isFallback) colors.accent else colors.primaryText,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = explanation,
                                style = typography.caption.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = colors.primaryText
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // 3. Loading State or Options List
            if (isLoadingOptions) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SonaraLoadingIndicator(size = 28.dp, color = colors.accent)
                        Text(
                            text = "Analyzing available video streams...",
                            style = typography.caption.copy(fontSize = 12.5.sp),
                            color = colors.secondaryText
                        )
                    }
                }
            } else if (availableOptions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No compatible video streams available for download.",
                        style = typography.body.copy(color = colors.secondaryText)
                    )
                }
            } else {
                Text(
                    text = "SELECT QUALITY",
                    style = typography.caption.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.secondaryText
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableOptions, key = { "${it.height}_${it.frameRate}_${it.container}" }) { option ->
                        val isSelected = option == selectedOption
                        val isExactPlaybackMatch = preselectionResult?.reason == DownloadPreselectionReason.EXACT_MATCH &&
                                preselectionResult.selectedOption == option

                        VideoDownloadOptionItem(
                            option = option,
                            isSelected = isSelected,
                            isPlaybackMatch = isExactPlaybackMatch,
                            onClick = { onSelectOption(option) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Download Confirmation Button
            val buttonText = if (selectedOption != null) {
                "Download (${selectedOption.displayLabel})"
            } else {
                "Download Video"
            }

            SonaraButton(
                text = buttonText,
                onClick = onConfirmDownload,
                enabled = selectedOption != null && !isLoadingOptions,
                variant = SonaraButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun VideoDownloadOptionItem(
    option: VideoDownloadOption,
    isSelected: Boolean,
    isPlaybackMatch: Boolean,
    onClick: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) colors.accent.copy(alpha = 0.15f) else colors.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            if (isSelected) colors.accent else colors.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Radio indicator
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(
                            1.5.dp,
                            if (isSelected) colors.accent else colors.secondaryText.copy(alpha = 0.6f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(colors.accent)
                        )
                    }
                }

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${option.height}p",
                            style = typography.body.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = colors.primaryText
                        )
                        // FPS pill
                        if (option.fpsLabel.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (option.frameRate >= 45) colors.accent.copy(alpha = 0.2f) else colors.surfaceVariant,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            ) {
                                Text(
                                    text = option.fpsLabel,
                                    style = typography.caption.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = if (option.frameRate >= 45) colors.accent else colors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Container badge
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.surfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        ) {
                            Text(
                                text = option.container.name,
                                style = typography.caption.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = colors.secondaryText,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        if (isPlaybackMatch) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = colors.accent.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Current Playback",
                                    style = typography.caption.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = colors.accent,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    val audioDesc = if (option.isMergeRequired) {
                        "Includes audio (${option.audioCodec ?: "Stereo"})"
                    } else {
                        "Direct stream (Audio included)"
                    }
                    Text(
                        text = audioDesc,
                        style = typography.caption.copy(
                            fontSize = 11.5.sp,
                            color = colors.secondaryText
                        )
                    )
                }
            }

            // Estimated size if available
            if (option.estimatedTotalSizeBytes != null && option.estimatedTotalSizeBytes > 0) {
                Text(
                    text = DownloadProgressTracker.formatBytes(option.estimatedTotalSizeBytes),
                    style = typography.caption.copy(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.secondaryText
                    )
                )
            }
        }
    }
}
