package com.example.sonara.domain.lyrics

import com.example.sonara.domain.model.LyricLine
import com.example.sonara.domain.model.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsOffsetCalibratorTest {

    @Test
    fun calibrate_beatItMichaelJackson_shiftsByMinus13Seconds() {
        val originalLines = listOf(
            LyricLine(timestampMs = 38_380L, text = "They told him, \"Don't you ever come around here\""),
            LyricLine(timestampMs = 41_450L, text = "\"Don't wanna see your face, you better disappear\""),
            LyricLine(timestampMs = 44_720L, text = "The fire's in their eyes and their words are really clear")
        )
        val originalLyrics = Lyrics.SyncedLyrics(originalLines)

        val calibrated = LyricsOffsetCalibrator.calibrate(
            lyrics = originalLyrics,
            title = "Beat It",
            artist = "Michael Jackson"
        )

        assertTrue(calibrated is Lyrics.SyncedLyrics)
        val synced = calibrated as Lyrics.SyncedLyrics
        assertEquals(25_200L, synced.lines[0].timestampMs)
        assertEquals(28_270L, synced.lines[1].timestampMs)
        assertEquals(31_540L, synced.lines[2].timestampMs)
    }

    @Test
    fun calibrate_beatItWithYouTubeTitleAndEmptyArtist_shiftsCorrectly() {
        val originalLines = listOf(
            LyricLine(timestampMs = 38_200L, text = "They told him, \"Don't you ever come around here\""),
            LyricLine(timestampMs = 41_160L, text = "\"Don't wanna see your face, you better disappear\"")
        )
        val originalLyrics = Lyrics.SyncedLyrics(originalLines)

        val calibrated = LyricsOffsetCalibrator.calibrate(
            lyrics = originalLyrics,
            title = "Michael Jackson - Beat It (Official 4K Video)",
            artist = ""
        )

        assertTrue(calibrated is Lyrics.SyncedLyrics)
        val synced = calibrated as Lyrics.SyncedLyrics
        assertEquals(25_200L, synced.lines[0].timestampMs)
        assertEquals(28_160L, synced.lines[1].timestampMs)
    }

    @Test
    fun calibrate_regularSong_doesNotShift() {
        val originalLines = listOf(
            LyricLine(timestampMs = 12_000L, text = "First line"),
            LyricLine(timestampMs = 16_000L, text = "Second line")
        )
        val originalLyrics = Lyrics.SyncedLyrics(originalLines)

        val calibrated = LyricsOffsetCalibrator.calibrate(
            lyrics = originalLyrics,
            title = "Random Song",
            artist = "Random Artist"
        )

        assertTrue(calibrated is Lyrics.SyncedLyrics)
        val synced = calibrated as Lyrics.SyncedLyrics
        assertEquals(12_000L, synced.lines[0].timestampMs)
        assertEquals(16_000L, synced.lines[1].timestampMs)
    }
}
