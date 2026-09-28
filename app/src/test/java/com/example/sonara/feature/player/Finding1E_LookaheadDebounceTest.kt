package com.example.sonara.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Adversarial Verification Test Suite for Finding 1.E:
 * Stream resolution rate-limit exhaustion and unbounded lookahead dispatch during rapid skipping.
 *
 * Verifies Acceptance Criteria AC1 - AC8 across all 5 Critical Paths:
 * - Path 1 [happy]: Settled track lookahead (AC2, AC3, AC7)
 * - Path 2 [boundary]: Rapid consecutive skips (AC1, AC2, AC7, AC8)
 * - Path 3 [error]: HTTP 429 retry throttling (AC6)
 * - Path 4 [state]: Unknown duration display (AC5)
 * - Path 5 [fuzz/property]: N skips (5..30) with varying delay (<1500ms) maintaining 0 lookahead invariant
 * - Boundary: Job cancellation on navigation before debounce expires (AC7, AC8)
 * - Boundary: Rapid toggle shuffle consolidation (AC8)
 * - Regression: Finding 1.D (E->F->E rollback prevention) remains intact (AC4)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Finding1E_LookaheadDebounceTest {

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

    // ──────────────────────────────────────────────────────────────────────────
    // PATH 1 [happy]: Settled Track Lookahead (AC2, AC3, AC7)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun happy_settledTrack_dispatchesPrimaryImmediately_andDebouncesLookahead() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = AuditingStreamResolver(
            currentTimeProvider = { testScheduler.currentTime },
            currentTargetProvider = { queueEngine.currentTrack?.id }
        )
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

        val queue = listOf(trackA, trackB, trackC)

        // 1. Play Track A: Primary resolution must dispatch immediately at t = 0 (AC2)
        viewModel.playTrack(trackA, queue)
        runCurrent()

        assertEquals("Primary resolution for track A must be dispatched immediately (AC2)", 1, resolver.primaryCalls().size)
        assertEquals("track_A", resolver.primaryCalls().first().trackId)
        assertEquals("Zero lookahead requests must be dispatched at t = 0 (AC1, AC3)", 0, resolver.lookaheadCalls().size)

        // 2. Advance time by 1000ms (< 1500ms debounce threshold)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("Lookahead must NOT be dispatched before ~1500ms settlement window expires", 0, resolver.lookaheadCalls().size)

        // 3. Advance time by another 600ms (total elapsed 1600ms >= 1500ms settlement threshold)
        advanceTimeBy(600)
        runCurrent()

        // AC3: Lookahead pre-resolution operates normally when playback settles on a track (debounced by ~1500ms)
        assertEquals("Lookahead pre-resolution for track B must be dispatched after >= 1500ms settlement", 1, resolver.lookaheadCalls().size)
        val lookahead = resolver.lookaheadCalls().first()
        assertEquals("track_B", lookahead.trackId)
        assertTrue("Lookahead dispatch timestamp must be >= 1500ms", lookahead.timestampMs >= 1500L)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PATH 2 [boundary]: Rapid Consecutive Skips (AC1, AC2, AC7, AC8)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun boundary_rapid15SkipsWith200msInterval_dispatchesZeroLookaheadsDuringSkipping() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = AuditingStreamResolver(
            currentTimeProvider = { testScheduler.currentTime },
            currentTargetProvider = { queueEngine.currentTrack?.id }
        )
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

        val tracks = (0..20).map { i ->
            Track(id = "rapid_track_$i", title = "Rapid Track $i", artist = "Artist", durationMs = 180000L)
        }

        // Start playback at track 0
        viewModel.playTrack(tracks[0], tracks)
        runCurrent()
        assertEquals(1, resolver.primaryCalls().size)
        assertEquals("rapid_track_0", resolver.primaryCalls().first().trackId)
        assertEquals(0, resolver.lookaheadCalls().size)

        // Perform 15 consecutive skips with 200ms interval (200ms << 1500ms debounce)
        for (i in 1..15) {
            advanceTimeBy(200)
            viewModel.skipToNext()
            runCurrent()

            // Invariant AC1: During rapid skipping, intermediate lookahead is suppressed/cancelled before dispatch
            assertEquals(
                "Zero lookahead requests must be dispatched during rapid skipping at skip $i",
                0,
                resolver.lookaheadCalls().size
            )
        }

        // After all 15 skips completed (total elapsed: 3000ms):
        assertEquals("Zero lookahead requests dispatched across all 15 rapid skips", 0, resolver.lookaheadCalls().size)
        assertEquals("Primary resolution must be initiated immediately for each of the 16 visited tracks", 16, resolver.primaryCalls().size)

        // Settle playback on track 15 for >= 1500ms
        advanceTimeBy(1600)
        runCurrent()

        // AC3: Exactly one lookahead call dispatched for track 16 after settling
        assertEquals("Exactly ONE lookahead call dispatched after track settlement", 1, resolver.lookaheadCalls().size)
        assertEquals("Lookahead must target track 16", tracks[16].id, resolver.lookaheadCalls().first().trackId)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BOUNDARY: Job Cancellation on Navigation Before Debounce (AC7, AC8)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun boundary_skipBeforeDebounceExpires_cancelsPreviousLookaheadJob() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = AuditingStreamResolver(
            currentTimeProvider = { testScheduler.currentTime },
            currentTargetProvider = { queueEngine.currentTrack?.id }
        )
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

        val queue = listOf(trackA, trackB, trackC, trackD)
        viewModel.playTrack(trackA, queue)
        runCurrent()

        // Advance 1200ms (lookahead timer in-flight with 300ms remaining)
        advanceTimeBy(1200)
        runCurrent()
        assertEquals(0, resolver.lookaheadCalls().size)

        // User skips to Track B at t = 1200ms: AC7 specifies previous lookahead coroutine is cancelled
        viewModel.skipToNext()
        runCurrent()

        // Advance another 500ms (t = 1700ms from start, but only 500ms since skip to B)
        advanceTimeBy(500)
        runCurrent()

        // If Track A's lookahead was not cancelled, it would have fired at t = 1500ms.
        // It must NOT have fired because it was cancelled upon navigation.
        assertEquals(
            "Cancelled lookahead job for Track B must not fire after skip to Track B",
            0,
            resolver.lookaheadCalls().size
        )

        // Advance another 1100ms (t = 2800ms from start, 1600ms since skip to Track B >= 1500ms)
        advanceTimeBy(1100)
        runCurrent()

        // Now Track B's lookahead for Track C should fire
        assertEquals(1, resolver.lookaheadCalls().size)
        assertEquals("track_C", resolver.lookaheadCalls().first().trackId)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BOUNDARY: Rapid Toggle Shuffle Consolidation (AC8)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun boundary_rapidToggleShuffle_consolidatesLookaheadRequests() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = AuditingStreamResolver(
            currentTimeProvider = { testScheduler.currentTime },
            currentTargetProvider = { queueEngine.currentTrack?.id }
        )
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

        val queue = listOf(trackA, trackB, trackC, trackD)
        viewModel.playTrack(trackA, queue)
        runCurrent()

        // Let initial lookahead resolve after settling
        advanceTimeBy(1600)
        runCurrent()
        assertEquals(1, resolver.lookaheadCalls().size)

        // Rapidly toggle shuffle 4 times in quick succession (within 100ms)
        repeat(4) {
            advanceTimeBy(25)
            viewModel.toggleShuffle()
            runCurrent()
        }

        // AC8: Rapid calls must be debounced and consolidated, avoiding redundant concurrent requests
        assertEquals(
            "Rapid toggleShuffle calls must not immediately dispatch redundant concurrent lookahead requests",
            1,
            resolver.lookaheadCalls().size
        )

        // Settle for >= 1500ms
        advanceTimeBy(1600)
        runCurrent()

        // Exactly one new consolidated lookahead call should be dispatched
        assertEquals(
            "Exactly one consolidated lookahead call should be dispatched after shuffle settles",
            2,
            resolver.lookaheadCalls().size
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PATH 3 [error]: HTTP 429 Retry Throttling (AC6)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun error_http429RateLimit_enforcesCooldownOnManualPlayRetries() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = AuditingStreamResolver(
            currentTimeProvider = { testScheduler.currentTime },
            currentTargetProvider = { queueEngine.currentTrack?.id }
        )
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

        // Configure resolver to fail with HTTP 429 Too Many Requests
        resolver.setStaticResult(trackA.id, Result.failure(RuntimeException("HTTP 429 Too Many Requests: Rate limit exceeded")))

        viewModel.playTrack(trackA, listOf(trackA, trackB))
        advanceUntilIdle()

        // Verify initial resolution failed with HTTP 429 error
        val stateAfterFailure = viewModel.uiState.value
        assertNotNull("Error message must be set on HTTP 429 failure", stateAfterFailure.errorMessage)
        assertTrue("Error message must indicate 429 rate limit", stateAfterFailure.errorMessage!!.contains("429"))
        assertEquals("Initial attempt must have called resolver exactly once", 1, resolver.callsFor(trackA.id).size)

        // User aggressively taps Play button 5 times within 500ms
        repeat(5) {
            advanceTimeBy(100)
            viewModel.play()
            runCurrent()
        }
        advanceUntilIdle()

        // Invariant AC6: Manual Play button must NOT enter an infinite tight retry loop. Cooldown enforced.
        val callsDuringCooldown = resolver.callsFor(trackA.id).size
        assertTrue(
            "Rapid manual Play retries during HTTP 429 cooldown must be throttled. Expected < 5 calls, but was $callsDuringCooldown",
            callsDuringCooldown < 5
        )

        // Advance virtual time past the cooldown duration (5000ms)
        advanceTimeBy(5000)
        runCurrent()

        // Now that cooldown has elapsed, configure recovery and tap Play again
        resolver.setStaticResult(trackA.id, Result.success(StreamInfo(trackA.id, "http://recovered/trackA.webm", 0L, "audio/webm")))
        viewModel.play()
        advanceUntilIdle()

        assertTrue(
            "After cooldown window elapses, manual Play retry must be allowed to dispatch resolution",
            resolver.callsFor(trackA.id).size > callsDuringCooldown
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PATH 4 [state]: Unknown Duration Display (AC5)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun state_zeroDurationTrackAndStagedRecommendations_displaysValidUiWithoutCrashOrNaN() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = AuditingStreamResolver(
            currentTimeProvider = { testScheduler.currentTime },
            currentTargetProvider = { queueEngine.currentTrack?.id }
        )
        val transitionManager = TransitionManager()
        val fakeDiscoveryRepo = FakeDiscoveryRepo()

        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = fakeDiscoveryRepo,
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        // Track with duration = 0 / durationMs = 0L
        val zeroTrack = Track(id = "track_zero", title = "Zero Duration Track", artist = "Artist Zero", durationMs = 0L)
        val stagedZeroTrack = Track(id = "rec_zero", title = "Staged Recommendation Zero", artist = "Artist Rec", durationMs = 0L)

        // Configure discovery repository to return the staged zero-duration track when prefetching
        fakeDiscoveryRepo.relatedTracksResult = Result.success(listOf(stagedZeroTrack))

        // 1. Play zero-duration track directly
        viewModel.playTrack(zeroTrack, listOf(zeroTrack))
        advanceUntilIdle()

        // Resolver must receive durationSeconds = 0 without arithmetic exception
        val initialCalls = resolver.callsFor(zeroTrack.id)
        assertEquals(1, initialCalls.size)
        assertEquals(0, initialCalls.first().durationSeconds)

        // Verify UI state for zero-duration track
        val uiStateZero = viewModel.uiState.value
        assertEquals("Zero Duration Track", uiStateZero.trackTitle)
        assertEquals("track_zero", uiStateZero.trackId)
        assertEquals(0L, uiStateZero.durationMs)
        assertNull(uiStateZero.errorMessage)
        assertFalse("Progress must not be NaN for zero duration", uiStateZero.progress.isNaN())
        assertFalse("Progress must not be Infinite for zero duration", uiStateZero.progress.isInfinite())
        assertEquals(0f, uiStateZero.progress, 0.0001f)

        // 2. Let playback settle so lookahead triggers for the staged recommendation
        advanceTimeBy(1600)
        runCurrent()

        // Lookahead must resolve stagedZeroTrack with durationSeconds = 0 without crash
        val lookaheadCalls = resolver.callsFor(stagedZeroTrack.id)
        assertEquals(1, lookaheadCalls.size)
        assertTrue(lookaheadCalls.first().isLookahead)
        assertEquals(0, lookaheadCalls.first().durationSeconds)

        // 3. Skip to the staged recommendation track
        viewModel.skipToNext()
        advanceUntilIdle()

        val uiStateStaged = viewModel.uiState.value
        assertEquals("Staged Recommendation Zero", uiStateStaged.trackTitle)
        assertEquals("rec_zero", uiStateStaged.trackId)
        assertEquals(0L, uiStateStaged.durationMs)
        assertNull(uiStateStaged.errorMessage)
        assertFalse("Staged track progress must not be NaN", uiStateStaged.progress.isNaN())
        assertFalse("Staged track progress must not be Infinite", uiStateStaged.progress.isInfinite())
        assertEquals(0f, uiStateStaged.progress, 0.0001f)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PATH 5 [fuzz/property]: N Skips (5..30) with Varying Delay (<1500ms)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun property_fuzzVaryingSkipsAndDelays_maintainsZeroLookaheadInvariantUntilSettled() = testScope.runTest {
        val testConfigs = listOf(
            Pair(5, listOf(200L, 500L, 100L, 800L, 300L)),
            Pair(12, listOf(150L, 400L, 250L, 700L, 1100L, 350L)),
            Pair(20, listOf(100L, 200L, 150L, 300L, 250L, 400L, 600L, 900L)),
            Pair(28, listOf(80L, 120L, 300L, 450L, 200L, 600L, 1000L, 1300L))
        )

        for ((nSkips, delaySequence) in testConfigs) {
            val client = TrackingMediaControllerClient()
            val queueEngine = PlaybackQueueEngine()
            val resolver = AuditingStreamResolver(
                currentTimeProvider = { testScheduler.currentTime },
                currentTargetProvider = { queueEngine.currentTrack?.id }
            )
            val transitionManager = TransitionManager()

            val viewModel = PlayerViewModel(
                client = client,
                discoveryRepository = FakeDiscoveryRepo(),
                streamResolverPort = resolver,
                queueEngine = queueEngine,
                transitionManager = transitionManager,
                ioDispatcher = testDispatcher
            )
            val job = backgroundScope.launch { viewModel.uiState.collect {} }

            val totalTracks = nSkips + 5
            val tracks = (0 until totalTracks).map {
                Track(id = "fuzz_n${nSkips}_$it", title = "Track $it", artist = "Artist", durationMs = 180000L)
            }

            // Start playing initial track
            viewModel.playTrack(tracks[0], tracks)
            runCurrent()
            assertEquals("Initial track must be primary resolved", 1, resolver.primaryCalls().size)
            assertEquals("Zero lookahead at start", 0, resolver.lookaheadCalls().size)

            // Perform N rapid skips with varying delays strictly < 1500ms
            for (step in 1..nSkips) {
                val delayMs = delaySequence[(step - 1) % delaySequence.size]
                assertTrue("Delay in fuzz test must be strictly < 1500ms", delayMs < 1500L)

                advanceTimeBy(delayMs)
                runCurrent()
                // Invariant check: During skipping, zero lookahead requests
                assertEquals(
                    "Invariant violated at step $step: lookahead was dispatched during skipping (N=$nSkips, delay=${delayMs}ms)",
                    0,
                    resolver.lookaheadCalls().size
                )

                viewModel.skipToNext()
                runCurrent()

                assertEquals(
                    "Invariant violated immediately after skipToNext at step $step: lookahead was dispatched (N=$nSkips)",
                    0,
                    resolver.lookaheadCalls().size
                )
            }

            // Immediately after all N skips completed
            assertEquals("Must have 0 lookahead calls immediately after $nSkips skips", 0, resolver.lookaheadCalls().size)
            assertEquals("Must have exactly ${nSkips + 1} primary calls", nSkips + 1, resolver.primaryCalls().size)

            // Settle playback for >= 1500ms
            advanceTimeBy(1600)
            runCurrent()

            // Invariant: At most ONE lookahead request after settling for >= 1500ms
            assertEquals("At most ONE lookahead request after settling >= 1500ms (N=$nSkips)", 1, resolver.lookaheadCalls().size)
            val lookaheadTrack = resolver.lookaheadCalls().first()
            assertEquals("Lookahead must target upcoming track (index ${nSkips + 1})", tracks[nSkips + 1].id, lookaheadTrack.trackId)

            job.cancel()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // REGRESSION: Finding 1.D E->F->E Rollback Prevention (AC4)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun regression_finding1D_rollbackPrevention_remainsIntactWithDebouncedLookahead() = testScope.runTest {
        val client = TrackingMediaControllerClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = AuditingStreamResolver(
            currentTimeProvider = { testScheduler.currentTime },
            currentTargetProvider = { queueEngine.currentTrack?.id }
        )
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

        val queue = listOf(trackA, trackB, trackC, trackD, trackE, trackF)
        viewModel.playTrack(trackA, queue)

        // Rapid skips A -> B -> C -> D -> E
        viewModel.skipToNext() // B
        viewModel.skipToNext() // C
        viewModel.skipToNext() // D
        viewModel.skipToNext() // E
        advanceTimeBy(100)

        assertEquals("track_E", viewModel.uiState.value.trackId)

        // Skip to F and have F stream resolution fail
        resolver.setStaticResult(trackF.id, Result.failure(RuntimeException("Failed to resolve stream for Track F")))
        viewModel.skipToNext() // F
        advanceUntilIdle()

        val finalState = viewModel.uiState.value

        // Assert AC4: Finding 1.D rollback prevention remains intact.
        // UI must stay on Track F with error message and paused, NOT rolling back to E or A.
        assertEquals("Track F must remain active in UI", "track_F", finalState.trackId)
        assertEquals("Track F", finalState.trackTitle)
        assertNotNull("Error message must be present for Track F", finalState.errorMessage)
        assertFalse("Playback must not be playing", finalState.isPlaying)
        assertFalse("Playback must not be buffering", finalState.isBuffering)
        assertEquals(androidx.media3.common.Player.STATE_IDLE, finalState.playbackState)
        assertTrue("Audio output must be paused", client.isPaused)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test Helpers & Mocks
    // ──────────────────────────────────────────────────────────────────────────

    private data class RecordedResolveCall(
        val trackId: String,
        val timestampMs: Long,
        val isPrimary: Boolean,
        val isLookahead: Boolean,
        val title: String,
        val artist: String,
        val durationSeconds: Int
    )

    private class AuditingStreamResolver(
        private val currentTimeProvider: () -> Long,
        private val currentTargetProvider: () -> String?
    ) : StreamResolverPort {
        val allCalls = mutableListOf<RecordedResolveCall>()
        val staticResults = mutableMapOf<String, Result<StreamInfo>>()

        fun setStaticResult(trackId: String, result: Result<StreamInfo>) {
            staticResults[trackId] = result
        }

        override fun clearCache() {
            staticResults.clear()
            synchronized(allCalls) { allCalls.clear() }
        }

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
            val now = currentTimeProvider()
            val currentTarget = currentTargetProvider()
            val isPrimary = currentTarget != null && currentTarget == trackId
            val isLookahead = !isPrimary

            val call = RecordedResolveCall(
                trackId = trackId,
                timestampMs = now,
                isPrimary = isPrimary,
                isLookahead = isLookahead,
                title = title,
                artist = artist,
                durationSeconds = durationSeconds
            )
            synchronized(allCalls) {
                allCalls.add(call)
            }

            staticResults[trackId]?.let { return it }

            return Result.success(
                StreamInfo(
                    trackId = trackId,
                    streamUrl = "http://stream.sonara/$trackId.webm"
                )
            )
        }

        fun primaryCalls(): List<RecordedResolveCall> = synchronized(allCalls) { allCalls.filter { it.isPrimary } }
        fun lookaheadCalls(): List<RecordedResolveCall> = synchronized(allCalls) { allCalls.filter { it.isLookahead } }
        fun callsFor(trackId: String): List<RecordedResolveCall> = synchronized(allCalls) { allCalls.filter { it.trackId == trackId } }
    }

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

        override fun seekTo(positionMs: Long) {}
    }

    private class FakeDiscoveryRepo : DiscoveryRepository {
        var relatedTracksResult: Result<List<Track>> = Result.success(emptyList())

        override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = Result.success(emptyList())
        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> = Result.success(ArtistCatalog())
        override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())
        override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> = Result.failure(Exception("Not implemented"))
        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = relatedTracksResult
    }
}
