package com.example.sonara.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.sonara.R
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.core.ui.theme.SourceBadgeTheme
import com.example.sonara.core.util.MediaTitleFormatter
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.TrackSource
import com.example.sonara.domain.model.soundcloud.SoundcloudSourceIdentity

/**
 * Shared TrackRow component for Sonara Android.
 * Native media list row — flat on the canvas, content + typography + artwork led.
 * No individual card/container boxes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    isLiked: Boolean,
    onPlay: () -> Unit,
    onToggleLike: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    isVideo: Boolean = false
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    val displayTitle = MediaTitleFormatter.format(track.title, track.artist)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onLongClick
            )
            .padding(vertical = dimensions.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        // Artwork — 56dp with subtle 6dp corner radius
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = "${track.title} artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = PhosphorIcons.MusicNote,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Title + Artist / Album metadata (weight 1f for clean truncation before the favorite button)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = displayTitle,
                style = typography.trackTitle,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = if (track.album.isNotBlank()) "${track.artist} • ${track.album}" else track.artist
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val source = TrackSource.fromTrack(track)
                if (source != null && (!isVideo || source != TrackSource.YOUTUBE_MUSIC)) {
                    TrackSourceBadge(source = source)
                }
                Text(
                    text = subtitle,
                    style = typography.artistMetadata,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        // Like Toggle Button
        SonaraIconButton(
            onClick = { onToggleLike(!isLiked) },
            contentDescription = if (isLiked) "Unlike ${track.title}" else "Like ${track.title}",
            variant = SonaraIconButtonVariant.Ghost,
            size = dimensions.minTouchTarget
        ) {
            Icon(
                imageVector = if (isLiked) PhosphorIcons.HeartFilled else PhosphorIcons.Heart,
                contentDescription = null,
                tint = if (isLiked) colors.accent else colors.secondaryText,
                modifier = Modifier.size(18.dp)
            )
        }

        if (trailingContent != null) {
            trailingContent()
        }
    }
}

/**
 * CompactTrackRow — Dense single-line track item for quick picks and accordion listings.
 */
@Composable
fun CompactTrackRow(
    track: Track,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    isLiked: Boolean = false,
    onToggleLike: ((Boolean) -> Unit)? = null,
    isVideo: Boolean = false
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions
    val displayTitle = MediaTitleFormatter.format(track.title, track.artist)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = dimensions.spaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!track.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = PhosphorIcons.MusicNote,
                    contentDescription = null,
                    tint = colors.secondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(dimensions.spaceSm))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = displayTitle,
                style = typography.trackTitle,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val source = TrackSource.fromTrack(track)
                if (source != null && (!isVideo || source != TrackSource.YOUTUBE_MUSIC)) {
                    TrackSourceBadge(source = source)
                }
                Text(
                    text = track.artist,
                    style = typography.caption,
                    color = colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        if (onToggleLike != null) {
            SonaraIconButton(
                onClick = { onToggleLike(!isLiked) },
                contentDescription = if (isLiked) "Unlike" else "Like",
                variant = SonaraIconButtonVariant.Ghost,
                size = dimensions.minTouchTarget
            ) {
                Icon(
                    imageVector = if (isLiked) PhosphorIcons.HeartFilled else PhosphorIcons.Heart,
                    contentDescription = null,
                    tint = if (isLiked) colors.accent else colors.secondaryText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Visual provider attribution badge for music tracks.
 * Applies source-specific color styling while keeping geometry and typography identical.
 */
@Composable
fun TrackSourceBadge(
    source: TrackSource,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val badgeColors = SourceBadgeTheme.colorsFor(source, colors.isDark)
    val label = when (source) {
        TrackSource.YOUTUBE_MUSIC -> stringResource(R.string.source_youtube_music)
        TrackSource.SOUNDCLOUD -> stringResource(R.string.source_soundcloud)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(badgeColors.container)
            .padding(horizontal = 5.dp, vertical = 1.5.dp)
    ) {
        Text(
            text = label,
            style = typography.caption.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = badgeColors.text
        )
    }
}

/**
 * Backward-compatible alias for the SoundCloud source badge.
 */
@Composable
fun SoundcloudSourceBadge(modifier: Modifier = Modifier) {
    TrackSourceBadge(source = TrackSource.SOUNDCLOUD, modifier = modifier)
}
