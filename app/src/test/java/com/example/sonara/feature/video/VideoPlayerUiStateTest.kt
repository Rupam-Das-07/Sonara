package com.example.sonara.feature.video

import androidx.media3.common.Player
import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerUiStateTest {
    private val track = Track(
        id = "dQw4w9WgXcQ",
        title = "Video title",
        artist = "Video artist",
        album = "Video album",
        artworkUrl = "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
    )

    @Test
    fun `video projection uses authoritative playing progress and duration`() {
        val uiState = track.toVideoPlayerUiState(
            youtubeState = YouTubePlayerState(
                videoId = track.id,
                generation = 1L,
                lifecycle = YouTubePlayerLifecycle.READY,
                playbackState = YouTubePlaybackState.PLAYING,
                currentPositionMs = 12_500L,
                durationMs = 213_000L
            )
        )

        assertTrue(uiState.isPlaying)
        assertFalse(uiState.isBuffering)
        assertEquals(12_500L, uiState.currentPositionMs)
        assertTrue(uiState.isPositionAvailable)
        assertEquals(213_000L, uiState.durationMs)
        assertEquals(Player.STATE_READY, uiState.playbackState)
    }

    @Test
    fun `video projection preserves unavailable position and duration`() {
        val uiState = track.toVideoPlayerUiState(
            youtubeState = YouTubePlayerState(
                videoId = track.id,
                generation = 1L,
                lifecycle = YouTubePlayerLifecycle.NOT_READY,
                playbackState = YouTubePlaybackState.UNKNOWN,
                currentPositionMs = null,
                durationMs = null
            )
        )

        assertFalse(uiState.isPlaying)
        assertFalse(uiState.isPositionAvailable)
        assertEquals(0L, uiState.currentPositionMs)
        assertEquals(0L, uiState.durationMs)
        assertEquals(Player.STATE_IDLE, uiState.playbackState)
    }

    @Test
    fun `buffering and ended remain distinct authoritative states`() {
        val buffering = track.toVideoPlayerUiState(
            YouTubePlayerState(
                videoId = track.id,
                generation = 1L,
                lifecycle = YouTubePlayerLifecycle.READY,
                playbackState = YouTubePlaybackState.BUFFERING
            )
        )
        val ended = track.toVideoPlayerUiState(
            YouTubePlayerState(
                videoId = track.id,
                generation = 1L,
                lifecycle = YouTubePlayerLifecycle.READY,
                playbackState = YouTubePlaybackState.ENDED
            )
        )

        assertTrue(buffering.isBuffering)
        assertEquals(Player.STATE_BUFFERING, buffering.playbackState)
        assertEquals(Player.STATE_ENDED, ended.playbackState)
    }

    @Test
    fun `video quality ui state reflects preparation and multiple variant choices`() {
        val preparing = VideoQualityUiState(
            requestedQuality = com.example.sonara.domain.model.video.VideoQuality.AUTO,
            availableHeights = emptyList(),
            actualHeight = null,
            isPreparing = true
        )
        assertTrue(preparing.isPreparing)
        assertTrue(preparing.availableHeights.isEmpty())

        val ready = VideoQualityUiState(
            requestedQuality = com.example.sonara.domain.model.video.VideoQuality.P720,
            availableHeights = listOf(1080, 720, 480, 360),
            actualHeight = 720,
            isPreparing = false
        )
        assertFalse(ready.isPreparing)
        assertEquals(4, ready.availableHeights.size)
        assertEquals(720, ready.actualHeight)
        assertEquals(com.example.sonara.domain.model.video.VideoQuality.P720, ready.requestedQuality)
    }

    @Test
    fun `video quality ui state preserves frame-rate-aware available qualities and actual quality`() {
        val options = listOf(
            com.example.sonara.domain.model.video.VideoQuality.P1080_60,
            com.example.sonara.domain.model.video.VideoQuality.P1080_30,
            com.example.sonara.domain.model.video.VideoQuality.P720_30
        )
        val state = VideoQualityUiState(
            requestedQuality = com.example.sonara.domain.model.video.VideoQuality.P1080_60,
            availableHeights = listOf(1080, 720),
            actualHeight = 1080,
            isPreparing = false,
            availableQualities = options,
            actualQuality = com.example.sonara.domain.model.video.VideoQuality.P1080_60
        )

        assertFalse(state.isPreparing)
        assertEquals(options, state.availableQualities)
        assertEquals(com.example.sonara.domain.model.video.VideoQuality.P1080_60, state.actualQuality)
        assertEquals("1080p · 60 FPS", state.actualQuality?.label)
    }

    @Test
    fun `video quality ui state handles fallback actual quality without falsely asserting requested rate`() {
        // User requested 1080p60, but track fell back to 1080p30
        val state = VideoQualityUiState(
            requestedQuality = com.example.sonara.domain.model.video.VideoQuality.P1080_60,
            availableHeights = listOf(1080),
            actualHeight = 1080,
            isPreparing = false,
            availableQualities = listOf(com.example.sonara.domain.model.video.VideoQuality.P1080_30),
            actualQuality = com.example.sonara.domain.model.video.VideoQuality.P1080_30
        )

        assertEquals(com.example.sonara.domain.model.video.VideoQuality.P1080_60, state.requestedQuality)
        assertEquals(com.example.sonara.domain.model.video.VideoQuality.P1080_30, state.actualQuality)
        assertEquals("1080p · 30 FPS", state.actualQuality?.label)
    }

    @Test
    fun `video quality ui state handles empty and single quality options safely`() {
        val emptyState = VideoQualityUiState(
            requestedQuality = com.example.sonara.domain.model.video.VideoQuality.AUTO,
            availableHeights = emptyList(),
            actualHeight = null,
            isPreparing = true,
            availableQualities = emptyList(),
            actualQuality = null
        )
        assertTrue(emptyState.isPreparing)
        assertTrue(emptyState.availableQualities.isEmpty())
        assertTrue(emptyState.availableHeights.isEmpty())

        val singleState = VideoQualityUiState(
            requestedQuality = com.example.sonara.domain.model.video.VideoQuality.P720,
            availableHeights = listOf(720),
            actualHeight = 720,
            isPreparing = false,
            availableQualities = listOf(com.example.sonara.domain.model.video.VideoQuality.P720),
            actualQuality = com.example.sonara.domain.model.video.VideoQuality.P720
        )
        assertFalse(singleState.isPreparing)
        assertEquals(1, singleState.availableQualities.size)
        assertEquals("720p", singleState.availableQualities.single().label)
    }

    @Test
    fun `view count is formatted and projected in video player ui state`() {
        val uiState = track.toVideoPlayerUiState(
            youtubeState = YouTubePlayerState(
                videoId = track.id,
                generation = 1L,
                lifecycle = YouTubePlayerLifecycle.READY,
                playbackState = YouTubePlaybackState.PLAYING
            ),
            viewCount = 1_234_567L
        )
        assertEquals(1_234_567L, uiState.viewCount)
        assertEquals("1.2M views", uiState.formattedViewCount)

        val nullCountState = track.toVideoPlayerUiState(
            youtubeState = YouTubePlayerState(
                videoId = track.id,
                generation = 1L,
                lifecycle = YouTubePlayerLifecycle.READY,
                playbackState = YouTubePlaybackState.PLAYING
            ),
            viewCount = null
        )
        assertEquals(null, nullCountState.viewCount)
        assertEquals(null, nullCountState.formattedViewCount)
    }

    @Test
    fun `view count is formatted and projected in media3 video player ui state`() {
        val mediaState = com.example.sonara.playback.client.MediaControllerState(
            isConnected = true,
            isPlaying = true,
            currentMediaItem = androidx.media3.common.MediaItem.Builder().setMediaId(track.id).build()
        )
        val uiState = track.toMedia3VideoPlayerUiState(
            mediaState = mediaState,
            viewCount = 950L
        )
        assertEquals(950L, uiState.viewCount)
        assertEquals("950 views", uiState.formattedViewCount)

        val sentinelState = track.toMedia3VideoPlayerUiState(
            mediaState = mediaState,
            viewCount = -1L
        )
        assertEquals(-1L, sentinelState.viewCount)
        assertEquals(null, sentinelState.formattedViewCount)
    }
}
