package com.example.sonara.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.sonara.core.ui.motion.LocalReduceMotion
import com.example.sonara.core.ui.motion.SonaraMotion
import com.example.sonara.core.ui.theme.SonaraTheme
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.home.components.HomeGreeting
import com.example.sonara.feature.home.screens.ArtistDetailScreen
import com.example.sonara.feature.home.screens.CuratedPlaylistDetailScreen
import com.example.sonara.feature.home.screens.CuratedPlaylistsScreen
import com.example.sonara.feature.home.components.becauseYouListenedModule
import com.example.sonara.feature.home.components.curatedPlaylistsModule
import com.example.sonara.feature.home.components.featuredArtistsModule
import com.example.sonara.feature.home.components.quickPicksModule
import com.example.sonara.feature.home.screens.FeaturedArtistsCatalogScreen

/**
 * Navigation state for Home screen — Feed vs Maximized Detail views vs Full Catalogues.
 */
sealed interface HomeViewMode {
    data object Feed : HomeViewMode
    data object CuratedPlaylistsCatalog : HomeViewMode
    data object FeaturedArtistsCatalog : HomeViewMode
    data class Artist(val artist: FeaturedArtist) : HomeViewMode
    data class Playlist(val playlist: PlaylistSummary) : HomeViewMode
}

/**
 * Resolves the active [HomeViewMode] given [HomeUiState].
 * [HomeUiState.selectedPlaylist] is prioritized over [HomeUiState.selectedArtist]
 * so that drilling down into an artist playlist preserves the artist in state
 * and popping the playlist returns directly to the originating artist view.
 */
fun resolveHomeViewMode(state: HomeUiState): HomeViewMode = when {
    state.selectedPlaylist != null -> HomeViewMode.Playlist(state.selectedPlaylist)
    state.selectedArtist != null -> HomeViewMode.Artist(state.selectedArtist)
    state.isViewingAllPlaylists -> HomeViewMode.CuratedPlaylistsCatalog
    state.isViewingAllArtists -> HomeViewMode.FeaturedArtistsCatalog
    else -> HomeViewMode.Feed
}

/**
 * HomeScreen — Phase 5E.4: Typography + Content Expansion + Detail Transition + Home Hierarchy.
 *
 * Pop / Scale / Smooth transition between the streamlined Home Feed, full catalogue views,
 * and dedicated Maximized Detail views with full back navigation.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    listState: LazyListState,
    onArtistClick: (FeaturedArtist) -> Unit,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onSeeAllPlaylists: () -> Unit = {},
    onSeeAllArtists: () -> Unit = {},
    onBackFromDetail: () -> Unit,
    onBackFromCatalog: () -> Unit = {},
    onBackFromArtistsCatalog: () -> Unit = {},
    onRefreshQuickPicks: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onToggleLike: (Track, Boolean) -> Unit,
    isLiked: (String) -> Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier
) {
    val currentMode = resolveHomeViewMode(state)
    // Home's internal Feed ↔ detail/catalog transition. It uses the SAME frozen depth primitives as
    // the rest of the app (SonaraMotion) and the SAME centralized reduced-motion gate as the shell.
    // This transition stays OWNED by Home — it is keyed on HomeViewMode (derived purely from
    // homeState), which is orthogonal to the shell's currentDestination key, so Home's internal
    // depth can never stack with a shell transition for a single action.
    val reduceMotion = LocalReduceMotion.current

    AnimatedContent(
        targetState = currentMode,
        transitionSpec = {
            // Popping back to Feed reads as depth-back; entering a detail/catalog reads as forward.
            if (targetState is HomeViewMode.Feed) {
                SonaraMotion.depthBack(reduceMotion)
            } else {
                SonaraMotion.depthForward(reduceMotion)
            }
        },
        label = "HomePopScaleDetailTransition",
        modifier = modifier.fillMaxSize()
    ) { mode ->
        when (mode) {
            is HomeViewMode.Feed -> {
                HomeFeed(
                    state = state,
                    listState = listState,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick,
                    onSeeAllPlaylists = onSeeAllPlaylists,
                    onSeeAllArtists = onSeeAllArtists,
                    onRefreshQuickPicks = onRefreshQuickPicks,
                    onPlayTrack = onPlayTrack,
                    onToggleLike = onToggleLike,
                    isLiked = isLiked,
                    contentPadding = contentPadding
                )
            }

            is HomeViewMode.CuratedPlaylistsCatalog -> {
                CuratedPlaylistsScreen(
                    playlistsModule = state.playlists,
                    onBack = onBackFromCatalog,
                    onPlaylistClick = onPlaylistClick,
                    modifier = Modifier.padding(contentPadding)
                )
            }

            is HomeViewMode.FeaturedArtistsCatalog -> {
                FeaturedArtistsCatalogScreen(
                    artistsModule = state.featuredArtists,
                    onBack = onBackFromArtistsCatalog,
                    onArtistClick = onArtistClick,
                    modifier = Modifier.padding(contentPadding)
                )
            }

            is HomeViewMode.Artist -> {
                ArtistDetailScreen(
                    artist = mode.artist,
                    tracksModule = state.artistDetailTracks,
                    playlistsModule = state.artistDetailPlaylists,
                    onBack = onBackFromDetail,
                    onPlayTrack = onPlayTrack,
                    onPlaylistClick = onPlaylistClick,
                    onToggleLike = onToggleLike,
                    isLiked = isLiked,
                    modifier = Modifier.padding(contentPadding)
                )
            }

            is HomeViewMode.Playlist -> {
                CuratedPlaylistDetailScreen(
                    playlist = mode.playlist,
                    detailModule = state.selectedPlaylistDetail,
                    onBack = onBackFromDetail,
                    onPlayTrack = onPlayTrack,
                    onToggleLike = onToggleLike,
                    isLiked = isLiked,
                    modifier = Modifier.padding(contentPadding)
                )
            }
        }
    }
}

@Composable
private fun HomeFeed(
    state: HomeUiState,
    listState: LazyListState,
    onArtistClick: (FeaturedArtist) -> Unit,
    onPlaylistClick: (PlaylistSummary) -> Unit,
    onSeeAllPlaylists: () -> Unit,
    onSeeAllArtists: () -> Unit = {},
    onRefreshQuickPicks: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onToggleLike: (Track, Boolean) -> Unit,
    isLiked: (String) -> Boolean,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier
) {
    val dimensions = SonaraTheme.dimensions

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = dimensions.spaceLg,
            end = dimensions.spaceLg,
            top = contentPadding.calculateTopPadding() + dimensions.spaceSm,
            bottom = contentPadding.calculateBottomPadding() + dimensions.spaceLg
        ),
        verticalArrangement = Arrangement.spacedBy(dimensions.spaceLg)
    ) {
        // 1. Contextual greeting (flushed with content edge)
        item(key = "greeting") {
            HomeGreeting()
        }

        // 2. Featured Artists (Two-Row Circular Shelf)
        featuredArtistsModule(
            module = state.featuredArtists,
            onArtistClick = onArtistClick,
            onSeeAllClick = onSeeAllArtists
        )

        // 3. Curated Playlists (Two-Row Circular Shelf with See All + Maximized Detail Click)
        curatedPlaylistsModule(
            module = state.playlists,
            onPlaylistClick = onPlaylistClick,
            onSeeAllClick = onSeeAllPlaylists
        )

        // 4. Quick Picks (56dp compact vertical list)
        quickPicksModule(
            module = state.quickPicks,
            onPlayTrack = onPlayTrack,
            onToggleLike = onToggleLike,
            isLiked = isLiked,
            onRefresh = onRefreshQuickPicks
        )

        // 5. Because You Listen To…
        becauseYouListenedModule(
            module = state.becauseYouListened,
            onPlayTrack = onPlayTrack,
            onToggleLike = onToggleLike,
            isLiked = isLiked
        )
    }
}
