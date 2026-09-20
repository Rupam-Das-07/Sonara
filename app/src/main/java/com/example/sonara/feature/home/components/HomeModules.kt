package com.example.sonara.feature.home.components

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.sonara.R
import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.core.ui.components.ShimmerBox
import com.example.sonara.core.ui.components.SonaraLoadingIndicator
import com.example.sonara.core.ui.components.TrackRow
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.home.BecauseYouListenedData
import com.example.sonara.feature.home.HomeModule

/**
 * Home discovery modules — Phase 5E.2: Content Shelf + Maximized Detail Interaction.
 *
 * Principles:
 * - Content + spacing + typography + artwork led
 * - Two-row compact presentation for Featured Artists and Curated Playlists
 * - No repeated rounded card containers
 * - Calm density: tight internal rhythm, clear section headers
 */

// -----------------------------------------------------------------------------
// 1. Featured Artists — Two-Row Vertical Shelf (no horizontal swiping required)
// -----------------------------------------------------------------------------

fun LazyListScope.featuredArtistsModule(
    module: HomeModule<List<FeaturedArtist>>,
    onArtistClick: (FeaturedArtist) -> Unit,
    onSeeAllClick: () -> Unit = {}
) {
    when (module) {
        HomeModule.Hidden -> Unit
        HomeModule.Loading -> item(key = "featured-loading") { FeaturedArtistsSkeleton() }
        is HomeModule.Ready -> item(key = "featured-artists") {
            val dimensions = SonaraTheme.dimensions
            val totalArtists = module.value
            val artists = totalArtists.take(8)
            val row1 = artists.take(4)
            val row2 = artists.drop(4).take(4)

            Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
                HomeSectionHeader(
                    title = stringResource(R.string.home_featured_artists),
                    actionLabel = if (totalArtists.size > 8) "See All" else null,
                    actionIcon = if (totalArtists.size > 8) PhosphorIcons.CaretRight else null,
                    onAction = if (totalArtists.size > 8) onSeeAllClick else null
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                ) {
                    row1.forEach { artist ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            ArtistTile(artist = artist, onClick = { onArtistClick(artist) })
                        }
                    }
                    repeat(4 - row1.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                if (row2.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                    ) {
                        row2.forEach { artist ->
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                ArtistTile(artist = artist, onClick = { onArtistClick(artist) })
                            }
                        }
                        repeat(4 - row2.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeaturedArtistsSkeleton() {
    val dimensions = SonaraTheme.dimensions
    Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
        ShimmerBox(modifier = Modifier.width(120.dp).height(18.dp))
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
            ) {
                repeat(4) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(dimensions.spaceXs)
                    ) {
                        ShimmerBox(modifier = Modifier.size(72.dp), shape = CircleShape)
                        ShimmerBox(modifier = Modifier.width(52.dp).height(12.dp))
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 2. Curated Playlists — Two-Row Vertical Shelf with Maximized Detail Click
// -----------------------------------------------------------------------------

fun LazyListScope.curatedPlaylistsModule(
    module: HomeModule<List<PlaylistSummary>>,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onSeeAllClick: () -> Unit = {}
) {
    when (module) {
        HomeModule.Hidden -> Unit
        HomeModule.Loading -> item(key = "curated-loading") { CuratedPlaylistsSkeleton() }
        is HomeModule.Ready -> item(key = "curated-playlists") {
            val dimensions = SonaraTheme.dimensions
            val totalPlaylists = module.value
            val previewPlaylists = totalPlaylists.take(8)
            val row1 = previewPlaylists.take(4)
            val row2 = previewPlaylists.drop(4).take(4)

            Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
                HomeSectionHeader(
                    title = stringResource(R.string.home_playlist_catalog),
                    actionLabel = if (totalPlaylists.size > 8) "See All" else null,
                    actionIcon = if (totalPlaylists.size > 8) PhosphorIcons.CaretRight else null,
                    onAction = if (totalPlaylists.size > 8) onSeeAllClick else null
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                ) {
                    row1.forEach { playlist ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            PlaylistGridTile(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist) }
                            )
                        }
                    }
                    repeat(4 - row1.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                if (row2.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
                    ) {
                        row2.forEach { playlist ->
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                PlaylistGridTile(
                                    playlist = playlist,
                                    onClick = { onPlaylistClick(playlist) }
                                )
                            }
                        }
                        repeat(4 - row2.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CuratedPlaylistsSkeleton() {
    val dimensions = SonaraTheme.dimensions
    Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
        ShimmerBox(modifier = Modifier.width(140.dp).height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimensions.spaceSm)
        ) {
            repeat(4) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(dimensions.spaceXs)
                ) {
                    ShimmerBox(modifier = Modifier.size(72.dp), shape = CircleShape)
                    ShimmerBox(modifier = Modifier.width(52.dp).height(12.dp))
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 3. Quick Picks — Native compact media rows
// -----------------------------------------------------------------------------

fun LazyListScope.quickPicksModule(
    module: HomeModule<List<Track>>,
    onRefresh: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onToggleLike: (Track, Boolean) -> Unit,
    isLiked: (String) -> Boolean
) {
    when (module) {
        HomeModule.Hidden -> Unit
        HomeModule.Loading -> item(key = "quickpicks-loading") { QuickPicksSkeleton() }
        is HomeModule.Ready -> item(key = "quick-picks") {
            val dimensions = SonaraTheme.dimensions
            Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
                HomeSectionHeader(
                    title = stringResource(R.string.home_quick_picks),
                    actionLabel = "Refresh",
                    onAction = onRefresh,
                    actionIcon = PhosphorIcons.ArrowClockwise
                )
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    module.value.take(6).forEach { track ->
                        QuickPickRow(
                            track = track,
                            onPlay = { onPlayTrack(track) },
                            onToggleLike = { liked -> onToggleLike(track, liked) },
                            isLiked = isLiked(track.id)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickPicksSkeleton() {
    val dimensions = SonaraTheme.dimensions
    Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
        ShimmerBox(modifier = Modifier.width(100.dp).height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
            repeat(4) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimensions.spaceMd)
                ) {
                    ShimmerBox(modifier = Modifier.size(56.dp), shape = SonaraTheme.shapes.small)
                    Column(modifier = Modifier.weight(1f)) {
                        ShimmerBox(modifier = Modifier.fillMaxWidth(0.7f).height(16.dp))
                        Spacer(modifier = Modifier.height(dimensions.spaceXs))
                        ShimmerBox(modifier = Modifier.fillMaxWidth(0.45f).height(12.dp))
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. Because You Listen To … — Truthful seed-based recommendations
// -----------------------------------------------------------------------------

fun LazyListScope.becauseYouListenedModule(
    module: HomeModule<BecauseYouListenedData>,
    onPlayTrack: (Track) -> Unit,
    onToggleLike: (Track, Boolean) -> Unit,
    isLiked: (String) -> Boolean
) {
    when (module) {
        HomeModule.Hidden -> Unit
        HomeModule.Loading -> item(key = "bylt-loading") { QuickPicksSkeleton() }
        is HomeModule.Ready -> item(key = "bylt") {
            val dimensions = SonaraTheme.dimensions
            Column(verticalArrangement = Arrangement.spacedBy(dimensions.spaceSm)) {
                HomeSectionHeader(title = "Because You Listen To ${module.value.seedTitle}")
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    module.value.tracks.take(5).forEach { track ->
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
    }
}
