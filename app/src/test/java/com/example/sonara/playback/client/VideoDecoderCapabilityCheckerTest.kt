package com.example.sonara.playback.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderCapabilityCheckerTest {

    @Test
    fun `canonical MIME resolution normalizes video codecs and container hints`() {
        // AV1 variants
        assertEquals("video/av01", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/webm", "av01.0.08M.08"))
        assertEquals("video/av01", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/mp4", "av1"))

        // VP9 variants
        assertEquals("video/x-vnd.on2.vp9", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/webm", "vp9"))
        assertEquals("video/x-vnd.on2.vp9", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/webm", "vp09.00.51.08"))

        // AVC / H.264 variants
        assertEquals("video/avc", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/mp4", "avc1.640028"))
        assertEquals("video/avc", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime(null, "h264"))

        // HEVC / H.265 variants
        assertEquals("video/hevc", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/mp4", "hev1.1.6.L93.B0"))
        assertEquals("video/hevc", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime(null, "hvc1.1.6.L93.B0"))
        assertEquals("video/hevc", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime(null, "h265"))

        // WebM defaults
        assertEquals("video/x-vnd.on2.vp9", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/webm", null))
        assertEquals("video/x-vnd.on2.vp8", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/webm", "vp8"))

        // MP4 default
        assertEquals("video/avc", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/mp4", null))

        // Direct standard MIME
        assertEquals("video/mp4v-es", AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("video/mp4v-es", null))
    }

    @Test
    fun `canonical MIME resolution rejects audio, unknown or malformed tokens`() {
        assertNull(AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime(null, null))
        assertNull(AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("", ""))
        assertNull(AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("audio/mp4", "mp4a.40.2"))
        assertNull(AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("audio/webm", "opus"))
        assertNull(AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("application/dash+xml", "unknown"))
        assertNull(AndroidVideoDecoderCapabilityChecker.resolveCanonicalMime("unknown/mime", "xyz"))
    }

    @Test
    fun `invalid or non-positive dimensions and frame rates are rejected defensively`() {
        val checker = AndroidVideoDecoderCapabilityChecker()
        assertFalse(checker.isSupported("video/webm", "vp9", width = 0, height = 0, frameRate = 30f))
        assertFalse(checker.isSupported("video/webm", "vp9", width = -1920, height = 1080, frameRate = 30f))
        assertFalse(checker.isSupported("video/webm", "vp9", width = 1920, height = -1080, frameRate = 30f))
        assertFalse(checker.isSupported("video/webm", "vp9", width = 1920, height = 0, frameRate = 30f))
        assertFalse(checker.isSupported("video/webm", "vp9", width = 0, height = 1080, frameRate = 30f))
        assertFalse(checker.isSupported("video/webm", "vp9", width = 1920, height = 1080, frameRate = -30f))
        assertFalse(checker.isSupported("video/webm", "vp9", width = 1920, height = 1080, frameRate = Float.NaN))
        assertFalse(checker.isSupported("unknown/mime", "unknown", width = 1920, height = 1080, frameRate = 30f))
    }

    @Test
    fun `decoder capability evaluation distinguishes 4K30 and 4K60 limits on constrained decoders`() {
        val constrainedChecker = AndroidVideoDecoderCapabilityChecker(
            decoderQuery = { mime, width, height, frameRate ->
                if (mime != "video/x-vnd.on2.vp9") return@AndroidVideoDecoderCapabilityChecker false
                // Simulates a decoder capable of 4K up to 30fps only (e.g. Redmi Note 12 5G hardware VP9 limit)
                val is4k = width <= 3840 && height <= 2160
                val isRateSupported = if (width > 2560 || height > 1440) {
                    frameRate <= 30.5f
                } else {
                    frameRate <= 60.5f
                }
                is4k && isRateSupported
            }
        )

        // 4K30 -> Supported by hardware
        assertTrue(constrainedChecker.isSupported("video/webm", "vp9", 3840, 2160, 30f))

        // 4K60 -> Exceeds hardware frame rate limit -> Unsupported
        assertFalse(constrainedChecker.isSupported("video/webm", "vp9", 3840, 2160, 60f))

        // 1440p60 -> Supported within rate limits
        assertTrue(constrainedChecker.isSupported("video/webm", "vp9", 2560, 1440, 60f))
    }

    @Test
    fun `decoder capability evaluation supports 4K60 when hardware decoders affirm rate capability`() {
        val highEndChecker = AndroidVideoDecoderCapabilityChecker(
            decoderQuery = { mime, width, height, frameRate ->
                if (mime != "video/x-vnd.on2.vp9") return@AndroidVideoDecoderCapabilityChecker false
                width <= 3840 && height <= 2160 && frameRate <= 60.5f
            }
        )

        // 4K60 affirmed by capable hardware decoder -> Supported
        assertTrue(highEndChecker.isSupported("video/webm", "vp9", 3840, 2160, 60f))
    }

    @Test
    fun `decoder capability evaluation checks dimensions and rate bounds against mock decoders`() {
        var queryCount = 0

        val checker = AndroidVideoDecoderCapabilityChecker(
            decoderQuery = { mime, width, height, frameRate ->
                queryCount++
                if (mime != "video/x-vnd.on2.vp9") return@AndroidVideoDecoderCapabilityChecker false
                val sizeOk = width <= 1920 && height <= 1080
                val rateOk = frameRate <= 0f || frameRate <= 60.0f
                sizeOk && rateOk
            }
        )

        // 1080p VP9 @ 60fps -> Supported
        assertTrue(checker.isSupported("video/webm", "vp9", 1920, 1080, 60f))

        // 720p VP9 @ 30fps -> Supported
        assertTrue(checker.isSupported("video/webm", "vp9", 1280, 720, 30f))

        // 4K (2160p) VP9 -> Exceeds 1080p limit -> Unsupported
        assertFalse(checker.isSupported("video/webm", "vp9", 3840, 2160, 60f))

        // 8K (4320p) VP9 -> Exceeds limit -> Unsupported
        assertFalse(checker.isSupported("video/webm", "vp9", 7680, 4320, 60f))

        // AV01 -> Decoder does not support AV01 MIME -> Unsupported
        assertFalse(checker.isSupported("video/webm", "av01.0.08M.08", 1920, 1080, 30f))

        // Verify Caching: Second query for 1080p VP9 @ 60fps must be served from cache without re-query
        val queriesBefore = queryCount
        assertTrue(checker.isSupported("video/webm", "vp9", 1920, 1080, 60f))
        assertEquals(queriesBefore, queryCount)
    }
}
