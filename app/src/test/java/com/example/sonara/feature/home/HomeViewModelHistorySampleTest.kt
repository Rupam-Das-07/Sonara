package com.example.sonara.feature.home

import com.example.sonara.domain.discovery.ArtistAffinityCalculator
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * I-2 — the REAL HomeViewModel must draw its personalization sample with the future bound applied
 * BEFORE the limit, so rows recorded while the clock was ahead cannot crowd genuine listening out.
 *
 * The SQL itself is verified on real SQLite by `HistorySampleWindowInstrumentedTest`. This test
 * pins the wiring: [SqlLikeHistoryRepository] reproduces each query's `ORDER BY listenedAt DESC
 * LIMIT` semantics, so the old call path (`getRecentHistory(500)`) hands the selector 500
 * future-dated rows and Hans Zimmer is crowded off the shelf, while the new path admits him.
 *
 * Deterministic: no sleeps; the only clock assertion is a bound, not a duration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelHistorySampleTest {

    private val dispatcher = StandardTestDispatcher()
    private val dayMs = 24L * 60L * 60L * 1000L

    private val roster = listOf("Artist A", "Artist B", "Artist C", "Artist D")
        .map { FeaturedArtist(id = it.lowercase().replace(' ', '-'), name = it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(artist: String, listenedAt: Long, index: Int) = HistoryItem(
        id = index.toLong(),
        track = Track(id = "vid-$artist-$index", title = "Song $index", artist = artist),
        listenedAt = listenedAt,
        completed = true,
        sourceSection = PlaybackOrigin.SEARCH_SONGS
    )

    @Test
    fun `future-dated rows cannot crowd a genuine artist out of the personalization sample`() =
        runTest(dispatcher) {
            val wallNow = System.currentTimeMillis()
            // More future-dated rows than the sample limit, all from an eligible origin, so they
            // would be crowding for the timestamp reason alone.
            val clockDrift = (0 until ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT + 100)
                .map { row("Clock Drift", wallNow + 400 * dayMs + it, it) }
            val genuine = (0 until 4).map { row("Hans Zimmer", wallNow - (it + 1) * 60_000L, 10_000 + it) }
            val history = SqlLikeHistoryRepository(clockDrift + genuine)

            val before = System.currentTimeMillis()
            val viewModel = HomeViewModel(
                discoveryRepository = FakeDiscoveryRepository(roster),
                historyRepository = history,
                libraryRepository = FakeLibraryRepository(),
                settingsRepository = FakeSettingsRepository()
            )
            advanceUntilIdle()
            val ready = viewModel.uiState.first { it.featuredArtists is HomeModule.Ready }
            val after = System.currentTimeMillis()

            @Suppress("UNCHECKED_CAST")
            val names = (ready.featuredArtists as HomeModule.Ready<List<FeaturedArtist>>)
                .value.map { it.name }

            assertTrue("genuine listening must survive the bounded sample: $names", "Hans Zimmer" in names)
            assertFalse("future-dated listening must never be admitted: $names", "Clock Drift" in names)

            // The bound is FA-001's own inclusive edge, from the shared constant.
            val (newestAllowed, limit) = history.upToCalls.single()
            assertEquals(ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT, limit)
            val tolerance = ArtistAffinityCalculator.FUTURE_TOLERANCE_MS
            assertTrue(
                "newestAllowed must be now + FUTURE_TOLERANCE_MS for the pass's own clock reading",
                newestAllowed in (before + tolerance)..(after + tolerance)
            )
        }

    // ------------------------------------------------------------------ fakes

    /** Models each DAO query's SQL: optional WHERE, then ORDER BY listenedAt DESC, then LIMIT. */
    private class SqlLikeHistoryRepository(private val rows: List<HistoryItem>) : HistoryRepository {
        val upToCalls = CopyOnWriteArrayList<Pair<Long, Int>>()

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> =
            flowOf(rows.sortedByDescending { it.listenedAt }.take(limit))

        override fun getRecentHistoryUpTo(newestAllowed: Long, limit: Int): Flow<List<HistoryItem>> {
            upToCalls += newestAllowed to limit
            return flowOf(
                rows.filter { it.listenedAt <= newestAllowed }
                    .sortedByDescending { it.listenedAt }
                    .take(limit)
            )
        }

        /**
         * I-5 added the provenance predicate to this query. It is modelled here too so this test
         * keeps exercising the real call path; its rows are all eligible, so the timestamp
         * behaviour under test is unchanged.
         */
        override fun getRecentHistoryUpToForOrigins(
            newestAllowed: Long,
            origins: Collection<PlaybackOrigin>,
            limit: Int
        ): Flow<List<HistoryItem>> {
            upToCalls += newestAllowed to limit
            return flowOf(
                rows.filter { it.listenedAt <= newestAllowed && it.sourceSection in origins }
                    .sortedByDescending { it.listenedAt }
                    .take(limit)
            )
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

    private class FakeDiscoveryRepository(
        private val featured: List<FeaturedArtist>
    ) : DiscoveryRepository {
        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = Result.success(featured)
        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> =
            Result.failure(IllegalStateException("not used"))
        override suspend fun getArtistImage(artistName: String): Result<String?> = Result.success(null)
        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())
        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> =
            Result.failure(IllegalStateException("not used"))
        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
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
}
