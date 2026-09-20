package com.example.sonara.feature.home.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.ShimmerBox
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.components.TrackRow
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.home.HomeModule
import com.example.sonara.feature.home.components.GlyphFallback
import com.example.sonara.feature.home.components.HomeSectionHeader
import com.example.sonara.feature.home.components.getCuratedPlaylistArtwork

/**
 * CuratedPlaylistDetailScreen — Maximized editorial curated playlist experience with Large Artwork Header.
 *
 * Visual Architecture:
 * 1. Top Bar with back navigation
 * 2. Large wide playlist artwork header (16:9) with smooth gradient dissolve into the background
 * 3. Dominant Playlist Title in Clash Display / Satoshi Bold
 * 4. Editorial description and track count
 * 5. Primary action "Play All" button
 * 6. Full track list rendered with TrackRow
 */
@Composable
fun CuratedPlaylistDetailScreen(
    playlist: PlaylistSummary,
    detailModule: HomeModule<PlaylistDetail>,
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onToggleLike: (Track, Boolean) -> Unit,
    isLiked: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val localArtwork = getCuratedPlaylistArtwork(playlist.id, playlist.name)
    val artworkModel: Any? = localArtwork ?: playlist.coverImage.takeIf { !it.isNullOrBlank() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = dimensions.space2Xl
        ),
        verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        // Top navigation bar
        item(key = "playlist-header-nav") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimensions.spaceLg,
                        end = dimensions.spaceLg,
                        top = dimensions.spaceSm
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SonaraIconButton(
                    onClick = onBack,
                    contentDescription = "Back to Home",
                    variant = SonaraIconButtonVariant.Ghost,
                    size = dimensions.minTouchTarget
                ) {
                    Icon(
                        imageVector = PhosphorIcons.ArrowLeft,
                        contentDescription = null,
                        tint = colors.primaryText,
                        modifier = Modifier.size(dimensions.iconMedium)
                    )
                }
                Text(
                    text = "Playlist",
                    style = typography.sectionTitle,
                    color = colors.primaryText,
                    modifier = Modifier.padding(start = dimensions.spaceSm)
                )
            }
        }

        // Large Playlist Cover Header with Soft Gradient Dissolve
        item(key = "playlist-large-header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (artworkModel != null) {
                    AsyncImage(
                        model = artworkModel,
                        contentDescription = "${playlist.name} banner artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    GlyphFallback(glyph = "≋", tint = colors.secondaryText, fontSize = 48.sp)
                }

                // Smooth dissolve gradient into theme background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.35f to Color.Transparent,
                                    0.75f to colors.background.copy(alpha = 0.65f),
                                    1.0f to colors.background
                                )
                            )
                        )
                )
            }
        }

        // Editorial Metadata & Actions (Playlist Title, Description, Track Count, Play All)
        item(key = "playlist-metadata") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensions.spaceLg),
                verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
            ) {
                // Title (Dominant)
                Text(
                    text = playlist.name,
                    style = typography.display,
                    color = colors.primaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Editorial description
                if (playlist.description.isNotBlank()) {
                    Text(
                        text = playlist.description,
                        style = typography.secondaryBody,
                        color = colors.secondaryText
                    )
                }

                // Track Count & Play All button
                if (detailModule is HomeModule.Ready && detailModule.value.tracks.isNotEmpty()) {
                    val tracks = detailModule.value.tracks
                    Text(
                        text = stringResource(R.string.playlist_tracks_count, tracks.size),
                        style = typography.caption,
                        color = colors.secondaryText
                    )
                    Spacer(modifier = Modifier.height(dimensions.spaceXs))
                    SonaraButton(
                        text = "Play All",
                        onClick = { onPlayTrack(tracks.first()) },
                        variant = SonaraButtonVariant.Primary,
                        leadingIcon = {
                            Icon(
                                imageVector = PhosphorIcons.Play,
                                contentDescription = null,
                                tint = colors.onAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        // Section header for playlist tracks
        item(key = "playlist-tracks-header") {
            Box(modifier = Modifier.padding(horizontal = dimensions.spaceLg)) {
                HomeSectionHeader(title = "Tracks")
            }
        }

        // Track list content
        when (detailModule) {
            HomeModule.Loading -> {
                item(key = "playlist-tracks-loading") {
                    Column(
                        modifier = Modifier.padding(horizontal = dimensions.spaceLg),
                        verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                    ) {
                        repeat(5) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                            ) {
                                ShimmerBox(modifier = Modifier.size(56.dp), shape = SonaraTheme.shapes.small)
                                Column(modifier = Modifier.weight(1f)) {
                                    ShimmerBox(modifier = Modifier.fillMaxWidth(0.65f).height(16.dp))
                                    Spacer(modifier = Modifier.height(dimensions.spaceXs))
                                    ShimmerBox(modifier = Modifier.fillMaxWidth(0.4f).height(12.dp))
                                }
                            }
                        }
                    }
                }
            }

            is HomeModule.Ready -> {
                val tracks = detailModule.value.tracks
                if (tracks.isEmpty()) {
                    item(key = "playlist-tracks-empty") {
                        Text(
                            text = "No playable tracks in this playlist.",
                            style = typography.secondaryBody,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(horizontal = dimensions.spaceLg, vertical = dimensions.spaceMd)
                        )
                    }
                } else {
                    items(tracks, key = { it.id }) { track ->
                        Box(modifier = Modifier.padding(horizontal = dimensions.spaceLg)) {
                            TrackRow(
                                track = track,
                                isLiked = isLiked(track.id),
                                onPlay = { onPlayTrack(track) },
                                onToggleLike = { liked -> onToggleLike(track, liked) }
                            )
                        }
                    }
                }
            }

            HomeModule.Hidden -> {
                item(key = "playlist-tracks-hidden") {
                    Text(
                        text = "Unable to load tracks for ${playlist.name}.",
                        style = typography.secondaryBody,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(horizontal = dimensions.spaceLg, vertical = dimensions.spaceMd)
                    )
                }
            }
        }
    }
}
