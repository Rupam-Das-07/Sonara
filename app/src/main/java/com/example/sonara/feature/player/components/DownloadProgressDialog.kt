package com.example.sonara.feature.player.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadProgressDialog(
    track: Track,
    downloadInfo: DownloadInfo?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onPlayOffline: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    val context = LocalContext.current

    val status = downloadInfo?.status ?: DownloadStatus.NOT_DOWNLOADED
    val isCompleted = status == DownloadStatus.DOWNLOADED
    val isQueued = status == DownloadStatus.QUEUED
    val isDownloading = status == DownloadStatus.DOWNLOADING

    // Auto-dismiss on completion after 1.4s ONLY if download transitioned to completed in this active dialog session
    val initiallyCompleted = remember { downloadInfo?.status == DownloadStatus.DOWNLOADED }
    LaunchedEffect(isCompleted) {
        if (!initiallyCompleted && isCompleted) {
            delay(1400)
            onDismiss()
        }
    }

    BasicAlertDialog(
        onDismissRequest = {
            if (!isDownloading && !isQueued) {
                onDismiss()
            }
        }
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimensions.spaceSm)
        ) {
            Column(
                modifier = Modifier.padding(dimensions.spaceLg),
                verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
            ) {
                // 1. HEADER ROW (Title & Status Icon)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val headerText = when (status) {
                        DownloadStatus.DOWNLOADED -> "Song Downloaded"
                        DownloadStatus.DOWNLOADING -> "Downloading Track"
                        DownloadStatus.QUEUED -> "Queued for Download"
                        DownloadStatus.FAILED -> "Download Failed"
                        DownloadStatus.CANCELLED -> "Download Cancelled"
                        DownloadStatus.NOT_DOWNLOADED -> "Not Downloaded"
                    }
                    Text(
                        text = headerText,
                        style = typography.cardTitle.copy(fontWeight = FontWeight.Bold),
                        color = colors.primaryText
                    )

                    when (status) {
                        DownloadStatus.DOWNLOADED -> {
                            Icon(
                                imageVector = PhosphorIcons.CheckCircle,
                                contentDescription = "Completed",
                                tint = colors.accent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        DownloadStatus.FAILED -> {
                            Icon(
                                imageVector = PhosphorIcons.WarningCircle,
                                contentDescription = "Failed",
                                tint = Color(0xFFE57373),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        DownloadStatus.CANCELLED -> {
                            Icon(
                                imageVector = PhosphorIcons.MinusCircle,
                                contentDescription = "Cancelled",
                                tint = colors.secondaryText,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        DownloadStatus.QUEUED -> {
                            Icon(
                                imageVector = PhosphorIcons.Clock,
                                contentDescription = "Queued",
                                tint = colors.secondaryText,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        DownloadStatus.NOT_DOWNLOADED -> {
                            Icon(
                                imageVector = PhosphorIcons.DownloadSimple,
                                contentDescription = "Not Downloaded",
                                tint = colors.secondaryText,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        DownloadStatus.DOWNLOADING -> {
                            // Active indeterminate/determinate progress indicator shown below
                        }
                    }
                }

                // 2. SONG IDENTITY CARD (Artwork, Title, Artist)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant)
                        .padding(dimensions.spaceSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surface)
                    ) {
                        if (!track.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(track.artworkUrl)
                                    .crossfade(200)
                                    .build(),
                                contentDescription = track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(54.dp)
                            )
                        } else {
                            Box(modifier = Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                                Text("♪", fontSize = 24.sp, color = colors.secondaryText)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(dimensions.spaceMd))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            style = typography.caption,
                            color = colors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 3. PROGRESS & METRICS SECTION
                when (status) {
                    DownloadStatus.DOWNLOADED -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val totalMb = String.format(java.util.Locale.US, "%.2f", (downloadInfo?.totalBytes ?: 0L) / (1024f * 1024f))
                            val formatDesc = when {
                                downloadInfo?.mimeType?.contains("mp4", ignoreCase = true) == true -> "M4A • ${downloadInfo.quality.name}"
                                downloadInfo?.mimeType?.contains("webm", ignoreCase = true) == true -> "WebM • ${downloadInfo.quality.name}"
                                else -> downloadInfo?.quality?.name ?: "Audio"
                            }
                            Text(
                                text = "Saved to offline storage ($totalMb MB • $formatDesc)",
                                style = typography.caption,
                                color = colors.accent
                            )
                            Text(
                                text = "Available for instantaneous zero-data playback.",
                                style = typography.caption,
                                color = colors.secondaryText
                            )
                        }
                    }

                    DownloadStatus.FAILED -> {
                        Text(
                            text = downloadInfo?.failureReason ?: "Network error occurred during stream download.",
                            style = typography.caption,
                            color = Color(0xFFE57373)
                        )
                    }

                    DownloadStatus.CANCELLED -> {
                        Text(
                            text = "The download was cancelled before completion. No partial files were saved.",
                            style = typography.caption,
                            color = colors.secondaryText
                        )
                    }

                    DownloadStatus.NOT_DOWNLOADED -> {
                        Text(
                            text = "This track is not saved for offline playback.",
                            style = typography.caption,
                            color = colors.secondaryText
                        )
                    }

                    DownloadStatus.QUEUED -> {
                        Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceXs)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = colors.accent,
                                trackColor = colors.surfaceVariant
                            )
                            Text(
                                text = "Waiting in queue to start downloading...",
                                style = typography.caption,
                                color = colors.secondaryText
                            )
                        }
                    }

                    DownloadStatus.DOWNLOADING -> {
                        Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceXs)) {
                            val progressFraction = downloadInfo?.progressFraction ?: 0f
                            val totalBytes = downloadInfo?.totalBytes ?: 0L
                            val downloadedBytes = downloadInfo?.downloadedBytes ?: 0L

                            if (totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { progressFraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = colors.accent,
                                    trackColor = colors.surfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val downloadedMb = String.format(java.util.Locale.US, "%.1f", downloadedBytes / (1024f * 1024f))
                                    val totalMb = String.format(java.util.Locale.US, "%.1f", totalBytes / (1024f * 1024f))
                                    Text(
                                        text = "$downloadedMb MB / $totalMb MB",
                                        style = typography.caption,
                                        color = colors.secondaryText
                                    )
                                    Text(
                                        text = "${(progressFraction * 100).toInt()}%",
                                        style = typography.caption.copy(fontWeight = FontWeight.Bold),
                                        color = colors.primaryText
                                    )
                                }
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = colors.accent,
                                    trackColor = colors.surfaceVariant
                                )
                                Text(
                                    text = "Connecting and downloading audio stream...",
                                    style = typography.caption,
                                    color = colors.secondaryText
                                )
                            }
                        }
                    }
                }

                // 4. ACTION BUTTONS ROW
                when (status) {
                    DownloadStatus.DOWNLOADED -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                            ) {
                                SonaraButton(
                                    text = "Play Offline",
                                    onClick = onPlayOffline,
                                    variant = SonaraButtonVariant.Primary,
                                    modifier = Modifier.weight(1f)
                                )
                                SonaraButton(
                                    text = "Done",
                                    onClick = onDismiss,
                                    variant = SonaraButtonVariant.Secondary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            TextButton(
                                onClick = onRemove,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Remove Download", color = Color(0xFFE57373), style = typography.buttonLabel)
                            }
                        }
                    }

                    DownloadStatus.FAILED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Close", color = colors.secondaryText, style = typography.buttonLabel)
                            }
                            Spacer(modifier = Modifier.width(dimensions.spaceSm))
                            SonaraButton(
                                text = "Retry",
                                onClick = onRetry,
                                variant = SonaraButtonVariant.Primary
                            )
                        }
                    }

                    DownloadStatus.CANCELLED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Close", color = colors.secondaryText, style = typography.buttonLabel)
                            }
                            Spacer(modifier = Modifier.width(dimensions.spaceSm))
                            SonaraButton(
                                text = "Download Again",
                                onClick = onRetry,
                                variant = SonaraButtonVariant.Primary
                            )
                        }
                    }

                    DownloadStatus.NOT_DOWNLOADED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Close", color = colors.secondaryText, style = typography.buttonLabel)
                            }
                            Spacer(modifier = Modifier.width(dimensions.spaceSm))
                            SonaraButton(
                                text = "Download",
                                onClick = onRetry,
                                variant = SonaraButtonVariant.Primary
                            )
                        }
                    }

                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = onCancel) {
                                Text("Cancel", color = colors.secondaryText, style = typography.buttonLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}
