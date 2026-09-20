package com.example.sonara.feature.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import java.util.Locale

/**
 * Featured Artist Tile — Clean circular portrait with name underneath.
 *
 * Phase 5E.3: Clean circular artwork (72dp CircleShape) sitting directly on canvas with no shadows,
 * with legible, balanced Satoshi Medium typography underneath.
 */
@Composable
fun ArtistTile(
    artist: FeaturedArtist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Column(
        modifier = modifier
            .width(76.dp)
            .clickable(onClickLabel = "View ${artist.name}", onClick = onClick)
            .padding(vertical = dimensions.spaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimensions.spaceXs)
    ) {
        // Circular portrait — 72dp directly on canvas
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (!artist.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = artist.imageUrl,
                    contentDescription = "${artist.name} portrait",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Monogram(name = artist.name, textStyle = typography.cardTitle)
                }
            }
        }
        Text(
            text = artist.name,
            style = typography.trackTitle.copy(
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = colors.primaryText,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Resolves local curated playlist artwork drawable resource, if available.
 */
fun getCuratedPlaylistArtwork(playlistId: String, playlistName: String): Int? {
    val normalizedId = playlistId.trim().lowercase().replace("-", "_").replace(" ", "_")
    val normalizedName = playlistName.trim().lowercase().replace("-", "_").replace(" ", "_")

    return when {
        normalizedId.contains("chill_night") || normalizedName.contains("chill_night") ->
            R.drawable.playlist_chill_nights
        normalizedId.contains("lofi_focus") || normalizedName.contains("lofi_focus") ->
            R.drawable.playlist_lofi_focus
        normalizedId.contains("retro_bollywood") || normalizedName.contains("retro_bollywood") ->
            R.drawable.playlist_retro_bollywood
        normalizedId.contains("punjabi_power") || normalizedName.contains("punjabi_power") ->
            R.drawable.playlist_punjabi_power
        normalizedId.contains("romantic") || normalizedName.contains("romantic") ->
            R.drawable.playlist_romantic_hits
        normalizedId.contains("workout_energy") || normalizedName.contains("workout_energy") ->
            R.drawable.playlist_workout_energy
        normalizedId.contains("sufi") || normalizedName.contains("sufi") ->
            R.drawable.playlist_sufi_vibes
        normalizedId.contains("trending_now") || normalizedName.contains("trending_now") ->
            R.drawable.playlist_trending_now
        normalizedId.contains("fresh_release") || normalizedName.contains("fresh_release") ->
            R.drawable.playlist_fresh_releases
        normalizedId.contains("arijit") || normalizedName.contains("arijit") ->
            R.drawable.playlist_arijit_singh_hits
        else -> null
    }
}

/**
 * Curated Playlist Grid Tile — Two-Row presentation tile.
 *
 * Phase 5E.4: Circular playlist artwork (72dp CircleShape) sitting directly on the canvas without
 * any box shadow, elevation, or container backgrounds. Full playlist name wraps naturally without ellipsis.
 */
@Composable
fun PlaylistGridTile(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val localArtwork = getCuratedPlaylistArtwork(playlist.id, playlist.name)
    val artworkModel: Any? = localArtwork ?: playlist.coverImage.takeIf { !it.isNullOrBlank() }

    Column(
        modifier = modifier
            .width(76.dp)
            .clickable(onClickLabel = "Open ${playlist.name}", onClick = onClick)
            .padding(vertical = dimensions.spaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimensions.spaceXs)
    ) {
        // Circular Cover artwork — 72dp CircleShape directly on canvas with no shadows or container boxes
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (artworkModel != null) {
                AsyncImage(
                    model = artworkModel,
                    contentDescription = "${playlist.name} cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    GlyphFallback(glyph = "≋", tint = colors.secondaryText, fontSize = 24.sp)
                }
            }
        }
        Text(
            text = playlist.name,
            style = typography.trackTitle.copy(
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = colors.primaryText,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Curated Playlist Tile — Native compact media row.
 *
 * Artwork (52dp) → title (dominant) → quiet single-line description/metadata → caret.
 * Flat on canvas — no floating card container or decorative borders.
 */
@Composable
fun PlaylistTile(
    playlist: PlaylistSummary,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "playlistChevron"
    )

    val localArtwork = getCuratedPlaylistArtwork(playlist.id, playlist.name)
    val artworkModel: Any? = localArtwork ?: playlist.coverImage.takeIf { !it.isNullOrBlank() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (expanded) "Collapse playlist" else "Expand playlist",
                onClick = onClick
            )
            .padding(vertical = dimensions.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        // Cover artwork — 52dp with subtle 6dp corner
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (artworkModel != null) {
                AsyncImage(
                    model = artworkModel,
                    contentDescription = "${playlist.name} cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                GlyphFallback(glyph = "≋", tint = colors.secondaryText, fontSize = 20.sp)
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = playlist.name,
                style = typography.trackTitle,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = if (playlist.description.isNotBlank()) playlist.description else "${playlist.size} tracks"
            Text(
                text = meta,
                style = typography.secondaryBody,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = PhosphorIcons.CaretDown,
                contentDescription = null,
                tint = if (expanded) colors.accent else colors.secondaryText,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }
    }
}

/**
 * Quick Pick Row — Native compact media row for Quick Picks.
 *
 * 56dp artwork + track title (strongest) + artist (quieter, + optional duration) + favorite + play affordance.
 * Flat on canvas — no card containers, no borders, no background blocks.
 */
@Composable
fun QuickPickRow(
    track: Track,
    onPlay: () -> Unit,
    onToggleLike: (Boolean) -> Unit,
    isLiked: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val durationStr = formatDuration(track.durationMs)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(vertical = dimensions.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        // Artwork — 56dp per spec §4
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
                GlyphFallback(glyph = "♪", tint = colors.secondaryText, fontSize = 20.sp)
            }
        }

        // Title + Artist (+ duration if available)
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
            val meta = if (durationStr.isNotBlank()) "${track.artist} • $durationStr" else track.artist
            Text(
                text = meta,
                style = typography.artistMetadata,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Like toggle (ghost)
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

        // Play action — Oxide play button (36dp with 14dp play icon)
        SonaraIconButton(
            onClick = onPlay,
            contentDescription = "Play ${track.title}",
            variant = SonaraIconButtonVariant.Accent,
            size = 36.dp
        ) {
            Icon(
                imageVector = PhosphorIcons.Play,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
