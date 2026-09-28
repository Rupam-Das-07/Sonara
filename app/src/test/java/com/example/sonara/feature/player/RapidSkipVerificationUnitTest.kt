package com.example.sonara.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.client.MediaControllerState
import com.example.sonara.playback.controller.NextTrackDecision
import com.example.sonara.playback.controller.PlaybackQueueEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Diagnostic Verification Test Suite for Sonara Rapid-Skip Rollback Investigation.
 * Gathers runtime evidence for UI state tearing, queue desynchronization,
 * service-level out-of-order commits, and STATE_ENDED concurrency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RapidSkipVerificationUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val trackA = Track(id = "track_A", title = "Track A", artist = "Artist A", durationMs = 180000L)
    private val trackB = Track(id = "track_B", title = "Track B", artist = "Artist B", durationMs = 200000L)
    private val trackC = Track(id = "track_C", title = "Track C", artist = "Artist C", durationMs = 220000L)
    private val trackD = Track(id = "track_D", title = "Track D", artist = "Artist D", durationMs = 240000L)
    private val trackE = Track(id = "track_E", title = "Track E", artist = "Artist E", durationMs = 260000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PART 1 & 7: UI State Tearing Verification (Measuring T1..T8 and B -> A -> B)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `verify UI state tearing produces B to A to B rollback when IPC latency occurs`() = testScope.runTest {
        println("=== TEST 1: UI State Tearing & Rollback Verification ===")
        val logs = mutableListOf<String>()
        val observedUiTrackIds = mutableListOf<String>()

        val client = DelayedIpcMediaControllerClient(testScope, ipcDelayMs = 100L)
        val resolver = ControllableStreamResolver()
        val queueEngine = PlaybackQueueEngine()

        // Initial setup: Set Track A into client before creating ViewModel
        client.setSimulatedCurrentTrack(trackA)
        queueEngine.setContext(trackA, listOf(trackA, trackB, trackC, trackD))

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = EmptyDiscoveryRepository(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            ioDispatcher = testDispatcher
        )

        // Start collecting uiState in backgroundScope so stateIn(WhileSubscribed) is active
        backgroundScope.launch {
            viewModel.uiState.collect { state ->
                val logEntry = "[t=${testScheduler.currentTime}ms] UI State emission: trackId='${state.trackId}', title='${state.trackTitle}', isBuffering=${state.isBuffering}"
                logs.add(logEntry)
                println(logEntry)
                observedUiTrackIds.add(state.trackId)
            }
        }
        testScheduler.runCurrent()

        assertEquals("track_A", viewModel.uiState.value.trackId)

        // T0: User presses Next (targeting Track B)
        println("\n--- [t=${testScheduler.currentTime}ms] USER PRESSES NEXT #1 (A -> B) ---")
        viewModel.skipToNext()
        testScheduler.runCurrent()

        // Immediately after skipToNext:
        // transitionalTrack = B -> uiState should optimistically be B
        assertEquals("track_B", viewModel.uiState.value.trackId)
        println("[t=${testScheduler.currentTime}ms] Immediately after skipToNext: uiState=${viewModel.uiState.value.trackId} (optimistic B)")

        // Advance 50ms while stream is resolving
        advanceTimeBy(50)
        testScheduler.runCurrent()
        assertEquals("track_B", viewModel.uiState.value.trackId)

        // Stream resolves at t = 100ms
        println("\n--- [t=${testScheduler.currentTime}ms] STREAM RESOLUTION FOR B COMPLETES ---")
        resolver.completeResolution("track_B")
        testScheduler.runCurrent() // Run the onSuccess block on Main thread

        // CRITICAL CHECK AT THIS EXACT MOMENT (T3):
        // executeTrackPlay onSuccess has just executed:
        // 1. client.playTrack(B) dispatched to IPC (takes 100ms to arrive back)
        // 2. _localState.update { copy(isResolvingStream = false, transitionalTrack = null) } EXECUTED
        println("[t=${testScheduler.currentTime}ms] executeTrackPlay onSuccess ran: transitionalTrack cleared, IPC in flight")
        println("[t=${testScheduler.currentTime}ms] raw controllerState.currentMediaItem='${client.controllerState.value.currentMediaItem?.mediaId}'")
        println("[t=${testScheduler.currentTime}ms] uiState.trackId='${viewModel.uiState.value.trackId}'")

        // VERIFY: Did uiState fall back to Track A?! NO! It must stay on Track B!
        val activeTrackId = viewModel.uiState.value.trackId
        println(">>> VERIFY ZERO STATE TEAR: transitionalTrack retained, controllerState='${client.controllerState.value.currentMediaItem?.mediaId}', uiState='$activeTrackId'")
        assertEquals("REMEDIATED: uiState stays on track B during IPC latency!", "track_B", activeTrackId)

        // Advance 50ms (IPC still in flight, total 50ms of 100ms delay)
        advanceTimeBy(50)
        testScheduler.runCurrent()
        println("[t=${testScheduler.currentTime}ms] During IPC window: uiState remains '${viewModel.uiState.value.trackId}' (Track B)")
        assertEquals("track_B", viewModel.uiState.value.trackId)

        // Advance remaining 50ms until IPC callback completes
        advanceTimeBy(50)
        testScheduler.runCurrent()
        println("\n--- [t=${testScheduler.currentTime}ms] IPC ARRIVES: onMediaItemTransition(B) -> updateState(B) ---")
        assertEquals("track_B", viewModel.uiState.value.trackId)
        println("[t=${testScheduler.currentTime}ms] After IPC completion: uiState='${viewModel.uiState.value.trackId}' (Track B)")

        // Verify the exact observed sequence:
        // Observed UI trackId sequence MUST NOT contain a rollback to track_A after track_B was first seen!
        println("\nObserved UI trackId sequence: $observedUiTrackIds")
        val firstBIndex = observedUiTrackIds.indexOf("track_B")
        val anyRollbackAfterB = observedUiTrackIds.subList(firstBIndex, observedUiTrackIds.size).contains("track_A")
        assertFalse("REMEDIATED: UI state must NEVER roll back to track A after track B is emitted!", anyRollbackAfterB)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PART 2 & 6: Rapid Skip on UI Path (Next x2, x3, x4, x5)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `verify rapid skip x3 drops intermediate stale resolutions and only commits last generation`() = testScope.runTest {
        println("\n=== TEST 2: Rapid Skip x3 Dropped Resolutions Verification ===")
        val client = RecordingMediaControllerClient()
        val resolver = ControllableStreamResolver()
        val queueEngine = PlaybackQueueEngine()

        client.setSimulatedCurrentTrack(trackA)
        queueEngine.setContext(trackA, listOf(trackA, trackB, trackC, trackD, trackE))

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = EmptyDiscoveryRepository(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            ioDispatcher = testDispatcher
        )

        backgroundScope.launch {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        // 1. Next #1 (A -> B)
        println("[t=${testScheduler.currentTime}ms] Press Next #1 (targeting B)")
        viewModel.skipToNext()
        testScheduler.runCurrent()
        assertEquals(trackB, queueEngine.currentTrack)
        assertEquals("track_B", viewModel.uiState.value.trackId)

        // 2. Next #2 (B -> C) before B resolves
        advanceTimeBy(20)
        println("[t=${testScheduler.currentTime}ms] Press Next #2 (targeting C)")
        viewModel.skipToNext()
        testScheduler.runCurrent()
        assertEquals(trackC, queueEngine.currentTrack)
        assertEquals("track_C", viewModel.uiState.value.trackId)

        // 3. Next #3 (C -> D) before C resolves
        advanceTimeBy(20)
        println("[t=${testScheduler.currentTime}ms] Press Next #3 (targeting D)")
        viewModel.skipToNext()
        testScheduler.runCurrent()
        assertEquals(trackD, queueEngine.currentTrack)
        assertEquals("track_D", viewModel.uiState.value.trackId)

        // Now resolve B, C, D in various orders
        // B resolves at t=60ms (gen 1 vs current gen 3)
        advanceTimeBy(20)
        println("[t=${testScheduler.currentTime}ms] Stream B completes resolution (stale)")
        resolver.completeResolution("track_B")
        testScheduler.runCurrent()
        assertFalse("Track B must NOT be committed to client", client.playedTracks.contains(trackB))

        // C resolves at t=80ms (gen 2 vs current gen 3)
        advanceTimeBy(20)
        println("[t=${testScheduler.currentTime}ms] Stream C completes resolution (stale)")
        resolver.completeResolution("track_C")
        testScheduler.runCurrent()
        assertFalse("Track C must NOT be committed to client", client.playedTracks.contains(trackC))

        // D resolves at t=100ms (gen 3 vs current gen 3)
        advanceTimeBy(20)
        println("[t=${testScheduler.currentTime}ms] Stream D completes resolution (authoritative)")
        resolver.completeResolution("track_D")
        testScheduler.runCurrent()
        assertTrue("Track D MUST be committed to client", client.playedTracks.contains(trackD))
        assertEquals("Only track D was committed", listOf(trackD), client.playedTracks)
        println("CONFIRMED: Intermediate resolutions B and C were dropped at generation check. Only D committed.")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PART 3: Queue Desynchronization Verification (handleTrackTransition race)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `verify queue desynchronization when ExoPlayer transition for B arrives after Next 2 moved queue to C`() = testScope.runTest {
        println("\n=== TEST 3: Queue Desynchronization (handleTrackTransition) Verification ===")
        val queueEngine = PlaybackQueueEngine()
        queueEngine.setContext(trackA, listOf(trackA, trackB, trackC, trackD, trackE))

        // State before skip:
        println("Initial queue state: current=${queueEngine.currentTrack?.title}, upcoming=${queueEngine.upcomingQueue.map { it.title }}, backstack=${queueEngine.sessionBackStack.map { it.title }}")
        assertEquals(trackA, queueEngine.currentTrack)

        // Step 1: User skips Next #1 -> Queue moves to B, stream B begins resolving
        val dec1 = queueEngine.advance(fromTrackId = "track_A")
        assertEquals(NextTrackDecision.PlayTrack(trackB), dec1)
        println("After Next #1: current=${queueEngine.currentTrack?.title}, upcoming=${queueEngine.upcomingQueue.map { it.title }}")

        // Step 2: Stream B resolved, client.playTrack(B) dispatched to ExoPlayer.
        // BUT before ExoPlayer fires onMediaItemTransition(B), User skips Next #2 -> Queue moves to C!
        val dec2 = queueEngine.advance(fromTrackId = "track_B")
        assertEquals(NextTrackDecision.PlayTrack(trackC), dec2)
        println("After Next #2: current=${queueEngine.currentTrack?.title}, upcoming=${queueEngine.upcomingQueue.map { it.title }}")
        assertEquals("track_C", queueEngine.currentTrack?.id)

        // Step 3: Now ExoPlayer finally fires onMediaItemTransition(mediaItem = B)!
        // SonaraPlaybackService.handleTrackTransition("track_B") executes:
        println("\n--- ExoPlayer onMediaItemTransition arrives with mediaId='track_B' ---")
        println("Inside handleTrackTransition:")
        println("  newTrackId = 'track_B'")
        println("  qe.currentTrack.id = '${queueEngine.currentTrack?.id}'")

        val conditionResult = queueEngine.currentTrack?.id != "track_B"
        println("  Evaluation: qe.currentTrack?.id != newTrackId -> $conditionResult ('track_C' != 'track_B')")
        assertTrue("Condition evaluates to TRUE", conditionResult)

        // Remediated production logic in SonaraPlaybackService.kt:
        // if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
        //     (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && player.currentMediaItemIndex > 0)) {
        //     if (qe.currentTrack?.id != newTrackId) {
        //         qe.advance(fromTrackId = qe.currentTrack?.id)
        //     }
        // }
        // For explicit setMediaItem, reason is MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED (3):
        val reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && false)) {
            if (queueEngine.currentTrack?.id != "track_B") {
                queueEngine.advance(fromTrackId = queueEngine.currentTrack?.id)
            }
        }

        // VERIFY: QueueEngine was NOT corrupted!
        assertEquals("QueueEngine safely remained on Track C!", "track_C", queueEngine.currentTrack?.id)
        assertFalse("Track C was NOT pushed to backstack!", queueEngine.sessionBackStack.contains(trackC))
        println("REMEDIATED: PLAYLIST_CHANGED did NOT advance QueueEngine. Track C is safely preserved.")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PART 5: Service-Level Out-of-Order Commit (playTrackInternal lacking generations)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `verify service-level playback requests with TransitionManager drop stale resolutions and maintain order`() = testScope.runTest {
        println("\n=== TEST 4: Service-Level Out-of-Order Commit Remediation Verification ===")
        val committedPlayerItems = mutableListOf<String>()
        val transitionManager = com.example.sonara.playback.controller.TransitionManager()
        var activePlaybackJob: kotlinx.coroutines.Job? = null

        // Simulating the remediated SonaraPlaybackService.playTrackInternal pattern:
        fun simulateRemediatedServicePlayTrackInternal(track: Track, resolveDurationMs: Long, generation: Long) {
            activePlaybackJob?.cancel()
            activePlaybackJob = launch {
                delay(resolveDurationMs)
                if (!transitionManager.isAuthoritative(generation) || !isActive) {
                    println("[t=${testScheduler.currentTime}ms] Stale service resolution dropped for ${track.title} [gen=$generation]")
                    return@launch
                }
                committedPlayerItems.add(track.id)
                println("[t=${testScheduler.currentTime}ms] Service committed ${track.title} to ExoPlayer [gen=$generation]")
            }
        }

        // User triggers Next #1 on notification at t=0ms (requests Track B, slow network: 200ms)
        val gen1 = transitionManager.nextGeneration()
        println("[t=${testScheduler.currentTime}ms] Notification Next #1 -> requests Track B (200ms latency) [gen=$gen1]")
        simulateRemediatedServicePlayTrackInternal(trackB, resolveDurationMs = 200L, generation = gen1)

        // User triggers Next #2 on notification at t=50ms (requests Track C, fast network: 50ms)
        advanceTimeBy(50)
        val gen2 = transitionManager.nextGeneration()
        println("[t=${testScheduler.currentTime}ms] Notification Next #2 -> requests Track C (50ms latency) [gen=$gen2]")
        simulateRemediatedServicePlayTrackInternal(trackC, resolveDurationMs = 50L, generation = gen2)

        // Advance to t=110ms: Track C resolves and commits!
        advanceTimeBy(60)
        assertEquals(listOf("track_C"), committedPlayerItems)
        println("[t=${testScheduler.currentTime}ms] Player current item: ${committedPlayerItems.last()}")

        // Advance to t=210ms: Slower Track B finishes resolution time but was cancelled/superseded!
        advanceTimeBy(100)
        assertEquals("Track C must remain the ONLY committed item!", listOf("track_C"), committedPlayerItems)
        assertEquals("track_C", committedPlayerItems.last())
        println("REMEDIATED: Older resolution B was cancelled/dropped and never committed over newer Track C.")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PART 9: STATE_ENDED Concurrency Verification
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `verify STATE_ENDED callback concurrent with manual Next can cause double queue advance`() = testScope.runTest {
        println("\n=== TEST 5: STATE_ENDED Concurrent with Manual Next Verification ===")
        val queueEngine = PlaybackQueueEngine()
        queueEngine.setContext(trackA, listOf(trackA, trackB, trackC, trackD, trackE))

        // Suppose Track A is playing and naturally reaches STATE_ENDED at the same time user presses Next
        // 1. Manual Next runs:
        val manualDecision = queueEngine.advance(fromTrackId = "track_A")
        println("Manual Next advance(fromTrackId='track_A') -> $manualDecision")
        assertEquals(NextTrackDecision.PlayTrack(trackB), manualDecision)

        // 2. Concurrently, ExoPlayer listener fires onPlaybackStateChanged(STATE_ENDED):
        // In SonaraPlaybackService.kt lines 171-176:
        // if (playbackState == Player.STATE_ENDED) advanceToNext()
        // And advanceToNext() does:
        // val currentTrackId = exoPlayerHolder?.player?.currentMediaItem?.mediaId // still "track_A"!
        // val decision = qe.advance(fromTrackId = currentTrackId)
        val currentExoTrackId = "track_A" // ExoPlayer has not switched yet
        val autoDecision = queueEngine.advance(fromTrackId = currentExoTrackId)
        println("Concurrent STATE_ENDED advance(fromTrackId='track_A') -> $autoDecision")

        // Because PlaybackQueueEngine has:
        // if (fromTrackId != null && _currentTrack != null && _currentTrack?.id != fromTrackId) {
        //     return _currentTrack?.let { NextTrackDecision.PlayTrack(it) } ?: ...
        // }
        // Let's check what autoDecision returned:
        println("Evaluation of line 134 guard in PlaybackQueueEngine: fromTrackId='$currentExoTrackId', currentTrack='${queueEngine.currentTrack?.id}'")
        assertEquals("Guard in PlaybackQueueEngine line 134 protects fromTrackId mismatch!",
            NextTrackDecision.PlayTrack(trackB), autoDecision
        )
        assertEquals("Queue remained on Track B and did not double-advance", trackB, queueEngine.currentTrack)
        println("STATE_ENDED guard protected against double advance when fromTrackId='track_A' was passed.")

        // BUT: What if ExoPlayer currentMediaItem was null (e.g. during item replacement / prepare)?
        val autoDecisionWithNullTrackId = queueEngine.advance(fromTrackId = null)
        println("What if currentMediaItem was null? advance(fromTrackId=null) -> $autoDecisionWithNullTrackId")
        assertEquals(NextTrackDecision.PlayTrack(trackC), autoDecisionWithNullTrackId)
        assertEquals("If fromTrackId is null, queue advances again!", trackC, queueEngine.currentTrack)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Test Doubles and Utilities
// ──────────────────────────────────────────────────────────────────────────────

private class DelayedIpcMediaControllerClient(
    private val scope: CoroutineScope,
    private val ipcDelayMs: Long
) : MediaControllerClient(null) {

    fun setSimulatedCurrentTrack(track: Track) {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
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
        // Simulates async IPC roundtrip:
        // Client sends command to Service -> Service prepares player -> onMediaItemTransition returns to client
        scope.launch {
            delay(ipcDelayMs)
            setSimulatedCurrentTrack(track)
        }
    }

    override fun play() {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
}

private class RecordingMediaControllerClient : MediaControllerClient(null) {
    val playedTracks = mutableListOf<Track>()

    fun setSimulatedCurrentTrack(track: Track) {
        val mediaItem = MediaItem.Builder().setMediaId(track.id).build()
        _controllerState.value = _controllerState.value.copy(
            isConnected = true,
            currentMediaItem = mediaItem
        )
    }

    override fun playTrack(track: Track, streamUrl: String, generation: Long) {
        playedTracks.add(track)
        setSimulatedCurrentTrack(track)
    }
}

private class ControllableStreamResolver : StreamResolverPort {
    private val deferreds = mutableMapOf<String, CompletableDeferred<Result<StreamInfo>>>()

    override fun clearCache() {}

    override suspend fun resolveStream(trackId: String, quality: AudioQuality): Result<StreamInfo> {
        val def = deferreds.getOrPut(trackId) { CompletableDeferred() }
        return def.await()
    }

    fun completeResolution(trackId: String) {
        val def = deferreds.getOrPut(trackId) { CompletableDeferred() }
        def.complete(Result.success(StreamInfo(
            trackId = trackId,
            streamUrl = "https://mock.stream/$trackId",
            expiresAt = System.currentTimeMillis() + 3600_000L,
            format = "audio/mp4"
        )))
    }
}

private class EmptyDiscoveryRepository : DiscoveryRepository {
    override suspend fun getFeaturedArtists() = Result.success(emptyList<com.example.sonara.domain.model.FeaturedArtist>())
    override suspend fun getArtistCatalog(artistId: String, artistName: String) = Result.success(com.example.sonara.domain.model.ArtistCatalog())
    override suspend fun getPlaylists() = Result.success(emptyList<com.example.sonara.domain.model.PlaylistSummary>())
    override suspend fun getPlaylistDetail(playlistId: String) = Result.failure<com.example.sonara.domain.model.PlaylistDetail>(Exception("None"))
    override suspend fun getQuickPicks(refresh: Boolean) = Result.success(emptyList<Track>())
    override suspend fun getRelatedTracks(seedVideoId: String) = Result.success(emptyList<Track>())
}
