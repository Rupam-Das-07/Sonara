package com.example.sonara.domain.model

/**
 * Domain representation of an individual word within a lyric line with estimated/actual timestamps.
 */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * Domain representation of a single line of lyrics with optional timestamp, Romanization, and word timings.
 */
data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val romanizedText: String? = null,
    val words: List<LyricWord> = emptyList(),
    val romanizedWords: List<LyricWord> = emptyList()
)

/**
 * Domain representation of lyrics for a track.
 */
sealed class Lyrics {
    data class SyncedLyrics(
        val lines: List<LyricLine>
    ) : Lyrics()

    data class PlainLyrics(
        val text: String,
        val romanizedText: String? = null
    ) : Lyrics()

    data object Instrumental : Lyrics()

    data class Unavailable(
        val reason: String = "Lyrics not available"
    ) : Lyrics()
}
