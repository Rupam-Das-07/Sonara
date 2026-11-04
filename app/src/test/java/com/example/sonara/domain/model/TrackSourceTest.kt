package com.example.sonara.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackSourceTest {

    // ─── 1. SoundCloud Source Identification ─────────────────────────────────

    @Test
    fun `soundCloud track IDs with sc prefix resolve to SOUNDCLOUD`() {
        assertEquals(
            TrackSource.SOUNDCLOUD,
            TrackSource.fromTrackId("sc:https://soundcloud.com/artist/lofi-track")
        )
        assertEquals(
            TrackSource.SOUNDCLOUD,
            TrackSource.fromTrackId("sc:12345678")
        )
    }

    @Test
    fun `soundCloud track IDs with soundcloud legacy prefix resolve to SOUNDCLOUD`() {
        assertEquals(
            TrackSource.SOUNDCLOUD,
            TrackSource.fromTrackId("soundcloud:98765432")
        )
    }

    @Test
    fun `soundCloud track model resolves to SOUNDCLOUD`() {
        val track = Track(
            id = "sc:https://soundcloud.com/user/track",
            title = "Chill Lofi",
            artist = "SoundCloud Creator"
        )
        assertEquals(TrackSource.SOUNDCLOUD, TrackSource.fromTrack(track))
        assertEquals(TrackSource.SOUNDCLOUD, track.source)
    }

    // ─── 2. YouTube Music Source Identification ──────────────────────────────

    @Test
    fun `canonical 11-character YouTube video IDs resolve to YOUTUBE_MUSIC`() {
        // Standard YouTube IDs
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("dQw4w9WgXcQ"))
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("abc12345678"))
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("c0000000001"))
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("5-sfS9V9qQc"))
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("sFMR_xCexDk"))
    }

    @Test
    fun `prefixed YouTube test and integration IDs resolve to YOUTUBE_MUSIC`() {
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("yt_1"))
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("yt_official"))
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("yt:dQw4w9WgXcQ"))
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("youtube:sample123"))
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrackId("ytmusic:track_abc"))
    }

    @Test
    fun `youTube Music track model resolves to YOUTUBE_MUSIC`() {
        val track = Track(
            id = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley"
        )
        assertEquals(TrackSource.YOUTUBE_MUSIC, TrackSource.fromTrack(track))
        assertEquals(TrackSource.YOUTUBE_MUSIC, track.source)
    }

    // ─── 3. Unknown / Missing Metadata Fails Closed (No Fabrication) ─────────

    @Test
    fun `null, blank, or empty track IDs resolve to null`() {
        assertNull(TrackSource.fromTrackId(null))
        assertNull(TrackSource.fromTrackId(""))
        assertNull(TrackSource.fromTrackId("   "))
        assertNull(TrackSource.fromTrack(null))
    }

    @Test
    fun `unrecognized short or arbitrary mock IDs resolve to null`() {
        // Short or arbitrary strings that are not valid 11-char YT IDs and have no supported prefix
        assertNull(TrackSource.fromTrackId("1"))
        assertNull(TrackSource.fromTrackId("2"))
        assertNull(TrackSource.fromTrackId("track"))
        assertNull(TrackSource.fromTrackId("track-1"))
        assertNull(TrackSource.fromTrackId("track-101"))
        assertNull(TrackSource.fromTrackId("rec_1"))
        assertNull(TrackSource.fromTrackId("id_0"))
        assertNull(TrackSource.fromTrackId("sample123")) // 9 chars
        assertNull(TrackSource.fromTrackId("f2_track"))   // 8 chars
        assertNull(TrackSource.fromTrackId("unknown"))
    }

    @Test
    fun `non-integrated external schemes do not fabricate a source`() {
        assertNull(TrackSource.fromTrackId("spotify:track:4cOdK2wGLETKBW3PvgPWqT"))
        assertNull(TrackSource.fromTrackId("jiosaavn:song:12345"))
        assertNull(TrackSource.fromTrackId("local:storage:file123"))
        assertNull(TrackSource.fromTrackId("file:///sdcard/music/test.mp3"))
        assertNull(TrackSource.fromTrackId("custom_provider:track_99"))
    }

    // ─── 4. Integrated Catalog Sources Completeness ──────────────────────────

    @Test
    fun `only supported sources are represented in TrackSource enum`() {
        val expectedSources = setOf(
            TrackSource.YOUTUBE_MUSIC,
            TrackSource.SOUNDCLOUD
        )
        assertEquals(expectedSources, TrackSource.entries.toSet())
    }
}
