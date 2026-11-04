package com.example.sonara.feature.video

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPreviousRestartAndRaceTest {
    private val origin = "https://app.sonara.music"

    @Test
    fun `previous at 1500 to 2000 ms with empty history restarts at zero and does not pause`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                statuses[seedA.id] = allowed(seedA)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            var activeTrack: Track? = null
            viewModel.seed(seedA) { activeTrack = it }
            advanceUntilIdle()

            assertEquals(seedA.id, activeTrack?.id)
            assertNull(viewModel.peekPreviousTrack())

            val positionMs = 1800L
            val hasPrevious = viewModel.peekPreviousTrack() != null

            // Coordinator check: should NOT pause active player when empty history
            assertFalse(
                "Must not pause player when restarting current video at or below 3000ms with empty history",
                VideoPreviousNavigationCoordinator.shouldPauseBeforePrevious(
                    currentPositionMs = positionMs,
                    hasPreviousTrack = hasPrevious
                )
            )

            var restartCurrentCalled = false
            var trackReadyCalled: Track? = null
            var playerWasPaused = false

            // Simulate UI action in SonaraAppRoot
            if (VideoPreviousNavigationCoordinator.shouldPauseBeforePrevious(positionMs, hasPrevious)) {
                playerWasPaused = true
            }

            viewModel.previous(
                currentPositionMs = positionMs,
                onRestartCurrent = { restartCurrentCalled = true },
                onTrackReady = { trackReadyCalled = it }
            )
            advanceUntilIdle()

            assertTrue("onRestartCurrent should be invoked", restartCurrentCalled)
            assertNull("onTrackReady must not be invoked when restarting", trackReadyCalled)
            assertFalse("Active player must not be paused", playerWasPaused)
            assertEquals(seedA.id, viewModel.state.value.current?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `previous at or below 3000 ms with history navigates to prior video and pauses before transition`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                statuses[seedA.id] = allowed(seedA)
                statuses[trackB.id] = allowed(trackB)
                radio = Result.success(listOf(trackB))
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seedA) {}
            advanceUntilIdle()

            viewModel.next {}
            advanceUntilIdle()
            assertEquals(trackB.id, viewModel.state.value.current?.id)
            assertEquals(seedA.id, viewModel.peekPreviousTrack()?.id)

            val positionMs = 2500L
            val hasPrevious = viewModel.peekPreviousTrack() != null

            // Coordinator check: MUST pause player when transitioning to a genuine prior track
            assertTrue(
                "Must pause player when navigating to prior video at or below threshold",
                VideoPreviousNavigationCoordinator.shouldPauseBeforePrevious(
                    currentPositionMs = positionMs,
                    hasPreviousTrack = hasPrevious
                )
            )

            var restartCurrentCalled = false
            var trackReadyCalled: Track? = null
            var playerWasPaused = false

            if (VideoPreviousNavigationCoordinator.shouldPauseBeforePrevious(positionMs, hasPrevious)) {
                playerWasPaused = true
            }

            viewModel.previous(
                currentPositionMs = positionMs,
                onRestartCurrent = { restartCurrentCalled = true },
                onTrackReady = { trackReadyCalled = it }
            )
            advanceUntilIdle()

            assertTrue("Active player must be paused before transition", playerWasPaused)
            assertFalse("onRestartCurrent must not be called", restartCurrentCalled)
            assertEquals(seedA.id, trackReadyCalled?.id)
            assertEquals(seedA.id, viewModel.state.value.current?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `double tapping previous after restart navigates to prior video instead of restarting twice`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                statuses[seedA.id] = allowed(seedA)
                statuses[trackB.id] = allowed(trackB)
                radio = Result.success(listOf(trackB))
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seedA) {}
            advanceUntilIdle()

            viewModel.next {}
            advanceUntilIdle()
            assertEquals(trackB.id, viewModel.state.value.current?.id)

            // Simulating UI state variables in SonaraAppRoot
            var activeVideoPlayerState: YouTubePlayerState? = YouTubePlayerState(
                videoId = trackB.id,
                generation = 2L,
                currentPositionMs = 5000L,
                durationMs = 180_000L
            )

            // === TAP 1: User taps Previous at 5000 ms ===
            val currentPos1 = VideoPreviousNavigationCoordinator.resolveCurrentPosition(
                isMedia3 = false,
                media3PositionMs = 0L,
                iframeStatePositionMs = activeVideoPlayerState?.currentPositionMs,
                retainedIFramePositionMs = viewModel.getIFramePosition(trackB.id)
            )
            assertEquals(5000L, currentPos1)

            val hasPrevious1 = viewModel.peekPreviousTrack() != null
            assertTrue(hasPrevious1)

            assertFalse(
                "Should not pause when restarting at 5000 ms",
                VideoPreviousNavigationCoordinator.shouldPauseBeforePrevious(currentPos1, hasPrevious1)
            )

            var tap1Restarted = false
            var tap1Navigated: Track? = null
            viewModel.previous(
                currentPositionMs = currentPos1,
                onRestartCurrent = {
                    tap1Restarted = true
                    viewModel.updateIFramePosition(trackB.id, 0L)
                    // Immediate state update implemented in SonaraAppRoot
                    activeVideoPlayerState = activeVideoPlayerState?.copy(currentPositionMs = 0L)
                },
                onTrackReady = { tap1Navigated = it }
            )
            advanceUntilIdle()

            assertTrue("Tap 1 must restart current track", tap1Restarted)
            assertNull("Tap 1 must not navigate to prior track", tap1Navigated)
            assertEquals(0L, activeVideoPlayerState?.currentPositionMs)

            // === TAP 2: User taps Previous immediately (e.g. 100 ms later, before any WebView timer tick) ===
            val currentPos2 = VideoPreviousNavigationCoordinator.resolveCurrentPosition(
                isMedia3 = false,
                media3PositionMs = 0L,
                iframeStatePositionMs = activeVideoPlayerState?.currentPositionMs,
                retainedIFramePositionMs = viewModel.getIFramePosition(trackB.id)
            )
            assertEquals("Second tap must read immediate 0 ms position, not stale 5000 ms", 0L, currentPos2)

            val hasPrevious2 = viewModel.peekPreviousTrack() != null
            assertTrue("Prior track seedA must still be available in history", hasPrevious2)

            assertTrue(
                "Second tap at 0 ms with history must trigger pause before transition",
                VideoPreviousNavigationCoordinator.shouldPauseBeforePrevious(currentPos2, hasPrevious2)
            )

            var tap2Restarted = false
            var tap2Navigated: Track? = null
            viewModel.previous(
                currentPositionMs = currentPos2,
                onRestartCurrent = { tap2Restarted = true },
                onTrackReady = { tap2Navigated = it }
            )
            advanceUntilIdle()

            assertFalse("Tap 2 must NOT restart current track twice", tap2Restarted)
            assertEquals("Tap 2 must successfully navigate to prior track", seedA.id, tap2Navigated?.id)
            assertEquals(seedA.id, viewModel.state.value.current?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `scrubbing from above 3000 ms to below 3000 ms immediately updates position and navigates on previous`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                statuses[seedA.id] = allowed(seedA)
                statuses[trackB.id] = allowed(trackB)
                radio = Result.success(listOf(trackB))
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seedA) {}
            advanceUntilIdle()
            viewModel.next {}
            advanceUntilIdle()

            val durationMs = 120_000L
            var activeVideoPlayerState: YouTubePlayerState? = YouTubePlayerState(
                videoId = trackB.id,
                generation = 2L,
                currentPositionMs = 25_000L,
                durationMs = durationMs
            )

            // User scrubs from 25s (0.208f) to 1.8s (0.015f)
            val scrubFraction = 0.015f
            val seekTargetMs = VideoPreviousNavigationCoordinator.calculateSeekTargetMs(scrubFraction, durationMs)
            assertEquals(1800L, seekTargetMs)

            // onPresentedSeek updates both viewModel retained position and activeVideoPlayerState
            viewModel.updateIFramePosition(trackB.id, seekTargetMs, durationMs)
            activeVideoPlayerState = activeVideoPlayerState?.copy(currentPositionMs = seekTargetMs)

            // User immediately taps Previous
            val resolvedPos = VideoPreviousNavigationCoordinator.resolveCurrentPosition(
                isMedia3 = false,
                media3PositionMs = 0L,
                iframeStatePositionMs = activeVideoPlayerState?.currentPositionMs,
                retainedIFramePositionMs = viewModel.getIFramePosition(trackB.id)
            )
            assertEquals(1800L, resolvedPos)

            val hasPrevious = viewModel.peekPreviousTrack() != null
            assertTrue(
                "Seek to 1800ms must be recognized as below 3000ms threshold",
                VideoPreviousNavigationCoordinator.shouldPauseBeforePrevious(resolvedPos, hasPrevious)
            )

            var navigatedTrack: Track? = null
            var restarted = false
            viewModel.previous(
                currentPositionMs = resolvedPos,
                onRestartCurrent = { restarted = true },
                onTrackReady = { navigatedTrack = it }
            )
            advanceUntilIdle()

            assertFalse("Must not restart current track when scrubbed below 3000ms with history", restarted)
            assertEquals("Must navigate to prior track", seedA.id, navigatedTrack?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `scrubbing from below 3000 ms to above 3000 ms immediately updates position and restarts on previous`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                statuses[seedA.id] = allowed(seedA)
                statuses[trackB.id] = allowed(trackB)
                radio = Result.success(listOf(trackB))
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seedA) {}
            advanceUntilIdle()
            viewModel.next {}
            advanceUntilIdle()

            val durationMs = 120_000L
            var activeVideoPlayerState: YouTubePlayerState? = YouTubePlayerState(
                videoId = trackB.id,
                generation = 2L,
                currentPositionMs = 1_000L,
                durationMs = durationMs
            )

            // User scrubs from 1s to 45s (0.375f)
            val scrubFraction = 0.375f
            val seekTargetMs = VideoPreviousNavigationCoordinator.calculateSeekTargetMs(scrubFraction, durationMs)
            assertEquals(45_000L, seekTargetMs)

            viewModel.updateIFramePosition(trackB.id, seekTargetMs, durationMs)
            activeVideoPlayerState = activeVideoPlayerState?.copy(currentPositionMs = seekTargetMs)

            val resolvedPos = VideoPreviousNavigationCoordinator.resolveCurrentPosition(
                isMedia3 = false,
                media3PositionMs = 0L,
                iframeStatePositionMs = activeVideoPlayerState?.currentPositionMs,
                retainedIFramePositionMs = viewModel.getIFramePosition(trackB.id)
            )
            assertEquals(45_000L, resolvedPos)

            val hasPrevious = viewModel.peekPreviousTrack() != null
            assertFalse(
                "Seek to 45000ms must be recognized as above 3000ms threshold",
                VideoPreviousNavigationCoordinator.shouldPauseBeforePrevious(resolvedPos, hasPrevious)
            )

            var navigatedTrack: Track? = null
            var restarted = false
            viewModel.previous(
                currentPositionMs = resolvedPos,
                onRestartCurrent = { restarted = true },
                onTrackReady = { navigatedTrack = it }
            )
            advanceUntilIdle()

            assertTrue("Must restart current track when scrubbed above 3000ms", restarted)
            assertNull("Must not navigate to prior track", navigatedTrack)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stale position message from outgoing video cannot overwrite current video position`() {
        val controller = YouTubePlayerController(
            videoId = seedA.id,
            generation = 1L,
            expectedOrigin = origin
        )

        // Make controller ready for video A
        val readyResult = controller.handleMessage(
            """{"type":"ready","videoId":"${seedA.id}","generation":1,"durationMs":200000}""",
            origin,
            isMainFrame = true
        )
        assertTrue(readyResult is YouTubeMessageHandlingResult.Accepted)

        // video A position arrives
        val posResultA = controller.handleMessage(
            """{"type":"position","videoId":"${seedA.id}","generation":1,"currentPositionMs":15000,"durationMs":200000}""",
            origin,
            isMainFrame = true
        )
        assertTrue(posResultA is YouTubeMessageHandlingResult.Accepted)
        assertEquals(15000L, controller.state.currentPositionMs)

        // Transition to video B, generation 2
        controller.replaceVideo(videoId = trackB.id, generation = 2L, initialPositionMs = 0L)
        assertEquals(trackB.id, controller.state.videoId)
        assertEquals(2L, controller.state.generation)
        assertNull(controller.state.currentPositionMs)

        // Delayed stale position message arrives from video A
        val staleResult = controller.handleMessage(
            """{"type":"position","videoId":"${seedA.id}","generation":1,"currentPositionMs":16000,"durationMs":200000}""",
            origin,
            isMainFrame = true
        )
        assertEquals(
            "Stale message from outgoing video must be ignored",
            YouTubeMessageHandlingResult.IgnoredStale,
            staleResult
        )
        assertNull("Stale message must not overwrite current video position", controller.state.currentPositionMs)
        assertEquals(trackB.id, controller.state.videoId)

        // Mismatched generation on current video ID is also ignored as stale
        val staleGenResult = controller.handleMessage(
            """{"type":"position","videoId":"${trackB.id}","generation":1,"currentPositionMs":5000,"durationMs":150000}""",
            origin,
            isMainFrame = true
        )
        assertEquals(
            "Message with outdated generation must be ignored",
            YouTubeMessageHandlingResult.IgnoredStale,
            staleGenResult
        )
        assertNull("Mismatched generation must not update position", controller.state.currentPositionMs)
    }

    @Test
    fun `youtubeEmbedDocument emits immediate position update on seek command`() {
        val html = youtubeEmbedDocument(
            videoId = "dQw4w9WgXcQ",
            generation = 42L,
            initialPositionMs = 0L
        )

        // Verify seek branch contains immediate position emit with activeVideoId and playerGeneration
        assertTrue("Embed HTML must contain seek command handler", html.contains("command.type === 'seek'"))
        assertTrue("Embed HTML must round target seek position", html.contains("var targetMs = Math.round(command.positionMs)"))
        assertTrue("Embed HTML must call player.seekTo with seconds", html.contains("player.seekTo(targetMs / 1000, true)"))
        assertTrue("Embed HTML must emit immediate position event", html.contains("type: 'position'"))
        assertTrue("Emit payload must reference activeVideoId", html.contains("videoId: activeVideoId"))
        assertTrue("Emit payload must reference playerGeneration", html.contains("generation: playerGeneration"))
        assertTrue("Emit payload must include currentPositionMs: targetMs", html.contains("currentPositionMs: targetMs"))
    }

    private class FakeBackendClient : SonaraBackendClient("https://example.invalid") {
        var radio: Result<List<Track>> = Result.success(emptyList())
        var radioDeferred: CompletableDeferred<Result<List<Track>>>? = null
        var related: Result<List<Track>> = Result.success(emptyList())
        var search: Result<List<Track>> = Result.success(emptyList())
        val statuses = mutableMapOf<String, Result<VideoStatus>>()

        override suspend fun getRadioTracks(seedVideoId: String): Result<List<Track>> =
            radioDeferred?.await() ?: radio

        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = related

        override suspend fun searchVideos(query: String, limit: Int): Result<List<Track>> = search

        override suspend fun getVideoStatus(videoId: String): Result<VideoStatus> =
            statuses[videoId] ?: Result.failure(IllegalStateException("missing status"))
    }

    private fun allowed(track: Track): Result<VideoStatus> = Result.success(
        VideoStatus(track.id, MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
    )

    private fun TestScope.installMainDispatcher() =
        StandardTestDispatcher(testScheduler).also { Dispatchers.setMain(it) }

    private companion object {
        fun track(id: String, title: String, artist: String) = Track(
            id = id,
            title = title,
            artist = artist,
            album = "Test Album",
            durationMs = 210_000L,
            artworkUrl = "https://example.invalid/art.jpg"
        )

        val seedA = track("c0000000001", "Seed Song A", "Artist One")
        val trackB = track("c0000000002", "Next Song B", "Artist One")
    }
}
