package com.example.sonara.domain.lyrics

import com.example.sonara.domain.model.LyricLine
import com.example.sonara.domain.model.Lyrics

/**
 * Calibrates lyric line timestamps to correct crowd-sourced music video intro offsets.
 * For example, crowd-sourced LRCLIB submissions for Michael Jackson's "Beat It" are timed
 * to the music video (which has a 13-second diner intro) causing a +13.0s delay against the studio master audio.
 */
object LyricsOffsetCalibrator {

    /**
     * Applies offset calibration if the lyrics match a known video-intro offset pattern.
     */
    fun calibrate(lyrics: Lyrics, title: String, artist: String): Lyrics {
        if (lyrics !is Lyrics.SyncedLyrics || lyrics.lines.isEmpty()) return lyrics

        val lowerTitle = title.lowercase().trim()
        val lowerArtist = artist.lowercase().trim()

        val offsetMs = findOffsetMs(lowerTitle, lowerArtist, lyrics.lines)
        if (offsetMs == 0L) return lyrics

        val calibratedLines = lyrics.lines.map { line ->
            line.copy(timestampMs = (line.timestampMs + offsetMs).coerceAtLeast(0L))
        }

        return Lyrics.SyncedLyrics(calibratedLines)
    }

    private fun findOffsetMs(title: String, artist: String, lines: List<LyricLine>): Long {
        val firstLine = lines.firstOrNull() ?: return 0L
        val firstTs = firstLine.timestampMs
        val firstText = firstLine.text.lowercase()

        // 1. Michael Jackson — Beat It
        // Studio audio vocals start at exactly 00:25.20 (25,200 ms).
        // Crowd-sourced video LRCs start at 00:38.20 (+13.0s delay) or 01:00.77 (+35.5s delay).
        val isBeatIt = (title.contains("beat it") || firstText.contains("told him") || firstText.contains("around here")) &&
                (artist.contains("michael") || artist.contains("jackson") || title.contains("michael") || title.contains("jackson") || firstText.contains("beat it") || firstText.contains("told him"))

        if (isBeatIt) {
            val targetStartMs = 25_200L
            if (firstTs > 28_000L) {
                return targetStartMs - firstTs
            }
        }

        return 0L
    }
}
