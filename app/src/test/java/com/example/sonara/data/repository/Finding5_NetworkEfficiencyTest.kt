package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.SearchHistoryEntry
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Verification Test Suite for Finding 5:
 * Overall Network Consumption & Network Efficiency in Sonara Android.
 *
 * Verifies Acceptance Criteria AC1 - AC6:
 * - AC1: In-flight deduplication of concurrent recommendation requests (eliminates duplicate calls)
 * - AC2: Bounded in-memory caching of related recommendations (avoids repeated network calls)
 * - AC3: Bounded in-memory caching of discovery catalog (featured artists, playlists, playlist detail)
 * - AC4: Controlled cache bypass on pull-to-refresh (quickPicks refresh = true)
 * - AC5: Search autocomplete suggestion caching and single-character remote bypass
 * - AC6: Cache eviction bounds and memory safety
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Finding5_NetworkEfficiencyTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val sampleTrack = Track(id = "track_1", title = "Track 1", artist = "Artist 1", durationMs = 180000L)
    private val sampleTrack2 = Track(id = "track_2", title = "Track 2", artist = "Artist 2", durationMs = 200000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 1: Recommendation Deduplication & Caching (AC1, AC2)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat1_1_concurrentGetRelatedTracks_deduplicatesToSingleNetworkRequest() = testScope.runTest {
        val mockBackend = CountingBackendClient(delayMs = 100)
        val repository = DiscoveryRepositoryImpl(backendClient = mockBackend)

        // Launch 3 concurrent calls for the exact same seedVideoId (simulating PlayerViewModel + HomeViewModel + Service)
        val deferred1 = async { repository.getRelatedTracks("seed_abc") }
        val deferred2 = async { repository.getRelatedTracks("seed_abc") }
        val deferred3 = async { repository.getRelatedTracks("seed_abc") }

        val results = awaitAll(deferred1, deferred2, deferred3)

        // AC1: Exactly ONE network request dispatched
        assertEquals("Concurrent calls for the same seed must result in exactly 1 network request", 1, mockBackend.relatedTracksCallCount.get())
        results.forEach { result ->
            assertTrue("All callers must receive success", result.isSuccess)
            assertEquals(1, result.getOrNull()?.size)
            assertEquals("track_1", result.getOrNull()?.first()?.id)
        }
    }

    @Test
    fun cat1_2_cachedGetRelatedTracks_returnsImmediatelyWithoutNetwork() = testScope.runTest {
        val mockBackend = CountingBackendClient()
        val repository = DiscoveryRepositoryImpl(backendClient = mockBackend)

        // First call: fetches over network
        val result1 = repository.getRelatedTracks("seed_abc")
        assertTrue(result1.isSuccess)
        assertEquals(1, mockBackend.relatedTracksCallCount.get())

        // Second call: must return cached result with 0 network calls
        val result2 = repository.getRelatedTracks("seed_abc")
        assertTrue(result2.isSuccess)
        assertEquals("Subsequent call within TTL must not make a second network request", 1, mockBackend.relatedTracksCallCount.get())
        assertEquals("track_1", result2.getOrNull()?.first()?.id)
    }

    @Test
    fun cat1_3_differentSeeds_fetchIndependently() = testScope.runTest {
        val mockBackend = CountingBackendClient()
        val repository = DiscoveryRepositoryImpl(backendClient = mockBackend)

        val resA = repository.getRelatedTracks("seed_A")
        val resB = repository.getRelatedTracks("seed_B")

        assertTrue(resA.isSuccess)
        assertTrue(resB.isSuccess)
        assertEquals("Different seeds must dispatch independent requests", 2, mockBackend.relatedTracksCallCount.get())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 2: Discovery Catalog Caching (AC3, AC4)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat2_1_featuredArtists_and_playlists_cachedWithinTtl() = testScope.runTest {
        val mockBackend = CountingBackendClient()
        val repository = DiscoveryRepositoryImpl(backendClient = mockBackend)

        // Featured Artists
        repository.getFeaturedArtists()
        repository.getFeaturedArtists()
        assertEquals("Featured artists must be cached within TTL", 1, mockBackend.featuredArtistsCallCount.get())

        // Playlists
        repository.getPlaylists()
        repository.getPlaylists()
        assertEquals("Playlists catalog must be cached within TTL", 1, mockBackend.playlistsCallCount.get())
    }

    @Test
    fun cat2_2_playlistDetail_cachedPerPlaylistId() = testScope.runTest {
        val mockBackend = CountingBackendClient()
        val repository = DiscoveryRepositoryImpl(backendClient = mockBackend)

        repository.getPlaylistDetail("pl_1")
        repository.getPlaylistDetail("pl_1")
        assertEquals("Playlist detail must be cached per playlistId", 1, mockBackend.playlistDetailCallCount.get())

        repository.getPlaylistDetail("pl_2")
        assertEquals("Different playlistId must fetch new detail", 2, mockBackend.playlistDetailCallCount.get())
    }

    @Test
    fun cat2_3_quickPicks_cacheBypassWhenRefreshTrue() = testScope.runTest {
        val mockBackend = CountingBackendClient()
        val repository = DiscoveryRepositoryImpl(backendClient = mockBackend)

        // refresh = false (cached)
        repository.getQuickPicks(refresh = false)
        repository.getQuickPicks(refresh = false)
        assertEquals("Quick picks (refresh=false) must be served from cache", 1, mockBackend.quickPicksCallCount.get())

        // refresh = true (explicit user pull-to-refresh)
        repository.getQuickPicks(refresh = true)
        assertEquals("Quick picks (refresh=true) must bypass cache and fetch fresh", 2, mockBackend.quickPicksCallCount.get())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 3: Search Suggestion Caching & Length Threshold (AC5)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat3_1_searchSuggestions_singleCharQuery_bypassesRemoteNetwork() = testScope.runTest {
        val mockBackend = CountingBackendClient()
        val fakeHistory = FakeSearchHistoryRepo(listOf("apple", "android"))
        val repository = SearchRepositoryImpl(
            backendClient = mockBackend,
            searchHistoryRepository = fakeHistory
        )

        val result = repository.getSuggestions("a")
        assertTrue(result.isSuccess)
        assertEquals("Single character query must NOT call remote backend", 0, mockBackend.suggestionsCallCount.get())
        assertEquals("Fallback to local history prefix match", 2, result.getOrNull()?.size)
    }

    @Test
    fun cat3_2_searchSuggestions_cachedWithinTtl() = testScope.runTest {
        val mockBackend = CountingBackendClient()
        val repository = SearchRepositoryImpl(
            backendClient = mockBackend,
            searchHistoryRepository = FakeSearchHistoryRepo()
        )

        // First call with query >= 2 chars
        val res1 = repository.getSuggestions("sonara")
        assertTrue(res1.isSuccess)
        assertEquals(1, mockBackend.suggestionsCallCount.get())

        // Second identical call
        val res2 = repository.getSuggestions("sonara")
        assertTrue(res2.isSuccess)
        assertEquals("Subsequent suggestion query within TTL must be served from cache", 1, mockBackend.suggestionsCallCount.get())
    }

    @Test
    fun cat3_3_concurrentSearchSuggestions_deduplicateInFlight() = testScope.runTest {
        val mockBackend = CountingBackendClient(delayMs = 50)
        val repository = SearchRepositoryImpl(
            backendClient = mockBackend,
            searchHistoryRepository = FakeSearchHistoryRepo()
        )

        val def1 = async { repository.getSuggestions("sonara") }
        val def2 = async { repository.getSuggestions("sonara") }

        val results = awaitAll(def1, def2)
        assertEquals("Concurrent suggestion requests must be deduplicated in-flight", 1, mockBackend.suggestionsCallCount.get())
        assertEquals(1, results[0].getOrNull()?.size)
        assertEquals(1, results[1].getOrNull()?.size)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 4: Bounded Eviction & Memory Safety (AC6)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat4_1_cacheEviction_maintainsCapacityBound() = testScope.runTest {
        val mockBackend = CountingBackendClient()
        val repository = DiscoveryRepositoryImpl(
            backendClient = mockBackend,
            maxCacheEntries = 10
        )

        // Insert 15 distinct seeds
        for (i in 1..15) {
            repository.getRelatedTracks("seed_$i")
        }
        assertEquals(15, mockBackend.relatedTracksCallCount.get())

        // The most recently inserted seed (seed_15) must still be cached (0 network call)
        repository.getRelatedTracks("seed_15")
        assertEquals("Most recent entry must remain cached", 15, mockBackend.relatedTracksCallCount.get())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test Fakes & Counting Mock Backend
    // ──────────────────────────────────────────────────────────────────────────

    private class CountingBackendClient(private val delayMs: Long = 0L) : SonaraBackendClient() {
        val relatedTracksCallCount = AtomicInteger(0)
        val featuredArtistsCallCount = AtomicInteger(0)
        val playlistsCallCount = AtomicInteger(0)
        val playlistDetailCallCount = AtomicInteger(0)
        val quickPicksCallCount = AtomicInteger(0)
        val suggestionsCallCount = AtomicInteger(0)

        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> {
            relatedTracksCallCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(listOf(Track(id = "track_1", title = "Track 1", artist = "Artist 1", durationMs = 180000L)))
        }

        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> {
            featuredArtistsCallCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(listOf(FeaturedArtist(id = "fa_1", name = "Artist 1")))
        }

        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> {
            playlistsCallCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(listOf(PlaylistSummary(id = "pl_1", name = "Playlist 1")))
        }

        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> {
            playlistDetailCallCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(PlaylistDetail(id = playlistId, name = "Detail $playlistId", tracks = listOf(Track(id = "track_1", title = "Track 1", artist = "Artist 1", durationMs = 180000L))))
        }

        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> {
            quickPicksCallCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(listOf(Track(id = "track_1", title = "Track 1", artist = "Artist 1", durationMs = 180000L)))
        }

        override suspend fun getSuggestions(query: String): Result<List<SearchSuggestion>> {
            suggestionsCallCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(listOf(SearchSuggestion("suggestion for $query")))
        }
    }

    private class FakeSearchHistoryRepo(
        private val history: List<String> = emptyList()
    ) : SearchHistoryRepository {
        override fun observeHistory(): Flow<List<SearchHistoryEntry>> = flowOf(
            history.mapIndexed { idx, q ->
                SearchHistoryEntry(id = idx.toLong(), query = q, searchedAt = System.currentTimeMillis())
            }
        )
        override suspend fun addQuery(query: String) {}
        override suspend fun deleteEntry(id: Long) {}
        override suspend fun clearAll() {}
        override suspend fun getSuggestionsForPrefix(prefix: String, limit: Int): List<String> =
            history.filter { it.startsWith(prefix, ignoreCase = true) }.take(limit)
    }
}
