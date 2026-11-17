package com.example.sonara.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.UserPreferences
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.LibraryRepository
import com.example.sonara.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Phase 2D — the REAL HomeViewModel path must personalize Featured Artists from
 * provenance-eligible history only.
 *
 * The domain rules are unit-tested in FeaturedArtistProvenanceTest; what is asserted here is
 * the integration: that the history HomeViewModel reads actually carries `sourceSection`, that
 * it reaches personalization, and that the shelf the UI observes therefore reflects eligible
 * listening only. Without this, a provenance gate could be correct in the domain and still be
 * bypassed (or fed origin-less rows) by the call path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelProvenanceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val now = System.currentTimeMillis()

    private val roster = listOf("Artist A", "Artist B", "Artist C", "Artist D")
        .map { FeaturedArtist(id = it.lowercase().replace(' ', '-'), name = it) }

    // ---------------------------------------------------------------- fakes

    private class FakeDiscoveryRepository(
        private val featured: List<FeaturedArtist>
    ) : DiscoveryRepository {
        var artistImageLookups = 0
        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = Result.success(featured)
        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> =
            Result.failure(IllegalStateException("not used"))
        override suspend fun getArtistImage(artistName: String): Result<String?> {
            artistImageLookups++
            // No portrait is available locally; the tile renders its monogram.
            return Result.success(null)
        }
        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())
        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> =
            Result.failure(IllegalStateException("not used"))
        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
    }

    private class FakeHistoryRepository(history: List<HistoryItem>) : HistoryRepository {
        private val flow = MutableStateFlow(history)
        /**
         * EVERY requested limit, not just the last one: HomeViewModel also reads history for
         * its "Because You Listened To" seed (limit 10) and, when a session exists, for the
         * hero (limit 25), so a single `lastRequestedLimit` would depend on coroutine ordering.
         */
        val requestedLimits = java.util.concurrent.CopyOnWriteArrayList<Int>()
        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> {
            requestedLimits.add(limit)
            return flow
        }
        override suspend fun recordHistory(track: Track, completed: Boolean): Long = 0L
        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long = 0L
        override suspend fun markHistoryCompleted(historyId: Long) {}
        override suspend fun clearHistory() {}
    }

    private class FakeLibraryRepository : LibraryRepository {
        override fun getLikedSongs(): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun isLiked(trackId: String): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setLiked(track: Track, isLiked: Boolean) {}
        override suspend fun cacheTrack(track: Track) {}
        override suspend fun getTrack(trackId: String): Track? = null
        override suspend fun getTracks(trackIds: List<String>): List<Track> = emptyList()
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val preferences = MutableStateFlow(UserPreferences())
        override fun getUserPreferences(): Flow<UserPreferences> = preferences
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun setShowRomanized(show: Boolean) {}
        override suspend fun setRestorePlaybackSession(enabled: Boolean) {}
        override suspend fun setDefaultSearchMode(mode: SearchMode) {}
        override suspend fun setReduceMotion(enabled: Boolean) {}
        override suspend fun setStreamingQuality(quality: AudioQuality) {}
        override suspend fun setDownloadQuality(quality: AudioQuality) {}
        override suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) {}
        override suspend fun resetSettings() {}
        override suspend fun setAppLanguage(language: com.example.sonara.domain.model.AppLanguage) {}
        override suspend fun setEqualizerEnabled(enabled: Boolean) {}
        override suspend fun setEqualizerBandGains(gains: List<Int>) {}
        override suspend fun setStopMusicOnTaskClear(enabled: Boolean) {}
        override suspend fun setDownloadFileFormat(format: com.example.sonara.domain.model.DownloadFileFormat) {}
        override suspend fun setDownloadLocationMode(mode: com.example.sonara.domain.model.DownloadLocationMode) {}
        override suspend fun setDownloadLocationUri(uri: String?) {}
        override suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) {}
        override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? = null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun plays(artist: String, origin: PlaybackOrigin, count: Int): List<HistoryItem> =
        (0 until count).map { i ->
            HistoryItem(
                id = i.toLong(),
                track = Track(id = "vid-$artist-$i", title = "Song $i", artist = artist),
                listenedAt = now - (i * 60_000L),
                completed = true,
                sourceSection = origin
            )
        }

    /**
     * Builds the real ViewModel and waits for the Featured Artists module to settle.
     *
     * Declared as a [TestScope] extension so it runs inside the same scope — and therefore the
     * same scheduler — as the calling `runTest` block.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun TestScope.shelfFor(
        history: List<HistoryItem>,
        discovery: FakeDiscoveryRepository = FakeDiscoveryRepository(roster),
        historyRepo: FakeHistoryRepository = FakeHistoryRepository(history)
    ): List<FeaturedArtist> {
        val store = ViewModelStore()
        val vm = store.homeViewModel(
            discoveryRepository = discovery,
            historyRepository = historyRepo,
            libraryRepository = FakeLibraryRepository(),
            settingsRepository = FakeSettingsRepository()
        )
        try {
            // advanceUntilIdle() only drains THIS scheduler, i.e. the Main-dispatcher `init`
            // coroutines. It cannot observe personalization, which hops to Dispatchers.Default — a
            // real thread pool the test scheduler does not own. The state read below waits for the
            // module to reach Ready, whichever dispatcher produced it, with no sleeping or fixed
            // delay. The ViewModel is then cleared in finally so its long-lived history observer
            // cannot outlive the test and dispatch onto Main after resetMain().
            advanceUntilIdle()
            val settled = vm.uiState.first { it.featuredArtists != HomeModule.Loading }
            return (settled.featuredArtists as? HomeModule.Ready<List<FeaturedArtist>>)?.value.orEmpty()
        } finally {
            store.clear()
            vm.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    private fun ViewModelStore.homeViewModel(
        discoveryRepository: DiscoveryRepository,
        historyRepository: HistoryRepository,
        libraryRepository: LibraryRepository,
        settingsRepository: SettingsRepository
    ): HomeViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(
                    discoveryRepository = discoveryRepository,
                    historyRepository = historyRepository,
                    libraryRepository = libraryRepository,
                    settingsRepository = settingsRepository
                ) as T
            }
        }
        return ViewModelProvider(this, factory)[HomeViewModel::class.java]
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `radio autoplay history reaches the view model but cannot reshape the shelf`() = runTest(testDispatcher) {
        val historyRepo = FakeHistoryRepository(plays("Hans Zimmer", PlaybackOrigin.RADIO_AUTOPLAY, 12))

        val shelf = shelfFor(emptyList(), historyRepo = historyRepo)

        assertFalse(
            "autoplay listening must not insert an artist via the real VM path: ${shelf.map { it.name }}",
            shelf.map { it.name }.contains("Hans Zimmer")
        )
        assertEquals(
            "ineligible listening must keep the shelf hidden",
            emptyList<String>(),
            shelf.map { it.name }
        )
        assertTrue(
            "the VM must read history for personalization with the bounded sample limit, saw ${historyRepo.requestedLimits}",
            historyRepo.requestedLimits.contains(
                com.example.sonara.domain.discovery.ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT
            )
        )
    }

    @Test
    fun `songs-search history flows through the real view model and admits the artist`() = runTest(testDispatcher) {
        val shelf = shelfFor(plays("Hans Zimmer", PlaybackOrigin.SEARCH_SONGS, 5))

        assertTrue(
            "eligible provenance must reach personalization through the real VM: ${shelf.map { it.name }}",
            shelf.map { it.name }.contains("Hans Zimmer")
        )
        val admitted = shelf.first { it.name == "Hans Zimmer" }
        assertNull("no portrait may be fabricated for a locally-derived artist", admitted.imageUrl)
    }

    @Test
    fun `liked songs history also admits through the real view model`() = runTest(testDispatcher) {
        val shelf = shelfFor(plays("Hans Zimmer", PlaybackOrigin.LIKED_SONGS, 5))
        assertTrue(shelf.map { it.name }.contains("Hans Zimmer"))
    }

    @Test
    fun `mixed history admits only on the eligible events`() = runTest(testDispatcher) {
        // Two eligible plays (enough to ORDER, not to ADMIT) plus a pile of ineligible ones.
        val mixed = plays("Hans Zimmer", PlaybackOrigin.SEARCH_SONGS, 2) +
            plays("Hans Zimmer", PlaybackOrigin.HOME, 20) +
            plays("Hans Zimmer", PlaybackOrigin.PLAYLIST, 20)

        val shelf = shelfFor(mixed)

        assertFalse(
            "ineligible events must not push the artist over the membership threshold",
            shelf.map { it.name }.contains("Hans Zimmer")
        )
        assertEquals(roster.map { it.name }, shelf.map { it.name })
    }

    @Test
    fun `legacy history keeps the shelf hidden through the real view model`() = runTest(testDispatcher) {
        val shelf = shelfFor(plays("Hans Zimmer", PlaybackOrigin.LEGACY_UNKNOWN, 30))
        assertTrue(shelf.isEmpty())
    }

    @Test
    fun `a history read failure cannot substitute the editorial roster`() = runTest(testDispatcher) {
        val failing = object : HistoryRepository {
            override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> =
                kotlinx.coroutines.flow.flow { throw IllegalStateException("history unavailable") }
            override suspend fun recordHistory(track: Track, completed: Boolean): Long = 0L
            override suspend fun recordHistoryWithProvenance(
                track: Track,
                completed: Boolean,
                sourceSection: PlaybackOrigin,
                playbackSessionId: String
            ): Long = 0L
            override suspend fun markHistoryCompleted(historyId: Long) {}
            override suspend fun clearHistory() {}
        }

        val store = ViewModelStore()
        val vm = store.homeViewModel(
            discoveryRepository = FakeDiscoveryRepository(roster),
            historyRepository = failing,
            libraryRepository = FakeLibraryRepository(),
            settingsRepository = FakeSettingsRepository()
        )
        val shelf = try {
            advanceUntilIdle()
            val ready = vm.uiState.first { it.featuredArtists != HomeModule.Loading }
            @Suppress("UNCHECKED_CAST")
            (ready.featuredArtists as? HomeModule.Ready<List<FeaturedArtist>>)?.value.orEmpty()
        } finally {
            store.clear()
            vm.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }

        assertEquals(
            "a local history failure must keep unverified artist recommendations hidden",
            emptyList<String>(),
            shelf.map { it.name }
        )
    }

    @Test
    fun `history observer propagates cancellation instead of treating it as a read failure`() = runTest(testDispatcher) {
        val store = ViewModelStore()
        val vm = store.homeViewModel(
            discoveryRepository = FakeDiscoveryRepository(roster),
            historyRepository = FakeHistoryRepository(emptyList()),
            libraryRepository = FakeLibraryRepository(),
            settingsRepository = FakeSettingsRepository()
        )

        try {
            var cancellationPropagated = false
            try {
                vm.collectHistoryUpdates(
                    flow<List<HistoryItem>> { throw CancellationException("test cancellation") }
                ) { }
                fail("history observer collection must propagate CancellationException")
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }

            assertTrue(cancellationPropagated)
        } finally {
            store.clear()
            vm.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }
}
