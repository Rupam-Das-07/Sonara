package com.example.sonara.feature.import

import com.example.sonara.data.import.spotify.SpotifyCsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class SpotifyCsvParserTest {

    @Test
    fun parse_standardExportifyCsv_preservesSourceOrderAndMetadata() {
        val csv = """
            Spotify ID,Track Name,Artist Name(s),Album Name,Duration (ms),ISRC
            spotify:track:111,Blinding Lights,The Weeknd,After Hours,200000,USUG11904206
            spotify:track:222,Save Your Tears,The Weeknd,After Hours,215000,USUG12000678
            spotify:track:333,In Your Eyes,The Weeknd,After Hours,237000,USUG12000789
        """.trimIndent()

        val summary = SpotifyCsvParser.parse(ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)), "My Playlist")

        assertEquals("My Playlist", summary.playlistName)
        assertEquals(3, summary.totalSourceRows)
        assertEquals(3, summary.parsedRows)
        assertEquals(0, summary.skippedRows)
        assertEquals(3, summary.tracks.size)

        val t0 = summary.tracks[0]
        assertEquals(0, t0.sourceOrder)
        assertEquals("Blinding Lights", t0.title)
        assertEquals("The Weeknd", t0.artist)
        assertEquals("After Hours", t0.album)
        assertEquals(200000L, t0.durationMs)
        assertEquals("USUG11904206", t0.isrc)
        assertEquals("spotify:track:111", t0.spotifyUri)
        assertFalse(t0.isLocalFile)
        assertFalse(t0.isEpisode)

        val t1 = summary.tracks[1]
        assertEquals(1, t1.sourceOrder)
        assertEquals("Save Your Tears", t1.title)

        val t2 = summary.tracks[2]
        assertEquals(2, t2.sourceOrder)
        assertEquals("In Your Eyes", t2.title)
    }

    @Test
    fun parse_toleratesColumnReorderingAndMissingOptionalColumns() {
        val csv = """
            Artist Name,Track Name
            Queen,Bohemian Rhapsody
            Radiohead,Creep
        """.trimIndent()

        val summary = SpotifyCsvParser.parse(ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)))

        assertEquals(2, summary.totalSourceRows)
        assertEquals(2, summary.parsedRows)
        assertEquals(2, summary.tracks.size)

        assertEquals("Bohemian Rhapsody", summary.tracks[0].title)
        assertEquals("Queen", summary.tracks[0].artist)
        assertNull(summary.tracks[0].album)
        assertNull(summary.tracks[0].durationMs)
        assertNull(summary.tracks[0].isrc)

        assertEquals("Creep", summary.tracks[1].title)
        assertEquals("Radiohead", summary.tracks[1].artist)
    }

    @Test
    fun parse_handlesQuotedCommasAndEscapedQuotes() {
        val csv = "\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\n" +
            "\"Song, With Comma\",\"Artist A, Artist B\",\"Album, Vol. 1\",180000\n" +
            "\"She Said \"\"Hello\"\"\",\"Artist C\",\"Album 2\",210000"

        val summary = SpotifyCsvParser.parse(ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)))

        assertEquals(2, summary.tracks.size)
        assertEquals("Song, With Comma", summary.tracks[0].title)
        assertEquals("Artist A, Artist B", summary.tracks[0].artist)
        assertEquals("Album, Vol. 1", summary.tracks[0].album)
        assertEquals(180000L, summary.tracks[0].durationMs)

        assertEquals("She Said \"Hello\"", summary.tracks[1].title)
        assertEquals("Artist C", summary.tracks[1].artist)
    }

    @Test
    fun parse_stripsUtf8Bom() {
        val csvWithBom = "\uFEFFTrack Name,Artist Name\nSong 1,Artist 1"
        val summary = SpotifyCsvParser.parse(ByteArrayInputStream(csvWithBom.toByteArray(StandardCharsets.UTF_8)))

        assertEquals(1, summary.tracks.size)
        assertEquals("Song 1", summary.tracks[0].title)
        assertEquals("Artist 1", summary.tracks[0].artist)
    }

    @Test
    fun parse_handlesCrlfNewlines() {
        val csvCrlf = "Track Name,Artist Name\r\nSong 1,Artist 1\r\nSong 2,Artist 2\r\n"
        val summary = SpotifyCsvParser.parse(ByteArrayInputStream(csvCrlf.toByteArray(StandardCharsets.UTF_8)))

        assertEquals(2, summary.tracks.size)
        assertEquals("Song 1", summary.tracks[0].title)
        assertEquals("Song 2", summary.tracks[1].title)
    }

    @Test
    fun parse_parsesMmSsDurationFormat() {
        val csv = """
            Track Name,Artist Name,Duration
            Song 1,Artist 1,3:45
            Song 2,Artist 2,1:05:30
        """.trimIndent()

        val summary = SpotifyCsvParser.parse(ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)))

        assertEquals(2, summary.tracks.size)
        // 3:45 = (3*60 + 45) * 1000 = 225,000 ms
        assertEquals(225000L, summary.tracks[0].durationMs)
        // 1:05:30 = (3600 + 5*60 + 30) * 1000 = 3,930,000 ms
        assertEquals(3930000L, summary.tracks[1].durationMs)
    }

    @Test
    fun parse_detectsLocalFilesAndPodcastEpisodes() {
        val csv = """
            Spotify ID,Track Name,Artist Name
            spotify:local:Artist:Album:LocalTrack:123,My Local Song,Local Artist
            spotify:episode:abc12345,Tech Podcast Ep 42,Tech Host
            spotify:track:xyz,Normal Song,Normal Artist
        """.trimIndent()

        val summary = SpotifyCsvParser.parse(ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)))

        assertEquals(3, summary.totalSourceRows)
        assertEquals(1, summary.localFilesCount)
        assertEquals(1, summary.episodesCount)
        assertEquals(1, summary.parsedRows) // Only 1 standard track
        assertEquals(2, summary.skippedRows) // 1 local + 1 episode

        assertTrue(summary.tracks[0].isLocalFile)
        assertFalse(summary.tracks[0].isEpisode)

        assertFalse(summary.tracks[1].isLocalFile)
        assertTrue(summary.tracks[1].isEpisode)

        assertFalse(summary.tracks[2].isLocalFile)
        assertFalse(summary.tracks[2].isEpisode)
    }
}
