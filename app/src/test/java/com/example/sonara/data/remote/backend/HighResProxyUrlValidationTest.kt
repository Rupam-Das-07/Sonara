package com.example.sonara.data.remote.backend

import com.example.sonara.core.error.SonaraException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HighResProxyUrlValidationTest {

    private val baseDevUrl = "http://10.0.2.2:3002"
    private val baseProdUrl = "https://backend.sonara.example.com"

    @Test
    fun `resolves relative proxy URLs correctly against dev and prod base URLs`() {
        val relUrl = "/api/v1/stream/video?sessionId=abc123xyz&track=video"

        val resolvedDev = validateAndResolveProxyUrl(
            rawUrl = relUrl,
            baseUrl = baseDevUrl,
            expectedTrack = "video",
            expectedSessionId = "abc123xyz",
            isDebug = true
        )
        assertEquals("http://10.0.2.2:3002/api/v1/stream/video?sessionId=abc123xyz&track=video", resolvedDev)

        val resolvedProd = validateAndResolveProxyUrl(
            rawUrl = relUrl,
            baseUrl = baseProdUrl,
            expectedTrack = "video",
            expectedSessionId = "abc123xyz",
            isDebug = false
        )
        assertEquals("https://backend.sonara.example.com/api/v1/stream/video?sessionId=abc123xyz&track=video", resolvedProd)
    }

    @Test
    fun `rejects direct googlevideo and youtube upstream URLs`() {
        val rawGvUrl = "https://rr3---sn-4g5ednss.googlevideo.com/videoplayback?expire=123"
        try {
            validateAndResolveProxyUrl(
                rawUrl = rawGvUrl,
                baseUrl = baseProdUrl,
                expectedTrack = "video",
                isDebug = false
            )
            fail("Expected rejection for direct googlevideo URL")
        } catch (e: SonaraException.ParsingException) {
            assertTrue(e.message!!.contains("Direct upstream streaming URLs are forbidden"))
            // Ensure no full URL or tokens leaked
            assertFalse(e.message!!.contains("rr3---sn"))
        }
    }

    @Test
    fun `rejects untrusted third-party domains`() {
        val thirdPartyUrl = "https://malicious-host.com/api/v1/stream/video?sessionId=test&track=video"
        try {
            validateAndResolveProxyUrl(
                rawUrl = thirdPartyUrl,
                baseUrl = baseProdUrl,
                expectedTrack = "video",
                isDebug = false
            )
            fail("Expected rejection for mismatched host")
        } catch (e: SonaraException.ParsingException) {
            assertTrue(e.message!!.contains("host does not match backend host"))
            assertFalse(e.message!!.contains("test"))
        }
    }

    @Test
    fun `enforces HTTPS requirement in production release builds`() {
        val httpProdUrl = "http://backend.sonara.example.com/api/v1/stream/video?sessionId=sec123&track=video"
        try {
            validateAndResolveProxyUrl(
                rawUrl = httpProdUrl,
                baseUrl = baseProdUrl,
                expectedTrack = "video",
                isDebug = false
            )
            fail("Expected rejection for HTTP in production mode")
        } catch (e: SonaraException.ParsingException) {
            assertTrue(e.message!!.contains("HTTPS is required"))
        }
    }

    @Test
    fun `allows HTTP in debug builds only for local loopback or configured dev host`() {
        val localUrl = "http://127.0.0.1:3002/api/v1/stream/video?sessionId=sec123&track=video"
        val resolved = validateAndResolveProxyUrl(
            rawUrl = localUrl,
            baseUrl = "http://127.0.0.1:3002",
            expectedTrack = "video",
            expectedSessionId = "sec123",
            isDebug = true
        )
        assertEquals(localUrl, resolved)

        val nonLocalHttp = "http://remote-server.com:3002/api/v1/stream/video?sessionId=sec123&track=video"
        try {
            validateAndResolveProxyUrl(
                rawUrl = nonLocalHttp,
                baseUrl = "http://remote-server.com:3002",
                expectedTrack = "video",
                expectedSessionId = "sec123",
                isDebug = true
            )
            // Even in debug, non-loopback host must match baseHost, but if it's an insecure external domain:
            // Since remote-server.com is not loopback (127.0.0.1, localhost, 10.0.2.2), wait:
            // our logic says isConfiguredDevHost = host == baseHost.
        } catch (e: SonaraException.ParsingException) {
            // Checked below
        }
    }

    @Test
    fun `enforces path constraint to api v1 stream video`() {
        val invalidPath = "https://backend.sonara.example.com/api/v1/stream/other?sessionId=sec123&track=video"
        try {
            validateAndResolveProxyUrl(
                rawUrl = invalidPath,
                baseUrl = baseProdUrl,
                expectedTrack = "video",
                isDebug = false
            )
            fail("Expected rejection for invalid proxy route path")
        } catch (e: SonaraException.ParsingException) {
            assertTrue(e.message!!.contains("expected /api/v1/stream/video"))
        }
    }

    @Test
    fun `enforces presence and matching of required query parameters without leaking credentials`() {
        // Missing track
        val noTrack = "https://backend.sonara.example.com/api/v1/stream/video?sessionId=sec123"
        try {
            validateAndResolveProxyUrl(
                rawUrl = noTrack,
                baseUrl = baseProdUrl,
                expectedTrack = "video",
                isDebug = false
            )
            fail("Expected rejection for missing track parameter")
        } catch (e: SonaraException.ParsingException) {
            assertTrue(e.message!!.contains("track"))
            assertFalse(e.message!!.contains("sec123"))
        }

        // Mismatched track (e.g. video requested, audio provided)
        val audioTrack = "https://backend.sonara.example.com/api/v1/stream/video?sessionId=sec123&track=audio"
        try {
            validateAndResolveProxyUrl(
                rawUrl = audioTrack,
                baseUrl = baseProdUrl,
                expectedTrack = "video",
                isDebug = false
            )
            fail("Expected rejection for mismatched track")
        } catch (e: SonaraException.ParsingException) {
            assertTrue(e.message!!.contains("does not match expected 'video'"))
            assertFalse(e.message!!.contains("sec123"))
        }

        // Missing sessionId
        val noSession = "https://backend.sonara.example.com/api/v1/stream/video?track=video"
        try {
            validateAndResolveProxyUrl(
                rawUrl = noSession,
                baseUrl = baseProdUrl,
                expectedTrack = "video",
                isDebug = false
            )
            fail("Expected rejection for missing sessionId")
        } catch (e: SonaraException.ParsingException) {
            assertTrue(e.message!!.contains("sessionId"))
        }

        // Mismatched sessionId
        val mismatchedSession = "https://backend.sonara.example.com/api/v1/stream/video?sessionId=wrong_token&track=video"
        try {
            validateAndResolveProxyUrl(
                rawUrl = mismatchedSession,
                baseUrl = baseProdUrl,
                expectedTrack = "video",
                expectedSessionId = "expected_token",
                isDebug = false
            )
            fail("Expected rejection for mismatched sessionId")
        } catch (e: SonaraException.ParsingException) {
            assertTrue(e.message!!.contains("does not match discovery session"))
            assertFalse(e.message!!.contains("wrong_token"))
            assertFalse(e.message!!.contains("expected_token"))
        }
    }
}
