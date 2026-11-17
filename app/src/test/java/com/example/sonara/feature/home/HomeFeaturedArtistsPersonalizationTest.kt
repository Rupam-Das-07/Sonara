package com.example.sonara.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.example.sonara.core.error.SonaraException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class HomeFeaturedArtistsPersonalizationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(id: String, artist: String) = Track(id = id, title = "Song $id", artist = artist)

    private fun play(id: Long, artist: String, offsetMs: Long = 0L) = HistoryItem(
        id = id,
        track = track("vid-$id", artist),
        listenedAt = now - offsetMs,
        completed = true,
        sourceSection = PlaybackOrigin.SEARCH_SONGS
    )

    private fun editorialArtist(name: String) = FeaturedArtist(
        id = name.lowercase().replace(' ', '-'),
        name = name,
        genre = "Editorial",
        imageUrl = null,
        browseId = "browse-$name"
    )

    @Test
    fun `backend editorial failure preserves local personal artist personalization`() = runTest(testDispatcher) {
        val discovery = TestDiscoveryRepository(
            featuredResult = Result.failure(SonaraException.NetworkException("Server unreachable"))
        )
        val historyItems = listOf(
            play(1L, "Pritam", 1000L),
            play(2L, "Pritam", 2000L),
            play(3L, "Pritam", 3000L)
        )
        val history = TestHistoryRepository(historyItems)
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery, history)

        try {
            advanceUntilIdle()
            val state = viewModel.uiState.first { it.featuredArtists != HomeModule.Loading }
            assertTrue("Shelf should be ready with personal candidate", state.featuredArtists is HomeModule.Ready<*>)
            @Suppress("UNCHECKED_CAST")
            val artists = (state.featuredArtists as HomeModule.Ready<List<FeaturedArtist>>).value
            assertEquals(1, artists.size)
            assertEquals("Pritam", artists[0].name)
            // Even though backend network failed, usable local content is ready so the screen is not offline
            assertFalse("Personal shelf is usable so screen is not forced offline", state.isOffline)
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    @Test
    fun `empty editorial roster from backend still personalizes from local history`() = runTest(testDispatcher) {
        val discovery = TestDiscoveryRepository(featuredResult = Result.success(emptyList()))
        val historyItems = listOf(
            play(1L, "Arijit Singh", 1000L),
            play(2L, "Arijit Singh", 2000L),
            play(3L, "Arijit Singh", 3000L)
        )
        val history = TestHistoryRepository(historyItems)
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery, history)

        try {
            advanceUntilIdle()
            val state = viewModel.uiState.first { it.featuredArtists != HomeModule.Loading }
            assertTrue("Shelf should be ready with personal candidate", state.featuredArtists is HomeModule.Ready<*>)
            @Suppress("UNCHECKED_CAST")
            val artists = (state.featuredArtists as HomeModule.Ready<List<FeaturedArtist>>).value
            assertEquals(1, artists.size)
            assertEquals("Arijit Singh", artists[0].name)
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    @Test
    fun `empty editorial roster and empty personal history resolves to HomeModule Hidden`() = runTest(testDispatcher) {
        val discovery = TestDiscoveryRepository(featuredResult = Result.success(emptyList()))
        val history = TestHistoryRepository(emptyList())
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery, history)

        try {
            advanceUntilIdle()
            val state = viewModel.uiState.first { it.featuredArtists != HomeModule.Loading }
            assertEquals(HomeModule.Hidden, state.featuredArtists)
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    @Test
    fun `backend editorial failure and empty personal history resolves to HomeModule Hidden`() = runTest(testDispatcher) {
        val discovery = TestDiscoveryRepository(
            featuredResult = Result.failure(SonaraException.NetworkException("Offline"))
        )
        val history = TestHistoryRepository(emptyList())
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery, history)

        try {
            advanceUntilIdle()
            val state = viewModel.uiState.first { it.featuredArtists != HomeModule.Loading }
            assertEquals(HomeModule.Hidden, state.featuredArtists)
            assertTrue(state.isOffline)
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    @Test
    fun `qualifying personal artists up to 8 occupy all 8 slots without editorial backfill`() = runTest(testDispatcher) {
        val editorial = (1..8).map { editorialArtist("Editorial $it") }
        val discovery = TestDiscoveryRepository(featuredResult = Result.success(editorial))

        val historyItems = (1..8).flatMap { i ->
            (1..3).map { j ->
                play((i * 10 + j).toLong(), "Personal $i", (i * 1000 + j).toLong())
            }
        }
        val history = TestHistoryRepository(historyItems)
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery, history)

        try {
            advanceUntilIdle()
            val state = viewModel.uiState.first { it.featuredArtists != HomeModule.Loading }
            assertTrue(state.featuredArtists is HomeModule.Ready<*>)
            @Suppress("UNCHECKED_CAST")
            val artists = (state.featuredArtists as HomeModule.Ready<List<FeaturedArtist>>).value
            assertEquals(8, artists.size)
            val names = artists.map { it.name }.toSet()
            for (i in 1..8) {
                assertTrue("Must contain Personal $i", names.contains("Personal $i"))
            }
            for (i in 1..8) {
                assertFalse("Must not contain Editorial $i", names.contains("Editorial $i"))
            }
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    @Test
    fun `reactive history update recalculates shelf automatically without refresh`() = runTest(testDispatcher) {
        val discovery = TestDiscoveryRepository(featuredResult = Result.success(emptyList()))
        val history = TestHistoryRepository(emptyList())
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery, history)

        try {
            advanceUntilIdle()
            val initialState = viewModel.uiState.first { it.featuredArtists != HomeModule.Loading }
            assertEquals(HomeModule.Hidden, initialState.featuredArtists)

            // Emit newly persisted qualifying listens
            val qualifyingListens = listOf(
                play(101L, "Diljit Dosanjh", 1000L),
                play(102L, "Diljit Dosanjh", 2000L),
                play(103L, "Diljit Dosanjh", 3000L)
            )
            history.historyFlow.value = qualifyingListens

            // Advance past the 300ms debounce
            advanceTimeBy(350L)
            advanceUntilIdle()

            val updatedState = viewModel.uiState.first { it.featuredArtists is HomeModule.Ready }
            @Suppress("UNCHECKED_CAST")
            val artists = (updatedState.featuredArtists as HomeModule.Ready<List<FeaturedArtist>>).value
            assertEquals(1, artists.size)
            assertEquals("Diljit Dosanjh", artists[0].name)
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    @Test
    fun `multiple rapid history emissions debounce into single recalculation`() = runTest(testDispatcher) {
        val discovery = TestDiscoveryRepository(featuredResult = Result.success(emptyList()))
        val history = TestHistoryRepository(emptyList())
        val store = ViewModelStore()
        val viewModel = store.homeViewModel(discovery, history)

        try {
            advanceUntilIdle()
            assertEquals(HomeModule.Hidden, viewModel.uiState.first { it.featuredArtists != HomeModule.Loading }.featuredArtists)

            // Emission 1 at t=0
            history.historyFlow.value = listOf(play(1L, "A. R. Rahman", 1000L))
            advanceTimeBy(100L)

            // Emission 2 at t=100
            history.historyFlow.value = listOf(
                play(1L, "A. R. Rahman", 1000L),
                play(2L, "A. R. Rahman", 2000L)
            )
            advanceTimeBy(100L)

            // Emission 3 at t=200
            history.historyFlow.value = listOf(
                play(1L, "A. R. Rahman", 1000L),
                play(2L, "A. R. Rahman", 2000L),
                play(3L, "A. R. Rahman", 3000L)
            )
            advanceTimeBy(100L) // t=300ms from start, but only 100ms from emission 3 (debounce is 300ms)

            // Still debouncing from emission 3
            assertEquals(HomeModule.Hidden, viewModel.uiState.value.featuredArtists)

            // Advance remaining time past 300ms debounce from emission 3
            advanceTimeBy(250L)
            advanceUntilIdle()

            val state = viewModel.uiState.first { it.featuredArtists is HomeModule.Ready }
            @Suppress("UNCHECKED_CAST")
            val artists = (state.featuredArtists as HomeModule.Ready<List<FeaturedArtist>>).value
            assertEquals(1, artists.size)
            assertEquals("A. R. Rahman", artists[0].name)
        } finally {
            store.clear()
            viewModel.viewModelScope.coroutineContext[Job]?.join()
            advanceUntilIdle()
        }
    }

    private fun ViewModelStore.homeViewModel(
        discoveryRepository: DiscoveryRepository,
        historyRepository: HistoryRepository
    ): HomeViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(
                    discoveryRepository = discoveryRepository,
                    historyRepository = historyRepository,
                    libraryRepository = EmptyLibraryRepository(),
                    settingsRepository = EmptySettingsRepository()
                ) as T
            }
        }
        return ViewModelProvider(this, factory)[HomeViewModel::class.java]
    }

    private class TestDiscoveryRepository(
        var featuredResult: Result<List<FeaturedArtist>>
    ) : DiscoveryRepository {
        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = featuredResult
        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> =
            Result.success(ArtistCatalog())
        override suspend fun getArtistImage(artistName: String): Result<String?> = Result.success(null)
        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())
        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> =
            Result.success(PlaylistDetail(id = playlistId, name = playlistId))
        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
    }

    private class TestHistoryRepository(
        initial: List<HistoryItem>
    ) : HistoryRepository {
        val historyFlow = MutableStateFlow(initial)

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> =
            historyFlow.map { it.take(limit) }

        override fun getRecentHistoryUpToForOrigins(
            newestAllowed: Long,
            origins: Collection<PlaybackOrigin>,
            limit: Int
        ): Flow<List<HistoryItem>> = historyFlow.map { items ->
            items.filter { it.listenedAt <= newestAllowed && it.sourceSection in origins }.take(limit)
        }

        override suspend fun recordHistory(track: Track, completed: Boolean): Long = 0L
        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long = 0L
        override suspend fun markHistoryCompleted(historyId: Long) = Unit
        override suspend fun clearHistory() {
            historyFlow.value = emptyList()
        }
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
