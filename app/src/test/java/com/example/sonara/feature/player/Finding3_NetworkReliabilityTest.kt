package com.example.sonara.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.domain.repository.DownloadRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.controller.PlaybackQueueEngine
import com.example.sonara.playback.controller.TransitionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * Adversarial Verification Test Suite for Finding 3:
 * Network Dependence / Loss-of-Network Playback Reliability in Sonara Android.
 *
 * Verifies Acceptance Criteria AC1 - AC10 across all 5 Categories:
 * - Category 1: Connectivity Transitions (AC1, AC4)
 * - Category 2: Playback Races & Offline Navigation (AC2, AC5, AC7, AC8)
 * - Category 3: Speculative Work & Background Isolation (AC3, AC9)
 * - Category 4: Recovery after Network Restoration (AC4, AC7)
 * - Category 5: State Integrity, Invariants & Connectivity Fuzzing (AC1-AC10)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Finding3_NetworkReliabilityTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val trackA = Track(id = "track_A", title = "Track A", artist = "Artist A", durationMs = 180000L)
    private val trackB = Track(id = "track_B", title = "Track B", artist = "Artist B", durationMs = 200000L)
    private val trackC = Track(id = "track_C", title = "Track C", artist = "Artist C", durationMs = 220000L)
    private val trackD = Track(id = "track_D", title = "Track D", artist = "Artist D", durationMs = 240000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 1: Connectivity Transitions (AC1, AC4)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat1_1_networkLostDuringActivePlayback_doesNotInterruptPlayback() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Start playback online
        resolver.isOnline = true
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        runCurrent()

        assertTrue("Playback must start when online", client.isPlaying)
        assertEquals("track_A", client.lastPlayedTrack?.id)
        assertNull("No error should be present", viewModel.uiState.value.errorMessage)

        // Simulate network drop during active playback
        resolver.isOnline = false
        runCurrent()

        // AC1: Already resolved / playing track must remain playing and unaffected
        assertTrue("Active playback must NOT be stopped by loss of network", client.isPlaying)
        assertEquals("track_A", viewModel.uiState.value.trackId)
        assertNull("UI must not show spurious error while active playback is running", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun cat1_2_flappingConnectivity_settlesCleanlyWithoutCrashOrDeadlock() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        resolver.isOnline = true
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        runCurrent()

        // Rapid flapping: 5 toggles
        for (i in 1..5) {
            resolver.isOnline = i % 2 == 0
            runCurrent()
        }

        // Active playback should still be playing
        assertTrue("Player should survive connectivity flapping", client.isPlaying)
        assertEquals("track_A", client.lastPlayedTrack?.id)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 2: Playback Races & Offline Navigation (AC2, AC5, AC7, AC8)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat2_1_skipToNextWhileOffline_failsCleanly_retainsQueueAndShowsError() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Start with Track A playing
        resolver.isOnline = true
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        runCurrent()

        // Cut network, then user skips to next
        resolver.isOnline = false
        viewModel.skipToNext()
        runCurrent()

        // AC2 & AC7: Logical queue moved to Track B, stream resolution failed cleanly, UI shows error
        assertEquals("Logical queue currentTrack must be track B", "track_B", queueEngine.currentTrack?.id)
        assertEquals("UI must target track B", "track_B", viewModel.uiState.value.trackId)
        assertFalse("Player must be paused on resolution failure", client.isPlaying)
        assertNotNull("Error message must be present reflecting network failure", viewModel.uiState.value.errorMessage)
        assertTrue("Error message must mention failure or network", viewModel.uiState.value.errorMessage!!.contains("Network", ignoreCase = true) || viewModel.uiState.value.errorMessage!!.contains("offline", ignoreCase = true) || viewModel.uiState.value.errorMessage!!.contains("Failed", ignoreCase = true))
    }

    @Test
    fun cat2_2_skipToPreviousWhileOffline_failsCleanlyWithoutCrash() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Play A, advance to B
        resolver.isOnline = true
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        runCurrent()
        viewModel.skipToNext()
        runCurrent()
        assertEquals("track_B", queueEngine.currentTrack?.id)

        // Simulate position < 3000ms (so skipToPrevious pops history back to track A)
        client.setSimulatedPosition(1500L)

        // Cut network and skip to previous
        resolver.isOnline = false
        viewModel.skipToPrevious()
        runCurrent()

        // Target should be track A, failed resolution gracefully handled
        assertEquals("track_A", queueEngine.currentTrack?.id)
        assertEquals("track_A", viewModel.uiState.value.trackId)
        assertFalse("Player paused", client.isPlaying)
        assertNotNull("Error message present", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun cat2_3_newerRequestAfterFailedOfflineRequest_cancelsOldAndCommitsNew() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Offline: try to play Track A -> fails
        resolver.isOnline = false
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        runCurrent()

        assertEquals("track_A", viewModel.uiState.value.trackId)
        assertNotNull("Track A failed", viewModel.uiState.value.errorMessage)

        // Network returns: user selects Track B instead
        resolver.isOnline = true
        viewModel.playTrack(trackB, listOf(trackA, trackB))
        runCurrent()

        // AC5: Track B must commit, clearing error and playing
        assertEquals("track_B", client.lastPlayedTrack?.id)
        assertEquals("track_B", viewModel.uiState.value.trackId)
        assertTrue("Track B is playing", client.isPlaying)
        assertNull("Error cleared for Track B", viewModel.uiState.value.errorMessage)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 3: Speculative Work & Background Isolation (AC3, AC9)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat3_1_lookaheadFailureWhileOffline_neverInterruptsActivePlayback() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Online: play Track A
        resolver.isOnline = true
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        runCurrent()

        assertTrue("Track A playing", client.isPlaying)

        // Network drops before lookahead debounce expires
        resolver.isOnline = false

        // Advance time to trigger lookahead (~1500ms)
        advanceTimeBy(1600)
        runCurrent()

        // AC3: Lookahead attempted and failed, BUT active playback for Track A MUST continue undisturbed
        assertTrue("Lookahead failure must NOT pause active playback", client.isPlaying)
        assertEquals("track_A", client.lastPlayedTrack?.id)
        assertEquals("track_A", viewModel.uiState.value.trackId)
        assertNull("Lookahead error must NOT leak to UI error state", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun cat3_2_recommendationFetchFailureWhileOffline_doesNotAffectAuthoritativePlayback() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()
        val discoveryRepo = FakeDiscoveryRepo().apply {
            shouldFail = true
        }

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = discoveryRepo,
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        resolver.isOnline = true
        viewModel.playTrack(trackA, listOf(trackA))
        runCurrent()

        // Recommendation prefetch fails in background
        advanceTimeBy(500)
        runCurrent()

        // AC9: Active playback continues uninterrupted
        assertTrue("Active playback must continue even if recommendations fail", client.isPlaying)
        assertEquals("track_A", viewModel.uiState.value.trackId)
        assertNull("Spurious recommendation error must not be shown", viewModel.uiState.value.errorMessage)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 4: Recovery after Network Restoration (AC4, AC7)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat4_1_offlineFailure_thenNetworkRestored_manualPlayRetriesAndRecovers() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Start offline, attempt to play Track A
        resolver.isOnline = false
        viewModel.playTrack(trackA, listOf(trackA, trackB))
        runCurrent()

        assertFalse("Cannot play offline", client.isPlaying)
        assertNotNull("Error message set", viewModel.uiState.value.errorMessage)
        assertEquals("track_A", viewModel.uiState.value.trackId)

        // Network restored
        resolver.isOnline = true

        // User taps Play to retry
        viewModel.play()
        runCurrent()

        // AC4: Seamlessly recovers, resolves stream, starts playing
        assertTrue("Playback resumes after manual retry with restored network", client.isPlaying)
        assertEquals("track_A", client.lastPlayedTrack?.id)
        assertNull("Error message cleared upon recovery", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun cat4_2_offlineSkipNext_thenNetworkRestored_skipNextSucceeds() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Play A online
        resolver.isOnline = true
        viewModel.playTrack(trackA, listOf(trackA, trackB, trackC))
        runCurrent()

        // Drop network, skip to B -> fails
        resolver.isOnline = false
        viewModel.skipToNext()
        runCurrent()

        assertEquals("track_B", queueEngine.currentTrack?.id)
        assertFalse("B paused", client.isPlaying)

        // Restore network, skip to C
        resolver.isOnline = true
        viewModel.skipToNext()
        runCurrent()

        // AC4: Next skip to C succeeds
        assertEquals("track_C", queueEngine.currentTrack?.id)
        assertEquals("track_C", client.lastPlayedTrack?.id)
        assertTrue("C is playing", client.isPlaying)
        assertNull("No error for C", viewModel.uiState.value.errorMessage)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CATEGORY 5: State Integrity, Invariants & Connectivity Fuzzing (AC1-AC10)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cat5_1_offlineDownload_playsImmediatelyWithoutNetwork() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()
        val fakeDownloadRepo = FakeDownloadRepository().apply {
            setDownloaded(trackA.id, "/data/user/0/com.example.sonara/files/track_A.m4a")
        }

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            downloadRepository = fakeDownloadRepo,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Complete offline mode: network is OFF
        resolver.isOnline = false

        viewModel.playTrack(trackA, listOf(trackA, trackB))
        runCurrent()

        // AC10: Downloaded track plays locally in 0ms without hitting StreamResolverPort
        assertTrue("Downloaded track must play while offline", client.isPlaying)
        assertEquals("track_A", client.lastPlayedTrack?.id)
        assertEquals("file:///data/user/0/com.example.sonara/files/track_A.m4a", client.lastPlayedStreamUrl)
        assertEquals("Zero network resolver calls made", 0, resolver.totalCalls)
        assertNull("Zero errors for downloaded track", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun cat5_2_propertyFuzz_randomConnectivityAndUserActions_maintainsInvariant() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = ControllableStreamResolver()
        val transitionManager = TransitionManager()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        val tracks = listOf(trackA, trackB, trackC, trackD)
        viewModel.playTrack(trackA, tracks)
        runCurrent()

        val rng = Random(42)

        // Run 25 fuzz iterations of random network toggles + user actions
        for (step in 1..25) {
            val netStatus = rng.nextBoolean()
            resolver.isOnline = netStatus

            val action = rng.nextInt(5)
            when (action) {
                0 -> viewModel.play()
                1 -> viewModel.pause()
                2 -> viewModel.skipToNext()
                3 -> viewModel.skipToPrevious()
                4 -> {
                    val randomTrack = tracks[rng.nextInt(tracks.size)]
                    viewModel.playTrack(randomTrack, tracks)
                }
            }
            advanceTimeBy(rng.nextLong(100, 2000))
            runCurrent()

            // Invariant 1: Queue must always have a valid current track
            assertNotNull("Queue currentTrack must never be null during fuzzing", queueEngine.currentTrack)

            // Invariant 2: uiState trackId must not be blank if currentTrack is present
            assertTrue("uiState trackId must be valid", viewModel.uiState.value.trackId.isNotBlank())

            // Invariant 3: If client is playing, errorMessage must be null
            if (client.isPlaying) {
                assertNull("Playing state cannot co-exist with error message", viewModel.uiState.value.errorMessage)
            }
        }

        // Final Recovery: Turn network online, trigger Play/Next, assert clean playing state
        resolver.isOnline = true
        viewModel.play()
        runCurrent()

        if (!client.isPlaying && viewModel.uiState.value.errorMessage != null) {
            // Tapping play on error retries
            viewModel.play()
            runCurrent()
        }

        assertTrue("After network restored, player must be operational", client.isPlaying || !viewModel.uiState.value.isBuffering)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test Fakes & Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private class ControllableStreamResolver : StreamResolverPort {
        var isOnline: Boolean = true
        var totalCalls: Int = 0

        override fun clearCache() {}

        override suspend fun resolveStream(
            trackId: String,
            quality: AudioQuality
        ): Result<StreamInfo> = resolveStream(trackId, quality, "", "", 0)

        override suspend fun resolveStream(
            trackId: String,
            quality: AudioQuality,
            title: String,
            artist: String,
            durationSeconds: Int
        ): Result<StreamInfo> {
            totalCalls++
            if (!isOnline) {
                return Result.failure(SonaraException.NetworkException("Device is offline (no network connection)"))
            }
            return Result.success(
                StreamInfo(
                    trackId = trackId,
                    streamUrl = "http://mock.stream/$trackId.webm"
                )
            )
        }
    }

    private data class PlayedTrackRecord(val track: Track, val streamUrl: String, val generation: Long)

    private class TrackingMediaControllerClient : MediaControllerClient(null) {
        val isPlaying: Boolean get() = _controllerState.value.isPlaying
        var lastPlayedTrack: Track? = null
        var lastPlayedStreamUrl: String? = null
        var lastPlayedGeneration: Long = 0L
        val playedTracksHistory = mutableListOf<PlayedTrackRecord>()
        var isPaused: Boolean = false

        fun setSimulatedPosition(pos: Long) {
            _controllerState.value = _controllerState.value.copy(currentPositionMs = pos)
        }

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
                isPlaying = true,
                durationMs = track.durationMs
            )
        }

        override fun playTrack(track: Track, streamUrl: String, generation: Long) {
            lastPlayedTrack = track
            lastPlayedStreamUrl = streamUrl
            lastPlayedGeneration = generation
            playedTracksHistory.add(PlayedTrackRecord(track, streamUrl, generation))
            isPaused = false
            setSimulatedCurrentTrack(track)
        }

        override fun pause() {
            isPaused = true
            _controllerState.value = _controllerState.value.copy(isPlaying = false)
        }

        override fun play() {
            isPaused = false
            _controllerState.value = _controllerState.value.copy(isPlaying = true)
        }

        override fun seekTo(positionMs: Long) {
            _controllerState.value = _controllerState.value.copy(currentPositionMs = positionMs)
        }
    }

    private class FakeDiscoveryRepo : DiscoveryRepository {
        var shouldFail: Boolean = false

        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = Result.success(emptyList())
        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> = Result.success(ArtistCatalog())
        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())
        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> = Result.failure(Exception("Not implemented"))
        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> {
            if (shouldFail) {
                return Result.failure(SonaraException.NetworkException("Network error fetching recommendations"))
            }
            return Result.success(emptyList())
        }
    }

    private class FakeDownloadRepository : DownloadRepository {
        private val downloads = mutableMapOf<String, String>()

        fun setDownloaded(trackId: String, filePath: String) {
            downloads[trackId] = filePath
        }

        override fun observeDownload(trackId: String): Flow<com.example.sonara.domain.model.DownloadInfo?> = flowOf(null)
        override fun observeAllDownloads(): Flow<List<com.example.sonara.domain.model.DownloadInfo>> = flowOf(emptyList())
        override fun observeCompletedDownloads(): Flow<List<com.example.sonara.domain.model.DownloadInfo>> = flowOf(emptyList())
        override fun observeDownloadsWithTrack(): Flow<List<com.example.sonara.domain.model.DownloadWithTrack>> = flowOf(emptyList())

        override suspend fun getDownload(trackId: String): com.example.sonara.domain.model.DownloadInfo? = null
        override suspend fun getDownloadedFileUri(trackId: String): String? = downloads[trackId]
        override suspend fun enqueueDownload(track: Track, quality: AudioQuality?): Result<Unit> = Result.success(Unit)
        override suspend fun retryDownload(trackId: String): Result<Unit> = Result.success(Unit)
        override suspend fun cancelDownload(trackId: String): Result<Unit> = Result.success(Unit)
        override suspend fun removeDownload(trackId: String): Result<Unit> = Result.success(Unit)
        override suspend fun removeAllDownloads(): Result<Unit> = Result.success(Unit)
    }
}
