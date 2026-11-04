package com.example.sonara.domain.model.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic-only contract tests. These do not establish that NewPipe Extractor can perform live
 * extraction; they pin the URL-free metadata shape that an approved implementation must produce.
 */
class VideoExtractionModelTest {

    @Test
    fun `result preserves optional metadata and stream classifications`() {
        val result = VideoExtractionResult(
            videoId = "dQw4w9WgXcQ",
            title = "Synthetic public video",
            uploader = "Example channel",
            durationMs = 213_000L,
            thumbnails = listOf("https://example.invalid/thumb.jpg"),
            streams = listOf(
                VideoStreamDescriptor(
                    kind = VideoStreamKind.COMBINED,
                    deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
                    container = "mp4",
                    formatId = 18,
                    resolution = "360p",
                    width = 640,
                    height = 360,
                    frameRate = 30,
                    videoCodec = "avc1",
                    audioCodec = "mp4a",
                    bitrateKbps = 700
                ),
                VideoStreamDescriptor(
                    kind = VideoStreamKind.VIDEO_ONLY,
                    deliveryMethod = VideoDeliveryMethod.DASH,
                    container = "webm",
                    formatId = 248,
                    resolution = "1080p",
                    width = 1920,
                    height = 1080,
                    frameRate = 30,
                    videoCodec = "vp9"
                ),
                VideoStreamDescriptor(
                    kind = VideoStreamKind.AUDIO_ONLY,
                    deliveryMethod = VideoDeliveryMethod.DASH,
                    container = "webm",
                    formatId = 251,
                    audioCodec = "opus",
                    bitrateKbps = 160
                )
            )
        )

        assertEquals("dQw4w9WgXcQ", result.videoId)
        assertEquals(213_000L, result.durationMs)
        assertEquals(3, result.streams.size)
        assertEquals(VideoStreamKind.COMBINED, result.streams[0].kind)
        assertEquals(VideoStreamKind.VIDEO_ONLY, result.streams[1].kind)
        assertEquals(VideoStreamKind.AUDIO_ONLY, result.streams[2].kind)
        assertEquals("vp9", result.streams[1].videoCodec)
        assertEquals("opus", result.streams[2].audioCodec)
    }

    @Test
    fun `missing extractor fields remain absent instead of being fabricated`() {
        val result = VideoExtractionResult(
            videoId = "synthetic-id",
            streams = listOf(
                VideoStreamDescriptor(
                    kind = VideoStreamKind.VIDEO_ONLY,
                    deliveryMethod = VideoDeliveryMethod.OTHER
                )
            )
        )

        assertNull(result.title)
        assertNull(result.uploader)
        assertNull(result.durationMs)
        assertTrue(result.thumbnails.isEmpty())
        assertNull(result.streams.single().container)
        assertNull(result.streams.single().resolution)
        assertNull(result.streams.single().videoCodec)
        assertNull(result.streams.single().audioCodec)
    }
}
