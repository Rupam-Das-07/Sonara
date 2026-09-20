package com.example.sonara.feature.home

import com.example.sonara.core.ui.components.PhosphorIcons
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Home state resolution contracts (Phase 5E).
 * ContinueListening state is driven by resolveConsoleHero() — tests verify state mapping.
 */
class HomeRedesignTest {

    @Test
    fun verifyContinueListening_activePlaybackState() {
        val playerState = PlayerUiState(
            isConnected = true,
            isPlaying = true,
            trackTitle = "Song A",
            artistName = "Artist B",
            artworkUrl = "https://example.com/art.jpg",
            currentPositionMs = 42000L,
            durationMs = 100000L
        )
        val baseline = ConsoleHeroState.ColdStart
        val hero = resolveConsoleHero(playerState, baseline)

        assertTrue(hero is ConsoleHeroState.Active)
        val active = hero as ConsoleHeroState.Active
        assertEquals("Song A", active.title)
        assertEquals("Artist B", active.artist)
        assertEquals(0.42f, active.progress ?: 0f, 0.01f)
    }

    @Test
    fun verifyContinueListening_bufferingPlaybackState() {
        val playerState = PlayerUiState(
            isConnected = true,
            isPlaying = false,
            isBuffering = true,
            trackTitle = "Buffering Song",
            artistName = "Artist X"
        )
        val baseline = ConsoleHeroState.ColdStart
        val hero = resolveConsoleHero(playerState, baseline)

        assertTrue(hero is ConsoleHeroState.Buffering)
        val buffering = hero as ConsoleHeroState.Buffering
        assertEquals("Buffering Song", buffering.title)
        assertEquals("Artist X", buffering.artist)
    }

    @Test
    fun verifyContinueListening_pausedPlaybackState() {
        val playerState = PlayerUiState(
            isConnected = true,
            isPlaying = false,
            isBuffering = false,
            trackTitle = "Paused Song",
            artistName = "Artist Y",
            currentPositionMs = 75000L,
            durationMs = 100000L
        )
        val baseline = ConsoleHeroState.ColdStart
        val hero = resolveConsoleHero(playerState, baseline)

        assertTrue(hero is ConsoleHeroState.Paused)
        val paused = hero as ConsoleHeroState.Paused
        assertEquals("Paused Song", paused.title)
        assertEquals("Artist Y", paused.artist)
        assertEquals(0.75f, paused.progress ?: 0f, 0.01f)
    }

    @Test
    fun verifyContinueListening_fallbackToPersistedSession() {
        val playerState = PlayerUiState(
            isConnected = true,
            trackTitle = "No Track Selected"
        )
        val session = ResumableSession(
            track = Track(
                id = "abc12345678",
                title = "Persisted Track",
                artist = "Persisted Artist"
            ),
            positionMs = 30000L,
            durationMs = 120000L
        )
        val baseline = ConsoleHeroState.Resumable(session)
        val hero = resolveConsoleHero(playerState, baseline)

        assertTrue(hero is ConsoleHeroState.Resumable)
        val resumable = hero as ConsoleHeroState.Resumable
        assertEquals("Persisted Track", resumable.session.track.title)
        assertEquals(0.25f, resumable.session.progress, 0.01f)
    }

    @Test
    fun verifyContinueListening_coldStartWhenNoSession() {
        val playerState = PlayerUiState(
            isConnected = true,
            trackTitle = "No Track Selected"
        )
        val baseline = ConsoleHeroState.ColdStart
        val hero = resolveConsoleHero(playerState, baseline)

        assertTrue(hero is ConsoleHeroState.ColdStart)
    }

    @Test
    fun verifyPhosphorIconsIntegrity() {
        assertEquals("PhosphorHouse", PhosphorIcons.House.name)
        assertEquals(24f, PhosphorIcons.House.viewportWidth)
        assertEquals(24f, PhosphorIcons.House.viewportHeight)

        assertEquals("PhosphorMagnifyingGlass", PhosphorIcons.MagnifyingGlass.name)
        assertEquals("PhosphorBooks", PhosphorIcons.Books.name)
        assertEquals("PhosphorPlay", PhosphorIcons.Play.name)
        assertEquals("PhosphorPause", PhosphorIcons.Pause.name)
        assertEquals("PhosphorArrowClockwise", PhosphorIcons.ArrowClockwise.name)
        assertEquals("PhosphorCaretDown", PhosphorIcons.CaretDown.name)
        assertEquals("PhosphorHeart", PhosphorIcons.Heart.name)
        assertEquals("PhosphorHeartFilled", PhosphorIcons.HeartFilled.name)
        assertEquals("PhosphorSparkle", PhosphorIcons.Sparkle.name)
        assertEquals("PhosphorWarningCircle", PhosphorIcons.WarningCircle.name)
        assertEquals("PhosphorArrowLeft", PhosphorIcons.ArrowLeft.name)
        assertEquals("PhosphorCaretRight", PhosphorIcons.CaretRight.name)
    }

    @Test
    fun verifyHomeUiState_moduleIndependentFailure() {
        val state = HomeUiState(
            featuredArtists = HomeModule.Ready(emptyList()),
            playlists = HomeModule.Hidden,
            quickPicks = HomeModule.Loading,
            becauseYouListened = HomeModule.Hidden
        )
        assertTrue(state.featuredArtists is HomeModule.Ready)
        assertTrue(state.playlists is HomeModule.Hidden)
        assertTrue(state.quickPicks is HomeModule.Loading)
        assertTrue(state.becauseYouListened is HomeModule.Hidden)
    }

    @Test
    fun verifyHomeUiState_catalogueExpansionHoldsAll22Playlists() {
        val all22Ids = listOf(
            "chill_nights", "lofi_focus", "retro_bollywood", "punjabi_power",
            "romantic_hits", "workout_energy", "sufi_vibes", "trending_now",
            "fresh_releases", "arijit_singh", "uncut_bollywood", "bollywood_2000s",
            "golden_bollywood", "heartbreak_hindi", "acoustic_unplugged", "indie_india",
            "desi_hip_hop", "ghazal_lounge", "south_blockbusters", "global_top_hits",
            "global_chill", "ar_rahman_magic"
        )
        val summaries = all22Ids.map { id ->
            com.example.sonara.domain.model.PlaylistSummary(
                id = id,
                name = id.replace("_", " ").replaceFirstChar { it.uppercase() },
                size = 25,
                coverImage = "https://example.com/$id.jpg"
            )
        }

        val state = HomeUiState(playlists = HomeModule.Ready(summaries))
        assertTrue(state.playlists is HomeModule.Ready)
        val ready = state.playlists as HomeModule.Ready
        assertEquals(22, ready.value.size)
        assertEquals(all22Ids, ready.value.map { it.id })
    }

    @Test
    fun verifyHomeUiState_catalogueNavigationState() {
        val state = HomeUiState()
        assertEquals(false, state.isViewingAllPlaylists)
        val openCatalogState = state.copy(isViewingAllPlaylists = true)
        assertEquals(true, openCatalogState.isViewingAllPlaylists)
    }

    @Test
    fun verifyCuratedPlaylistArtworkMapping() {
        assertEquals(com.example.sonara.R.drawable.playlist_chill_nights, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("chill_nights", "Chill Nights"))
        assertEquals(com.example.sonara.R.drawable.playlist_lofi_focus, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("lofi_focus", "Lofi Focus"))
        assertEquals(com.example.sonara.R.drawable.playlist_retro_bollywood, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("retro_bollywood", "Retro Bollywood"))
        assertEquals(com.example.sonara.R.drawable.playlist_punjabi_power, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("punjabi_power", "Punjabi Power"))
        assertEquals(com.example.sonara.R.drawable.playlist_romantic_hits, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("romantic_hits", "Romantic Hits"))
        assertEquals(com.example.sonara.R.drawable.playlist_workout_energy, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("workout_energy", "Workout Energy"))
        assertEquals(com.example.sonara.R.drawable.playlist_trending_now, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("trending_now", "Trending Now"))
        assertEquals(com.example.sonara.R.drawable.playlist_fresh_releases, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("fresh_releases", "Fresh Releases"))
        assertEquals(com.example.sonara.R.drawable.playlist_arijit_singh_hits, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("arijit_singh", "Arijit Singh Hits"))
        assertEquals(com.example.sonara.R.drawable.playlist_sufi_vibes, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("sufi_vibes", "Sufi Vibes"))
        assertEquals(null, com.example.sonara.feature.home.components.getCuratedPlaylistArtwork("unknown_playlist", "Unknown Playlist"))
    }

    @Test
    fun verifyFeaturedArtist_preservesBrowseIdAndFallback() {
        val artistWithBrowseId = FeaturedArtist(
            id = "arijit-singh",
            name = "Arijit Singh",
            genre = "Bollywood",
            browseId = "UCPRWWK1Vp1zHRnGKmSSXtaA"
        )
        assertEquals("UCPRWWK1Vp1zHRnGKmSSXtaA", artistWithBrowseId.browseId)
        val drillThroughWithBrowseId = artistWithBrowseId.browseId ?: artistWithBrowseId.id
        assertEquals("UCPRWWK1Vp1zHRnGKmSSXtaA", drillThroughWithBrowseId)

        val legacyArtistWithoutBrowseId = FeaturedArtist(
            id = "arijit-singh",
            name = "Arijit Singh",
            genre = "Bollywood"
        )
        assertNull(legacyArtistWithoutBrowseId.browseId)
        val drillThroughFallback = legacyArtistWithoutBrowseId.browseId ?: legacyArtistWithoutBrowseId.id
        assertEquals("arijit-singh", drillThroughFallback)
    }

    @Test
    fun verifyArtistCatalog_holdsTracksAndPlaylists() {
        val defaultCatalog = ArtistCatalog()
        assertTrue(defaultCatalog.tracks.isEmpty())
        assertTrue(defaultCatalog.playlists.isEmpty())

        val catalog = ArtistCatalog(
            tracks = listOf(Track(id = "trk1", title = "Song 1", artist = "Artist")),
            playlists = listOf(
                PlaylistSummary(id = "arijit_singh", name = "Arijit Singh Hits", size = 25),
                PlaylistSummary(id = "arijit_singh_romantic", name = "Arijit Singh Romantic", size = 20)
            )
        )
        assertEquals(1, catalog.tracks.size)
        assertEquals(2, catalog.playlists.size)
        assertEquals("arijit_singh_romantic", catalog.playlists[1].id)
    }

    @Test
    fun verifyHomeUiState_artistDetailPlaylistsLifecycle() {
        val initialState = HomeUiState()
        assertEquals(HomeModule.Hidden, initialState.artistDetailPlaylists)

        val loadingState = initialState.copy(
            artistDetailTracks = HomeModule.Loading,
            artistDetailPlaylists = HomeModule.Loading
        )
        assertEquals(HomeModule.Loading, loadingState.artistDetailPlaylists)

        val readyPlaylists = listOf(PlaylistSummary(id = "p1", name = "Playlist 1"))
        val readyState = loadingState.copy(
            artistDetailPlaylists = HomeModule.Ready(readyPlaylists)
        )
        assertTrue(readyState.artistDetailPlaylists is HomeModule.Ready)
        assertEquals("p1", (readyState.artistDetailPlaylists as HomeModule.Ready).value.first().id)

        val resetState = readyState.copy(
            selectedArtist = null,
            artistDetailTracks = HomeModule.Hidden,
            artistDetailPlaylists = HomeModule.Hidden
        )
        assertEquals(HomeModule.Hidden, resetState.artistDetailPlaylists)
    }

    @Test
    fun verifyHomeViewMode_resolutionAndBackStackPreservation() {
        val testArtist = FeaturedArtist(
            id = "arijit-singh",
            name = "Arijit Singh",
            genre = "Bollywood",
            browseId = "UCPRWWK1Vp1zHRnGKmSSXtaA"
        )
        val testPlaylist = PlaylistSummary(
            id = "arijit_singh",
            name = "Arijit Singh Hits",
            size = 25
        )

        // 1. Initial State -> Feed
        val feedState = HomeUiState()
        assertEquals(HomeViewMode.Feed, resolveHomeViewMode(feedState))

        // 2. Select Artist -> Artist Detail
        val artistState = feedState.copy(
            selectedArtist = testArtist,
            artistDetailTracks = HomeModule.Loading,
            artistDetailPlaylists = HomeModule.Loading
        )
        val modeAfterArtistSelect = resolveHomeViewMode(artistState)
        assertTrue(modeAfterArtistSelect is HomeViewMode.Artist)
        assertEquals("arijit-singh", (modeAfterArtistSelect as HomeViewMode.Artist).artist.id)

        // 3. Drill down into Artist Playlist -> Playlist Detail takes precedence, selectedArtist is PRESERVED
        val playlistState = artistState.copy(
            selectedPlaylist = testPlaylist,
            selectedPlaylistDetail = HomeModule.Loading
        )
        assertEquals(testArtist, playlistState.selectedArtist) // Crucial invariant: artist is NOT destroyed
        val modeAfterPlaylistSelect = resolveHomeViewMode(playlistState)
        assertTrue(modeAfterPlaylistSelect is HomeViewMode.Playlist)
        assertEquals("arijit_singh", (modeAfterPlaylistSelect as HomeViewMode.Playlist).playlist.id)

        // 4. Press Back from Playlist Detail -> Popping playlist returns directly to Artist Detail
        val backToArtistState = playlistState.copy(
            selectedPlaylist = null,
            selectedPlaylistDetail = HomeModule.Hidden
        )
        val modeAfterPoppingPlaylist = resolveHomeViewMode(backToArtistState)
        assertTrue(modeAfterPoppingPlaylist is HomeViewMode.Artist)
        assertEquals("arijit-singh", (modeAfterPoppingPlaylist as HomeViewMode.Artist).artist.id)

        // 5. Press Back from Artist Detail -> Popping artist returns to Home Feed
        val backToFeedState = backToArtistState.copy(
            selectedArtist = null,
            artistDetailTracks = HomeModule.Hidden,
            artistDetailPlaylists = HomeModule.Hidden
        )
        assertEquals(HomeViewMode.Feed, resolveHomeViewMode(backToFeedState))
    }

    @Test
    fun verifyHomeViewMode_curatedPlaylistsCatalogAndDirectPlaylistBackStack() {
        val testPlaylist = PlaylistSummary(
            id = "retro_bollywood",
            name = "Retro Bollywood",
            size = 30
        )

        // 1. Direct Playlist from Home Feed
        val feedPlaylistState = HomeUiState(selectedPlaylist = testPlaylist)
        assertEquals(HomeViewMode.Playlist(testPlaylist), resolveHomeViewMode(feedPlaylistState))
        val poppedFeedPlaylistState = feedPlaylistState.copy(selectedPlaylist = null)
        assertEquals(HomeViewMode.Feed, resolveHomeViewMode(poppedFeedPlaylistState))

        // 2. Curated Playlists Catalog Flow
        val catalogState = HomeUiState(isViewingAllPlaylists = true)
        assertEquals(HomeViewMode.CuratedPlaylistsCatalog, resolveHomeViewMode(catalogState))

        // 3. Drill down into Playlist from Catalog
        val catalogPlaylistState = catalogState.copy(selectedPlaylist = testPlaylist)
        assertEquals(HomeViewMode.Playlist(testPlaylist), resolveHomeViewMode(catalogPlaylistState))

        // 4. Back from Playlist returns to Catalog (since isViewingAllPlaylists is preserved)
        val backToCatalogState = catalogPlaylistState.copy(selectedPlaylist = null)
        assertEquals(HomeViewMode.CuratedPlaylistsCatalog, resolveHomeViewMode(backToCatalogState))

        // 5. Back from Catalog returns to Feed
        val backToFeedFromCatalogState = backToCatalogState.copy(isViewingAllPlaylists = false)
        assertEquals(HomeViewMode.Feed, resolveHomeViewMode(backToFeedFromCatalogState))
    }
}

