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
 * Additive provider / match-quality metadata attached to a [Lyrics] result
 * (Lyrics Architecture Revision #1, Stage 3).
 *
 * Every field is optional and defaults to null ("unknown"), so this type is purely additive:
 * existing [Lyrics] construction sites that do not supply metadata keep compiling and behave
 * exactly as before.
 *
 * @property source identifier of the provider that produced the lyrics (e.g. "LRCLIB"); null if unknown.
 * @property language BCP-47-ish language tag if the provider exposes one; null if unknown.
 * @property matchConfidence display-only match-quality signal in 0f..1f derived from the provider's
 *   existing candidate score (higher = closer). It is metadata only and does NOT participate in
 *   candidate selection. Null when the provider did not score the chosen candidate.
 */
data class LyricsMetadata(
    val source: String? = null,
    val language: String? = null,
    val matchConfidence: Float? = null
)

/**
 * Domain representation of lyrics for a track.
 */
sealed class Lyrics {
    /**
     * Additive provider/quality metadata (Stage 3). Defaults to empty [LyricsMetadata] so existing
     * behavior and tests are unaffected.
     */
    abstract val metadata: LyricsMetadata

    data class SyncedLyrics(
        val lines: List<LyricLine>,
        override val metadata: LyricsMetadata = LyricsMetadata()
    ) : Lyrics()

    data class PlainLyrics(
        val text: String,
        val romanizedText: String? = null,
        override val metadata: LyricsMetadata = LyricsMetadata()
    ) : Lyrics()

    data object Instrumental : Lyrics() {
        override val metadata: LyricsMetadata = LyricsMetadata()
    }

    data class Unavailable(
        val reason: String = "Lyrics not available",
        override val metadata: LyricsMetadata = LyricsMetadata()
    ) : Lyrics()
}

/**
 * Returns a copy of this [Lyrics] with [metadata] applied.
 *
 * Used to re-attach provider metadata after the (subtype-reconstructing) offset-calibration and
 * Romanization passes, which rebuild [Lyrics.SyncedLyrics]/[Lyrics.PlainLyrics] and would otherwise
 * reset metadata to the default. [Lyrics.Instrumental] is a singleton that carries only default
 * metadata, so it is returned unchanged.
 */
fun Lyrics.withMetadata(metadata: LyricsMetadata): Lyrics = when (this) {
    is Lyrics.SyncedLyrics -> copy(metadata = metadata)
    is Lyrics.PlainLyrics -> copy(metadata = metadata)
    is Lyrics.Instrumental -> this
    is Lyrics.Unavailable -> copy(metadata = metadata)
}
