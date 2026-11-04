package com.example.sonara.feature.home

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelCancellationTest {

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
    fun `hero history cancellation does not become a cold start fallback`() = runTest(dispatcher) {
        val history = RecordingHistoryRepository { limit ->
            if (limit == 25) cancellingHistory() else flowOf(emptyList())
        }
        val viewModel = createViewModel(
            history = history,
            snapshot = PlaybackSessionSnapshot(lastTrackId = "track-1")
        )

        advanceUntilIdle()

        assertEquals(ConsoleHeroState.Loading, viewModel.uiState.value.hero)
        assertTrue(history.requestedLimits.contains(25))
    }

    @Test
    fun `refresh seed cancellation stops refresh instead of starting fallback work`() = runTest(dispatcher) {
        val history = RecordingHistoryRepository { limit ->
            if (limit == 10) cancellingHistory() else flowOf(emptyList())
        }
        val viewModel = createViewModel(history)

        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isRefreshing)
        assertTrue(history.requestedLimits.count { it == 10 } >= 2)
    }

    @Test
    fun `ordinary hero history failure still falls back to cold start`() = runTest(dispatcher) {
        val history = RecordingHistoryRepository { limit ->
            if (limit == 25) failingHistory() else flowOf(emptyList())
        }
        val viewModel = createViewModel(
            history = history,
            snapshot = PlaybackSessionSnapshot(lastTrackId = "track-1")
        )

        advanceUntilIdle()

        assertEquals(ConsoleHeroState.ColdStart, viewModel.uiState.value.hero)
    }

    @Test
    fun `ordinary refresh seed failure still completes the refresh`() = runTest(dispatcher) {
        val history = RecordingHistoryRepository { limit ->
            if (limit == 10) failingHistory() else flowOf(emptyList())
        }
        val discovery = FakeDiscoveryRepository()
        val viewModel = createViewModel(history = history, discovery = discovery)

        advanceUntilIdle()
        discovery.relatedSeeds.clear()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isRefreshing)
        assertTrue(discovery.relatedSeeds.isEmpty())
    }

    @Test
    fun `successful hero history read still restores the matching track`() = runTest(dispatcher) {
        val track = Track(id = "track-1", title = "Song", artist = "Artist", durationMs = 240_000L)
        val history = RecordingHistoryRepository { limit ->
            if (limit == 25) flowOf(listOf(historyItem(track))) else flowOf(emptyList())
        }
        val viewModel = createViewModel(
            history = history,
            snapshot = PlaybackSessionSnapshot(lastTrackId = track.id, lastPositionMs = 30_000L)
        )

        advanceUntilIdle()

        assertEquals(
            ConsoleHeroState.Resumable(
                ResumableSession(track = track, positionMs = 30_000L, durationMs = 240_000L)
            ),
            viewModel.uiState.value.hero
        )
    }

    @Test
    fun `successful refresh seed still reaches the related-content loader`() = runTest(dispatcher) {
        val track = Track(id = "track-1", title = "Song", artist = "Artist")
        val history = RecordingHistoryRepository { limit ->
            if (limit == 10) flowOf(listOf(historyItem(track))) else flowOf(emptyList())
        }
        val discovery = FakeDiscoveryRepository()
        val viewModel = createViewModel(history = history, discovery = discovery)

        advanceUntilIdle()
        discovery.relatedSeeds.clear()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.isRefreshing)
        assertTrue(discovery.relatedSeeds.contains(track.id))
    }

    private fun createViewModel(
        history: HistoryRepository,
        discovery: DiscoveryRepository = FakeDiscoveryRepository(),
        snapshot: PlaybackSessionSnapshot? = null
    ): HomeViewModel = HomeViewModel(
        discoveryRepository = discovery,
        historyRepository = history,
        libraryRepository = FakeLibraryRepository(),
        settingsRepository = FakeSettingsRepository(snapshot)
    )

    private fun historyItem(track: Track) = HistoryItem(
        id = 1L,
        track = track,
        listenedAt = 1L,
        completed = true,
        sourceSection = PlaybackOrigin.SEARCH_SONGS
    )

    private fun cancellingHistory(): Flow<List<HistoryItem>> = flow {
        throw CancellationException("test cancellation")
    }

    private fun failingHistory(): Flow<List<HistoryItem>> = flow {
        throw IllegalStateException("history unavailable")
    }

    private class RecordingHistoryRepository(
        private val historyForLimit: (Int) -> Flow<List<HistoryItem>>
    ) : HistoryRepository {
        val requestedLimits = mutableListOf<Int>()

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> {
            requestedLimits += limit
            return historyForLimit(limit)
        }

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

    private class FakeDiscoveryRepository : DiscoveryRepository {
        val relatedSeeds = mutableListOf<String>()

        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = Result.success(emptyList())

        override suspend fun getArtistCatalog(
            artistId: String,
            artistName: String
        ): Result<ArtistCatalog> = Result.success(ArtistCatalog())

        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())

        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> =
            Result.success(PlaylistDetail(id = playlistId, name = ""))

        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())

        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> {
            relatedSeeds += seedVideoId
            return Result.success(emptyList())
        }
    }

    private class FakeLibraryRepository : LibraryRepository {
        override fun getLikedSongs(): Flow<List<Track>> = flowOf(emptyList())

        override fun isLiked(trackId: String): Flow<Boolean> = flowOf(false)

        override suspend fun setLiked(track: Track, isLiked: Boolean) = Unit

        override suspend fun cacheTrack(track: Track) = Unit

        override suspend fun getTrack(trackId: String): Track? = null

        override suspend fun getTracks(trackIds: List<String>): List<Track> = emptyList()
    }

    private class FakeSettingsRepository(
        private val snapshot: PlaybackSessionSnapshot?
    ) : SettingsRepository {
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

        override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? = snapshot
    }
}
