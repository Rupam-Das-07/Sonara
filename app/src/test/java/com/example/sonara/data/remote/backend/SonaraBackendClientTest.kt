package com.example.sonara.data.remote.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for backend → domain mapping logic.
 *
 * Tests target [TrackMapper] directly, which accepts pure Kotlin types.
 * No org.json or Android stubs are used — runs cleanly on the JVM.
 *
 * The URL-resolution logic for stream proxy URLs is also validated here.
 */
class SonaraBackendClientTest {

    // -------------------------------------------------------------------------
    // TrackMapper — core field mapping
    // -------------------------------------------------------------------------

    @Test
    fun `TrackMapper maps all core fields correctly`() {
        val track = TrackMapper.map(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "Whenever You Need Somebody",
            durationSec = 213,
            rawArtworkUrl = null
        )
        assertNotNull(track)
        assertEquals("dQw4w9WgXcQ", track?.id)
        assertEquals("Never Gonna Give You Up", track?.title)
        assertEquals("Rick Astley", track?.artist)
        assertEquals("Whenever You Need Somebody", track?.album)
        assertEquals(213_000L, track?.durationMs)
    }

    @Test
    fun `TrackMapper returns null when videoId is null`() {
        val track = TrackMapper.map(
            videoId = null,
            title = "Title",
            artist = "Artist",
            album = "",
            durationSec = 0,
            rawArtworkUrl = null
        )
        assertNull(track)
    }

    @Test
    fun `TrackMapper returns null when videoId is blank`() {
        val track = TrackMapper.map(
            videoId = "   ",
            title = "Title",
            artist = "Artist",
            album = "",
            durationSec = 0,
            rawArtworkUrl = null
        )
        assertNull(track)
    }

    @Test
    fun `TrackMapper converts duration seconds to milliseconds`() {
        val track = TrackMapper.map("abc", "T", "A", "", 180, null)
        assertEquals(180_000L, track?.durationMs)
    }

    @Test
    fun `TrackMapper uses zero durationMs when durationSec is zero`() {
        val track = TrackMapper.map("abc", "T", "A", "", 0, null)
        assertEquals(0L, track?.durationMs)
    }

    @Test
    fun `TrackMapper uses default title when title is blank`() {
        val track = TrackMapper.map("abc", "", "A", "", 0, null)
        assertEquals("Unknown Title", track?.title)
    }

    @Test
    fun `TrackMapper uses default artist when artist is blank`() {
        val track = TrackMapper.map("abc", "T", "", "", 0, null)
        assertEquals("Unknown Artist", track?.artist)
    }

    @Test
    fun `TrackMapper upgrades lh3 artwork URL`() {
        val track = TrackMapper.map(
            "abc", "T", "A", "", 0,
            "https://lh3.googleusercontent.com/art=w120-h120"
        )
        // ArtworkUrlUpgrader should upgrade to w544-h544
        assertTrue("Expected upgraded lh3 URL", track?.artworkUrl?.contains("googleusercontent") == true)
        assertTrue("Expected w544 in URL", track?.artworkUrl?.contains("w544") == true)
    }

    @Test
    fun `TrackMapper upgrades ytimg artwork URL`() {
        val track = TrackMapper.map(
            "abc", "T", "A", "", 0,
            "https://i.ytimg.com/vi/abc/hqdefault.jpg"
        )
        // ArtworkUrlUpgrader should upgrade to maxresdefault
        assertTrue("Expected maxresdefault", track?.artworkUrl?.contains("maxresdefault") == true)
    }

    @Test
    fun `TrackMapper returns null artworkUrl when rawArtworkUrl is null`() {
        val track = TrackMapper.map("abc", "T", "A", "", 0, null)
        assertNull(track?.artworkUrl)
    }

    // -------------------------------------------------------------------------
    // Result ordering — preserved through the mapper
    // -------------------------------------------------------------------------

    @Test
    fun `server result ordering is preserved`() {
        val ids = listOf("aaa", "bbb", "ccc")
        val tracks = ids.mapNotNull { id ->
            TrackMapper.map(id, "Title $id", "Artist", "", 0, null)
        }
        assertEquals(ids, tracks.map { it.id })
    }

    // -------------------------------------------------------------------------
    // Stream URL resolution logic
    // -------------------------------------------------------------------------

    @Test
    fun `absolute audio_url is used unchanged`() {
        val absolute = "https://backend.sonara.app/stream-youtube-audio?video_id=abc&audio_url=foo"
        assertEquals(absolute, resolveStreamUrl(absolute))
    }

    @Test
    fun `relative audio_url is prefixed with baseUrl`() {
        val relative = "/stream-youtube-audio?video_id=abc&audio_url=encoded"
        val result = resolveStreamUrl(relative)
        assertTrue(result.startsWith(SonaraBackendConfig.BASE_URL))
        assertTrue(result.endsWith(relative))
    }

    @Test
    fun `stream URL never double-prefixes baseUrl`() {
        val relative = "/stream-youtube-audio?video_id=abc"
        val result = resolveStreamUrl(relative)
        val httpCount = result.split("http://").size - 1
        assertEquals("Should contain exactly one http://", 1, httpCount)
    }

    @Test
    fun `debug BASE_URL is non-empty and matches BuildConfig BASE_URL`() {
        assertEquals(com.example.sonara.BuildConfig.BASE_URL, SonaraBackendConfig.BASE_URL)
        assertTrue(SonaraBackendConfig.BASE_URL.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Helper: mirrors SonaraBackendClient.getStreamUrl relative-URL resolution
    // -------------------------------------------------------------------------
    private fun resolveStreamUrl(rawAudioUrl: String): String =
        if (rawAudioUrl.startsWith("http")) rawAudioUrl
        else "${SonaraBackendConfig.BASE_URL}$rawAudioUrl"
}
