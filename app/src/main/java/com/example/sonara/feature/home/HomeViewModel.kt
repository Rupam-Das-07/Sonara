package com.example.sonara.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.LibraryRepository
import com.example.sonara.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Drives the corrected Phase 5C Home screen.
 *
 * Each module loads independently into its own [HomeModule] slot so a single
 * failing or empty section self-hides without collapsing the screen. The Console
 * Hero baseline is derived from the persisted playback session (the real re-entry
 * source); live playback is overlaid downstream via [resolveConsoleHero] so this
 * ViewModel never duplicates playback ownership. Nothing here fabricates content:
 * empty or failed loads become [HomeModule.Hidden].
 */
class HomeViewModel(
    private val discoveryRepository: DiscoveryRepository,
    private val historyRepository: HistoryRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Set when any module fails specifically with a network error (drives offline). */
    private var networkErrorSeen = false

    /** Guards redundant "Because You Listened To" reloads as history emits. */
    private var lastByltSeedId: String? = null

    init {
        observeHistory()
        viewModelScope.launch { refreshFeaturedArtists() }
        viewModelScope.launch { refreshPlaylists() }
        viewModelScope.launch { refreshQuickPicks(refresh = false) }
        viewModelScope.launch { refreshHero() }
    }

    // -------------------------------------------------------------------------
    // History-driven module (Because You Listen To …)
    // -------------------------------------------------------------------------

    /**
     * The BYLT seed is the most recently listened track. We observe history so the
     * section re-seeds when the head changes, and reload only when it actually does.
     */
    private fun observeHistory() {
        viewModelScope.launch {
            historyRepository.getRecentHistory(limit = 10).collect { history ->
                val seed = history.firstOrNull()?.track
                if (seed?.id != lastByltSeedId) {
                    lastByltSeedId = seed?.id
                    refreshBecauseYouListened(seed)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Independent module loaders
    // -------------------------------------------------------------------------

    private suspend fun refreshFeaturedArtists() {
        _uiState.update { it.copy(featuredArtists = HomeModule.Loading) }
        discoveryRepository.getFeaturedArtists().fold(
            onSuccess = { list ->
                _uiState.update {
                    it.copy(
                        featuredArtists = if (list.isEmpty()) HomeModule.Hidden
                        else HomeModule.Ready(list)
                    )
                }
            },
            onFailure = { e ->
                noteNetworkError(e)
                _uiState.update { it.copy(featuredArtists = HomeModule.Hidden) }
            }
        )
        recomputeDerived()
    }

    private suspend fun refreshPlaylists() {
        _uiState.update { it.copy(playlists = HomeModule.Loading) }
        discoveryRepository.getPlaylists().fold(
            onSuccess = { list ->
                _uiState.update {
                    it.copy(
                        playlists = if (list.isEmpty()) HomeModule.Hidden
                        else HomeModule.Ready(list)
                    )
                }
            },
            onFailure = { e ->
                noteNetworkError(e)
                _uiState.update { it.copy(playlists = HomeModule.Hidden) }
            }
        )
        recomputeDerived()
    }

    private suspend fun refreshQuickPicks(refresh: Boolean) {
        _uiState.update { it.copy(quickPicks = HomeModule.Loading) }
        discoveryRepository.getQuickPicks(refresh).fold(
            onSuccess = { list ->
                _uiState.update {
                    it.copy(
                        quickPicks = if (list.isEmpty()) HomeModule.Hidden
                        else HomeModule.Ready(list)
                    )
                }
            },
            onFailure = { e ->
                noteNetworkError(e)
                _uiState.update { it.copy(quickPicks = HomeModule.Hidden) }
            }
        )
        recomputeDerived()
    }

    private suspend fun refreshBecauseYouListened(seed: Track?) {
        if (seed == null || seed.id.isBlank()) {
            _uiState.update { it.copy(becauseYouListened = HomeModule.Hidden) }
            recomputeDerived()
            return
        }
        _uiState.update { it.copy(becauseYouListened = HomeModule.Loading) }
        discoveryRepository.getRelatedTracks(seed.id).fold(
            onSuccess = { related ->
                // Never recommend the seed back to the listener.
                val tracks = related.filter { it.id != seed.id }
                _uiState.update {
                    it.copy(
                        becauseYouListened = if (tracks.isEmpty()) HomeModule.Hidden
                        else HomeModule.Ready(BecauseYouListenedData(seed.title, tracks))
                    )
                }
            },
            onFailure = { e ->
                noteNetworkError(e)
                _uiState.update { it.copy(becauseYouListened = HomeModule.Hidden) }
            }
        )
        recomputeDerived()
    }

    /**
     * Console Hero baseline. Authority is the persisted playback session snapshot
     * (last track id + position); metadata is resolved from the library cache, then
     * from history as a fallback. No snapshot (or unresolvable track) → ColdStart.
     * Live playback, when present, is overlaid later by [resolveConsoleHero].
     */
    private suspend fun refreshHero() {
        val prefs = settingsRepository.getUserPreferences().firstOrNull()
        if (prefs?.restorePlaybackSession == false) {
            _uiState.update { it.copy(hero = ConsoleHeroState.ColdStart) }
            recomputeDerived()
            return
        }

        val snapshot = settingsRepository.getPlaybackSession()
        val lastId = snapshot?.lastTrackId?.takeIf { it.isNotBlank() }
        if (snapshot == null || lastId == null) {
            _uiState.update { it.copy(hero = ConsoleHeroState.ColdStart) }
            recomputeDerived()
            return
        }
        val track = libraryRepository.getTrack(lastId)
            ?: runCatching { historyRepository.getRecentHistory(limit = 25).first() }
                .getOrNull()
                ?.firstOrNull { it.track.id == lastId }
                ?.track
        _uiState.update {
            if (track == null) {
                it.copy(hero = ConsoleHeroState.ColdStart)
            } else {
                it.copy(
                    hero = ConsoleHeroState.Resumable(
                        ResumableSession(
                            track = track,
                            positionMs = snapshot.lastPositionMs,
                            durationMs = track.durationMs
                        )
                    )
                )
            }
        }
        recomputeDerived()
    }

    // -------------------------------------------------------------------------
    // Public actions
    // -------------------------------------------------------------------------

    /** Pull-to-refresh: reload every module (Quick Picks rotates) and re-derive. */
    fun refresh() {        viewModelScope.launch {
            networkErrorSeen = false
            _uiState.update { it.copy(isRefreshing = true) }
            val seed = runCatching { historyRepository.getRecentHistory(limit = 10).first() }
                .getOrNull()?.firstOrNull()?.track
            val jobs = listOf(
                launch { refreshFeaturedArtists() },
                launch { refreshPlaylists() },
                launch { refreshQuickPicks(refresh = true) },
                launch { refreshHero() },
                launch { refreshBecauseYouListened(seed) }
            )
            jobs.joinAll()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * Targeted rotation of just the Quick Picks module — the section header's
     * "Refresh" action. Leaves the hero and the other modules untouched.
     */
    fun refreshQuickPicks() {
        viewModelScope.launch { refreshQuickPicks(refresh = true) }
    }

    /**
     * Maximized Artist Detail view selection.
     */
    fun selectArtist(artist: com.example.sonara.domain.model.FeaturedArtist?) {
        if (artist == null) {
            _uiState.update {
                it.copy(
                    selectedArtist = null,
                    artistDetailTracks = HomeModule.Hidden,
                    artistDetailPlaylists = HomeModule.Hidden
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                selectedArtist = artist,
                selectedPlaylist = null,
                artistDetailTracks = HomeModule.Loading,
                artistDetailPlaylists = HomeModule.Loading
            )
        }
        viewModelScope.launch {
            val drillThroughId = artist.browseId ?: artist.id
            discoveryRepository.getArtistCatalog(drillThroughId, artist.name).fold(
                onSuccess = { catalog ->
                    _uiState.update {
                        if (it.selectedArtist?.id != artist.id) it
                        else it.copy(
                            artistDetailTracks = if (catalog.tracks.isEmpty()) HomeModule.Hidden
                            else HomeModule.Ready(catalog.tracks),
                            artistDetailPlaylists = if (catalog.playlists.isEmpty()) HomeModule.Hidden
                            else HomeModule.Ready(catalog.playlists)
                        )
                    }
                },
                onFailure = {
                    _uiState.update {
                        if (it.selectedArtist?.id != artist.id) it
                        else it.copy(
                            artistDetailTracks = HomeModule.Hidden,
                            artistDetailPlaylists = HomeModule.Hidden
                        )
                    }
                }
            )
        }
    }

    /**
     * Maximized Playlist Detail view selection.
     */
    fun selectPlaylist(playlist: com.example.sonara.domain.model.PlaylistSummary?) {
        if (playlist == null) {
            _uiState.update { it.copy(selectedPlaylist = null, selectedPlaylistDetail = HomeModule.Hidden) }
            return
        }
        _uiState.update {
            it.copy(selectedPlaylist = playlist, selectedPlaylistDetail = HomeModule.Loading)
        }
        viewModelScope.launch {
            discoveryRepository.getPlaylistDetail(playlist.id).fold(
                onSuccess = { detail ->
                    _uiState.update {
                        if (it.selectedPlaylist?.id != playlist.id) it
                        else it.copy(
                            selectedPlaylistDetail = if (detail.tracks.isEmpty()) HomeModule.Hidden
                            else HomeModule.Ready(detail)
                        )
                    }
                },
                onFailure = {
                    _uiState.update {
                        if (it.selectedPlaylist?.id != playlist.id) it
                        else it.copy(selectedPlaylistDetail = HomeModule.Hidden)
                    }
                }
            )
        }
    }

    /**
     * Toggles viewing the full curated playlists catalogue.
     */
    fun viewAllPlaylists(open: Boolean) {
        _uiState.update { it.copy(isViewingAllPlaylists = open) }
    }

    /**
     * Toggles viewing the full featured artists catalogue.
     */
    fun viewAllArtists(open: Boolean) {
        _uiState.update { it.copy(isViewingAllArtists = open) }
    }


    // -------------------------------------------------------------------------
    // Derived state
    // -------------------------------------------------------------------------

    private fun noteNetworkError(e: Throwable) {
        if (e is SonaraException.NetworkException) networkErrorSeen = true
    }

    /**
     * Offline = a network error occurred AND no module produced usable content.
     * When offline with nothing resumable, the hero degrades ColdStart→Offline
     * (and recovers Offline→ColdStart symmetrically), while a Resumable hero is
     * preserved (its local metadata is still truthful to display).
     */
    private fun recomputeDerived() {
        _uiState.update { s ->
            val anyReady = s.featuredArtists is HomeModule.Ready<*> ||
                s.playlists is HomeModule.Ready<*> ||
                s.quickPicks is HomeModule.Ready<*> ||
                s.becauseYouListened is HomeModule.Ready<*>
            val offline = networkErrorSeen && !anyReady
            val hero = when {
                offline && s.hero is ConsoleHeroState.ColdStart -> ConsoleHeroState.Offline
                !offline && s.hero is ConsoleHeroState.Offline -> ConsoleHeroState.ColdStart
                else -> s.hero
            }
            s.copy(isOffline = offline, hero = hero)
        }
    }
}
