package com.example.sonara.data.repository

import com.example.sonara.data.remote.providers.LyricsProviderResolver
import com.example.sonara.domain.lyrics.RomanizationEngine
import com.example.sonara.domain.lyrics.WordTimingCalculator
import com.example.sonara.domain.model.LyricLine
import com.example.sonara.domain.model.Lyrics
import com.example.sonara.domain.model.withMetadata
import com.example.sonara.domain.repository.LyricsRepository

/**
 * Implements LyricsRepository with automatic Indic Romanization.
 * Provider resolution, the positive in-memory cache, and in-flight request de-duplication are
 * delegated to [LyricsProviderResolver] (Lyrics Architecture Revision #1, Stage 2).
 * Follows Phase 4B-3 §16 & Phase 4C §5 rules (no negative caching of errors).
 */
class LyricsRepositoryImpl(
    private val resolver: LyricsProviderResolver
) : LyricsRepository {

    override suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long
    ): Result<Lyrics> {
        val result = resolver.getLyrics(trackId, title, artist, durationMs)

        return result.map { rawLyrics ->
            // Stage 3: capture provider metadata before the calibration + Romanization passes (both
            // reconstruct Lyrics subtypes and would otherwise reset metadata to the default), then
            // re-attach it to the processed result. Calibration/Romanization behavior is unchanged.
            val metadata = rawLyrics.metadata
            val calibratedLyrics = com.example.sonara.domain.lyrics.LyricsOffsetCalibrator.calibrate(rawLyrics, title, artist)
            applyRomanization(calibratedLyrics).withMetadata(metadata)
        }
    }

    private fun applyRomanization(lyrics: Lyrics): Lyrics {
        return when (lyrics) {
            is Lyrics.SyncedLyrics -> {
                val romanizedLines = lyrics.lines.map { line ->
                    val romanized = RomanizationEngine.transliterate(line.text)
                    val finalRomanized = if (romanized != line.text && romanized.isNotBlank()) romanized else null
                    LyricLine(
                        timestampMs = line.timestampMs,
                        text = line.text,
                        romanizedText = finalRomanized
                    )
                }
                val timedLines = WordTimingCalculator.calculate(romanizedLines)
                Lyrics.SyncedLyrics(timedLines)
            }
            is Lyrics.PlainLyrics -> {
                val romanized = RomanizationEngine.transliterate(lyrics.text)
                val finalRomanized = if (romanized != lyrics.text && romanized.isNotBlank()) romanized else null
                Lyrics.PlainLyrics(
                    text = lyrics.text,
                    romanizedText = finalRomanized
                )
            }
            else -> lyrics
        }
    }
}
