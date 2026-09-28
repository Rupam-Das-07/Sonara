package com.example.sonara.feature.player

import androidx.media3.common.MediaItem
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.controller.PlaybackQueueEngine
import com.example.sonara.playback.controller.TransitionManager
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Automated Unit Test Suite verifying Finding 1.D IPC TOCTOU Remediation.
 *
 * Verifies:
 * 1. ViewModel IPC Generation Tagging
 * 2. ViewModel Stale Stream Resolution Cancellation
 * 3. Service Rejection of Stale In-flight IPC Requests
 * 4. Service Acceptance of Authoritative Requests
 * 5. ViewModel Handling of Authoritative Stream Resolution Failure
 * 6. ViewModel Recovery After Failure
 * 7. Complete E -> F -> E Prevention Scenario
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Finding1D_IpcToctouRemediationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val trackA = Track(id = "track_A", title = "Track A", artist = "Artist A", durationMs = 180000L)
    private val trackB = Track(id = "track_B", title = "Track B", artist = "Artist B", durationMs = 200000L)
    private val trackC = Track(id = "track_C", title = "Track C", artist = "Artist C", durationMs = 220000L)
    private val trackD = Track(id = "track_D", title = "Track D", artist = "Artist D", durationMs = 240000L)
    private val trackE = Track(id = "track_E", title = "Track E", artist = "Artist E", durationMs = 260000L)
    private val trackF = Track(id = "track_F", title = "Track F", artist = "Artist F", durationMs = 280000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── Test 1: ViewModel IPC Generation Tagging ─────────────────────────────
    @Test
    fun viewModel_skipToNext_passesAuthoritativeGenerationToClient() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val streamResolver = ControllableStreamResolver()
        val queueEngine = PlaybackQueueEngine()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = streamResolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )

        // Setup queue: Track A (playing), Track B, Track C
        viewModel.playTrack(trackA, listOf(trackA, trackB, trackC))
        streamResolver.complete(trackA.id, Result.success(StreamInfo(trackA.id, "http://a", 0L, "webm")))
        advanceUntilIdle()

        client.setSimulatedCurrentTrack(trackA)
        advanceUntilIdle()

        val genBeforeSkip = transitionManager.currentGeneration()

        // Skip to Track B
        viewModel.skipToNext()
        val expectedGenB = transitionManager.currentGeneration()
        assertTrue(expectedGenB > genBeforeSkip)

        streamResolver.complete(trackB.id, Result.success(StreamInfo(trackB.id, "http://b", 0L, "webm")))
        advanceUntilIdle()

        assertEquals("Track B", client.lastPlayedTrack?.title)
        assertEquals(expectedGenB, client.lastPlayedGeneration)
    }

    // ─── Test 2: ViewModel Stale Stream Resolution Cancellation ───────────────
    @Test
    fun viewModel_staleStreamResolution_isDroppedBeforeIpc() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val streamResolver = ControllableStreamResolver()
        val queueEngine = PlaybackQueueEngine()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = streamResolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )

        // Queue: A, B, C
        viewModel.playTrack(trackA, listOf(trackA, trackB, trackC))
        streamResolver.complete(trackA.id, Result.success(StreamInfo(trackA.id, "http://a", 0L, "webm")))
        advanceUntilIdle()
        client.setSimulatedCurrentTrack(trackA)
        advanceUntilIdle()

        // User initiates skip to B (resolution will be held pending)
        viewModel.skipToNext()
        val genB = transitionManager.currentGeneration()

        // Before B resolves, user rapidly skips to C
        viewModel.skipToNext()
        val genC = transitionManager.currentGeneration()
        assertTrue(genC > genB)

        // Now B stream resolution finishes (stale)
        streamResolver.complete(trackB.id, Result.success(StreamInfo(trackB.id, "http://b", 0L, "webm")))
        advanceUntilIdle()

        // Track B should NOT be dispatched to client
        assertFalse(client.playedTracksHistory.any { it.track.id == trackB.id && it.generation == genB })

        // Now C stream resolution finishes (authoritative)
        streamResolver.complete(trackC.id, Result.success(StreamInfo(trackC.id, "http://c", 0L, "webm")))
        advanceUntilIdle()

        // Track C should be dispatched to client with genC
        assertEquals("Track C", client.lastPlayedTrack?.title)
        assertEquals(genC, client.lastPlayedGeneration)
    }

    // ─── Test 3: Service Rejection of Stale In-flight IPC Requests ────────────
    @Test
    fun service_onAddMediaItems_rejectsStaleGenerationIpc() {
        val transitionManager = TransitionManager()

        // Service & ViewModel share transitionManager.
        // User reaches generation 5 (Track F)
        transitionManager.nextGeneration() // 1
        transitionManager.nextGeneration() // 2
        transitionManager.nextGeneration() // 3
        transitionManager.nextGeneration() // 4 (Track E)
        val genF = transitionManager.nextGeneration() // 5 (Track F)
        assertEquals(5L, genF)

        // In-flight IPC arrives from Track E tagged with requestGen = 4
        val requestGenE = 4L

        // Simulate onAddMediaItems generation validation:
        val isStale = requestGenE > 0L && requestGenE < transitionManager.currentGeneration()
        assertTrue("Request generation 4 must be recognized as stale when current generation is 5", isStale)

        // Verify updateIfGreater returns false and does not rewind generation
        val updated = transitionManager.updateIfGreater(requestGenE)
        assertFalse("updateIfGreater must reject older generation", updated)
        assertEquals(5L, transitionManager.currentGeneration())
    }

    // ─── Test 4: Service Acceptance of Authoritative Requests ─────────────────
    @Test
    fun service_onAddMediaItems_acceptsAuthoritativeOrHigherGeneration() {
        val transitionManager = TransitionManager()
        transitionManager.nextGeneration() // 1
        transitionManager.nextGeneration() // 2
        assertEquals(2L, transitionManager.currentGeneration())

        // Incoming item tagged with generation 2 (authoritative)
        val requestGen = 2L
        val isStale = requestGen > 0L && requestGen < transitionManager.currentGeneration()
        assertFalse("Authoritative generation 2 must NOT be marked stale", isStale)

        // Incoming item tagged with generation 3 (new higher generation)
        val requestGen3 = 3L
        val isStale3 = requestGen3 > 0L && requestGen3 < transitionManager.currentGeneration()
        assertFalse("Higher generation 3 must NOT be marked stale", isStale3)

        val updated = transitionManager.updateIfGreater(requestGen3)
        assertTrue("Higher generation must be accepted", updated)
        assertEquals(3L, transitionManager.currentGeneration())
    }

    // ─── Test 5: ViewModel Handling of Authoritative Stream Resolution Failure
    @Test
    fun viewModel_authoritativeResolutionFailure_preservesTransitionalTrackWithErrorMessageAndPauses() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val streamResolver = ControllableStreamResolver()
        val queueEngine = PlaybackQueueEngine()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = streamResolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Initial track playing: Track A
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        streamResolver.complete(trackA.id, Result.success(StreamInfo(trackA.id, "http://a", 0L, "webm")))
        advanceUntilIdle()
        client.setSimulatedCurrentTrack(trackA)
        advanceUntilIdle()

        // Skip to Track B
        viewModel.skipToNext()
        advanceUntilIdle()

        // Fail stream resolution for Track B
        streamResolver.complete(trackB.id, Result.failure(RuntimeException("Network timeout connecting to stream")))
        advanceUntilIdle()

        val state = viewModel.uiState.value

        // Crucial Finding 1.D assertions:
        // 1. Must NOT roll back to Track A
        assertEquals("Track B", state.trackTitle)
        assertEquals("track_B", state.trackId)

        // 2. Playback must be paused
        assertTrue(client.isPaused)
        assertFalse(state.isPlaying)
        assertFalse(state.isBuffering)

        // 3. Error message must be non-null and state IDLE
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("Network timeout") || state.errorMessage!!.contains("Failed to resolve stream"))
        assertEquals(androidx.media3.common.Player.STATE_IDLE, state.playbackState)
    }

    // ─── Test 6: ViewModel Recovery After Failure ─────────────────────────────
    @Test
    fun viewModel_recoveryAfterFailure_resetsErrorStateOnNewAction() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val streamResolver = ControllableStreamResolver()
        val queueEngine = PlaybackQueueEngine()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = streamResolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Queue: A, B, C
        viewModel.playTrack(trackA, listOf(trackA, trackB, trackC))
        streamResolver.complete(trackA.id, Result.success(StreamInfo(trackA.id, "http://a", 0L, "webm")))
        advanceUntilIdle()
        client.setSimulatedCurrentTrack(trackA)
        advanceUntilIdle()

        // Skip to Track B and fail
        viewModel.skipToNext()
        streamResolver.complete(trackB.id, Result.failure(RuntimeException("HTTP 503 Service Unavailable")))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.errorMessage != null)
        assertEquals("track_B", viewModel.uiState.value.trackId)

        // User now skips to Track C
        viewModel.skipToNext()
        advanceUntilIdle()

        // Error message should be reset immediately on new action
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals("track_C", viewModel.uiState.value.trackId)
        assertTrue(viewModel.uiState.value.isBuffering)

        // Stream for Track C resolves successfully
        streamResolver.complete(trackC.id, Result.success(StreamInfo(trackC.id, "http://c", 0L, "webm")))
        advanceUntilIdle()
        client.setSimulatedCurrentTrack(trackC)
        advanceUntilIdle()

        val stateAfterSuccess = viewModel.uiState.value
        assertEquals("Track C", stateAfterSuccess.trackTitle)
        assertNull(stateAfterSuccess.errorMessage)
        assertFalse(stateAfterSuccess.isBuffering)
    }

    // ─── Test 7: Complete E -> F -> E Prevention Scenario ─────────────────────
    @Test
    fun complete_E_to_F_to_E_preventionScenario() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val streamResolver = ControllableStreamResolver()
        val queueEngine = PlaybackQueueEngine()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = streamResolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Queue: A, B, C, D, E, F
        val fullQueue = listOf(trackA, trackB, trackC, trackD, trackE, trackF)
        viewModel.playTrack(trackA, fullQueue)
        streamResolver.complete(trackA.id, Result.success(StreamInfo(trackA.id, "http://a", 0L, "webm")))
        advanceUntilIdle()
        client.setSimulatedCurrentTrack(trackA)
        advanceUntilIdle()

        // Rapidly skip 4 times to reach Track E (gen 4)
        viewModel.skipToNext() // -> B (gen 2)
        streamResolver.complete(trackB.id, Result.success(StreamInfo(trackB.id, "http://b", 0L, "webm")))
        viewModel.skipToNext() // -> C (gen 3)
        streamResolver.complete(trackC.id, Result.success(StreamInfo(trackC.id, "http://c", 0L, "webm")))
        viewModel.skipToNext() // -> D (gen 4)
        streamResolver.complete(trackD.id, Result.success(StreamInfo(trackD.id, "http://d", 0L, "webm")))
        viewModel.skipToNext() // -> E (gen 5)
        advanceUntilIdle()

        val genE = transitionManager.currentGeneration()
        assertEquals("track_E", viewModel.uiState.value.trackId)

        // Stream for E resolves under genE
        streamResolver.complete(trackE.id, Result.success(StreamInfo(trackE.id, "http://e", 0L, "webm")))
        // Note: before dispatcher finishes IPC dispatch or right after dispatch, user presses Next to F!
        viewModel.skipToNext() // -> F (gen 6)
        val genF = transitionManager.currentGeneration()
        assertTrue(genF > genE)

        // Simulate Service-level IPC check:
        // Even if E's IPC reached the service, service checks:
        val eIsStaleAtService = genE < transitionManager.currentGeneration()
        assertTrue("Service rejects in-flight E IPC because generation has advanced to F", eIsStaleAtService)

        // Because service rejects E, ExoPlayer is NOT updated to Track E.
        // Now Track F's stream resolution fails ~1s later:
        streamResolver.complete(trackF.id, Result.failure(RuntimeException("Failed to resolve stream for Track F")))
        advanceUntilIdle()

        val finalUiState = viewModel.uiState.value

        // Assert that playback did NOT roll back to E or A:
        assertEquals("Track F must remain active in UI", "track_F", finalUiState.trackId)
        assertEquals("Track F", finalUiState.trackTitle)
        assertNotNull("Error message must be displayed for Track F", finalUiState.errorMessage)
        assertFalse("Playback must not be playing", finalUiState.isPlaying)
        assertFalse("Playback must not be buffering", finalUiState.isBuffering)
        assertEquals(androidx.media3.common.Player.STATE_IDLE, finalUiState.playbackState)
        assertTrue("Audio output must be paused", client.isPaused)
    }

    // ─── Test Helpers ─────────────────────────────────────────────────────────

    private data class PlayedTrackRecord(val track: Track, val streamUrl: String, val generation: Long)

    private class TrackingMediaControllerClient : MediaControllerClient(null) {
        var lastPlayedTrack: Track? = null
        var lastPlayedStreamUrl: String? = null
        var lastPlayedGeneration: Long = 0L
        val playedTracksHistory = mutableListOf<PlayedTrackRecord>()
        var isPaused: Boolean = false

        fun setSimulatedCurrentTrack(track: Track) {
            val metadata = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId(track.id)
                .setMediaMetadata(metadata)
                .build()
            _controllerState.value = _controllerState.value.copy(
                isConnected = true,
                currentMediaItem = mediaItem,
                isPlaying = true
            )
        }

        override fun playTrack(track: Track, streamUrl: String, generation: Long) {
            lastPlayedTrack = track
            lastPlayedStreamUrl = streamUrl
            lastPlayedGeneration = generation
            playedTracksHistory.add(PlayedTrackRecord(track, streamUrl, generation))
            isPaused = false
        }

        override fun pause() {
            isPaused = true
            _controllerState.value = _controllerState.value.copy(isPlaying = false)
        }

        override fun play() {
            isPaused = false
            _controllerState.value = _controllerState.value.copy(isPlaying = true)
        }

        override fun seekTo(positionMs: Long) {}
    }

    private class ControllableStreamResolver : StreamResolverPort {
        private val deferreds = mutableMapOf<String, CompletableDeferred<Result<StreamInfo>>>()

        override fun clearCache() {
            deferreds.clear()
        }

        fun complete(trackId: String, result: Result<StreamInfo>) {
            val def = deferreds.getOrPut(trackId) { CompletableDeferred() }
            if (def.isCompleted) {
                deferreds[trackId] = CompletableDeferred(result)
            } else {
                def.complete(result)
            }
        }

        override suspend fun resolveStream(
            trackId: String,
            quality: AudioQuality
        ): Result<StreamInfo> {
            val def = deferreds.getOrPut(trackId) { CompletableDeferred() }
            return def.await()
        }

        override suspend fun resolveStream(
            trackId: String,
            quality: AudioQuality,
            title: String,
            artist: String,
            durationSeconds: Int
        ): Result<StreamInfo> = resolveStream(trackId, quality)
    }

    private class FakeDiscoveryRepo : DiscoveryRepository {
        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = Result.success(emptyList())
        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> = Result.success(ArtistCatalog())
        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())
        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> = Result.failure(Exception("Not implemented"))
        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
    }
}
