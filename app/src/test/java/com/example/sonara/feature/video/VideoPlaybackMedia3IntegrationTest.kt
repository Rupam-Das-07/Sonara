package com.example.sonara.feature.video

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoDeliveryMethod
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.model.video.VideoStreamKind
import com.example.sonara.domain.model.video.VideoQuality
import com.example.sonara.domain.ports.VideoExtractionPort
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.client.MediaControllerState
import com.example.sonara.playback.controller.TransitionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import com.example.sonara.playback.service.SonaraPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackMedia3IntegrationTest {
    @Test
    fun `route selects Media3 surface for extracted playback and loading for resolution`() {
        assertTrue(VideoPlaybackRoute.MEDIA3.shouldRenderMedia3Surface())
        assertTrue(VideoPlaybackRoute.RESOLVING.shouldRenderResolutionState())
        assertTrue(!VideoPlaybackRoute.IFRAME.shouldRenderMedia3Surface())
        assertTrue(!VideoPlaybackRoute.IFRAME.shouldRenderResolutionState())
    }

    @Test
    fun `currentMedia3PlayerFlow reactively exposes the media controller player`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(source("c0000000001"))))
            assertNull(viewModel.currentMedia3Player())
            assertNull(viewModel.currentMedia3PlayerFlow.value)

            val dummyPlayer = java.lang.reflect.Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java)
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> 42
                    else -> null
                }
            } as Player

            client.setFakePlayer(dummyPlayer)
            assertTrue(viewModel.currentMedia3Player() === dummyPlayer)
            assertTrue(viewModel.currentMedia3PlayerFlow.value === dummyPlayer)
        } finally {
            Dispatchers.resetMain()
        }
    }
    @Test
    fun `successful extraction is handed to the existing Media3 controller`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(source(track.id))))

            viewModel.seed(track) { }
            advanceUntilIdle()

            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(track.id, client.playedTrack?.id)
            assertEquals(source(track.id).streamUrl, client.playedSource?.streamUrl)
            assertEquals(0, client.clearCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `extraction failure selects the IFrame fallback without starting Media3`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val selected = mutableListOf<String>()
            val viewModel = viewModel(
                client,
                FakeExtractor(Result.failure(IllegalStateException("extract failed")))
            )

            viewModel.seed(track) { selected += it.id }
            advanceUntilIdle()

            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
            assertEquals(listOf(track.id), selected)
            assertNull(client.playedTrack)
            assertEquals(0, client.clearCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `invalid extracted source is rejected before Media3 playback`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val invalid = source(track.id).copy(streamUrl = "")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(invalid)))

            viewModel.seed(track) { }
            advanceUntilIdle()

            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
            assertNull(client.playedTrack)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stale extraction cannot replace a newer video request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val first = track("c0000000001")
            val second = track("c0000000002")
            val firstDeferred = CompletableDeferred<Result<VideoPlaybackSource>>()
            val secondDeferred = CompletableDeferred<Result<VideoPlaybackSource>>()
            val extractor = DeferredExtractor(
                mapOf(first.id to firstDeferred, second.id to secondDeferred)
            )
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, extractor)

            viewModel.seed(first) { }
            runCurrent()
            viewModel.seed(second) { }
            runCurrent()
            firstDeferred.complete(Result.success(source(first.id)))
            secondDeferred.complete(Result.success(source(second.id)))
            advanceUntilIdle()

            assertEquals(second.id, client.playedTrack?.id)
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Media3 preparation error falls back once without retry loop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(source(track.id))))

            viewModel.seed(track) { }
            advanceUntilIdle()
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)

            client.emitError("prepare failed")
            runCurrent()
            client.emitError("prepare failed again")
            runCurrent()

            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
            assertEquals(1, client.clearCount)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `HLS preparation error falls back to progressive before IFrame`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(hlsSource(track.id))))

            viewModel.seed(track) { }
            advanceUntilIdle()
            client.emitError("HLS prepare failed")
            runCurrent()

            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, client.playedSource?.deliveryMethod)
            assertEquals("https://example.invalid/${track.id}.mp4", client.playedSource?.streamUrl)

            client.emitError("progressive prepare failed")
            runCurrent()
            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `DASH playback starts with DASH delivery method and manifest URL`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(dashSource(track.id))))

            viewModel.seed(track) { }
            advanceUntilIdle()

            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(VideoDeliveryMethod.DASH, client.playedSource?.deliveryMethod)
            assertEquals("https://example.invalid/${track.id}.mpd", client.playedSource?.streamUrl)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `DASH preparation error falls back to HLS then progressive then IFrame`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(dashSource(track.id, hasHls = true, hasProgressive = true))))

            viewModel.seed(track) { }
            advanceUntilIdle()
            assertEquals(VideoDeliveryMethod.DASH, client.playedSource?.deliveryMethod)

            // 1. DASH fails -> falls back to HLS
            client.emitError("DASH manifest parse error")
            runCurrent()
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(VideoDeliveryMethod.HLS, client.playedSource?.deliveryMethod)
            assertEquals("https://example.invalid/${track.id}.m3u8", client.playedSource?.streamUrl)

            // 2. HLS fails -> falls back to Progressive
            client.emitError("HLS 404 error")
            runCurrent()
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, client.playedSource?.deliveryMethod)
            assertEquals("https://example.invalid/${track.id}.mp4", client.playedSource?.streamUrl)

            // 3. Progressive fails -> falls back to IFrame
            client.emitError("Progressive playback error")
            runCurrent()
            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `DASH preparation error without HLS falls back directly to progressive then IFrame`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(dashSource(track.id, hasHls = false, hasProgressive = true))))

            viewModel.seed(track) { }
            advanceUntilIdle()
            assertEquals(VideoDeliveryMethod.DASH, client.playedSource?.deliveryMethod)

            // DASH fails -> jumps over absent HLS directly to Progressive
            client.emitError("DASH error")
            runCurrent()
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, client.playedSource?.deliveryMethod)
            assertEquals("https://example.invalid/${track.id}.mp4", client.playedSource?.streamUrl)

            // Progressive fails -> falls back to IFrame
            client.emitError("Progressive error")
            runCurrent()
            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `DASH preparation error with no fallbacks available falls back to IFrame without loop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(dashSource(track.id, hasHls = false, hasProgressive = false))))

            viewModel.seed(track) { }
            advanceUntilIdle()
            assertEquals(VideoDeliveryMethod.DASH, client.playedSource?.deliveryMethod)

            client.emitError("DASH fatal error")
            runCurrent()
            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)

            // Subsequent errors while in IFRAME do not trigger retry loops
            client.emitError("Spurious error")
            runCurrent()
            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `manual quality override is reset when a new video is selected`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val first = track("c0000000001")
            val second = track("c0000000002")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(
                client,
                MappingExtractor(mapOf(first.id to source(first.id), second.id to source(second.id)))
            )

            viewModel.seed(first) { }
            advanceUntilIdle()
            viewModel.selectVideoQuality(VideoQuality.P720)
            assertEquals(VideoQuality.P720, client.appliedQualities.last())

            viewModel.seed(second) { }
            advanceUntilIdle()
            assertEquals(VideoQuality.AUTO, client.appliedQualities.last())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stopping video playback clears the shared Media3 item and route`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(source(track.id))))

            viewModel.seed(track) { }
            advanceUntilIdle()
            viewModel.stop()

            assertEquals(VideoPlaybackRoute.IDLE, viewModel.playback.value.route)
            assertTrue(client.clearCount > 0)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `eligibility denial prevents the Media3 extractor path`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val backend = FakeBackendClient().apply {
                status = Result.success(
                    VideoStatus(track.id, MadeForKidsStatus.UNKNOWN, EmbeddabilityStatus.EMBEDDABLE)
                )
            }
            val client = FakeMediaControllerClient()
            val extractor = RecordingExtractor(Result.success(source(track.id)))
            val viewModel = VideoPlaybackViewModel(
                backendClient = backend,
                videoExtractor = extractor,
                mediaControllerClient = client,
                transitionManager = TransitionManager(),
                ioDispatcher = Dispatchers.Main
            )

            viewModel.seed(track) { }
            advanceUntilIdle()

            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
            assertEquals(0, extractor.calls)
            assertNull(client.playedTrack)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `backgrounding preserves MEDIA3 route and does not pause or clear active media item`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(source(track.id))))

            viewModel.seed(track) { }
            advanceUntilIdle()

            // Verify active Media3 playback state
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(track.id, client.playedTrack?.id)
            assertEquals(0, client.clearCount)
            assertEquals(0, client.pauseCount)
            assertTrue(client.controllerState.value.isPlaying)

            // When the Activity is stopped, stopMedia3ForBackground() is NOT invoked.
            // Simulate lifecycle backgrounding where playback continues in SonaraPlaybackService:
            // Route must remain MEDIA3 (not downgraded to IFRAME)
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(0, client.clearCount)
            assertEquals(0, client.pauseCount)
            assertTrue(client.controllerState.value.isPlaying)
            assertEquals(track.id, viewModel.playback.value.trackId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `returning to foreground preserves MEDIA3 route and playback position`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(source(track.id))))

            viewModel.seed(track) { }
            advanceUntilIdle()
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)

            // Audio playback progresses in background service
            client.updatePosition(42_000L)

            // On returning to foreground, route and position are intact without re-seeding or reloading
            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertEquals(42_000L, viewModel.media3State.value.currentPositionMs)
            assertEquals(0, client.clearCount)
            assertEquals(0, client.pauseCount)
            assertEquals(track.id, client.playedTrack?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `extracted video completion does not advance music queue or record listening history`() {
        val extractedMarker = SonaraPlaybackService.isExtractedVideoMarker(
            tag = MediaControllerClient.MEDIA_KIND_EXTRACTED_VIDEO,
            metadataMediaKind = null,
            requestMediaKind = null
        )
        assertTrue(extractedMarker)

        // Queue must NOT advance when extracted video finishes
        val advancesQueue = SonaraPlaybackService.shouldAdvanceQueueOnEnded(
            isExtractedVideo = true,
            hasCurrentMediaItem = true,
            mediaItemCount = 1,
            playWhenReady = true
        )
        assertFalse(advancesQueue)

        // Listening history must NOT be written when extracted video finishes
        val recordsHistory = SonaraPlaybackService.shouldRecordAutoTransitionHistory(
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            isExtractedVideo = true
        )
        assertFalse(recordsHistory)

        // Native track transition handling must be bypassed
        assertFalse(SonaraPlaybackService.shouldHandleNativeTrackTransition(isExtractedVideo = true))
    }

    @Test
    fun `explicit stopMedia3ForBackground remains functional for direct callers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, FakeExtractor(Result.success(source(track.id))))

            viewModel.seed(track) { }
            advanceUntilIdle()
            viewModel.stopMedia3ForBackground()

            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
            assertEquals(track.id, viewModel.state.value.current?.id)
            assertTrue(client.clearCount > 0)
            assertTrue(client.pauseCount > 0)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `user pause during RESOLVING prevents playback on Media3 readiness`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val deferred = CompletableDeferred<Result<VideoPlaybackSource>>()
            val extractor = DeferredExtractor(mapOf(track.id to deferred))
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, extractor)

            viewModel.seed(track) { }
            runCurrent()
            assertEquals(VideoPlaybackRoute.RESOLVING, viewModel.playback.value.route)
            assertTrue(viewModel.playback.value.userPlayIntent)

            // User taps pause while still resolving
            viewModel.pause()
            assertFalse(viewModel.playback.value.userPlayIntent)

            // Extraction finishes
            deferred.complete(Result.success(source(track.id)))
            advanceUntilIdle()

            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertFalse(viewModel.playback.value.userPlayIntent)
            assertTrue(client.pauseCount > 0)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `user play during RESOLVING preserves play intent on Media3 readiness`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val deferred = CompletableDeferred<Result<VideoPlaybackSource>>()
            val extractor = DeferredExtractor(mapOf(track.id to deferred))
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, extractor)

            viewModel.seed(track) { }
            runCurrent()
            assertEquals(VideoPlaybackRoute.RESOLVING, viewModel.playback.value.route)

            viewModel.play()
            assertTrue(viewModel.playback.value.userPlayIntent)

            deferred.complete(Result.success(source(track.id)))
            advanceUntilIdle()

            assertEquals(VideoPlaybackRoute.MEDIA3, viewModel.playback.value.route)
            assertTrue(viewModel.playback.value.userPlayIntent)
            assertEquals(track.id, client.playedTrack?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `extraction failure during RESOLVING safely falls back to IFRAME without deadlock`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val track = track("c0000000001")
            val deferred = CompletableDeferred<Result<VideoPlaybackSource>>()
            val extractor = DeferredExtractor(mapOf(track.id to deferred))
            val client = FakeMediaControllerClient()
            val viewModel = viewModel(client, extractor)

            viewModel.seed(track) { }
            runCurrent()
            assertEquals(VideoPlaybackRoute.RESOLVING, viewModel.playback.value.route)

            deferred.complete(Result.failure(IllegalStateException("Simulated extraction error")))
            advanceUntilIdle()

            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
            assertEquals("Simulated extraction error", viewModel.playback.value.errorMessage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `youtube embed document respects autoPlay intent and controls suppression`() {
        val autoPlayHtml = youtubeEmbedDocument("lTRiuFIWV54", 1L, 0L, autoPlay = true)
        assertTrue(autoPlayHtml.contains("&autoplay=1"))
        assertTrue(autoPlayHtml.contains("&controls=0"))
        assertTrue(autoPlayHtml.contains("&fs=0"))
        assertTrue(autoPlayHtml.contains("&disablekb=1"))
        assertTrue(autoPlayHtml.contains("&playsinline=1"))
        assertTrue(autoPlayHtml.contains("&iv_load_policy=3"))
        assertTrue(autoPlayHtml.contains("pointer-events: none;"))
        assertTrue(autoPlayHtml.contains("id=\"loading-scrim\""))

        val pausedHtml = youtubeEmbedDocument("lTRiuFIWV54", 1L, 0L, autoPlay = false)
        assertTrue(pausedHtml.contains("&autoplay=0"))
        assertTrue(pausedHtml.contains("&controls=0"))
        assertTrue(pausedHtml.contains("&fs=0"))
        assertTrue(pausedHtml.contains("&disablekb=1"))
        assertTrue(pausedHtml.contains("&playsinline=1"))
        assertTrue(pausedHtml.contains("&iv_load_policy=3"))
        assertTrue(pausedHtml.contains("pointer-events: none;"))
        assertTrue(pausedHtml.contains("id=\"loading-scrim\""))
    }

    @Test
    fun `youtube embed document transitions loading scrim on playback state change and error`() {
        val html = youtubeEmbedDocument("lTRiuFIWV54", 1L, 0L, autoPlay = true)
        // Verify that onPlayerStateChange hides scrim when event.data === 1 (PLAYING)
        assertTrue(html.contains("if (event.data === 1)"))
        assertTrue(html.contains("scrim.className = 'hidden'"))

        // Verify that onPlayerError also hides scrim to prevent permanent black screen
        assertTrue(html.contains("function onPlayerError(event)"))
    }

    private fun viewModel(
        client: FakeMediaControllerClient,
        extractor: VideoExtractionPort
    ): VideoPlaybackViewModel = VideoPlaybackViewModel(
        backendClient = FakeBackendClient(),
        videoExtractor = extractor,
        mediaControllerClient = client,
        transitionManager = TransitionManager(),
        ioDispatcher = Dispatchers.Main
    )

    private class FakeBackendClient : SonaraBackendClient("https://example.invalid") {
        var status: Result<VideoStatus>? = null
        override suspend fun getRadioTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
        override suspend fun searchVideos(query: String, limit: Int): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getVideoStatus(videoId: String): Result<VideoStatus> = status ?: Result.success(
            VideoStatus(videoId, MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
        )
    }

    private class FakeExtractor(
        private val result: Result<VideoPlaybackSource>
    ) : VideoExtractionPort {
        override suspend fun extractPlaybackSource(
            input: String,
            status: VideoStatus
        ): Result<VideoPlaybackSource> = result
    }

    private class RecordingExtractor(
        private val result: Result<VideoPlaybackSource>
    ) : VideoExtractionPort {
        var calls = 0
        override suspend fun extractPlaybackSource(
            input: String,
            status: VideoStatus
        ): Result<VideoPlaybackSource> {
            calls++
            return result
        }
    }

    private class MappingExtractor(
        private val sources: Map<String, VideoPlaybackSource>
    ) : VideoExtractionPort {
        override suspend fun extractPlaybackSource(
            input: String,
            status: VideoStatus
        ): Result<VideoPlaybackSource> = Result.success(sources.getValue(input))
    }

    private class DeferredExtractor(
        private val results: Map<String, CompletableDeferred<Result<VideoPlaybackSource>>>
    ) : VideoExtractionPort {
        override suspend fun extractPlaybackSource(
            input: String,
            status: VideoStatus
        ): Result<VideoPlaybackSource> = withContext(NonCancellable) {
            results.getValue(input).await()
        }
    }

    private class FakeMediaControllerClient : MediaControllerClient(null) {
        private val state = MutableStateFlow(MediaControllerState())
        override val controllerState = state
        private val playerState = MutableStateFlow<Player?>(null)
        override val currentPlayerState = playerState
        var playedTrack: Track? = null
        var playedSource: VideoPlaybackSource? = null
        var clearCount = 0
        var pauseCount = 0
        val appliedQualities = mutableListOf<VideoQuality>()

        fun setFakePlayer(player: Player?) {
            playerState.value = player
        }

        fun updatePosition(positionMs: Long) {
            state.value = state.value.copy(currentPositionMs = positionMs)
        }

        override fun currentPlayer(): Player? = playerState.value

        override fun pause() {
            pauseCount++
            state.value = state.value.copy(isPlaying = false)
        }

        override fun applyVideoQuality(quality: VideoQuality): com.example.sonara.playback.client.VideoQualitySelection {
            appliedQualities += quality
            return com.example.sonara.playback.client.VideoQualitySelection(quality, null)
        }

        override fun playExtractedVideo(
            track: Track,
            source: VideoPlaybackSource,
            generation: Long,
            startPositionMs: Long?,
            autoPlay: Boolean
        ) {
            playedTrack = track
            playedSource = source
            state.value = MediaControllerState(
                isConnected = true,
                currentMediaItem = MediaItem.Builder().setMediaId(track.id).build(),
                playbackState = Player.STATE_READY,
                isPlaying = true
            )
        }

        override fun clearMediaItems() {
            clearCount++
            state.value = MediaControllerState(isConnected = true)
        }

        fun emitError(message: String) {
            val item = playedTrack?.let { MediaItem.Builder().setMediaId(it.id).build() }
            state.value = MediaControllerState(
                isConnected = true,
                currentMediaItem = item,
                playbackState = Player.STATE_IDLE,
                errorMessage = message
            )
        }
    }

    private fun source(videoId: String) = VideoPlaybackSource(
        videoId = videoId,
        streamUrl = "https://example.invalid/$videoId.mp4",
        kind = VideoStreamKind.COMBINED,
        deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
        durationMs = 213_000L
    )

    private fun hlsSource(videoId: String) = VideoPlaybackSource(
        videoId = videoId,
        streamUrl = "https://example.invalid/$videoId.m3u8",
        kind = VideoStreamKind.COMBINED,
        deliveryMethod = VideoDeliveryMethod.HLS,
        progressiveFallbackUrl = "https://example.invalid/$videoId.mp4",
        durationMs = 213_000L
    )

    private fun dashSource(
        videoId: String,
        hasHls: Boolean = true,
        hasProgressive: Boolean = true
    ) = VideoPlaybackSource(
        videoId = videoId,
        streamUrl = "https://example.invalid/$videoId.mpd",
        kind = VideoStreamKind.COMBINED,
        deliveryMethod = VideoDeliveryMethod.DASH,
        dashUrl = "https://example.invalid/$videoId.mpd",
        hlsUrl = if (hasHls) "https://example.invalid/$videoId.m3u8" else null,
        progressiveFallbackUrl = if (hasProgressive) "https://example.invalid/$videoId.mp4" else null,
        durationMs = 213_000L
    )

    private fun track(id: String) = Track(
        id = id,
        title = "Video $id",
        artist = "Synthetic Artist",
        durationMs = 213_000L
    )
}
