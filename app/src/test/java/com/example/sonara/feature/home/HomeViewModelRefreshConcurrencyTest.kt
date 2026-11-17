package com.example.sonara.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.DownloadLocationMode
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelRefreshConcurrencyTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `older featured refresh cannot overwrite newer completed refresh`() = runTest(dispatcher) {
        val older = CompletableDeferred<Result<List<FeaturedArtist>>>()
        val newer = CompletableDeferred<Result<List<FeaturedArtist>>>()
        val discovery = BlockingDiscoveryRepository(older, newer)
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery)

        try {
            // Initial Home loading is refresh A and remains unresolved.
            advanceUntilIdle()
            assertEquals(1, discovery.featuredCalls)

            // Pull-to-refresh starts refresh B while A is still in flight.
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(2, discovery.featuredCalls)

            // B is the latest refresh and publishes first.
            newer.complete(Result.success(listOf(artist("newer"))))
            advanceUntilIdle()
            assertEquals(listOf("newer"), readyArtists(viewModel).map { it.name })

            // A completes later with obsolete data and must not replace B.
            older.complete(Result.success(listOf(artist("older"))))
            advanceUntilIdle()
            assertEquals(listOf("newer"), readyArtists(viewModel).map { it.name })
            assertTrue(discovery.featuredCalls >= 2)
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    /**
     * The refresh SPINNER must reflect "any refresh in flight", not "my refresh finished".
     *
     * Each `refresh()` launches its own coroutine that awaits only its OWN module jobs, so
     * whichever call completes first used to clear `isRefreshing` even while a later refresh was
     * still running — leaving the user with no spinner and a shelf that is still changing.
     *
     * Fully deterministic: every featured load is parked on its own [CompletableDeferred], so the
     * completion order is chosen by the test rather than by timing.
     */
    @Test
    fun `an earlier refresh must not clear the spinner while a later refresh is still running`() =
        runTest(dispatcher) {
            val initialLoad = CompletableDeferred<Result<List<FeaturedArtist>>>()
            val firstRefresh = CompletableDeferred<Result<List<FeaturedArtist>>>()
            val secondRefresh = CompletableDeferred<Result<List<FeaturedArtist>>>()
            val discovery = SequencedDiscoveryRepository(
                listOf(initialLoad, firstRefresh, secondRefresh)
            )
            val store = ViewModelStore()
            val viewModel = store.homeViewModel(discovery)

            try {
                // Settle the initial Home load so only explicit refreshes drive the spinner.
                advanceUntilIdle()
                initialLoad.complete(Result.success(listOf(artist("initial"))))
                advanceUntilIdle()
                assertFalse(
                    "the initial load is not a pull-to-refresh",
                    viewModel.uiState.value.isRefreshing
                )

                // Refresh A, then refresh B while A is still in flight.
                viewModel.refresh()
                advanceUntilIdle()
                viewModel.refresh()
                advanceUntilIdle()
                assertEquals(3, discovery.calls)
                assertTrue("two refreshes are in flight", viewModel.uiState.value.isRefreshing)

                // A completes first. B is still blocked, so the spinner must remain visible.
                firstRefresh.complete(Result.success(listOf(artist("a"))))
                advanceUntilIdle()
                assertTrue(
                    "an earlier refresh must not clear the spinner while a later one is running",
                    viewModel.uiState.value.isRefreshing
                )

                // Only when the last refresh finishes may the spinner clear.
                secondRefresh.complete(Result.success(listOf(artist("b"))))
                advanceUntilIdle()
                viewModel.uiState.first { !it.isRefreshing }
                assertFalse(
                    "the spinner must clear once no refresh is in flight",
                    viewModel.uiState.value.isRefreshing
                )
            } finally {
                store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
                advanceUntilIdle()
            }
        }

    @Test
    fun `a single refresh still raises and clears the spinner`() = runTest(dispatcher) {
        // Guards against over-correcting the counter: the ordinary one-refresh path must work.
        val initialLoad = CompletableDeferred<Result<List<FeaturedArtist>>>()
        val onlyRefresh = CompletableDeferred<Result<List<FeaturedArtist>>>()
        val discovery = SequencedDiscoveryRepository(listOf(initialLoad, onlyRefresh))
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery)

        try {
            advanceUntilIdle()
            initialLoad.complete(Result.success(listOf(artist("initial"))))
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isRefreshing)

            onlyRefresh.complete(Result.success(listOf(artist("only"))))
            advanceUntilIdle()
            viewModel.uiState.first { !it.isRefreshing }
            assertFalse(viewModel.uiState.value.isRefreshing)
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun readyArtists(viewModel: HomeViewModel): List<FeaturedArtist> {
        val module = viewModel.uiState.first { it.featuredArtists is HomeModule.Ready }.featuredArtists
        assertTrue("Featured Artists must be ready", module is HomeModule.Ready<*>)
        return (module as HomeModule.Ready<List<FeaturedArtist>>).value
    }

    private fun artist(name: String) = FeaturedArtist(
        id = name,
        name = name,
        genre = "Editorial",
        imageUrl = null,
        browseId = "browse-$name"
    )

    private fun ViewModelStore.homeViewModel(discovery: DiscoveryRepository): HomeViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(
                    discoveryRepository = discovery,
                    historyRepository = EligibleHistoryRepository(),
                    libraryRepository = EmptyLibraryRepository(),
                    settingsRepository = EmptySettingsRepository()
                ) as T
            }
        }
        return ViewModelProvider(this, factory)[HomeViewModel::class.java]
    }

    private class BlockingDiscoveryRepository(
        private val older: CompletableDeferred<Result<List<FeaturedArtist>>>,
        private val newer: CompletableDeferred<Result<List<FeaturedArtist>>>
    ) : DiscoveryRepository {
        var featuredCalls = 0

        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> {
            featuredCalls++
            return if (featuredCalls == 1) older.await() else newer.await()
        }

        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> =
            Result.success(ArtistCatalog())

        override suspend fun getArtistImage(artistName: String): Result<String?> = Result.success(null)

        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())

        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> =
            Result.success(PlaylistDetail(id = playlistId, name = playlistId))

        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())

        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
    }

    /**
     * Hands out one [CompletableDeferred] per `getFeaturedArtists` call, in order, so a test can
     * decide exactly which load finishes when. Calls beyond the supplied list reuse the last one.
     */
    private class SequencedDiscoveryRepository(
        private val featured: List<CompletableDeferred<Result<List<FeaturedArtist>>>>
    ) : DiscoveryRepository {
        var calls = 0

        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> {
            calls++
            return featured[minOf(calls, featured.size) - 1].await()
        }

        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> =
            Result.success(ArtistCatalog())

        override suspend fun getArtistImage(artistName: String): Result<String?> = Result.success(null)

        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())

        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> =
            Result.success(PlaylistDetail(id = playlistId, name = playlistId))

        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())

        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
    }

    private class EligibleHistoryRepository : HistoryRepository {
        // Visibility requires the existing two-event affinity threshold. Keep the
        // concurrency fixture eligible without introducing a BYLT seed.
        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = flowOf(
            if (limit == com.example.sonara.domain.discovery.ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT)
                (0..1).map { HistoryItem(it.toLong(), Track("$it", "Song", "Signal artist"),
                    System.currentTimeMillis(), true, PlaybackOrigin.SEARCH_SONGS) }
            else emptyList()
        )

        override suspend fun recordHistory(track: Track, completed: Boolean): Long = 0L

        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long = 0L

        override suspend fun markHistoryCompleted(historyId: Long) = Unit

        override suspend fun clearHistory() = Unit
    }

    private class EmptyLibraryRepository : LibraryRepository {
        override fun getLikedSongs(): Flow<List<Track>> = flowOf(emptyList())

        override fun isLiked(trackId: String): Flow<Boolean> = flowOf(false)

        override suspend fun setLiked(track: Track, isLiked: Boolean) = Unit

        override suspend fun cacheTrack(track: Track) = Unit

        override suspend fun getTrack(trackId: String): Track? = null

        override suspend fun getTracks(trackIds: List<String>): List<Track> = emptyList()
    }

    private class EmptySettingsRepository : SettingsRepository {
        override fun getUserPreferences(): Flow<UserPreferences> = flowOf(UserPreferences())

        override suspend fun setThemeMode(mode: ThemeMode) = Unit

        override suspend fun setShowRomanized(show: Boolean) = Unit

        override suspend fun setRestorePlaybackSession(enabled: Boolean) = Unit

        override suspend fun setDefaultSearchMode(mode: SearchMode) = Unit

        override suspend fun setReduceMotion(enabled: Boolean) = Unit

        override suspend fun setStreamingQuality(quality: AudioQuality) = Unit

        override suspend fun setDownloadQuality(quality: AudioQuality) = Unit

        override suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) = Unit

        override suspend fun resetSettings() = Unit

        override suspend fun setAppLanguage(language: AppLanguage) = Unit

        override suspend fun setEqualizerEnabled(enabled: Boolean) = Unit

        override suspend fun setEqualizerBandGains(gains: List<Int>) = Unit

        override suspend fun setStopMusicOnTaskClear(enabled: Boolean) = Unit

        override suspend fun setDownloadFileFormat(format: DownloadFileFormat) = Unit

        override suspend fun setDownloadLocationMode(mode: DownloadLocationMode) = Unit

        override suspend fun setDownloadLocationUri(uri: String?) = Unit

        override suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) = Unit

        override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? = null
    }
}
