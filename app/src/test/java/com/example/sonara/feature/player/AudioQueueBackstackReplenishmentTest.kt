package com.example.sonara.feature.player

import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.controller.NextTrackDecision
import com.example.sonara.playback.controller.PlaybackQueueEngine
import com.example.sonara.playback.controller.PreviousTrackDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class AudioQueueBackstackReplenishmentTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeClient: FakeMediaControllerClient
    private lateinit var fakeDiscoveryRepo: FakeDiscoveryRepository
    private lateinit var fakeStreamResolver: FakeStreamResolver

    private val trackA = Track(id = "track_a", title = "Track A", artist = "Artist One")
    private val trackB = Track(id = "track_b", title = "Track B", artist = "Artist One")
    private val rec1 = Track(id = "rec_1", title = "Recommended 1", artist = "Artist Two")
    private val rec2 = Track(id = "rec_2", title = "Recommended 2", artist = "Artist Three")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeClient = FakeMediaControllerClient()
        fakeDiscoveryRepo = FakeDiscoveryRepository()
        fakeStreamResolver = FakeStreamResolver()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): PlayerViewModel {
        return PlayerViewModel(
            client = fakeClient,
            discoveryRepository = fakeDiscoveryRepo,
            streamResolverPort = fakeStreamResolver,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `play Track A then Track B, exhaust queue, recommendation fails, Previous returns to Track A`() = testScope.runTest {
        val viewModel = createViewModel()

        // 1. Play Track A with context [Track A, Track B]
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        advanceUntilIdle()
        assertEquals(trackA, viewModel.queueEngine.currentTrack)
        assertTrue(viewModel.queueEngine.sessionBackStack.isEmpty())

        // 2. User / auto-advance skips to Track B
        viewModel.skipToNext()
        advanceUntilIdle()
        assertEquals(trackB, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(trackA), viewModel.queueEngine.sessionBackStack)
        assertTrue(viewModel.queueEngine.upcomingQueue.isEmpty())

        // 3. User attempts Next when upcoming queue is empty and recommendations FAIL
        fakeDiscoveryRepo.relatedResult = Result.failure(Exception("HTTP 503 Backend Service Unavailable"))
        viewModel.skipToNext()
        advanceUntilIdle()

        // Current track remains Track B, playback is paused gracefully
        assertEquals(trackB, viewModel.queueEngine.currentTrack)
        assertTrue("Playback should pause on recommendation failure", fakeClient.isPaused)

        // CRITICAL: Track B must NOT be committed to backstack when recommendations fail!
        assertEquals("Backstack must only contain Track A, not Track B", listOf(trackA), viewModel.queueEngine.sessionBackStack)
        assertEquals("peekPreviousTrack must return Track A", trackA, viewModel.peekPreviousTrack())

        // 4. User presses Previous at 1200ms (<= 3000ms threshold)
        fakeClient.setTestPosition(1200L)
        viewModel.skipToPrevious()
        advanceUntilIdle()

        // VERIFY: Must return to Track A, NOT replay Track B!
        assertEquals("Previous must navigate back to Track A", trackA, viewModel.queueEngine.currentTrack)
        assertEquals("Last played track must be Track A", trackA, fakeClient.lastPlayedTrack)
        assertEquals("Track B must be placed back on upcoming queue", listOf(trackB), viewModel.queueEngine.upcomingQueue)
        assertTrue("Backstack should now be empty", viewModel.queueEngine.sessionBackStack.isEmpty())
    }

    @Test
    fun `successful recommendation replenishment records outgoing track exactly once`() = testScope.runTest {
        val viewModel = createViewModel()

        // Start on Track A, advance to Track B
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(trackB, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(trackA), viewModel.queueEngine.sessionBackStack)

        // Recommendations succeed with rec1 and rec2
        fakeDiscoveryRepo.relatedResult = Result.success(listOf(rec1, rec2))
        viewModel.skipToNext()
        advanceUntilIdle()

        // Advanced to rec1
        assertEquals(rec1, viewModel.queueEngine.currentTrack)
        assertEquals(rec1, fakeClient.lastPlayedTrack)

        // VERIFY: Outgoing track Track B is recorded in backstack EXACTLY ONCE
        assertEquals(listOf(trackA, trackB), viewModel.queueEngine.sessionBackStack)
        assertEquals(
            "Track B must appear exactly once in backstack",
            1,
            viewModel.queueEngine.sessionBackStack.count { it.id == trackB.id }
        )
        assertEquals("peekPreviousTrack must return Track B", trackB, viewModel.peekPreviousTrack())

        // Pressing Previous at 1000ms returns to Track B
        fakeClient.setTestPosition(1000L)
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(trackB, viewModel.queueEngine.currentTrack)
        assertEquals(trackB, fakeClient.lastPlayedTrack)
        assertEquals(listOf(trackA), viewModel.queueEngine.sessionBackStack)
    }

    @Test
    fun `empty recommendation result does not corrupt history`() = testScope.runTest {
        val viewModel = createViewModel()

        viewModel.playTrack(trackA, listOf(trackA, trackB))
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(trackB, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(trackA), viewModel.queueEngine.sessionBackStack)

        // Recommendations return empty candidate list
        fakeDiscoveryRepo.relatedResult = Result.success(emptyList())
        viewModel.skipToNext()
        advanceUntilIdle()

        assertTrue("Playback pauses when no candidates available", fakeClient.isPaused)
        assertEquals(trackB, viewModel.queueEngine.currentTrack)

        // History must NOT be corrupted: Track B is not added to backstack
        assertEquals(listOf(trackA), viewModel.queueEngine.sessionBackStack)
        assertEquals(trackA, viewModel.peekPreviousTrack())

        // Pressing Previous returns cleanly to Track A
        fakeClient.setTestPosition(1000L)
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(trackA, viewModel.queueEngine.currentTrack)
        assertEquals(trackA, fakeClient.lastPlayedTrack)
    }

    @Test
    fun `duplicate candidates filtered to empty do not corrupt history`() = testScope.runTest {
        val viewModel = createViewModel()

        viewModel.playTrack(trackA, listOf(trackA, trackB))
        advanceUntilIdle()
        viewModel.skipToNext()
        advanceUntilIdle()

        // Return candidates that match Track A and Track B (will be filtered out by multi-set deduplication)
        val duplicateA = Track(id = "track_a", title = "Track A", artist = "Artist One")
        val duplicateB = Track(id = "track_b", title = "Track B", artist = "Artist One")
        fakeDiscoveryRepo.relatedResult = Result.success(listOf(duplicateA, duplicateB))

        viewModel.skipToNext()
        advanceUntilIdle()

        assertTrue(fakeClient.isPaused)
        assertEquals(trackB, viewModel.queueEngine.currentTrack)
        assertEquals("Backstack must not be corrupted by deduplicated empty recommendations", listOf(trackA), viewModel.queueEngine.sessionBackStack)

        fakeClient.setTestPosition(800L)
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(trackA, viewModel.queueEngine.currentTrack)
        assertEquals(trackA, fakeClient.lastPlayedTrack)
    }

    @Test
    fun `direct PlaybackQueueEngine advance does not commit current track to backstack on NeedRecommendations`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(trackA, listOf(trackA, trackB), origin = PlaybackOrigin.SEARCH_SONGS)

        // Step 1: Advance to Track B
        val d1 = engine.advance()
        assertTrue(d1 is NextTrackDecision.PlayTrack)
        assertEquals(trackB, (d1 as NextTrackDecision.PlayTrack).track)
        assertEquals(listOf(trackA), engine.sessionBackStack)

        // Step 2: Advance when upcoming queue is empty -> NeedRecommendations
        val d2 = engine.advance()
        assertTrue(d2 is NextTrackDecision.NeedRecommendations)
        assertEquals("track_b", (d2 as NextTrackDecision.NeedRecommendations).seedTrackId)

        // VERIFY: Track B is NOT committed to backstack prematurely
        assertEquals("Backstack must still contain only Track A", listOf(trackA), engine.sessionBackStack)
        assertEquals("Current track remains Track B", trackB, engine.currentTrack)

        // Step 3: Previous at <= 3000ms returns to Track A
        val prevDecision = engine.previous(currentPositionMs = 1500L)
        assertTrue(prevDecision is PreviousTrackDecision.PlayTrack)
        assertEquals(trackA, (prevDecision as PreviousTrackDecision.PlayTrack).track)
        assertEquals(trackA, engine.currentTrack)
        assertEquals(listOf(trackB), engine.upcomingQueue)
        assertTrue(engine.sessionBackStack.isEmpty())
    }

    private class FakeMediaControllerClient : MediaControllerClient(null) {
        var lastPlayedTrack: Track? = null
        var lastPlayedStreamUrl: String? = null
        var lastSeekPosition: Long? = null
        var isPaused: Boolean = false

        fun setTestPosition(posMs: Long) {
            _controllerState.value = _controllerState.value.copy(
                isConnected = true,
                currentPositionMs = posMs
            )
        }

        override fun playTrack(track: Track, streamUrl: String, generation: Long) {
            lastPlayedTrack = track
            lastPlayedStreamUrl = streamUrl
            isPaused = false
        }

        override fun seekTo(positionMs: Long) {
            lastSeekPosition = positionMs
        }

        override fun pause() {
            isPaused = true
        }

        override fun play() {
            isPaused = false
        }
    }

    private class FakeStreamResolver : StreamResolverPort {
        override fun clearCache() {}
        override suspend fun resolveStream(trackId: String, quality: com.example.sonara.domain.model.AudioQuality): Result<StreamInfo> {
            return Result.success(
                StreamInfo(
                    trackId = trackId,
                    streamUrl = "https://stream/$trackId",
                    expiresAt = System.currentTimeMillis() + 3600_000L,
                    format = "audio/webm"
                )
            )
        }
    }

    private class FakeDiscoveryRepository : DiscoveryRepository {
        var relatedResult: Result<List<Track>> = Result.success(emptyList())

        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = Result.success(emptyList())
        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> = Result.success(ArtistCatalog())
        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())
        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> = Result.failure(Exception("Not implemented"))
        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = relatedResult
    }
}
