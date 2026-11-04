package com.example.sonara.feature.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.sonara.core.util.MediaTitleFormatter

class VideoTitleFormatterTest {

    @Test
    fun `video facade delegates exactly to shared formatter`() {
        val raw = "Imagine Dragons - Believer (Official Music Video)"
        assertEquals(
            MediaTitleFormatter.format(raw, "Imagine Dragons"),
            VideoTitleFormatter.format(raw, "Imagine Dragons")
        )
    }

    @Test
    fun `formats complex Indian cinema pipe metadata title to song name only`() {
        val raw = "One Name - Lyric Video | Jailer 2 | Superstar Rajinikanth | Sun Pictures | Nelson | Anirudh"
        val formatted = VideoTitleFormatter.format(raw, artist = "Sun Pictures")
        assertEquals("One Name", formatted)
    }

    @Test
    fun `strips redundant artist prefix and official music video badge`() {
        val raw = "Imagine Dragons - Believer (Official Music Video)"
        val formatted = VideoTitleFormatter.format(raw, artist = "Imagine Dragons")
        assertEquals("Believer", formatted)
    }

    @Test
    fun `strips featured artist and movie attribution brackets`() {
        val raw = "Don Toliver - Lose My Mind (feat. Doja Cat) [From F1 The Movie] [Official Music Video]"
        val formatted = VideoTitleFormatter.format(raw, artist = "Don Toliver")
        assertEquals("Lose My Mind", formatted)
    }

    @Test
    fun `strips movie attribution and official video label`() {
        val raw = "Tate McRae - Just Keep Watching (From F1 The Movie) (Official Video)"
        val formatted = VideoTitleFormatter.format(raw, artist = "Tate McRae")
        assertEquals("Just Keep Watching", formatted)
    }

    @Test
    fun `preserves meaningful live acoustic version distinction in brackets`() {
        val raw = "The Weeknd - Blinding Lights (Live Acoustic Version)"
        val formatted = VideoTitleFormatter.format(raw, artist = "The Weeknd")
        assertEquals("Blinding Lights (Live Acoustic Version)", formatted)
    }

    @Test
    fun `preserves remaster version distinction in brackets`() {
        val raw = "Queen - Bohemian Rhapsody (Remastered 2011)"
        val formatted = VideoTitleFormatter.format(raw, artist = "Queen")
        assertEquals("Bohemian Rhapsody (Remastered 2011)", formatted)
    }

    @Test
    fun `strips featured artist and promo label`() {
        val raw = "Dua Lipa - Levitating (feat. DaBaby) (Official Music Video)"
        val formatted = VideoTitleFormatter.format(raw, artist = "Dua Lipa")
        assertEquals("Levitating", formatted)
    }

    @Test
    fun `preserves meaningful musical version suffix after hyphen without brackets`() {
        val raw = "Song Name - Live Acoustic Version"
        val formatted = VideoTitleFormatter.format(raw)
        assertEquals("Song Name - Live Acoustic Version", formatted)
    }

    @Test
    fun `preserves live performance distinction from secondary pipe segment`() {
        val raw = "Song Name | Live at Wembley"
        val formatted = VideoTitleFormatter.format(raw)
        assertTrue(formatted.contains("Song Name"))
        assertTrue(formatted.contains("Live at Wembley"))
    }

    @Test
    fun `formats animated music video through video compatibility facade`() {
        val raw = "Levitating ( Official Animated Music Video )"
        val formatted = VideoTitleFormatter.format(raw, artist = "Dua Lipa")
        assertEquals("Levitating", formatted)

        val fullRaw = "Dua Lipa - Levitating (Official Animated Music Video)"
        assertEquals("Levitating", VideoTitleFormatter.format(fullRaw, artist = "Dua Lipa"))
    }
}
