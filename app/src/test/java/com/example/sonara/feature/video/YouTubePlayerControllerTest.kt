package com.example.sonara.feature.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayerControllerTest {
    private val origin = "https://app.sonara.music"

    @Test
    fun `initial state is unavailable until ready`() {
        val controller = controller()

        assertEquals(YouTubePlayerLifecycle.NOT_READY, controller.state.lifecycle)
        assertEquals(YouTubePlaybackState.UNKNOWN, controller.state.playbackState)
        assertNull(controller.state.currentPositionMs)
        assertNull(controller.state.durationMs)
    }

    @Test
    fun `ready event exposes duration and flushes queued command`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)

        assertTrue(controller.play() is YouTubeCommandResult.Queued)
        val result = controller.handleMessage(ready(), origin, isMainFrame = true)

        assertTrue(result is YouTubeMessageHandlingResult.Accepted)
        assertEquals(YouTubePlayerLifecycle.READY, controller.state.lifecycle)
        assertEquals(213_000L, controller.state.durationMs)
        assertEquals(listOf(YouTubePlayerCommand.Play), transport.commands)
    }

    @Test
    fun `repeated commands coalesce before readiness`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)

        controller.play()
        controller.pause()
        controller.seekTo(5_000L)
        controller.seekTo(8_000L)
        controller.handleMessage(ready(), origin, isMainFrame = true)

        assertEquals(
            listOf(YouTubePlayerCommand.Pause, YouTubePlayerCommand.SeekTo(8_000L)),
            transport.commands
        )
    }

    @Test
    fun `play pause and seek are dispatched only after readiness`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)

        assertTrue(controller.play() is YouTubeCommandResult.Queued)
        assertTrue(controller.pause() is YouTubeCommandResult.Queued)
        assertTrue(controller.seekTo(1_000L) is YouTubeCommandResult.Queued)
        assertTrue(transport.commands.isEmpty())

        controller.handleMessage(ready(), origin, isMainFrame = true)
        assertEquals(
            listOf(YouTubePlayerCommand.Pause, YouTubePlayerCommand.SeekTo(1_000L)),
            transport.commands
        )

        transport.commands.clear()
        assertTrue(controller.play() is YouTubeCommandResult.Sent)
        assertTrue(controller.pause() is YouTubeCommandResult.Sent)
        assertTrue(controller.seekTo(2_000L) is YouTubeCommandResult.Sent)
        assertEquals(
            listOf(
                YouTubePlayerCommand.Play,
                YouTubePlayerCommand.Pause,
                YouTubePlayerCommand.SeekTo(2_000L)
            ),
            transport.commands
        )
    }

    @Test
    fun `invalid seek positions are rejected`() {
        val controller = controller()
        assertTrue(controller.seekTo(-1L) is YouTubeCommandResult.Rejected)

        controller.handleMessage(ready(), origin, isMainFrame = true)
        assertTrue(controller.seekTo(213_001L) is YouTubeCommandResult.Rejected)
    }

    @Test
    fun `state and position events synchronize playback state`() {
        val controller = controller()
        controller.handleMessage(ready(), origin, isMainFrame = true)

        controller.handleMessage(state("PLAYING"), origin, isMainFrame = true)
        controller.handleMessage(position(12_345L), origin, isMainFrame = true)
        assertEquals(YouTubePlaybackState.PLAYING, controller.state.playbackState)
        assertEquals(12_345L, controller.state.currentPositionMs)
        assertEquals(213_000L, controller.state.durationMs)

        controller.handleMessage(state("BUFFERING"), origin, isMainFrame = true)
        assertEquals(YouTubePlaybackState.BUFFERING, controller.state.playbackState)
        controller.handleMessage(state("PAUSED"), origin, isMainFrame = true)
        assertEquals(YouTubePlaybackState.PAUSED, controller.state.playbackState)
        controller.handleMessage(state("ENDED"), origin, isMainFrame = true)
        assertEquals(YouTubePlaybackState.ENDED, controller.state.playbackState)
    }

    @Test
    fun `events before readiness do not confirm playback`() {
        val controller = controller()

        val stateResult = controller.handleMessage(state("PLAYING"), origin, isMainFrame = true)
        val positionResult = controller.handleMessage(position(1_000L), origin, isMainFrame = true)

        assertEquals(YouTubeMessageHandlingResult.IgnoredBeforeReady, stateResult)
        assertEquals(YouTubeMessageHandlingResult.IgnoredBeforeReady, positionResult)
        assertEquals(YouTubePlaybackState.UNKNOWN, controller.state.playbackState)
        assertNull(controller.state.currentPositionMs)
    }

    @Test
    fun `malformed unknown and out of range messages are rejected`() {
        val invalidMessages = listOf(
            "",
            "not-json",
            "{}",
            "{\"type\":\"state\",\"videoId\":123,\"generation\":1,\"state\":\"PLAYING\"}",
            "{\"type\":\"unknown\",\"videoId\":\"$videoA\",\"generation\":1}",
            "{\"type\":\"state\",\"videoId\":\"$videoA\",\"generation\":1,\"state\":\"BROKEN\"}",
            position(-1L),
            position(213_001L),
            "{\"type\":\"ready\",\"videoId\":\"$videoA\",\"generation\":1,\"durationMs\":1,\"extra\":true}",
            "{\"type\":\"ready\",\"videoId\":\"$videoA\",\"generation\":1,\"durationMs\":1,\"durationMs\":2}"
        )

        invalidMessages.forEach { raw ->
            val result = YouTubePlayerMessageParser.parse(raw)
            assertTrue("Expected rejection for $raw", result is YouTubePlayerParseResult.Failure)
        }
    }

    @Test
    fun `wrong origin and subframe messages are rejected`() {
        val controller = controller()

        assertTrue(
            controller.handleMessage(ready(), "https://evil.example", true)
                is YouTubeMessageHandlingResult.Rejected
        )
        assertTrue(
            controller.handleMessage(ready(), origin, false)
                is YouTubeMessageHandlingResult.Rejected
        )
        assertEquals(YouTubePlayerLifecycle.NOT_READY, controller.state.lifecycle)
    }

    @Test
    fun `duplicate readiness is idempotent`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)
        controller.play()

        controller.handleMessage(ready(), origin, true)
        controller.handleMessage(ready(), origin, true)

        assertEquals(listOf(YouTubePlayerCommand.Play), transport.commands)
    }

    @Test
    fun `player and communication errors are retained`() {
        val controller = controller()
        controller.handleMessage(ready(), origin, true)
        controller.handleMessage(error(), origin, true)

        assertEquals(150, controller.state.lastError?.code)
        assertFalse(controller.state.lastError?.communication == true)

        controller.reportCommunicationFailure("connection lost")
        assertEquals("connection lost", controller.state.lastError?.message)
        assertTrue(controller.state.lastError?.communication == true)
    }

    @Test
    fun `dispose prevents commands and late events`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)
        controller.handleMessage(ready(), origin, true)
        controller.dispose()

        assertEquals(YouTubePlayerLifecycle.DISPOSED, controller.state.lifecycle)
        assertEquals(YouTubeCommandResult.IgnoredAfterDisposal, controller.play())
        assertEquals(
            YouTubeMessageHandlingResult.IgnoredAfterDisposal,
            controller.handleMessage(state("PLAYING"), origin, true)
        )
        assertEquals(0, transport.commands.size)
        assertTrue(transport.disposed)
    }

    @Test
    fun `replacement rejects stale events from previous video`() {
        val controller = controller()
        controller.handleMessage(ready(), origin, true)
        controller.replaceVideo(videoB, generation = 2L)

        val stale = controller.handleMessage(ready(videoA, generation = 1L), origin, true)
        val current = controller.handleMessage(ready(videoB, generation = 2L), origin, true)

        assertEquals(YouTubeMessageHandlingResult.IgnoredStale, stale)
        assertTrue(current is YouTubeMessageHandlingResult.Accepted)
        assertEquals(videoB, controller.state.videoId)
        assertEquals(2L, controller.state.generation)
    }

    @Test
    fun `parser preserves unavailable duration`() {
        val result = YouTubePlayerMessageParser.parse(
            "{\"type\":\"ready\",\"videoId\":\"$videoA\",\"generation\":1,\"durationMs\":null}"
        )

        assertEquals(
            YouTubePlayerMessage.Ready(videoA, 1L, null),
            (result as YouTubePlayerParseResult.Success).message
        )
    }

    @Test
    fun `youtubeEmbedDocument configures controls=0 and suppresses native controls`() {
        val doc = youtubeEmbedDocument(videoA, 1L)

        // Verifies required suppression parameters are present
        assertTrue("Must contain controls=0", doc.contains("controls=0"))
        assertTrue("Must contain fs=0", doc.contains("fs=0"))
        assertTrue("Must contain disablekb=1", doc.contains("disablekb=1"))

        // Verifies required functional parameters are retained
        assertTrue("Must retain playsinline=1", doc.contains("playsinline=1"))
        assertTrue("Must retain enablejsapi=1", doc.contains("enablejsapi=1"))
        assertTrue("Must retain rel=0", doc.contains("rel=0"))
        assertTrue("Must retain origin", doc.contains("origin="))

        // Verifies controls=1 is NOT present
        assertFalse("Must NOT contain controls=1", doc.contains("controls=1"))
    }

    @Test
    fun `youtubeEmbedDocument validates videoId format`() {
        val validDoc = youtubeEmbedDocument(videoA, 1L)
        assertTrue(validDoc.contains("https://www.youtube.com/embed/$videoA"))

        val invalidDoc = youtubeEmbedDocument("<script>alert(1)</script>", 1L)
        assertTrue(invalidDoc.contains("Invalid YouTube video ID"))
        assertFalse(invalidDoc.contains("<iframe"))
    }

    @Test
    fun `youtubeEmbedDocument with non-zero initial position includes start parameter and initialization seek`() {
        val doc = youtubeEmbedDocument(videoA, 1L, initialPositionMs = 45_678L)

        assertTrue("Must include &start=45 in embed URL", doc.contains("&start=45"))
        assertTrue("Must declare initialPositionMs = 45678", doc.contains("var initialPositionMs = 45678;"))
        assertTrue("Must declare initialSeekDone = false", doc.contains("var initialSeekDone = false;"))
        assertTrue("Must perform seek on ready", doc.contains("player.seekTo(initialPositionMs / 1000, true)"))

        // Verifies all required embed parameters are retained
        assertTrue("Must contain controls=0", doc.contains("controls=0"))
        assertTrue("Must contain fs=0", doc.contains("fs=0"))
        assertTrue("Must contain disablekb=1", doc.contains("disablekb=1"))
        assertTrue("Must retain playsinline=1", doc.contains("playsinline=1"))
        assertTrue("Must retain enablejsapi=1", doc.contains("enablejsapi=1"))
        assertTrue("Must retain rel=0", doc.contains("rel=0"))
        assertTrue("Must retain origin", doc.contains("origin="))
    }

    @Test
    fun `youtubeEmbedDocument with zero or negative initial position omits start parameter`() {
        val zeroDoc = youtubeEmbedDocument(videoA, 1L, initialPositionMs = 0L)
        assertFalse("Must not include &start= in zero embed URL", zeroDoc.contains("&start="))
        assertTrue("Must declare initialPositionMs = 0", zeroDoc.contains("var initialPositionMs = 0;"))

        val negativeDoc = youtubeEmbedDocument(videoA, 1L, initialPositionMs = -10_000L)
        assertFalse("Must not include &start= in negative embed URL", negativeDoc.contains("&start="))
        assertTrue("Must declare initialPositionMs = 0 for negative offset", negativeDoc.contains("var initialPositionMs = 0;"))
    }

    @Test
    fun `controller initializes state with provided initial position`() {
        val controller = YouTubePlayerController(
            videoId = videoA,
            generation = 1L,
            expectedOrigin = origin,
            initialPositionMs = 35_000L
        )

        assertEquals(YouTubePlayerLifecycle.NOT_READY, controller.state.lifecycle)
        assertEquals(35_000L, controller.state.currentPositionMs)
        assertNull(controller.state.durationMs)
    }

    @Test
    fun `controller preserves initial position across ready event when duration is reported`() {
        val controller = YouTubePlayerController(
            videoId = videoA,
            generation = 1L,
            expectedOrigin = origin,
            initialPositionMs = 42_000L
        )

        controller.handleMessage(ready(), origin, isMainFrame = true)
        assertEquals(YouTubePlayerLifecycle.READY, controller.state.lifecycle)
        assertEquals(42_000L, controller.state.currentPositionMs)
        assertEquals(213_000L, controller.state.durationMs)

        // Position event later updates it
        controller.handleMessage(position(45_000L), origin, isMainFrame = true)
        assertEquals(45_000L, controller.state.currentPositionMs)
    }

    @Test
    fun `setVolume converts float to 0 to 100 percent and queues until ready`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)

        assertTrue(controller.setVolume(0.75f) is YouTubeCommandResult.Queued)
        controller.handleMessage(ready(), origin, isMainFrame = true)

        assertEquals(
            listOf(YouTubePlayerCommand.SetVolume(75)),
            transport.commands
        )
    }

    @Test
    fun `setMuted queues until ready and dispatches boolean value`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)

        assertTrue(controller.setMuted(true) is YouTubeCommandResult.Queued)
        controller.handleMessage(ready(), origin, isMainFrame = true)

        assertEquals(
            listOf(YouTubePlayerCommand.SetMuted(true)),
            transport.commands
        )
    }

    @Test
    fun `repeated volume and mute commands coalesce before readiness`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)

        controller.setVolume(0.2f)
        controller.setVolume(0.8f)
        controller.setMuted(true)
        controller.setMuted(false)
        controller.handleMessage(ready(), origin, isMainFrame = true)

        assertEquals(
            listOf(
                YouTubePlayerCommand.SetVolume(80),
                YouTubePlayerCommand.SetMuted(false)
            ),
            transport.commands
        )
    }

    @Test
    fun `volume and mute commands dispatch immediately when ready`() {
        val transport = RecordingTransport()
        val controller = controller()
        controller.attachTransport(transport)
        controller.handleMessage(ready(), origin, isMainFrame = true)

        assertTrue(controller.setVolume(0.5f) is YouTubeCommandResult.Sent)
        assertTrue(controller.setMuted(true) is YouTubeCommandResult.Sent)

        assertEquals(
            listOf(
                YouTubePlayerCommand.SetVolume(50),
                YouTubePlayerCommand.SetMuted(true)
            ),
            transport.commands
        )
    }

    private fun controller(): YouTubePlayerController = YouTubePlayerController(
        videoId = videoA,
        generation = 1L,
        expectedOrigin = origin
    )

    private fun ready(videoId: String = videoA, generation: Long = 1L): String =
        "{\"type\":\"ready\",\"videoId\":\"$videoId\",\"generation\":$generation,\"durationMs\":213000}"

    private fun state(value: String): String =
        "{\"type\":\"state\",\"videoId\":\"$videoA\",\"generation\":1,\"state\":\"$value\"}"

    private fun position(value: Long): String =
        "{\"type\":\"position\",\"videoId\":\"$videoA\",\"generation\":1,\"currentPositionMs\":$value,\"durationMs\":213000}"

    private fun error(): String =
        "{\"type\":\"error\",\"videoId\":\"$videoA\",\"generation\":1,\"code\":150,\"message\":null}"

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

    private companion object {
        const val videoA = "dQw4w9WgXcQ"
        const val videoB = "9bZkp7q19f0"
    }
}
