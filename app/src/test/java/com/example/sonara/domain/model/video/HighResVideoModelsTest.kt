package com.example.sonara.domain.model.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HighResVideoModelsTest {

    private fun validByteRange(start: Long = 0L, end: Long = 1000L) = HighResByteRange(start, end)

    private fun validVideoRep(
        width: Int = 7680,
        height: Int = 4320,
        fps: Int = 60,
        bitrate: Long = 35_000_000L,
        duration: Double = 120.0,
        codec: String = "av01.0.12M.08",
        mimeType: String = "video/mp4",
        initRange: HighResByteRange = HighResByteRange(0, 842),
        indexRange: HighResByteRange = HighResByteRange(843, 2154),
        streamProxyUrl: String = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_123&track=video"
    ) = HighResVideoRepresentation(
        itag = 571,
        width = width,
        height = height,
        fps = fps,
        codec = codec,
        mimeType = mimeType,
        bitrate = bitrate,
        duration = duration,
        initRange = initRange,
        indexRange = indexRange,
        streamProxyUrl = streamProxyUrl
    )

    private fun validAudioRep(
        bitrate: Long = 128_000L,
        duration: Double = 120.0,
        codec: String = "mp4a.40.2",
        mimeType: String = "audio/mp4",
        initRange: HighResByteRange = HighResByteRange(0, 624),
        indexRange: HighResByteRange = HighResByteRange(625, 1850),
        streamProxyUrl: String = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_123&track=audio"
    ) = HighResAudioRepresentation(
        itag = 140,
        codec = codec,
        mimeType = mimeType,
        bitrate = bitrate,
        duration = duration,
        initRange = initRange,
        indexRange = indexRange,
        streamProxyUrl = streamProxyUrl
    )

    @Test
    fun `HighResByteRange enforces non-negative bounds and correct ordering`() {
        val valid = HighResByteRange(0, 500)
        assertEquals(0, valid.start)
        assertEquals(500, valid.end)
        assertEquals(501, valid.size)

        try {
            HighResByteRange(-1, 500)
            fail("Expected IllegalArgumentException for negative start")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-negative"))
        }

        try {
            HighResByteRange(500, 499)
            fail("Expected IllegalArgumentException for end < start")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(">= start"))
        }

        try {
            HighResByteRange(0, 100_000_000_001L)
            fail("Expected IllegalArgumentException for exceeding 100GB limit")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds reasonable maximum"))
        }
    }

    @Test
    fun `HighResVideoRepresentation strictly enforces 8K 4320p dimensions and valid bounds`() {
        // Valid instance succeeds
        val valid = validVideoRep()
        assertEquals(7680, valid.width)
        assertEquals(4320, valid.height)

        // Non-8K resolution rejected
        try {
            validVideoRep(width = 3840, height = 2160)
            fail("Expected IllegalArgumentException for 4K video in 8K representation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("7680x4320"))
        }

        // Invalid fps rejected
        try {
            validVideoRep(fps = 0)
            fail("Expected IllegalArgumentException for 0 fps")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fps"))
        }

        // Invalid bitrate rejected
        try {
            validVideoRep(bitrate = 0L)
            fail("Expected IllegalArgumentException for 0 bitrate")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bitrate"))
        }

        // Non-positive or infinite duration rejected
        try {
            validVideoRep(duration = 0.0)
            fail("Expected IllegalArgumentException for 0 duration")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Duration"))
        }

        try {
            validVideoRep(duration = Double.POSITIVE_INFINITY)
            fail("Expected IllegalArgumentException for infinite duration")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("finite"))
        }

        // Invalid MIME type rejected
        try {
            validVideoRep(mimeType = "audio/mp4")
            fail("Expected IllegalArgumentException for audio mimeType in video representation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("video/"))
        }

        // Disjoint byte ranges: indexRange must start after initRange.end
        try {
            validVideoRep(
                initRange = HighResByteRange(0, 1000),
                indexRange = HighResByteRange(900, 2000)
            )
            fail("Expected IllegalArgumentException for indexRange overlapping initRange")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must start after initRange"))
        }
    }

    @Test
    fun `HighResAudioRepresentation strictly enforces audio constraints`() {
        val valid = validAudioRep()
        assertEquals(140, valid.itag)

        // Invalid bitrate rejected
        try {
            validAudioRep(bitrate = 0L)
            fail("Expected IllegalArgumentException for 0 bitrate")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bitrate"))
        }

        // Invalid MIME type rejected
        try {
            validAudioRep(mimeType = "video/mp4")
            fail("Expected IllegalArgumentException for video mimeType in audio representation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("audio/"))
        }
    }

    @Test
    fun `HighResVideoDiscovery Available validates video-audio duration drift and session consistency`() {
        val video = validVideoRep(duration = 100.0)
        val audioMatching = validAudioRep(duration = 102.0)

        val discovery = HighResVideoDiscovery.Available(
            videoId = "dQw4w9WgXcQ",
            sessionId = "sess_123",
            extractedAt = 1000L,
            expiresAt = 8200L,
            video = video,
            audio = audioMatching
        )
        assertTrue(discovery.hasHighRes)
        assertEquals("dQw4w9WgXcQ", discovery.videoId)
        assertEquals("sess_123", discovery.sessionId)

        // Duration drift exceeding tolerance (> max(15s, 15%)) rejected
        val audioDrifted = validAudioRep(duration = 125.0) // 25s drift > 15s and > 15%
        try {
            HighResVideoDiscovery.Available(
                videoId = "dQw4w9WgXcQ",
                sessionId = "sess_123",
                extractedAt = 1000L,
                expiresAt = 8200L,
                video = video,
                audio = audioDrifted
            )
            fail("Expected IllegalArgumentException for excessive duration drift")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Duration drift"))
        }

        // Session ID mismatch in proxy URL rejected
        val audioMismatchedSession = validAudioRep(
            streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=other_sess&track=audio"
        )
        try {
            HighResVideoDiscovery.Available(
                videoId = "dQw4w9WgXcQ",
                sessionId = "sess_123",
                extractedAt = 1000L,
                expiresAt = 8200L,
                video = video,
                audio = audioMismatchedSession
            )
            fail("Expected IllegalArgumentException for session ID mismatch in proxy URL")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("session"))
        }
    }

    @Test
    fun `HighResVideoDiscovery Unavailable correctly preserves negative discovery status`() {
        val unavailable = HighResVideoDiscovery.Unavailable(
            videoId = "dQw4w9WgXcQ",
            extractedAt = 1000L,
            expiresAt = 8200L
        )
        assertFalse(unavailable.hasHighRes)
        assertEquals("dQw4w9WgXcQ", unavailable.videoId)
        assertEquals(8200L, unavailable.expiresAt)
    }
}
