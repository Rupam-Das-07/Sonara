package com.example.sonara.data.repository

import androidx.collection.LruCache
import com.example.sonara.data.remote.providers.lrclib.LrclibLyricsProvider
import com.example.sonara.domain.lyrics.RomanizationEngine
import com.example.sonara.domain.lyrics.WordTimingCalculator
import com.example.sonara.domain.model.LyricLine
import com.example.sonara.domain.model.Lyrics
import com.example.sonara.domain.repository.LyricsRepository

/**
 * Implements LyricsRepository with automatic Indic Romanization and positive in-memory LRU caching.
 * Follows Phase 4B-3 §16 & Phase 4C §5 rules (no negative caching of errors).
 */
class LyricsRepositoryImpl(
    private val remoteProvider: LrclibLyricsProvider = LrclibLyricsProvider()
) : LyricsRepository {

    // Positive in-memory cache: trackId -> Lyrics (max 100 entries)
    private val memoryCache = LruCache<String, Lyrics>(100)

    override suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long
    ): Result<Lyrics> {
        // Check in-memory cache first
        val cached = memoryCache[trackId]
        if (cached != null) {
            return Result.success(cached)
        }

        val result = remoteProvider.getLyrics(title, artist, durationMs)

        return result.map { rawLyrics ->
            val calibratedLyrics = com.example.sonara.domain.lyrics.LyricsOffsetCalibrator.calibrate(rawLyrics, title, artist)
            val romanizedLyrics = applyRomanization(calibratedLyrics)
            // Store successful lyrics in positive cache
            if (romanizedLyrics is Lyrics.SyncedLyrics || romanizedLyrics is Lyrics.PlainLyrics || romanizedLyrics is Lyrics.Instrumental) {
                memoryCache.put(trackId, romanizedLyrics)
            }
            romanizedLyrics
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
