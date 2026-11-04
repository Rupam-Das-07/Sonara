package com.example.sonara.domain.lyrics

/**
 * Provider-neutral candidate for track matching.
 *
 * Carries exactly the fields the LRCLIB scoring heuristic reads, so the heuristic can be reused and
 * unit-tested without a JSON / network dependency.
 */
data class TrackCandidate(
    val syncedLyrics: String,
    val durationSec: Double,
    val artistName: String
)

/**
 * Reusable track-matching heuristic extracted from LrclibLyricsProvider (Lyrics Architecture
 * Revision #1, Stage 3, Change 2).
 *
 * The scoring arithmetic, thresholds, and tie-breaking are byte-for-byte identical to the previous
 * private `LrclibLyricsProvider.scoreCandidate` / `extractFirstTimestampSec`. Title / artist /
 * duration matching behavior is unchanged — this component only *relocates* the heuristic so it is
 * reusable and testable. No new matching algorithm is introduced.
 */
object TrackMatcher {

    /** Score returned for a candidate with no synced lyrics (lower = closer match). */
    const val NO_SYNCED_SCORE = 999999.0

    /**
     * Candidate match score; lower = closer. Preserves the exact heuristic:
     * duration delta (×3), artist-token proximity bonus (−15), video-intro-delay penalties
     * (+25 / +15), and completeness bonus/penalty (−5 / +20).
     */
    fun score(candidate: TrackCandidate, targetDurationSec: Double, targetArtist: String): Double {
        val synced = candidate.syncedLyrics.trim()
        if (synced.isBlank()) return NO_SYNCED_SCORE

        val dur = candidate.durationSec
        val durDiff = if (targetDurationSec > 0.0 && dur > 0.0) {
            kotlin.math.abs(dur - targetDurationSec)
        } else {
            50.0
        }
        var score = durDiff * 3.0

        // Artist proximity bonus
        if (targetArtist.isNotBlank()) {
            val candidateArtist = candidate.artistName.lowercase()
            val targetTokens = targetArtist.split(Regex("[,&;/]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE))
                .map { it.trim().lowercase() }
                .filter { it.length > 2 }
            if (targetTokens.any { candidateArtist.contains(it) }) {
                score -= 15.0
            }
        }

        // Dialogue / Video Intro Delay Check
        val firstTs = extractFirstTimestampSec(synced)
        if (firstTs > 30.0) {
            score += 25.0
        } else if (firstTs > 20.0 && durDiff > 4.0) {
            score += 15.0
        }

        // Completeness bonus (rewards well-formatted full tracks over truncated 5-line submissions)
        val lineCount = synced.lineSequence().count { it.isNotBlank() }
        if (lineCount >= 30) {
            score -= 5.0
        } else if (lineCount < 10) {
            score += 20.0
        }

        return score
    }

    /**
     * Display-only mapping of [score] (lower = closer) to a 0f..1f confidence (higher = closer).
     *
     * This is metadata only: it is NOT used to select candidates — selection uses [score] directly —
     * so it cannot alter matching behavior. Monotonically non-increasing in [score].
     */
    fun confidenceFromScore(score: Double): Float =
        (1.0 / (1.0 + kotlin.math.max(0.0, score))).toFloat()

    private fun extractFirstTimestampSec(syncedLyrics: String): Double {
        val line = syncedLyrics.lineSequence().firstOrNull { it.trim().isNotBlank() } ?: return 0.0
        val match = Regex("\\[(\\d+):(\\d+\\.?\\d*)\\]").find(line) ?: return 0.0
        val min = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val sec = match.groupValues[2].toDoubleOrNull() ?: 0.0
        return (min * 60) + sec
    }
}
