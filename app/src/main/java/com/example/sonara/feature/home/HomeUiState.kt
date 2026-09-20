package com.example.sonara.feature.home

import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.player.PlayerUiState

/**
 * Generic state for a single Home module.
 *
 * Each Home section loads independently — one failing (or returning nothing
 * usable) must not take down the rest of the screen.
 *
 *  - [Loading] initial fetch in flight (render a skeleton)
 *  - [Ready]   fetch succeeded with usable content
 *  - [Hidden]  fetch failed OR returned nothing usable → the module self-hides.
 *              Per the Phase 5C data contract we never substitute fabricated or
 *              placeholder content, so "nothing real to show" means "show nothing".
 */
sealed interface HomeModule<out T> {
    data object Loading : HomeModule<Nothing>
    data class Ready<T>(val value: T) : HomeModule<T>
    data object Hidden : HomeModule<Nothing>
}

/**
 * A persisted, resumable listening session — the real re-entry source for the
 * Console Hero. Sourced from [com.example.sonara.domain.repository.SettingsRepository.getPlaybackSession];
 * the track metadata is resolved locally (library cache, then history fallback)
 * and never fabricated.
 */
data class ResumableSession(
    val track: Track,
    val positionMs: Long,
    val durationMs: Long
) {
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * "Because You Listened To {seedTitle}" payload. Truthful and seed-based — the
 * whole module is hidden when there is no seed or no related content, and it is
 * never relabelled as generic "Recommended For You".
 */
data class BecauseYouListenedData(
    val seedTitle: String,
    val tracks: List<Track>
)

/**
 * The seven truthful states of the Console Hero re-entry surface.
 *
 * The live states ([Buffering] / [Active] / [Paused]) are overlaid from the
 * authoritative [PlayerUiState] by [resolveConsoleHero]; the baseline states
 * ([Loading] / [Resumable] / [ColdStart] / [Offline]) are computed by
 * [HomeViewModel]. The hero never fabricates a "now playing" track — when nothing
 * is playing and nothing is resumable it invites the listener to start.
 */
sealed interface ConsoleHeroState {
    data object Loading : ConsoleHeroState
    data class Buffering(
        val title: String,
        val artist: String,
        val artworkUrl: String?
    ) : ConsoleHeroState
    data class Active(
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val progress: Float
    ) : ConsoleHeroState
    data class Paused(
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val progress: Float
    ) : ConsoleHeroState
    data class Resumable(val session: ResumableSession) : ConsoleHeroState
    data object ColdStart : ConsoleHeroState
    data object Offline : ConsoleHeroState
}

/**
 * Complete Home screen state (corrected Phase 5C IA order):
 *   1. [hero] Console Hero
 *   2. [featuredArtists] Featured Artists
 *   3. [playlists] Curated Playlists
 *   4. [quickPicks] Quick Picks
 *   5. [becauseYouListened] Because You Listen To …
 *
 * Trending is intentionally absent (deferred). Each module renders only when
 * [HomeModule.Ready]; the screen silently omits Loading-into-Hidden sections.
 */
data class HomeUiState(
    val hero: ConsoleHeroState = ConsoleHeroState.Loading,
    val featuredArtists: HomeModule<List<FeaturedArtist>> = HomeModule.Loading,
    val playlists: HomeModule<List<PlaylistSummary>> = HomeModule.Loading,
    val quickPicks: HomeModule<List<Track>> = HomeModule.Loading,
    val becauseYouListened: HomeModule<BecauseYouListenedData> = HomeModule.Loading,
    val selectedArtist: FeaturedArtist? = null,
    val artistDetailTracks: HomeModule<List<Track>> = HomeModule.Hidden,
    val artistDetailPlaylists: HomeModule<List<PlaylistSummary>> = HomeModule.Hidden,
    val selectedPlaylist: PlaylistSummary? = null,
    val selectedPlaylistDetail: HomeModule<PlaylistDetail> = HomeModule.Hidden,
    val isViewingAllPlaylists: Boolean = false,
    val isViewingAllArtists: Boolean = false,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false
)

/**
 * Overlays authoritative live playback onto the hero [baseline].
 *
 * When the player is connected to a real track the hero reflects the live state
 * (and its primary action toggles the existing player, never a second player);
 * otherwise the computed [baseline] (Resumable / ColdStart / Offline / Loading)
 * is used. Pure and unit-testable; mirrors the MiniPlayer's own "real track"
 * visibility guard so the two surfaces never disagree.
 */
fun resolveConsoleHero(player: PlayerUiState, baseline: ConsoleHeroState): ConsoleHeroState {
    val hasLiveTrack = player.isConnected &&
        player.trackTitle.isNotBlank() &&
        player.trackTitle != "No Track Selected"
    if (!hasLiveTrack) return baseline
    return when {
        player.isBuffering ->
            ConsoleHeroState.Buffering(player.trackTitle, player.artistName, player.artworkUrl)
        player.isPlaying ->
            ConsoleHeroState.Active(player.trackTitle, player.artistName, player.artworkUrl, player.progress)
        else ->
            ConsoleHeroState.Paused(player.trackTitle, player.artistName, player.artworkUrl, player.progress)
    }
}
