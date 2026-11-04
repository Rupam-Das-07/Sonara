package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3 (Change 2) tests for [TrackMatcher] — the track-matching heuristic extracted verbatim from
 * LrclibLyricsProvider. These lock in the existing scoring behavior (duration delta ×3, artist
 * proximity −15, intro-delay penalties, completeness bonus/penalty), confirm that a clearly different
 * track scores far worse than a close match, and verify the display-only confidence mapping. No new
 * matching algorithm is introduced, so these tests also serve as a regression guard on the relocation.
 */
class TrackMatcherTest {

    /** Builds [lineCount] non-blank LRC lines; only the first line's timestamp affects intro detection. */
    private fun buildSynced(lineCount: Int, firstTsTag: String = "[00:05.00]"): String =
        (0 until lineCount).joinToString("\n") { i ->
            val tag = if (i == 0) firstTsTag else "[00:%02d.00]".format((i % 40) + 10)
            "$tag line $i"
        }

    @Test
    fun `candidate without synced lyrics gets the no-match score`() {
        assertEquals(TrackMatcher.NO_SYNCED_SCORE, TrackMatcher.score(TrackCandidate("", 200.0, "a"), 200.0, "a"), 0.0)
        assertEquals(TrackMatcher.NO_SYNCED_SCORE, TrackMatcher.score(TrackCandidate("   ", 200.0, "a"), 200.0, "a"), 0.0)
    }

    @Test
    fun `closer duration scores lower than farther duration`() {
        val lyrics = buildSynced(30)
        val close = TrackMatcher.score(TrackCandidate(lyrics, 200.0, "zzz"), 200.0, "qqq")
        val far = TrackMatcher.score(TrackCandidate(lyrics, 260.0, "zzz"), 200.0, "qqq")
        assertTrue("Closer duration must score lower (better)", close < far)
        // Only the duration delta differs: 60s * 3.0 = 180.0.
        assertEquals(180.0, far - close, 0.0001)
    }

    @Test
    fun `artist token match reduces score by exactly 15`() {
        val lyrics = buildSynced(30)
        val matched = TrackMatcher.score(TrackCandidate(lyrics, 200.0, "Adele"), 200.0, "Adele")
        val unmatched = TrackMatcher.score(TrackCandidate(lyrics, 200.0, "Unknown Singer"), 200.0, "Adele")
        assertEquals(15.0, unmatched - matched, 0.0001)
    }

    @Test
    fun `late first timestamp adds an intro-delay penalty`() {
        val onTime = TrackMatcher.score(TrackCandidate(buildSynced(30, "[00:05.00]"), 200.0, "zzz"), 200.0, "qqq")
        val late = TrackMatcher.score(TrackCandidate(buildSynced(30, "[00:40.00]"), 200.0, "zzz"), 200.0, "qqq")
        // firstTs > 30s adds +25.0; nothing else differs.
        assertEquals(25.0, late - onTime, 0.0001)
    }

    @Test
    fun `very short lyrics are penalized relative to complete lyrics`() {
        val complete = TrackMatcher.score(TrackCandidate(buildSynced(30), 200.0, "zzz"), 200.0, "qqq")
        val sparse = TrackMatcher.score(TrackCandidate(buildSynced(5), 200.0, "zzz"), 200.0, "qqq")
        assertTrue("Sparse lyrics must score worse than complete lyrics", sparse > complete)
        // complete (>=30 lines): -5.0; sparse (<10 lines): +20.0; delta = 25.0.
        assertEquals(25.0, sparse - complete, 0.0001)
    }

    @Test
    fun `clearly different track scores far worse than a close match`() {
        val target = 200.0
        val exact = TrackMatcher.score(TrackCandidate(buildSynced(32, "[00:03.00]"), 200.0, "Adele"), target, "Adele")
        val different = TrackMatcher.score(TrackCandidate(buildSynced(6, "[00:45.00]"), 260.0, "Other Band"), target, "Adele")
        assertTrue("A clearly different track must score worse than a close match", different > exact)
    }

    @Test
    fun `minByOrNull selection picks the closest candidate exactly as before`() {
        val target = 200.0
        val targetArtist = "Adele"
        val good = TrackCandidate(buildSynced(32, "[00:02.00]"), 201.0, "Adele")
        val wrongDuration = TrackCandidate(buildSynced(32, "[00:02.00]"), 320.0, "Adele")
        val wrongArtist = TrackCandidate(buildSynced(32, "[00:02.00]"), 201.0, "Someone Else")
        val pool = listOf(wrongDuration, wrongArtist, good)
        val best = pool.minByOrNull { TrackMatcher.score(it, target, targetArtist) }
        assertEquals(good, best)
    }

    @Test
    fun `confidenceFromScore is within unit range and non-increasing in score`() {
        val c0 = TrackMatcher.confidenceFromScore(0.0)
        val c1 = TrackMatcher.confidenceFromScore(1.0)
        val c9 = TrackMatcher.confidenceFromScore(9.0)
        assertEquals(1.0f, c0, 0.0001f)
        assertEquals(0.5f, c1, 0.0001f)
        assertEquals(0.1f, c9, 0.0001f)
        assertTrue("Confidence must be non-increasing in score", c0 >= c1 && c1 >= c9)
        // Negative (very strong) scores clamp to full confidence; the result always stays in 0f..1f.
        assertEquals(1.0f, TrackMatcher.confidenceFromScore(-20.0), 0.0001f)
        assertTrue(TrackMatcher.confidenceFromScore(TrackMatcher.NO_SYNCED_SCORE) in 0.0f..1.0f)
    }
}
