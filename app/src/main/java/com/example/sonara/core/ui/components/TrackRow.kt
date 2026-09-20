package com.example.sonara.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.Track

/**
 * Shared TrackRow component for Sonara Android.
 * Native media list row — flat on the canvas, content + typography + artwork led.
 * No individual card/container boxes.
 */
@Composable
fun TrackRow(
    track: Track,
    isLiked: Boolean,
    onPlay: () -> Unit,
    onToggleLike: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
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
                text = track.title,
                style = typography.trackTitle,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = if (track.album.isNotBlank()) "${track.artist} • ${track.album}" else track.artist
            Text(
                text = subtitle,
                style = typography.artistMetadata,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    onToggleLike: ((Boolean) -> Unit)? = null
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

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
                text = track.title,
                style = typography.trackTitle,
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
