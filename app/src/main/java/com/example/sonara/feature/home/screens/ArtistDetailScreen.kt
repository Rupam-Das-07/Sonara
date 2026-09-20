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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.ShimmerBox
import com.example.sonara.core.ui.components.SonaraButton
import com.example.sonara.core.ui.components.SonaraButtonVariant
import com.example.sonara.core.ui.components.SonaraIconButton
import com.example.sonara.core.ui.components.SonaraIconButtonVariant
import com.example.sonara.core.ui.components.TrackRow
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.home.HomeModule
import com.example.sonara.feature.home.components.HomeSectionHeader
import com.example.sonara.feature.home.components.Monogram
import com.example.sonara.feature.home.components.PlaylistGridTile

/**
 * ArtistDetailScreen — Maximized editorial artist experience.
 *
 * Visual Architecture:
 * 1. 16:9 atmospheric artwork header with smooth gradient dissolve (matches CuratedPlaylistDetailScreen)
 * 2. Contextual back navigation row overlaid at top of artwork
 * 3. Artist name (Clash Display Bold, left-aligned, max 2 lines)
 * 4. Genre + track count metadata (secondaryBody, secondaryText)
 * 5. Play All primary action button (left-aligned)
 * 6. Artist Playlists shelf (LazyRow of PlaylistGridTile)
 * 7. Popular Songs (TrackRow list)
 *
 * No global Sonara Top Bar — screen owns its single contextual header.
 */
@Composable
fun ArtistDetailScreen(
    artist: FeaturedArtist,
    tracksModule: HomeModule<List<Track>>,
    playlistsModule: HomeModule<List<PlaylistSummary>> = HomeModule.Hidden,
    onBack: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlaylistClick: (PlaylistSummary) -> Unit = {},
    onToggleLike: (Track, Boolean) -> Unit,
    isLiked: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    val trackCount = (tracksModule as? HomeModule.Ready)?.value?.size ?: 0

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = dimensions.space2Xl),
        verticalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
    ) {
        // ── 1. Top navigation bar ───────────────────────────────────────────────────────────────
        item(key = "artist-header-nav") {
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
                    text = "Artist",
                    style = typography.sectionTitle,
                    color = colors.primaryText,
                    modifier = Modifier.padding(start = dimensions.spaceSm)
                )
            }
        }

        // ── 2. Large Artist Artwork Header with Soft Gradient Dissolve ───────────────────────────
        item(key = "artist-large-header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // Artwork or monogram fallback
                if (!artist.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artist.imageUrl,
                        contentDescription = "${artist.name} artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Monogram(name = artist.name, textStyle = typography.display)
                }

                // Dissolved gradient from artwork into theme background
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

        // ── 2. Editorial metadata + actions ──────────────────────────────────────────────────────
        item(key = "artist-metadata") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimensions.spaceLg),
                verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
            ) {
                // Artist name — Clash Display Bold, left-aligned
                Text(
                    text = artist.name,
                    style = typography.display,
                    color = colors.primaryText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Genre + track count metadata
                val metaLine = buildString {
                    if (artist.genre.isNotBlank()) append(artist.genre)
                    if (artist.genre.isNotBlank() && trackCount > 0) append(" • ")
                    if (trackCount > 0) append("$trackCount tracks")
                }
                if (metaLine.isNotBlank()) {
                    Text(
                        text = metaLine,
                        style = typography.secondaryBody,
                        color = colors.secondaryText
                    )
                }

                // Play All — left-aligned, visible only when tracks are ready and non-empty
                if (tracksModule is HomeModule.Ready && tracksModule.value.isNotEmpty()) {
                    SonaraButton(
                        text = "Play All",
                        onClick = { onPlayTrack(tracksModule.value.first()) },
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

        // ── 3. Artist Playlists shelf ─────────────────────────────────────────────────────────────
        if (playlistsModule is HomeModule.Ready && playlistsModule.value.isNotEmpty()) {
            item(key = "artist-playlists-section") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                ) {
                    HomeSectionHeader(
                        title = "Artist Playlists",
                        modifier = Modifier.padding(horizontal = dimensions.spaceLg)
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = dimensions.spaceLg),
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                    ) {
                        items(playlistsModule.value, key = { it.id }) { playlist ->
                            PlaylistGridTile(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist) }
                            )
                        }
                    }
                }
            }
        }

        // ── 4. Popular Songs section header ──────────────────────────────────────────────────────
        item(key = "tracks-header") {
            HomeSectionHeader(
                title = "Top Songs",
                modifier = Modifier.padding(horizontal = dimensions.spaceLg)
            )
        }

        // ── 5. Track list ─────────────────────────────────────────────────────────────────────────
        when (tracksModule) {
            HomeModule.Loading -> {
                item(key = "tracks-loading") {
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
                if (tracksModule.value.isEmpty()) {
                    item(key = "tracks-empty") {
                        Text(
                            text = "No songs available right now.",
                            style = typography.secondaryBody,
                            color = colors.secondaryText,
                            modifier = Modifier.padding(
                                horizontal = dimensions.spaceLg,
                                vertical = dimensions.spaceMd
                            )
                        )
                    }
                } else {
                    items(tracksModule.value, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            isLiked = isLiked(track.id),
                            onPlay = { onPlayTrack(track) },
                            onToggleLike = { liked -> onToggleLike(track, liked) }
                        )
                    }
                }
            }

            HomeModule.Hidden -> {
                item(key = "tracks-hidden") {
                    Text(
                        text = "Unable to load songs for ${artist.name}.",
                        style = typography.secondaryBody,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(
                            horizontal = dimensions.spaceLg,
                            vertical = dimensions.spaceMd
                        )
                    )
                }
            }
        }
    }
}
