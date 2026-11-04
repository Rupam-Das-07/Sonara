package com.example.sonara.playback.service

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionResult
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.feature.video.VideoPlaybackRoute
import com.example.sonara.feature.video.VideoPlaybackIntegrationState
import com.example.sonara.feature.video.VideoPlaybackViewModel
import com.example.sonara.playback.controller.PlaybackQueueEngine
import com.example.sonara.playback.controller.PlaybackRouteCoordinator
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class IFrameAudioSuppressionTest {

    private fun createFakePlayer(
        underlyingAvailableCommands: Set<Int> = setOf(Player.COMMAND_PLAY_PAUSE),
        onPlayCalled: (() -> Unit)? = null,
        onSetPlayWhenReadyCalled: ((Boolean) -> Unit)? = null
    ): Player {
        val defaultCommands = Player.Commands.Builder().build()

        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAvailableCommands" -> defaultCommands
                "getMediaMetadata" -> androidx.media3.common.MediaMetadata.EMPTY
                "getCurrentMediaItem" -> null
                "isCommandAvailable" -> {
                    val cmd = args[0] as Int
                    underlyingAvailableCommands.contains(cmd)
                }
                "play" -> {
                    onPlayCalled?.invoke()
                    null
                }
                "setPlayWhenReady" -> {
                    val pwr = args[0] as Boolean
                    onSetPlayWhenReadyCalled?.invoke(pwr)
                    null
                }
                "hasNextMediaItem" -> false
                "seekToNext", "seekToNextMediaItem", "seekToPrevious", "seekToPreviousMediaItem" -> null
                "addListener", "removeListener" -> null
                "equals" -> false
                "hashCode" -> 0
                "toString" -> "FakePlayer"
                else -> {
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Float::class.javaPrimitiveType -> 0f
                        else -> null
                    }
                }
            }
        } as Player
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. SonaraPlaybackService command request resolution tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `resolvePlayerCommandRequestResult skips Play command when IFrame video is active`() {
        val result = SonaraPlaybackService.resolvePlayerCommandRequestResult(
            playerCommand = Player.COMMAND_PLAY_PAUSE,
            isIFrameVideoActive = true,
            defaultResultSupplier = { SessionResult.RESULT_SUCCESS }
        )
        assertEquals(SessionResult.RESULT_INFO_SKIPPED, result)
    }

    @Test
    fun `resolvePlayerCommandRequestResult allows Play command when returning to audio`() {
        val result = SonaraPlaybackService.resolvePlayerCommandRequestResult(
            playerCommand = Player.COMMAND_PLAY_PAUSE,
            isIFrameVideoActive = false,
            defaultResultSupplier = { SessionResult.RESULT_SUCCESS }
        )
        assertEquals(SessionResult.RESULT_SUCCESS, result)
    }

    @Test
    fun `resolvePlayerCommandRequestResult skips SeekToNext and SeekToPrevious during IFrame`() {
        val nextResult = SonaraPlaybackService.resolvePlayerCommandRequestResult(
            playerCommand = Player.COMMAND_SEEK_TO_NEXT,
            isIFrameVideoActive = true,
            defaultResultSupplier = { SessionResult.RESULT_SUCCESS }
        )
        assertEquals(SessionResult.RESULT_INFO_SKIPPED, nextResult)

        val prevResult = SonaraPlaybackService.resolvePlayerCommandRequestResult(
            playerCommand = Player.COMMAND_SEEK_TO_PREVIOUS,
            isIFrameVideoActive = true,
            defaultResultSupplier = { SessionResult.RESULT_SUCCESS }
        )
        assertEquals(SessionResult.RESULT_INFO_SKIPPED, prevResult)
    }

    @Test
    fun `shouldSuppressPlaybackCommand evaluates strictly based on IFrame video active state`() {
        assertTrue(SonaraPlaybackService.shouldSuppressPlaybackCommand(isIFrameVideoActive = true))
        assertFalse(SonaraPlaybackService.shouldSuppressPlaybackCommand(isIFrameVideoActive = false))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. SonaraForwardingPlayer suppression & command availability tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `forwardingPlayer suppresses play and setPlayWhenReady while IFrame video is active`() {
        var playCount = 0
        var setPlayWhenReadyCount = 0

        val fakePlayer = createFakePlayer(
            underlyingAvailableCommands = setOf(Player.COMMAND_PLAY_PAUSE),
            onPlayCalled = { playCount++ },
            onSetPlayWhenReadyCalled = { if (it) setPlayWhenReadyCount++ }
        )

        var isIFrame = true
        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            isIFrameVideoActive = { isIFrame },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        // While IFrame is active, play calls must be suppressed
        forwardingPlayer.play()
        assertEquals(0, playCount)

        forwardingPlayer.setPlayWhenReady(true)
        assertEquals(0, setPlayWhenReadyCount)

        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))

        // When returning to audio (or closing video), normal controls are restored
        isIFrame = false
        forwardingPlayer.play()
        assertEquals(1, playCount)

        forwardingPlayer.setPlayWhenReady(true)
        assertEquals(1, setPlayWhenReadyCount)

        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun `forwardingPlayer seek methods are suppressed while IFrame is active`() {
        var nextCount = 0
        var prevCount = 0

        val fakePlayer = createFakePlayer()
        var isIFrame = true

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            isIFrameVideoActive = { isIFrame },
            onSeekToNext = { nextCount++ },
            onSeekToPrevious = { prevCount++ }
        )

        forwardingPlayer.seekToNext()
        forwardingPlayer.seekToNextMediaItem()
        forwardingPlayer.seekToPrevious()
        forwardingPlayer.seekToPreviousMediaItem()

        assertEquals(0, nextCount)
        assertEquals(0, prevCount)

        isIFrame = false
        forwardingPlayer.seekToNext()
        forwardingPlayer.seekToPrevious()

        assertEquals(1, nextCount)
        assertEquals(1, prevCount)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. PlaybackRouteCoordinator & VideoPlaybackViewModel integration tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `PlaybackRouteCoordinator transitions and notifies onRouteChanged`() {
        val coordinator = PlaybackRouteCoordinator()
        assertFalse(coordinator.isIFrameVideoActive)

        var routeChangedCount = 0
        coordinator.onRouteChanged = { routeChangedCount++ }

        // Transition: Audio -> IFrame
        coordinator.setIFrameVideoActive(true)
        assertTrue(coordinator.isIFrameVideoActive)
        assertEquals(1, routeChangedCount)

        // Idempotent call should not trigger listener again
        coordinator.setIFrameVideoActive(true)
        assertEquals(1, routeChangedCount)

        // Transition: IFrame -> Audio (or closed)
        coordinator.setIFrameVideoActive(false)
        assertFalse(coordinator.isIFrameVideoActive)
        assertEquals(2, routeChangedCount)

        // Reset
        coordinator.reset()
        assertFalse(coordinator.isIFrameVideoActive)
        assertEquals(2, routeChangedCount)
    }

    @Test
    fun `VideoPlaybackViewModel coordinates IFrame and Media3 routes without stale suppression`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val coordinator = PlaybackRouteCoordinator()
            val backendClient = SonaraBackendClient()

            val viewModel = VideoPlaybackViewModel(
                backendClient = backendClient,
                playbackRouteCoordinator = coordinator
            )

            // Initial state is IDLE -> suppression is false
            assertFalse(coordinator.isIFrameVideoActive)

            val testTrack = Track(
                id = "vid_test_123",
                title = "Test Video",
                artist = "Artist"
            )

            // Seed video without extractor -> falls back directly to IFRAME route
            viewModel.seed(testTrack) {}

            assertEquals(VideoPlaybackRoute.IFRAME, viewModel.playback.value.route)
            assertEquals("vid_test_123", viewModel.playback.value.trackId)
            assertTrue(coordinator.isIFrameVideoActive)

            // Stopping / closing video restores route to IDLE and clears suppression
            viewModel.stop()

            assertEquals(VideoPlaybackRoute.IDLE, viewModel.playback.value.route)
            assertFalse(coordinator.isIFrameVideoActive)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `rapid route transitions AUDIO to IFRAME to MEDIA3 to IFRAME to IDLE leave consistent state`() {
        val coordinator = PlaybackRouteCoordinator()
        val changeLog = mutableListOf<Boolean>()
        coordinator.onRouteChanged = {
            changeLog.add(coordinator.isIFrameVideoActive)
        }

        // 1. Initial audio playback
        assertFalse(coordinator.isIFrameVideoActive)

        // 2. Open IFrame video
        coordinator.setIFrameVideoActive(true)
        assertTrue(coordinator.isIFrameVideoActive)

        // 3. Extracted Media3 video resolves -> Media3 does not suppress controls
        coordinator.setIFrameVideoActive(false)
        assertFalse(coordinator.isIFrameVideoActive)

        // 4. Media3 error fallbacks to IFrame
        coordinator.setIFrameVideoActive(true)
        assertTrue(coordinator.isIFrameVideoActive)

        // 5. User closes video to return to audio
        coordinator.setIFrameVideoActive(false)
        assertFalse(coordinator.isIFrameVideoActive)

        assertEquals(listOf(true, false, true, false), changeLog)
    }
}
