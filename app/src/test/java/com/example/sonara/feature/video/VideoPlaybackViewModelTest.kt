package com.example.sonara.feature.video

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoExtractionResult
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.ports.VideoExtractionPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackViewModelTest {
    @Test
    fun `ended advancement waits for replenishment before retrying`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val gate = CompletableDeferred<Result<List<Track>>>()
            val next = track("c0000000002", "Next Song", "Artist One")
            val client = FakeBackendClient().apply {
                radioDeferred = gate
                statuses[next.id] = allowed(next)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            val played = mutableListOf<String>()

            viewModel.seed(seed) { played += it.id }
            runCurrent()
            assertTrue(viewModel.state.value.isReplenishing)

            viewModel.onEnded { played += it.id }
            runCurrent()
            assertEquals(listOf(seed.id), played)
            assertFalse(viewModel.state.value.noCandidatesAvailable)

            gate.complete(Result.success(listOf(next)))
            advanceUntilIdle()

            assertEquals(listOf(seed.id, next.id), played)
            assertEquals(next.id, viewModel.state.value.current?.id)
            assertFalse(viewModel.state.value.isReplenishing)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `empty recommendations terminate safely and publish actual empty result`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                radio = Result.failure(IllegalStateException("radio unavailable"))
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            val played = mutableListOf<String>()

            viewModel.seed(seed) { played += it.id }
            advanceUntilIdle()
            viewModel.onEnded { played += it.id }
            advanceUntilIdle()

            assertEquals(listOf(seed.id), played)
            assertFalse(viewModel.state.value.isReplenishing)
            assertTrue(viewModel.state.value.noCandidatesAvailable)
            assertEquals(seed.id, viewModel.state.value.current?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `repeated ended emissions cause only one effective advancement`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val next = track("c0000000002", "Next Song", "Artist One")
            val client = FakeBackendClient().apply {
                radio = Result.success(listOf(next))
                statuses[next.id] = allowed(next)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            val played = mutableListOf<String>()

            viewModel.seed(seed) { played += it.id }
            advanceUntilIdle()
            viewModel.onEnded { played += it.id }
            viewModel.onEnded { played += it.id }
            advanceUntilIdle()

            assertEquals(listOf(seed.id, next.id), played)
            assertEquals(next.id, viewModel.state.value.current?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `updateIFramePosition retains position for specified video`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)
            viewModel.updateIFramePosition("video123", 15_000L, 100_000L)
            assertEquals(15_000L, viewModel.getIFramePosition("video123"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `different video does not inherit previous video position`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)
            viewModel.updateIFramePosition("videoA", 45_000L, 120_000L)
            assertEquals(45_000L, viewModel.getIFramePosition("videoA"))
            assertEquals(0L, viewModel.getIFramePosition("videoB"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `negative position is rejected and not retained`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)
            viewModel.updateIFramePosition("video123", -5_000L, 100_000L)
            assertEquals(0L, viewModel.getIFramePosition("video123"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `position exceeding duration is clamped to duration`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)
            viewModel.updateIFramePosition("video123", 250_000L, 200_000L)
            assertEquals(200_000L, viewModel.getIFramePosition("video123"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `blank video id is rejected`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)
            viewModel.updateIFramePosition("   ", 10_000L, 100_000L)
            assertEquals(0L, viewModel.getIFramePosition("   "))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `onEnded clears retained position for ended video`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val next = track("c0000000002", "Next Song", "Artist One")
            val client = FakeBackendClient().apply {
                radio = Result.success(listOf(next))
                statuses[next.id] = allowed(next)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            advanceUntilIdle()

            viewModel.updateIFramePosition(seed.id, 50_000L, seed.durationMs)
            assertEquals(50_000L, viewModel.getIFramePosition(seed.id))

            viewModel.onEnded {}
            advanceUntilIdle()

            assertEquals(0L, viewModel.getIFramePosition(seed.id))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `restartCurrent clears retained position for restarted video`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)
            viewModel.seed(seed) {}
            advanceUntilIdle()

            viewModel.updateIFramePosition(seed.id, 10_000L, seed.durationMs)
            assertEquals(10_000L, viewModel.getIFramePosition(seed.id))

            var restarted = false
            viewModel.previous(
                currentPositionMs = 5_000L,
                onRestartCurrent = { restarted = true },
                onTrackReady = {}
            )
            assertTrue(restarted)
            assertEquals(0L, viewModel.getIFramePosition(seed.id))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `video volume clamps to valid range and exposes in state flow`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)
            assertEquals(1f, viewModel.videoVolume.value)

            viewModel.setVolume(0.42f)
            assertEquals(0.42f, viewModel.videoVolume.value, 0.001f)

            viewModel.setVolume(-0.5f)
            assertEquals(0f, viewModel.videoVolume.value)

            viewModel.setVolume(1.5f)
            assertEquals(1f, viewModel.videoVolume.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `toggleMute updates videoMuted state and unmuting restores volume`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val viewModel = VideoPlaybackViewModel(FakeBackendClient(), dispatcher)
            assertFalse(viewModel.videoMuted.value)

            val muted = viewModel.toggleMute()
            assertTrue(muted)
            assertTrue(viewModel.videoMuted.value)

            val unmuted = viewModel.toggleMute()
            assertFalse(unmuted)
            assertFalse(viewModel.videoMuted.value)

            // Setting volume while muted unmutes if volume > 0
            viewModel.toggleMute()
            assertTrue(viewModel.videoMuted.value)
            viewModel.setVolume(0.8f)
            assertFalse(viewModel.videoMuted.value)
            assertEquals(0.8f, viewModel.videoVolume.value, 0.001f)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `peekNextTrack and peekPreviousTrack reflect queue state without mutating`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val nextTrack = track("c0000000002", "Next Song", "Artist One")
            val client = FakeBackendClient().apply {
                radio = Result.success(listOf(nextTrack))
                statuses[nextTrack.id] = allowed(nextTrack)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)

            assertNull(viewModel.peekNextTrack())
            assertNull(viewModel.peekPreviousTrack())

            viewModel.seed(seed) {}
            advanceUntilIdle()

            assertEquals(nextTrack.id, viewModel.peekNextTrack()?.id)
            assertNull(viewModel.peekPreviousTrack())

            var transitionedTrack: Track? = null
            viewModel.next { transitionedTrack = it }
            advanceUntilIdle()

            assertEquals(nextTrack.id, transitionedTrack?.id)
            assertNull(viewModel.peekNextTrack())
            assertEquals(seed.id, viewModel.peekPreviousTrack()?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `previous switches to previous track when position is at or below restart threshold`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val nextTrack = track("c0000000002", "Next Song", "Artist One")
            val client = FakeBackendClient().apply {
                radio = Result.success(listOf(nextTrack))
                statuses[nextTrack.id] = allowed(nextTrack)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            advanceUntilIdle()

            viewModel.next {}
            advanceUntilIdle()
            assertEquals(nextTrack.id, viewModel.state.value.current?.id)

            var restarted = false
            var readyTrack: Track? = null
            viewModel.previous(
                currentPositionMs = 2_000L,
                onRestartCurrent = { restarted = true },
                onTrackReady = { readyTrack = it }
            )
            advanceUntilIdle()

            assertFalse(restarted)
            assertEquals(seed.id, readyTrack?.id)
            assertEquals(seed.id, viewModel.state.value.current?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `view count from metadata extraction updates activeViewCount flow`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                statuses[seed.id] = allowed(seed)
            }
            val extractor = FakeExtractionPort(
                extractionResult = Result.success(
                    VideoExtractionResult(
                        videoId = seed.id,
                        viewCount = 1_500_000L
                    )
                )
            )
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher,
                videoExtractor = extractor
            )

            viewModel.seed(seed) {}
            advanceUntilIdle()

            assertEquals(1_500_000L, viewModel.activeViewCount.value)
            assertEquals(1_500_000L, viewModel.getViewCount(seed.id))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `switching video immediately resets activeViewCount and adopts cached count`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val track2 = track("c0000000002", "Song Two", "Artist Two")
            val client = FakeBackendClient().apply {
                statuses[seed.id] = allowed(seed)
                statuses[track2.id] = allowed(track2)
                radio = Result.success(listOf(track2))
            }
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher
            )
            viewModel.updateViewCount(track2.id, 2_400_000L)

            viewModel.seed(seed) {}
            advanceUntilIdle()
            assertNull(viewModel.activeViewCount.value)

            viewModel.updateViewCount(seed.id, 800_000L)
            assertEquals(800_000L, viewModel.activeViewCount.value)

            viewModel.next {}
            advanceUntilIdle()
            assertEquals(2_400_000L, viewModel.activeViewCount.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `metadata extraction failure does not disrupt playback and keeps activeViewCount null`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                statuses[seed.id] = allowed(seed)
            }
            val extractor = FakeExtractionPort(
                extractionResult = Result.failure(RuntimeException("Network error"))
            )
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher,
                videoExtractor = extractor
            )

            var readyTrack: Track? = null
            viewModel.seed(seed) { readyTrack = it }
            advanceUntilIdle()

            assertEquals(seed.id, readyTrack?.id)
            assertNull(viewModel.activeViewCount.value)
            assertNull(viewModel.getViewCount(seed.id))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stop clears activeViewCount`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient()
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            viewModel.updateViewCount(seed.id, 50_000L)
            assertEquals(50_000L, viewModel.activeViewCount.value)

            viewModel.stop()
            assertNull(viewModel.activeViewCount.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeExtractionPort(
        var playbackSourceResult: Result<VideoPlaybackSource> = Result.failure(IllegalStateException("no source")),
        var extractionResult: Result<VideoExtractionResult> = Result.failure(IllegalStateException("no extract"))
    ) : VideoExtractionPort {
        override suspend fun extract(input: String, status: VideoStatus): Result<VideoExtractionResult> = extractionResult

        override suspend fun extractPlaybackSource(input: String, status: VideoStatus): Result<VideoPlaybackSource> =
            playbackSourceResult
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
        val seed = track("c0000000001", "Seed Song", "Artist One")

        fun track(id: String, title: String, artist: String) = Track(
            id = id,
            title = title,
            artist = artist,
            durationMs = 210_000L
        )
    }
}
