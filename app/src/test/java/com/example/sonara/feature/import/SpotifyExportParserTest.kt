package com.example.sonara.feature.import

import com.example.sonara.data.import.spotify.SpotifyExportParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class SpotifyExportParserTest {

    @Test
    fun parse_csvContent_routesToCsvParser() {
        val csv = "Track Name,Artist Name\nSong A,Artist A"
        val summary = SpotifyExportParser.parse(ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)))

        assertEquals(1, summary.tracks.size)
        assertEquals("Song A", summary.tracks[0].title)
    }

    @Test
    fun parse_jsonContent_routesToJsonParserAndThrowsPendingVerification() {
        val json = """[{"track": "Song A", "artist": "Artist A"}]"""
        val exception = assertThrows(UnsupportedOperationException::class.java) {
            SpotifyExportParser.parse(ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)))
        }

        assertTrue(exception.message!!.contains("pending verified sample schema"))
    }

    @Test
    fun parse_emptyStream_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            SpotifyExportParser.parse(ByteArrayInputStream(ByteArray(0)))
        }
    }
}
