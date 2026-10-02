package com.example.sonara.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * POINT 2 — Area 1 (low-latency in-app Next) adversarial verification suite.
 *
 * Verifies that an in-app Next consumes the service's ALREADY-preloaded index+1 item via a gapless
 * controller seek (instead of discarding it with a fresh resolve + setMediaItem), but ONLY when the
 * preloaded item's identity provably matches the authoritative queue target — and never otherwise.
 *
 * Mapping to the required test list ("Preloaded Next"):
 *  - matching preload consumed via seek, no fallback resolve ....... [matchingPreload_consumedViaGaplessSeek_withNoResolve]
 *  - preloaded item consumed then reconciled (guard released) ....... [matchingPreload_afterControllerReportsTransition_reconcilesAndClearsTransitional]
 *  - mismatched preload falls back ................................. [mismatchedPreload_fallsBackToResolvePath]
 *  - no / not-yet-available preload falls back ..................... [absentPreload_fallsBackToResolvePath]
 *  - stale preload after queue mutation never consumes wrong track . [stalePreloadAfterQueueMutation_fallsBack_neverConsumesWrongTrack]
 *  - current-track desync falls back .............................. [currentTrackDesync_fallsBackToResolvePath]
 *  - repeat-one does not consume (ReplayCurrent) .................. [repeatOne_doesNotConsume_replaysCurrentInstead]
 *  - rapid Next generation-safe (in-flight drop + recovery) ....... [rapidNext_secondNextDroppedWhileConsumeInFlight_recoversAfterTransition]
 *  - Next-then-Previous (Previous dropped in-flight + recovery) ... [nextThenPrevious_previousDroppedWhileConsumeInFlight_worksAfterTransition]
 *  - Previous unchanged (threshold intact, not in-flight) ......... [previousAboveRestartThreshold_seeksToStart_unaffectedByGuard]
 *  - in-flight guard backstop timeout ............................. [consumeInFlightGuard_releasedByBackstopTimeout]
 *  - in-flight guard released on controller error ................. [consumeInFlightGuard_releasedWhenControllerReportsError]
 *
 * Test fakes construct [MediaControllerClient] with a null context so the real controller is absent;
 * [PreloadTimelineClient] overrides exactly the Area 1 live-timeline reads (currentMediaId /
 * peekNextMediaId), the gapless seek (skipToNext), and the fallback entry point (playTrack), and
 * models the controller's ASYNCHRONOUS transition report via [PreloadTimelineClient.completeSeek] so
 * the in-flight guard window is observable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PointTwoPreloadConsumeTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val trackA = Track(id = "track_A", title = "Track A", artist = "Artist A", durationMs = 180_000L)
    private val trackB = Track(id = "track_B", title = "Track B", artist = "Artist B", durationMs = 200_000L)
    private val trackC = Track(id = "track_C", title = "Track C", artist = "Artist C", durationMs = 220_000L)
    private val trackX = Track(id = "track_X", title = "Track X", artist = "Artist X", durationMs = 240_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CONSUME: matching preload is consumed gaplessly, with NO fresh resolution
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun matchingPreload_consumedViaGaplessSeek_withNoResolve() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        // Current = A, upcoming = [B, C]; service has preloaded EXACTLY the authoritative next (B).
        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        f.viewModel.skipToNext()
        runCurrent()

        // The preloaded B is consumed with a single gapless seek — no fresh resolve, no setMediaItem.
        assertEquals("Gapless seek must be dispatched exactly once", 1, f.client.skipToNextCalls)
        assertEquals("No fallback setMediaItem/playTrack may occur on a consume", 0, f.client.playedTracks.size)
        assertEquals("No stream resolution may occur on a gapless consume", 0, f.resolver.totalCalls())
        // Authoritative queue advanced to B (advance popped exactly one item).
        assertEquals("Queue must advance to B", trackB.id, f.queueEngine.currentTrack?.id)
        assertEquals("Upcoming must now be [C]", listOf(trackC.id), f.queueEngine.upcomingQueue.map { it.id })
        // Optimistic UI reflects B immediately (before the controller confirms the transition).
        assertEquals("UI shows B optimistically", trackB.id, f.viewModel.uiState.value.trackId)

        advanceUntilIdle()
    }

    @Test
    fun matchingPreload_afterControllerReportsTransition_reconcilesAndClearsTransitional() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        f.viewModel.skipToNext()
        runCurrent()
        assertEquals(1, f.client.skipToNextCalls)

        // Controller now reports the seek landed on B (async transition).
        f.client.completeSeek(newNextId = trackC.id)
        runCurrent()

        // The reconcile collector releases the optimistic transitional track; UI is now driven by the
        // live controller item (B, playing).
        val ui = f.viewModel.uiState.value
        assertEquals("UI driven by live controller item B", trackB.id, ui.trackId)
        assertTrue("Live controller reports B playing", ui.isPlaying)
        // Guard released: a subsequent Next is accepted (not dropped) and consumes the new preload (C).
        f.viewModel.skipToNext()
        runCurrent()
        assertEquals("Second Next accepted after transition reported", 2, f.client.skipToNextCalls)
        assertEquals("Queue advanced to C via the second gapless consume", trackC.id, f.queueEngine.currentTrack?.id)
        assertEquals("Still no fallback resolves across two consumes", 0, f.client.playedTracks.size)

        advanceUntilIdle()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FALLBACK: a preload that is NOT the authoritative target is never consumed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun mismatchedPreload_fallsBackToResolvePath() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        // Authoritative next is B, but the preloaded index+1 item is some unrelated id.
        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = "track_STALE_UNRELATED")
        runCurrent()

        f.viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals("A mismatched preload must NOT be consumed via gapless seek", 0, f.client.skipToNextCalls)
        assertEquals("Fallback must resolve + play exactly the authoritative next (B)", listOf(trackB.id), f.client.playedTracks.map { it.id })
        assertEquals("Queue advanced to B", trackB.id, f.queueEngine.currentTrack?.id)
    }

    @Test
    fun absentPreload_fallsBackToResolvePath() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        // Preload not yet available (nothing at index+1).
        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = null)
        runCurrent()

        f.viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals("No preload means no gapless seek", 0, f.client.skipToNextCalls)
        assertEquals("Fallback resolves + plays B", listOf(trackB.id), f.client.playedTracks.map { it.id })
        assertEquals(trackB.id, f.queueEngine.currentTrack?.id)
    }

    @Test
    fun stalePreloadAfterQueueMutation_fallsBack_neverConsumesWrongTrack() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        // Service preloaded B. Then the user mutates the queue so the authoritative next becomes X.
        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        f.viewModel.playNext(trackX) // upcoming becomes [X, B, C]; the preload (B) is now stale.
        runCurrent()

        f.viewModel.skipToNext()
        advanceUntilIdle()

        // The now-stale preload (B) must NEVER be consumed; the authoritative next is X.
        assertEquals("Stale preload must not be gaplessly consumed after a queue mutation", 0, f.client.skipToNextCalls)
        assertEquals("Fallback plays the real authoritative next (X), not the stale preload (B)", trackX.id, f.queueEngine.currentTrack?.id)
        assertTrue("X must be played", f.client.playedTracks.any { it.id == trackX.id })
        assertFalse("The stale preload B must never be played", f.client.playedTracks.any { it.id == trackB.id })
    }

    @Test
    fun currentTrackDesync_fallsBackToResolvePath() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        // The controller is sitting on a DIFFERENT item than the authoritative current track → desync.
        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = "track_DESYNCED", nextId = trackB.id)
        runCurrent()

        f.viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals("A current-track desync must block the gapless consume", 0, f.client.skipToNextCalls)
        assertEquals("Fallback resolves + plays B", listOf(trackB.id), f.client.playedTracks.map { it.id })
        assertEquals(trackB.id, f.queueEngine.currentTrack?.id)
    }

    @Test
    fun repeatOne_doesNotConsume_replaysCurrentInstead() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.viewModel.toggleRepeatMode() // 0 -> 1 (repeat all)
        f.viewModel.toggleRepeatMode() // 1 -> 2 (repeat one)
        // Even with a valid-looking preload present, repeat-one must replay the current track.
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        f.viewModel.skipToNext()
        runCurrent()

        assertEquals("Repeat-one must not consume a preload via gapless seek", 0, f.client.skipToNextCalls)
        assertEquals("Repeat-one must not resolve/play a different track", 0, f.client.playedTracks.size)
        assertEquals("Repeat-one must not resolve a stream", 0, f.resolver.totalCalls())
        assertTrue("Repeat-one must seek current to start", f.client.seekToZeroCalls >= 1)
        assertTrue("Repeat-one must resume playback", f.client.playCalls >= 1)
        assertEquals("Queue must remain on A (no advance)", trackA.id, f.queueEngine.currentTrack?.id)

        advanceUntilIdle()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GENERATION SAFETY: the in-flight consume guard
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun rapidNext_secondNextDroppedWhileConsumeInFlight_recoversAfterTransition() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        // First Next consumes B gaplessly; the consume is now in flight (controller not yet reported).
        f.viewModel.skipToNext()
        runCurrent()
        assertEquals(1, f.client.skipToNextCalls)
        assertEquals(trackB.id, f.queueEngine.currentTrack?.id)

        // Second rapid Next DURING the in-flight window must be DROPPED (not fall back, not re-advance):
        // a racing dispatch would desync the authoritative queue against the pending async SEEK.
        f.viewModel.skipToNext()
        runCurrent()
        assertEquals("Second Next must be dropped while the consume is in flight", 1, f.client.skipToNextCalls)
        assertEquals("Dropped Next must NOT re-advance the queue", trackB.id, f.queueEngine.currentTrack?.id)
        assertEquals("Dropped Next must NOT trigger a fallback resolve", 0, f.client.playedTracks.size)

        // Controller reports the first seek landed on B, with C now preloaded. Guard releases.
        f.client.completeSeek(newNextId = trackC.id)
        runCurrent()

        // A Next after the transition is accepted again and consumes the new preload (C).
        f.viewModel.skipToNext()
        runCurrent()
        assertEquals("Next accepted again after the transition reported", 2, f.client.skipToNextCalls)
        assertEquals("Queue advanced to C", trackC.id, f.queueEngine.currentTrack?.id)
        assertEquals("Still no fallback resolves", 0, f.client.playedTracks.size)

        advanceUntilIdle()
    }

    @Test
    fun nextThenPrevious_previousDroppedWhileConsumeInFlight_worksAfterTransition() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        // Consume B; now in flight.
        f.viewModel.skipToNext()
        runCurrent()
        assertEquals(1, f.client.skipToNextCalls)

        // Previous during the in-flight window must be DROPPED (a Next-then-Previous race must not
        // inject a setMediaItem that desyncs the pending seek).
        f.viewModel.skipToPrevious()
        runCurrent()
        assertEquals("Previous must be dropped while the consume is in flight", 0, f.client.playedTracks.size)
        assertEquals("Dropped Previous must not touch the queue", trackB.id, f.queueEngine.currentTrack?.id)
        assertEquals("Dropped Previous must not seek", 0, f.client.seekToZeroCalls)

        // Controller reports B landed; backstack now holds A. Guard releases.
        f.client.completeSeek(newNextId = trackC.id)
        runCurrent()

        // Previous now works normally: it pops A from the session backstack and resolves it.
        f.viewModel.skipToPrevious()
        advanceUntilIdle()
        assertEquals("Previous after transition pops and plays A", trackA.id, f.queueEngine.currentTrack?.id)
        assertEquals("A is resolved + played via the normal fallback path", trackA.id, f.client.playedTracks.last().id)
    }

    @Test
    fun previousAboveRestartThreshold_seeksToStart_unaffectedByGuard() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        // Consume B and let the controller confirm, so NO consume is in flight for the Previous below.
        f.viewModel.skipToNext()
        runCurrent()
        f.client.completeSeek(newNextId = trackC.id)
        runCurrent()

        // Position is past the 3000ms restart threshold → Previous must seek-to-start, unchanged.
        f.client.setPosition(5_000L)
        f.viewModel.skipToPrevious()
        runCurrent()

        assertTrue("Previous above threshold must seek to start", f.client.seekToZeroCalls >= 1)
        assertEquals("Seek-to-start must not change the current track", trackB.id, f.queueEngine.currentTrack?.id)
        assertEquals("Seek-to-start must not resolve/play any track", 0, f.client.playedTracks.size)

        advanceUntilIdle()
    }

    @Test
    fun consumeInFlightGuard_releasedByBackstopTimeout() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        // Consume B, then NEVER report the transition (pathological: controller wedged).
        f.viewModel.skipToNext()
        runCurrent()
        assertEquals(1, f.client.skipToNextCalls)

        // Backstop timeout (3000ms) must release the guard so skip handling is not wedged forever.
        advanceUntilIdle() // fires the 3000ms backstop (and the lookahead)

        // A subsequent Next is accepted again (not dropped). Because the controller never confirmed B,
        // currentMediaId still reads A while the queue is on B → consume ineligible → it falls back and
        // advances to C. The key signal is that the skip was PROCESSED (queue advanced), proving the
        // guard was released.
        f.viewModel.skipToNext()
        advanceUntilIdle()
        assertEquals("Guard released by backstop: subsequent Next is processed and advances the queue", trackC.id, f.queueEngine.currentTrack?.id)
        assertTrue("Subsequent Next falls back and plays C", f.client.playedTracks.any { it.id == trackC.id })
    }

    @Test
    fun consumeInFlightGuard_releasedWhenControllerReportsError() = testScope.runTest {
        val f = Fixture()
        backgroundScope.launch { f.viewModel.uiState.collect {} }

        f.queueEngine.setContext(trackA, listOf(trackA, trackB, trackC))
        f.client.setTimeline(currentId = trackA.id, nextId = trackB.id)
        runCurrent()

        // Consume B; then the controller surfaces a player error for the pending transition.
        f.viewModel.skipToNext()
        runCurrent()
        assertEquals(1, f.client.skipToNextCalls)

        f.client.failPendingSeek("player error on pending transition")
        runCurrent()

        // Guard released by the error branch of the reconcile collector → subsequent Next is processed.
        f.viewModel.skipToNext()
        advanceUntilIdle()
        assertEquals("Guard released on error: subsequent Next is processed and advances the queue", trackC.id, f.queueEngine.currentTrack?.id)
        assertTrue("Subsequent Next falls back and plays C", f.client.playedTracks.any { it.id == trackC.id })
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fixtures & fakes
    // ──────────────────────────────────────────────────────────────────────────

    private inner class Fixture {
        val client = PreloadTimelineClient()
        val queueEngine = PlaybackQueueEngine()
        val resolver = RecordingStreamResolver()
        val transitionManager = TransitionManager()
        val viewModel = PlayerViewModel(
            client = client,
            discoveryRepository = FakeDiscoveryRepo(),
            streamResolverPort = resolver,
            queueEngine = queueEngine,
            transitionManager = transitionManager,
            ioDispatcher = testDispatcher
        )
    }

    /**
     * A [MediaControllerClient] (null context → no real controller) that simulates a live ExoPlayer
     * timeline with a preloaded index+1 item, and models the controller's ASYNCHRONOUS transition
     * report so the Point 2 in-flight consume window is observable.
     */
    private class PreloadTimelineClient : MediaControllerClient(null) {
        private var simCurrentId: String? = null
        private var simNextId: String? = null
        private var pendingSeekTarget: String? = null

        var skipToNextCalls = 0
            private set
        var seekToZeroCalls = 0
            private set
        var playCalls = 0
            private set
        val playedTracks = mutableListOf<Track>()

        /** Seed the simulated timeline: the live current item and the preloaded index+1 item. */
        fun setTimeline(currentId: String?, nextId: String?) {
            simCurrentId = currentId
            simNextId = nextId
            if (currentId != null) emitCurrent(currentId)
        }

        fun setPosition(positionMs: Long) {
            _controllerState.value = _controllerState.value.copy(currentPositionMs = positionMs)
        }

        private fun emitCurrent(id: String) {
            val mediaItem = MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(id).build())
                .build()
            _controllerState.value = _controllerState.value.copy(
                isConnected = true,
                currentMediaItem = mediaItem,
                isPlaying = true,
                errorMessage = null
            )
        }

        // ── Point 2 Area 1 live-timeline reads under test ──
        override fun currentMediaId(): String? = simCurrentId
        override fun peekNextMediaId(): String? = simNextId

        override fun skipToNext() {
            // Models controller.seekToNextMediaItem(): the gapless seek is dispatched now, but the
            // controller reports the resulting transition only asynchronously (see completeSeek()).
            skipToNextCalls++
            pendingSeekTarget = simNextId
        }

        /** Simulate the controller reporting that the dispatched gapless seek has landed on the preload. */
        fun completeSeek(newNextId: String? = null) {
            val target = pendingSeekTarget ?: return
            pendingSeekTarget = null
            simCurrentId = target
            simNextId = newNextId
            emitCurrent(target)
        }

        /** Simulate the controller surfacing a player error for the pending (in-flight) transition. */
        fun failPendingSeek(message: String) {
            pendingSeekTarget = null
            _controllerState.value = _controllerState.value.copy(errorMessage = message)
        }

        override fun playTrack(track: Track, streamUrl: String, generation: Long) {
            // Legacy fallback resolve path lands here; reflect it as the live current item synchronously.
            playedTracks.add(track)
            simCurrentId = track.id
            simNextId = null
            pendingSeekTarget = null
            emitCurrent(track.id)
        }

        override fun seekTo(positionMs: Long) {
            if (positionMs == 0L) seekToZeroCalls++
        }

        override fun play() {
            playCalls++
            _controllerState.value = _controllerState.value.copy(isPlaying = true)
        }

        override fun pause() {
            _controllerState.value = _controllerState.value.copy(isPlaying = false)
        }
    }

    private class RecordingStreamResolver : StreamResolverPort {
        private val resolvedTrackIds = mutableListOf<String>()

        override suspend fun resolveStream(
            trackId: String,
            quality: AudioQuality
        ): Result<StreamInfo> {
            synchronized(resolvedTrackIds) { resolvedTrackIds.add(trackId) }
            return Result.success(StreamInfo(trackId = trackId, streamUrl = "http://stream.sonara/$trackId.webm"))
        }

        // resolveStream(5-arg) and refreshStream default to the 2-arg override above (interface defaults).

        override fun clearCache() {
            synchronized(resolvedTrackIds) { resolvedTrackIds.clear() }
        }

        fun totalCalls(): Int = synchronized(resolvedTrackIds) { resolvedTrackIds.size }
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
