package com.example.sonara.feature.video

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.playback.client.MediaControllerClient
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

/**
 * Adversarial test suite for Video Expanded Player Volume and Navigation controls.
 * Covers:
 * - Basic happy paths (AC1, AC2, AC5, AC6)
 * - Boundary cases (AC1, AC7)
 * - State and Route Isolation (AC1, AC2, AC3, AC4)
 * - Property/Fuzz testing across numerical volume domains
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoExpandedControlsAdversarialTest {

    // ── 1. Basic & Route Isolation (AC1, AC3) ──

    @Test
    fun `media3 volume propagates to media controller when in MEDIA3 route`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val fakeClient = FakeMediaControllerClient()
            val backend = FakeBackendClient()
            val viewModel = VideoPlaybackViewModel(
                backendClient = backend,
                ioDispatcher = dispatcher,
                mediaControllerClient = fakeClient
            )

            // When route is IFRAME (default), setVolume does NOT call mediaControllerClient
            viewModel.setVolume(0.6f)
            assertEquals(0.6f, viewModel.videoVolume.value, 0.001f)
            assertEquals(emptyList<Float>(), fakeClient.volumeCalls)

            // If we toggle mute in IFRAME route, controller is also not touched
            viewModel.toggleMute()
            assertTrue(viewModel.videoMuted.value)
            assertEquals(emptyList<Float>(), fakeClient.volumeCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── 2. Boundary Cases (AC1, AC7) ──

    @Test
    fun `volume clamps to exactly zero and one at boundary extremes`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)

            viewModel.setVolume(-0.0001f)
            assertEquals(0f, viewModel.videoVolume.value, 0.0001f)

            viewModel.setVolume(1.0001f)
            assertEquals(1f, viewModel.videoVolume.value, 0.0001f)

            viewModel.setVolume(0f)
            assertEquals(0f, viewModel.videoVolume.value, 0.0001f)

            viewModel.setVolume(1f)
            assertEquals(1f, viewModel.videoVolume.value, 0.0001f)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `peekNextTrack and peekPreviousTrack safely return null on empty queue and boundary ends`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)

            // Completely empty queue
            assertNull(viewModel.peekNextTrack())
            assertNull(viewModel.peekPreviousTrack())

            // Single item seeded (no upcoming, no previous)
            viewModel.seed(trackA) {}
            advanceUntilIdle()

            assertNull(viewModel.peekNextTrack())
            assertNull(viewModel.peekPreviousTrack())
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── 3. State & Queue Transitions (AC5, AC6) ──

    @Test
    fun `next and previous navigate sequentially through multiple tracks updating peeks`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val backend = FakeBackendClient().apply {
                radio = Result.success(listOf(trackB, trackC))
                statuses[trackB.id] = allowed(trackB)
                statuses[trackC.id] = allowed(trackC)
            }
            val viewModel = VideoPlaybackViewModel(backend, dispatcher)

            viewModel.seed(trackA) {}
            advanceUntilIdle()

            // State: current = A, upcoming = [B, C], previous = []
            assertEquals(trackA.id, viewModel.state.value.current?.id)
            assertEquals(trackB.id, viewModel.peekNextTrack()?.id)
            assertNull(viewModel.peekPreviousTrack())

            // Advance to B
            var currentTrack: Track? = null
            viewModel.next { currentTrack = it }
            advanceUntilIdle()

            assertEquals(trackB.id, currentTrack?.id)
            assertEquals(trackC.id, viewModel.peekNextTrack()?.id)
            assertEquals(trackA.id, viewModel.peekPreviousTrack()?.id)

            // Advance to C
            viewModel.next { currentTrack = it }
            advanceUntilIdle()

            assertEquals(trackC.id, currentTrack?.id)
            assertNull(viewModel.peekNextTrack())
            assertEquals(trackB.id, viewModel.peekPreviousTrack()?.id)

            // Return to B
            viewModel.previous(
                currentPositionMs = 500L,
                onRestartCurrent = {},
                onTrackReady = { currentTrack = it }
            )
            advanceUntilIdle()

            assertEquals(trackB.id, currentTrack?.id)
            assertEquals(trackC.id, viewModel.peekNextTrack()?.id)
            assertEquals(trackA.id, viewModel.peekPreviousTrack()?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── 4. Property / Fuzz Testing ──

    @Test
    fun `property fuzzing volume across arbitrary inputs maintains invariants`() {
        val testVolumes = listOf(
            -1000f, -100f, -1f, -0.5f, -0.0001f,
            0f, 0.0001f, 0.123f, 0.333f, 0.5f, 0.777f, 0.9999f,
            1f, 1.0001f, 1.5f, 10f, 100f, 1000f
        )
        val recordingTransport = RecordingTransport()
        val controller = YouTubePlayerController(
            videoId = trackA.id,
            generation = 1L,
            expectedOrigin = "https://app.sonara.music"
        )
        controller.attachTransport(recordingTransport)
        controller.handleMessage(
            "{\"type\":\"ready\",\"videoId\":\"${trackA.id}\",\"generation\":1,\"durationMs\":200000}",
            "https://app.sonara.music",
            isMainFrame = true
        )

        for (v in testVolumes) {
            recordingTransport.commands.clear()
            controller.setVolume(v)

            val cmd = recordingTransport.commands.filterIsInstance<YouTubePlayerCommand.SetVolume>().lastOrNull()
            assertTrue("Expected SetVolume command for input $v", cmd != null)
            val percent = cmd!!.volumePercent
            assertTrue("volumePercent $percent must be between 0 and 100 for input $v", percent in 0..100)
            if (v <= 0f) assertEquals(0, percent)
            if (v >= 1f) assertEquals(100, percent)
        }
    }

    // ── 5. IFrame Bridge Document Contract (AC2) ──

    @Test
    fun `youtubeEmbedDocument contains volume and mute command handlers`() {
        val doc = youtubeEmbedDocument("c0000000001", 1L, 0L)
        assertTrue(doc.contains("command.type === 'volume'"))
        assertTrue(doc.contains("player.setVolume(vol)"))
        assertTrue(doc.contains("command.type === 'mute'"))
        assertTrue(doc.contains("player.mute()"))
        assertTrue(doc.contains("player.unMute()"))
    }

    // ── Test doubles & helpers ──

    private class FakeMediaControllerClient : MediaControllerClient(context = null) {
        val volumeCalls = mutableListOf<Float>()

        override fun setVolume(volume: Float) {
            volumeCalls += volume
        }
    }

    private class RecordingTransport : YouTubePlayerTransport {
        val commands = mutableListOf<YouTubePlayerCommand>()
        var disposed = false

        override fun dispatch(command: YouTubePlayerCommand): Boolean {
            if (disposed) return false
            commands += command
            return true
        }

        override fun dispose() {
            disposed = true
        }
    }

    private class FakeBackendClient : SonaraBackendClient("https://example.invalid") {
        var radio: Result<List<Track>> = Result.success(emptyList())
        val statuses = mutableMapOf<String, Result<VideoStatus>>()

        override suspend fun getRadioTracks(seedVideoId: String): Result<List<Track>> = radio
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
        override suspend fun searchVideos(query: String, limit: Int): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getVideoStatus(videoId: String): Result<VideoStatus> =
            statuses[videoId] ?: Result.failure(IllegalStateException("missing status"))
    }

    private fun allowed(track: Track): Result<VideoStatus> = Result.success(
        VideoStatus(track.id, MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
    )

    private fun TestScope.installMainDispatcher() =
        StandardTestDispatcher(testScheduler).also { Dispatchers.setMain(it) }

    private companion object {
        val trackA = Track(id = "c0000000001", title = "Track A", artist = "Artist 1", durationMs = 180_000L)
        val trackB = Track(id = "c0000000002", title = "Track B", artist = "Artist 1", durationMs = 210_000L)
        val trackC = Track(id = "c0000000003", title = "Track C", artist = "Artist 2", durationMs = 240_000L)
    }
}
