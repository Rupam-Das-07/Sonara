package com.example.sonara.feature.video

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoExtractionResult
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.ports.VideoExtractionPort
import com.example.sonara.playback.controller.PlaybackRouteCoordinator
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
import org.junit.Assert.assertNotNull
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

    @Test
    fun `testNext_whenQueueHasItems_advancesToNextTrackAndActivates`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val nextTrack = track("c0000000002", "Next Song", "Artist Two")
            val client = FakeBackendClient().apply {
                radio = Result.success(listOf(nextTrack))
                statuses[seed.id] = allowed(seed)
                statuses[nextTrack.id] = allowed(nextTrack)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            advanceUntilIdle()

            var readyTrack: Track? = null
            viewModel.next { readyTrack = it }
            advanceUntilIdle()

            assertEquals(nextTrack.id, readyTrack?.id)
            assertEquals(nextTrack.id, viewModel.state.value.current?.id)
            assertTrue(viewModel.playback.value.userPlayIntent)
            assertEquals(listOf(seed.id), viewModel.state.value.previous.map { it.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `testNext_whenQueueEmpty_keepsCurrentVideoPlayingDuringRecommendationFetch`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val gate = CompletableDeferred<Result<List<Track>>>()
            val nextTrack = track("c0000000002", "Next Song", "Artist Two")
            val client = FakeBackendClient().apply {
                radioDeferred = gate
                statuses[seed.id] = allowed(seed)
                statuses[nextTrack.id] = allowed(nextTrack)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            runCurrent()

            var readyTrack: Track? = null
            viewModel.next { readyTrack = it }
            runCurrent()

            // During fetch, current video must NOT be paused and current track is still seed
            assertNull(readyTrack)
            assertEquals(seed.id, viewModel.state.value.current?.id)
            assertTrue(viewModel.playback.value.userPlayIntent)
            assertTrue(viewModel.state.value.isReplenishing)

            gate.complete(Result.success(listOf(nextTrack)))
            advanceUntilIdle()

            // After completion, next video is activated
            assertEquals(nextTrack.id, readyTrack?.id)
            assertEquals(nextTrack.id, viewModel.state.value.current?.id)
            assertTrue(viewModel.playback.value.userPlayIntent)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `testNext_whenReplenishmentInFlight_doesNotCancelExistingRequest`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val gate = CompletableDeferred<Result<List<Track>>>()
            val nextTrack = track("c0000000002", "Next Song", "Artist Two")
            val client = FakeBackendClient().apply {
                radioDeferred = gate
                statuses[seed.id] = allowed(seed)
                statuses[nextTrack.id] = allowed(nextTrack)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            runCurrent()

            assertTrue(gate.isActive)

            var readyTrack: Track? = null
            viewModel.next { readyTrack = it }
            runCurrent()

            // The in-flight gate was NOT cancelled by pressing Next
            assertFalse(gate.isCancelled)
            assertTrue(gate.isActive)

            gate.complete(Result.success(listOf(nextTrack)))
            advanceUntilIdle()

            assertEquals(nextTrack.id, readyTrack?.id)
            assertEquals(nextTrack.id, viewModel.state.value.current?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `testNext_whenReplenishmentFails_keepsCurrentVideoPlayingAndEmitsNotice`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                radio = Result.failure(IllegalStateException("Network failure"))
                statuses[seed.id] = allowed(seed)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            advanceUntilIdle()

            var readyTrack: Track? = null
            viewModel.next { readyTrack = it }
            advanceUntilIdle()

            // Current video remains playing, not paused
            assertNull(readyTrack)
            assertEquals(seed.id, viewModel.state.value.current?.id)
            assertTrue(viewModel.playback.value.userPlayIntent)
            assertTrue(viewModel.state.value.noCandidatesAvailable)
            assertEquals("No next video recommendations available", viewModel.feedbackMessage.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `testNext_whenNoCandidatesAvailable_doesNotLeavePlayerPaused`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val client = FakeBackendClient().apply {
                radio = Result.success(emptyList())
                statuses[seed.id] = allowed(seed)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            advanceUntilIdle()

            viewModel.play()
            assertTrue(viewModel.playback.value.userPlayIntent)

            viewModel.next {}
            advanceUntilIdle()

            assertEquals(seed.id, viewModel.state.value.current?.id)
            assertTrue(viewModel.playback.value.userPlayIntent)
            assertEquals("No next video recommendations available", viewModel.feedbackMessage.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `testNext_repeatedRapidClicks_doNotCorruptQueueOrDuplicateRequests`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val gate = CompletableDeferred<Result<List<Track>>>()
            val track2 = track("c0000000002", "Song Two", "Artist Two")
            val track3 = track("c0000000003", "Song Three", "Artist Three")
            val client = FakeBackendClient().apply {
                radioDeferred = gate
                statuses[seed.id] = allowed(seed)
                statuses[track2.id] = allowed(track2)
                statuses[track3.id] = allowed(track3)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            runCurrent()

            var readyCount = 0
            val onReady: (Track) -> Unit = { readyCount++ }

            // 5 rapid clicks while recommendations are loading
            repeat(5) {
                viewModel.next(onReady)
            }
            runCurrent()

            assertFalse(gate.isCancelled)

            gate.complete(Result.success(listOf(track2, track3)))
            advanceUntilIdle()

            // Exactly 1 transition occurred to track2
            assertEquals(1, readyCount)
            assertEquals(track2.id, viewModel.state.value.current?.id)
            assertEquals(listOf(track3.id), viewModel.state.value.upcoming.map { it.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `testNext_staleRecommendationResult_doesNotOverrideNewerTransition`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val gate = CompletableDeferred<Result<List<Track>>>()
            val staleTrack = track("c0000000002", "Stale Song", "Artist Two")
            val newSeed = track("c0000000099", "New Seed", "Artist New")
            val client = FakeBackendClient().apply {
                radioDeferred = gate
                statuses[seed.id] = allowed(seed)
                statuses[staleTrack.id] = allowed(staleTrack)
                statuses[newSeed.id] = allowed(newSeed)
            }
            val viewModel = VideoPlaybackViewModel(client, dispatcher)
            viewModel.seed(seed) {}
            runCurrent()

            var readyTrack: Track? = null
            viewModel.next { readyTrack = it }
            runCurrent()

            // User changes to a new seed track while recommendation for old seed is still fetching
            viewModel.seed(newSeed) { readyTrack = it }
            runCurrent()

            assertEquals(newSeed.id, viewModel.state.value.current?.id)

            // Old gate completes with stale track
            gate.complete(Result.success(listOf(staleTrack)))
            advanceUntilIdle()

            // Stale track must NOT override newSeed
            assertEquals(newSeed.id, viewModel.state.value.current?.id)
            assertFalse(viewModel.state.value.upcoming.any { it.id == staleTrack.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Route Coordinator & Video Bridge Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `queue availability and seeding update route coordinator`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val routeCoordinator = PlaybackRouteCoordinator()
            var routeChangedCalls = 0
            routeCoordinator.onRouteChanged = { routeChangedCalls++ }

            val client = FakeBackendClient().apply {
                statuses[seed.id] = allowed(seed)
            }
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher,
                playbackRouteCoordinator = routeCoordinator
            )

            // When initialized, delegate is registered
            assertEquals(1, routeChangedCalls)

            // Seed updates queue and notifies coordinator
            viewModel.seed(seed) {}
            runCurrent()
            assertTrue(routeChangedCalls > 1)
            assertEquals(seed.id, routeCoordinator.currentVideoTrack()?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `recommendation replenishment updates Next availability`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val routeCoordinator = PlaybackRouteCoordinator()
            val gate = CompletableDeferred<Result<List<Track>>>()
            val nextTrack = track("c0000000002", "Next Song", "Artist Two")
            val client = FakeBackendClient().apply {
                radioDeferred = gate
                statuses[seed.id] = allowed(seed)
                statuses[nextTrack.id] = allowed(nextTrack)
            }
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher,
                playbackRouteCoordinator = routeCoordinator
            )

            viewModel.seed(seed) {}
            runCurrent()

            // When current is set and not exhausted, canAdvance is true
            assertTrue(routeCoordinator.canVideoAdvance())

            // Complete gate with candidate
            gate.complete(Result.success(listOf(nextTrack)))
            advanceUntilIdle()

            assertTrue(routeCoordinator.canVideoAdvance())
            assertEquals(1, viewModel.state.value.upcoming.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `repeat mode changes update Next availability and notify route coordinator`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val routeCoordinator = PlaybackRouteCoordinator()
            var notificationCount = 0
            routeCoordinator.onRouteChanged = { notificationCount++ }

            val client = FakeBackendClient().apply {
                statuses[seed.id] = allowed(seed)
            }
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher,
                playbackRouteCoordinator = routeCoordinator
            )

            viewModel.seed(seed) {}
            runCurrent()

            val initialNotifications = notificationCount
            viewModel.toggleRepeat() // ALL
            assertTrue(notificationCount > initialNotifications)
            assertTrue(routeCoordinator.canVideoAdvance())

            viewModel.toggleRepeat() // ONE
            assertTrue(routeCoordinator.canVideoAdvance())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `previous and next callbacks on coordinator invoke existing video queue methods`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val routeCoordinator = PlaybackRouteCoordinator()
            val second = track("c0000000002", "Second Track", "Artist Two")
            val client = FakeBackendClient().apply {
                radio = Result.success(listOf(second))
                statuses[seed.id] = allowed(seed)
                statuses[second.id] = allowed(second)
            }
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher,
                playbackRouteCoordinator = routeCoordinator
            )

            val played = mutableListOf<String>()
            viewModel.seed(seed) { played += it.id }
            advanceUntilIdle()

            assertEquals(seed.id, viewModel.state.value.current?.id)

            // Invoke Next via coordinator
            routeCoordinator.onVideoSeekToNext()
            advanceUntilIdle()

            assertEquals(second.id, viewModel.state.value.current?.id)
            assertTrue(played.contains(second.id))

            // Invoke Previous via coordinator (>3000ms threshold restart or navigate)
            routeCoordinator.onVideoSeekToPrevious()
            advanceUntilIdle()

            // After previous (when pos <= 3s), returns to seed
            assertEquals(seed.id, viewModel.state.value.current?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `callback registration and cleanup work safely on viewModel lifecycle`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val routeCoordinator = PlaybackRouteCoordinator()
            val client = FakeBackendClient().apply {
                statuses[seed.id] = allowed(seed)
            }
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher,
                playbackRouteCoordinator = routeCoordinator
            )

            viewModel.seed(seed) {}
            runCurrent()

            assertNotNull(routeCoordinator.currentVideoTrack())

            // Simulate ViewModel onCleared
            val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
            onClearedMethod.isAccessible = true
            onClearedMethod.invoke(viewModel)

            // After onCleared, delegate is unregistered cleanly
            assertFalse(routeCoordinator.isVideoActive)
            assertNull(routeCoordinator.currentVideoTrack())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `IFrame playback restrictions remain intact when route is iframe`() = runTest {
        val dispatcher = installMainDispatcher()
        try {
            val routeCoordinator = PlaybackRouteCoordinator()
            val client = FakeBackendClient()
            val viewModel = VideoPlaybackViewModel(
                backendClient = client,
                ioDispatcher = dispatcher,
                playbackRouteCoordinator = routeCoordinator
            )

            // Activate IFrame
            routeCoordinator.setIFrameVideoActive(true)
            assertTrue(routeCoordinator.isIFrameVideoActive)

            // When IFrame active, video Media3 delegate is not active
            assertFalse(routeCoordinator.isVideoActive)

            routeCoordinator.setIFrameVideoActive(false)
            assertFalse(routeCoordinator.isIFrameVideoActive)
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
            if (radioDeferred != null && seedVideoId == seed.id) radioDeferred!!.await() else radio

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
