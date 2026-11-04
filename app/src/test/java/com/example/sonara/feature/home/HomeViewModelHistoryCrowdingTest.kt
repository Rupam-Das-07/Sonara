package com.example.sonara.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import kotlinx.coroutines.test.TestScope
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
 * I-5 — provenance crowding in the personalization sample.
 *
 * I-2 bounded the sample by TIME, but nothing bounded it by PROVENANCE. The personalization query
 * is `WHERE listenedAt <= :newestAllowed ORDER BY listenedAt DESC LIMIT :limit`, and eligibility is
 * enforced only later, in memory. A device that autoplays radio heavily writes enough
 * RADIO_AUTOPLAY rows into the newest slots to fill all
 * [ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT] of them, so eligible Songs-search listening that
 * is genuinely older never reaches the fold at all and the artist silently disappears from the
 * shelf — even though the policy would have accepted that listening.
 *
 * These tests drive the REAL [HomeViewModel] through the production
 * [HistoryRepository.getRecentHistoryUpToForOrigins] contract. [SqlLikeHistoryRepository] reproduces
 * the DAO query's exact clause order — WHERE, then `ORDER BY listenedAt DESC`, then `LIMIT` — so
 * "no provenance predicate in the query" is modelled faithfully instead of being asserted against a
 * hand-built list.
 *
 * Deterministic: explicit timestamps, no sleeps, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelHistoryCrowdingTest {

    private val dispatcher = StandardTestDispatcher()
    private val limit = ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT
    private val hourMs = 60L * 60L * 1000L

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

    private fun row(
        artist: String,
        listenedAt: Long,
        index: Int,
        origin: PlaybackOrigin = PlaybackOrigin.SEARCH_SONGS
    ) = HistoryItem(
        id = index.toLong(),
        track = Track(id = "vid-$artist-$index", title = "Song $index", artist = artist),
        listenedAt = listenedAt,
        completed = true,
        sourceSection = origin
    )

    /**
     * A heavy radio-autoplay device: MORE ineligible rows than the whole sample holds, and DENSER
     * (1s apart) than the user's own listening, so every one of the newest 500 slots is ineligible.
     */
    private fun denseAutoplay(now: Long) = (0 until limit + 100).map {
        row("Radio Artist", now - it * 1_000L, 1_000 + it, PlaybackOrigin.RADIO_AUTOPLAY)
    }

    /** Six genuine listens spread over three days, as real deliberate listening looks. */
    private fun spreadEligible(now: Long) = (0 until 6).map {
        row("Hans Zimmer", now - (it + 1) * 12 * hourMs, 500_000 + it, PlaybackOrigin.SEARCH_SONGS)
    }

    /** Drives the real ViewModel exactly as [HomeViewModelProvenanceTest] does. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun TestScope.shelfFor(
        historyRepository: HistoryRepository
    ): List<FeaturedArtist> {
        val store = ViewModelStore()
        val vm = store.homeViewModel(historyRepository)
        try {
            // advanceUntilIdle() only drains THIS scheduler, i.e. the Main-dispatcher `init`
            // coroutines. It cannot observe personalization, which hops to Dispatchers.Default — a
            // real thread pool the test scheduler does not own. The state read below waits for the
            // module to reach Ready, whichever dispatcher produced it, with no sleeping or fixed
            // delay. The ViewModel is then cleared in finally so its long-lived history observer
            // cannot outlive the test and dispatch onto Main after resetMain().
            advanceUntilIdle()
            val ready = vm.uiState.first { it.featuredArtists is HomeModule.Ready }
            return (ready.featuredArtists as HomeModule.Ready<List<FeaturedArtist>>).value
        } finally {
            store.clear()
            advanceUntilIdle()
        }
    }

    private fun ViewModelStore.homeViewModel(historyRepository: HistoryRepository): HomeViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(
                    discoveryRepository = FakeDiscoveryRepository(roster),
                    historyRepository = historyRepository,
                    libraryRepository = FakeLibraryRepository(),
                    settingsRepository = FakeSettingsRepository()
                ) as T
            }
        }
        return ViewModelProvider(this, factory)[HomeViewModel::class.java]
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `more than a full sample of ineligible autoplay rows cannot crowd out older songs-search history`() {
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            val history = SqlLikeHistoryRepository(spreadEligible(now) + denseAutoplay(now))

            val shelf = shelfFor(history).map { it.name }

            assertTrue(
                "eligible Songs-search listening must reach the fold despite crowding: $shelf",
                "Hans Zimmer" in shelf
            )
            assertFalse(
                "ineligible autoplay listening must never be admitted: $shelf",
                "Radio Artist" in shelf
            )
        }
    }

    @Test
    fun `the defect is real - the same history loses the artist when the sample is taken before provenance`() {
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            val history = SqlLikeHistoryRepository(spreadEligible(now) + denseAutoplay(now))

            // The OLD call path: bound the sample by time, take the limit, and only then filter
            // provenance in memory.
            val blindSample = history.timeBoundedSample(now)

            assertEquals("the time-bounded sample is a full 500 rows", limit, blindSample.size)
            assertEquals(
                "every slot is an ineligible autoplay row, so no eligible listening survives",
                0,
                blindSample.count { ArtistAffinityCalculator.isEligibleForPersonalization(it) }
            )

            // The same history, through the production path, still knows the artist.
            val shelf = shelfFor(history).map { it.name }
            assertTrue("the repaired path must keep the artist: $shelf", "Hans Zimmer" in shelf)
        }
    }

    @Test
    fun `the personalization path asks the query for the authoritative eligible origins`() {
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            val history = SqlLikeHistoryRepository(
                (0 until 6).map { row("Hans Zimmer", now - it * 60_000L, it) }
            )

            shelfFor(history)

            val (requestedNewestAllowed, requestedLimit) = history.upToCalls.single()
            assertEquals("the I-2 sample limit must be unchanged", limit, requestedLimit)
            assertTrue(
                "the query bound must still be now + FUTURE_TOLERANCE_MS, I-2's inclusive edge",
                requestedNewestAllowed >= now + ArtistAffinityCalculator.FUTURE_TOLERANCE_MS
            )
            assertEquals(
                "the repository must receive the one authoritative provenance policy, not a copy",
                ArtistAffinityCalculator.ELIGIBLE_ORIGINS,
                history.requestedOrigins.single()
            )
            assertEquals(
                "the authoritative policy has not been widened",
                setOf(PlaybackOrigin.SEARCH_SONGS, PlaybackOrigin.LIKED_SONGS),
                ArtistAffinityCalculator.ELIGIBLE_ORIGINS
            )
        }
    }

    @Test
    fun `every eligible origin keeps reaching the fold while unknown provenance stays out`() {
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            val history = SqlLikeHistoryRepository(
                ArrayList<HistoryItem>().apply {
                    addAll((0 until 6).map { row("Search Artist", now - (it + 1) * 3 * hourMs, it, PlaybackOrigin.SEARCH_SONGS) })
                    addAll((0 until 6).map { row("Liked Artist", now - (it + 1) * 4 * hourMs - 1_000L, 100 + it, PlaybackOrigin.LIKED_SONGS) })
                    addAll((0 until 6).map { row("Radio Artist", now - (it + 1) * 5 * hourMs - 2_000L, 300 + it, PlaybackOrigin.RADIO_AUTOPLAY) })
                    // Dense, newest and ineligible: SQL now filters these out of the sample, and
                    // they must still not become eligible merely because the query touched them.
                    addAll((0 until limit + 100).map { row("Unknown Artist", now - it * 1_000L, 200 + it, PlaybackOrigin.LEGACY_UNKNOWN) })
                }
            )

            val shelf = shelfFor(history).map { it.name }

            assertTrue("SEARCH_SONGS must remain eligible: $shelf", "Search Artist" in shelf)
            assertTrue("LIKED_SONGS must remain eligible: $shelf", "Liked Artist" in shelf)
            assertFalse("unknown provenance must stay ineligible: $shelf", "Unknown Artist" in shelf)
            assertFalse("autoplay provenance must stay ineligible: $shelf", "Radio Artist" in shelf)
        }
    }

    @Test
    fun `ordering stays newest-first and the limit applies only after provenance filtering`() {
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            // Ineligible rows are DENSER than the eligible ones, so a limit-before-filter query
            // spends every slot on them and yields no eligible row at all. The provenance-aware
            // query instead returns the 500 newest ELIGIBLE rows.
            val rows = ArrayList<HistoryItem>()
            for (i in 0 until limit + 100) {
                rows.add(row("Radio Artist", now - i * 1_000L, 1_000 + i, PlaybackOrigin.RADIO_AUTOPLAY))
            }
            for (i in 0 until limit + 100) {
                rows.add(row("Hans Zimmer", now - (i + 1) * 12 * hourMs, 500_000 + i, PlaybackOrigin.SEARCH_SONGS))
            }
            val history = SqlLikeHistoryRepository(rows)

            assertTrue(
                "the old path returns no eligible row at all",
                history.timeBoundedSample(now).none {
                    ArtistAffinityCalculator.isEligibleForPersonalization(it)
                }
            )

            val sample = history.sample(now)

            assertEquals("the limit must be filled from ELIGIBLE rows", limit, sample.size)
            assertTrue(
                "every returned row must be eligible: ${sample.map { it.sourceSection }.distinct()}",
                sample.all { ArtistAffinityCalculator.isEligibleForPersonalization(it) }
            )
            assertEquals(
                "eligible rows must stay newest-first",
                sample.sortedByDescending { it.listenedAt },
                sample
            )
        }
    }

    // ------------------------------------------------------------------ fakes

    /**
     * Models the DAO query's real clause order: WHERE predicates, then `ORDER BY listenedAt DESC`,
     * then `LIMIT`. The provenance predicate belongs in the WHERE clause, i.e. BEFORE the limit,
     * which is exactly the behaviour under test.
     */
    private class SqlLikeHistoryRepository(private val rows: List<HistoryItem>) : HistoryRepository {
        val upToCalls = CopyOnWriteArrayList<Pair<Long, Int>>()
        val requestedOrigins = CopyOnWriteArrayList<Collection<PlaybackOrigin>>()

        /**
         * The production call path: I-2's bound, the authoritative origins, the production limit.
         */
        fun sample(nowMs: Long): List<HistoryItem> = kotlinx.coroutines.runBlocking {
            getRecentHistoryUpToForOrigins(
                newestAllowed = nowMs + ArtistAffinityCalculator.FUTURE_TOLERANCE_MS,
                origins = ArtistAffinityCalculator.ELIGIBLE_ORIGINS,
                limit = ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT
            ).first()
        }

        /**
         * The PRE-I-5 call path: bounded by time only, limited, and only then filtered by
         * provenance in memory. Used to demonstrate the defect rather than assert around it.
         */
        fun timeBoundedSample(nowMs: Long): List<HistoryItem> = kotlinx.coroutines.runBlocking {
            getRecentHistoryUpTo(
                newestAllowed = nowMs + ArtistAffinityCalculator.FUTURE_TOLERANCE_MS,
                limit = ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT
            ).first()
        }

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> =
            flowOf(rows.sortedByDescending { it.listenedAt }.take(limit))

        override fun getRecentHistoryUpTo(newestAllowed: Long, limit: Int): Flow<List<HistoryItem>> =
            flowOf(
                rows.filter { it.listenedAt <= newestAllowed }
                    .sortedByDescending { it.listenedAt }
                    .take(limit)
            )

        override fun getRecentHistoryUpToForOrigins(
            newestAllowed: Long,
            origins: Collection<PlaybackOrigin>,
            limit: Int
        ): Flow<List<HistoryItem>> {
            upToCalls += newestAllowed to limit
            requestedOrigins += origins
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
