package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FA-001 — the shared future-timestamp policy.
 *
 * Before this fix both the calculator and the selector did
 * `(nowMs - listenedAt).coerceAtLeast(0L)` and then tested the clamped value against the window.
 * A future-dated row therefore reported age 0, passed the window test, earned the MAXIMUM
 * recency weight, and counted toward the thresholds that gate ordering and personal admission.
 *
 * The policy now lives in one place, [ArtistAffinityCalculator.scoringAgeMsOrNull], and has three
 * bands: at-or-before `nowMs` scores normally; up to
 * [ArtistAffinityCalculator.FUTURE_TOLERANCE_MS] in the future is plausible clock skew and still
 * scores as age 0; beyond that the row is dropped outright — from scoring AND from the event
 * counts, because a merely-low score would still have bought admission.
 *
 * Every timestamp here is injected, so no case depends on the system clock.
 */
class FeaturedArtistTimestampPolicyTest {

    private val now = 1_700_000_000_000L
    private val minuteMs = 60_000L
    private val dayMs = 24L * 60L * 60L * 1000L
    private val windowMs = ArtistAffinityCalculator.HISTORY_WINDOW_DAYS * dayMs
    private val toleranceMs = ArtistAffinityCalculator.FUTURE_TOLERANCE_MS

    private fun event(
        artist: String,
        listenedAt: Long,
        origin: PlaybackOrigin = PlaybackOrigin.SEARCH_SONGS,
        completed: Boolean = false,
        index: Int = 0
    ) = HistoryItem(
        id = index.toLong(),
        track = Track(id = "vid-$artist-$index-$listenedAt", title = "Song", artist = artist),
        listenedAt = listenedAt,
        completed = completed,
        sourceSection = origin
    )

    private fun events(
        artist: String,
        listenedAt: Long,
        count: Int,
        origin: PlaybackOrigin = PlaybackOrigin.SEARCH_SONGS
    ) = (0 until count).map { event(artist, listenedAt, origin, index = it) }

    /** An editorial roster that deliberately does NOT contain the personal artist under test. */
    private val roster = (1..8).map { FeaturedArtist(id = "ed$it", name = "Editorial $it") }

    private fun scoreOf(history: List<HistoryItem>, artist: String): Float? =
        ArtistAffinityCalculator.lookupAffinity(
            ArtistAffinityCalculator.calculateAffinity(history, now),
            artist
        )

    private fun admitted(history: List<HistoryItem>, artist: String): Boolean =
        FeaturedArtistSelector.select(roster, history, now).any { it.name == artist }

    // ------------------------------------------------- 1. normal past, correct age

    @Test
    fun `a normal recent past event is accepted with its real age`() {
        assertEquals(2 * dayMs, ArtistAffinityCalculator.scoringAgeMsOrNull(now, now - 2 * dayMs))
        assertEquals(0L, ArtistAffinityCalculator.scoringAgeMsOrNull(now, now))

        // And it still scores: two zero-age, non-completed events => 2 x 1.0
        val score = scoreOf(events("Artist", now, 2), "Artist")
        assertNotNull(score)
        assertEquals(2.0f, score!!, 1e-4f)
    }

    // --------------------------------------------- 2,3. history-window boundaries

    @Test
    fun `an event exactly at the window edge keeps the existing inclusive contract`() {
        assertEquals(
            "age == window was included before this change and must stay included",
            windowMs,
            ArtistAffinityCalculator.scoringAgeMsOrNull(now, now - windowMs)
        )
        assertEquals(
            windowMs - 1,
            ArtistAffinityCalculator.scoringAgeMsOrNull(now, now - windowMs + 1)
        )
    }

    @Test
    fun `an event just outside the window is rejected`() {
        assertNull(ArtistAffinityCalculator.scoringAgeMsOrNull(now, now - windowMs - 1))
        assertTrue(
            ArtistAffinityCalculator.calculateAffinity(events("Artist", now - windowMs - 1, 5), now)
                .isEmpty()
        )
    }

    // ------------------------------------------- 4,5. future-tolerance boundaries

    @Test
    fun `an event exactly at the future tolerance edge is accepted as age zero`() {
        assertEquals(0L, ArtistAffinityCalculator.scoringAgeMsOrNull(now, now + toleranceMs))
        assertEquals(0L, ArtistAffinityCalculator.scoringAgeMsOrNull(now, now + 1))
        assertEquals(0L, ArtistAffinityCalculator.scoringAgeMsOrNull(now, now + toleranceMs - 1))
    }

    @Test
    fun `an event one millisecond beyond the tolerance is rejected`() {
        assertNull(ArtistAffinityCalculator.scoringAgeMsOrNull(now, now + toleranceMs + 1))
        assertNull(ArtistAffinityCalculator.scoringAgeMsOrNull(now, now + 6 * minuteMs))
    }

    @Test
    fun `a slightly future event still scores like a just-recorded listen`() {
        val score = scoreOf(events("Artist", now + toleranceMs, 2), "Artist")
        assertNotNull("within tolerance a row must remain a real listen", score)
        assertEquals(2.0f, score!!, 1e-4f)
    }

    // ------------------------------------- 6. significantly future cannot inflate

    @Test
    fun `a significantly future-dated event cannot inflate affinity`() {
        for (offset in listOf(6 * minuteMs, dayMs, 30 * dayMs, 400 * dayMs)) {
            val history = events("Artist", now + offset, 5)
            assertTrue(
                "offset=+${offset}ms must contribute no affinity",
                ArtistAffinityCalculator.calculateAffinity(history, now).isEmpty()
            )
            assertNull(scoreOf(history, "Artist"))
        }
    }

    // ----------------------------- 7. cannot satisfy ordering/membership thresholds

    @Test
    fun `future-dated rows beyond tolerance cannot reach the ordering threshold`() {
        // MIN_QUALIFYING_EVENTS is 2; ten future rows must still produce nothing.
        assertTrue(
            ArtistAffinityCalculator.calculateAffinity(events("Hans Zimmer", now + 400 * dayMs, 10), now)
                .isEmpty()
        )
    }

    @Test
    fun `future-dated rows beyond tolerance cannot win personal admission`() {
        assertEquals(3, FeaturedArtistSelector.MIN_PERSONAL_CANDIDATE_EVENTS)
        val history = events("Hans Zimmer", now + 400 * dayMs, 10)

        assertFalse("a clock anomaly must not buy a shelf slot", admitted(history, "Hans Zimmer"))
        assertEquals(
            "rejected timestamps must not enable the editorial shelf",
            emptyList<String>(),
            FeaturedArtistSelector.select(roster, history, now).map { it.name }
        )
    }

    @Test
    fun `rows within tolerance can still win admission`() {
        // Guards against over-correcting: legitimate just-recorded sessions must still qualify.
        assertTrue(admitted(events("Hans Zimmer", now + toleranceMs, 4), "Hans Zimmer"))
        assertTrue(admitted(events("Hans Zimmer", now - minuteMs, 4), "Hans Zimmer"))
    }

    // ------------------------------------------------------------- 8. mixed input

    @Test
    fun `a mixed set counts only the valid and slightly-future rows`() {
        val history =
            events("Artist", now - dayMs, 2) +                    // valid
                events("Artist", now + toleranceMs, 1) +          // slightly future, tolerated
                events("Artist", now + 400 * dayMs, 5) +          // invalid future
                events("Artist", now - windowMs - 1, 5)           // stale

        val score = scoreOf(history, "Artist")
        assertNotNull(score)

        // Only the 3 accepted rows contribute: 2 at one day old + 1 at age 0.
        val expected = 2 * Math.exp(-1.0 / ArtistAffinityCalculator.RECENCY_DECAY_DAYS).toFloat() + 1.0f
        assertEquals(expected, score!!, 1e-3f)
    }

    @Test
    fun `invalid future rows cannot top up a below-threshold artist`() {
        // One valid event is under MIN_QUALIFYING_EVENTS; future rows must not make up the gap.
        val history = events("Artist", now - dayMs, 1) + events("Artist", now + 400 * dayMs, 9)
        assertTrue(ArtistAffinityCalculator.calculateAffinity(history, now).isEmpty())
    }

    // -------------------------------- 9. scoring and selection share one policy

    @Test
    fun `scoring and candidate selection agree on every timestamp band`() {
        val offsets = listOf(
            -400 * dayMs to false,          // stale
            -windowMs - 1 to false,         // just outside
            -windowMs to true,              // window edge
            -dayMs to true,                 // normal
            0L to true,                     // now
            toleranceMs to true,            // tolerance edge
            toleranceMs + 1 to false,       // just beyond
            400 * dayMs to false            // gross future
        )

        for ((offset, expectedEligible) in offsets) {
            val at = now + offset
            val helperAccepts = ArtistAffinityCalculator.scoringAgeMsOrNull(now, at) != null
            assertEquals("helper at offset=$offset", expectedEligible, helperAccepts)

            // Ranking: 4 rows either score or do not.
            val ranked = ArtistAffinityCalculator
                .calculateAffinity(events("Hans Zimmer", at, 4), now)
                .isNotEmpty()
            assertEquals("ranking at offset=$offset", expectedEligible, ranked)

            // Membership: the same 4 rows either admit or do not.
            assertEquals(
                "membership at offset=$offset",
                expectedEligible,
                admitted(events("Hans Zimmer", at, 4), "Hans Zimmer")
            )
        }
    }

    // ---------------------------------------------------- 10. extreme timestamps

    @Test
    fun `extreme timestamps cannot become eligible through overflow`() {
        assertNull(ArtistAffinityCalculator.scoringAgeMsOrNull(now, Long.MAX_VALUE))
        assertNull(ArtistAffinityCalculator.scoringAgeMsOrNull(now, Long.MIN_VALUE))
        assertNull(ArtistAffinityCalculator.scoringAgeMsOrNull(now, 0L))
        assertNull(ArtistAffinityCalculator.scoringAgeMsOrNull(now, -1L))

        for (extreme in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 0L, -1L)) {
            val history = events("Hans Zimmer", extreme, 6)
            assertTrue(
                "listenedAt=$extreme must not score",
                ArtistAffinityCalculator.calculateAffinity(history, now).isEmpty()
            )
            assertFalse("listenedAt=$extreme must not admit", admitted(history, "Hans Zimmer"))
        }
    }

    @Test
    fun `an accepted age is never negative`() {
        for (offset in listOf(-windowMs, -dayMs, -1L, 0L, 1L, toleranceMs)) {
            val age = ArtistAffinityCalculator.scoringAgeMsOrNull(now, now + offset)
            assertNotNull("offset=$offset should be accepted", age)
            assertTrue("age must never be negative (weight must stay <= 1.0)", age!! >= 0L)
        }
    }

    // --------------------------------------- 11. provenance policy is unchanged

    @Test
    fun `provenance eligibility is unchanged by the timestamp policy`() {
        assertEquals(
            setOf(
                PlaybackOrigin.SEARCH_SONGS,
                PlaybackOrigin.LIKED_SONGS,
                PlaybackOrigin.QUICK_PICKS,
                PlaybackOrigin.FEATURED_ARTISTS
            ),
            ArtistAffinityCalculator.ELIGIBLE_ORIGINS
        )

        // A perfectly timed row from an ineligible origin is still ineligible.
        for (origin in PlaybackOrigin.values()) {
            val eligibleOrigin = origin in ArtistAffinityCalculator.ELIGIBLE_ORIGINS
            val history = events("Hans Zimmer", now - minuteMs, 4, origin)
            assertEquals(
                "origin=$origin scoring",
                eligibleOrigin,
                ArtistAffinityCalculator.calculateAffinity(history, now).isNotEmpty()
            )
            assertEquals(
                "origin=$origin admission",
                eligibleOrigin,
                admitted(history, "Hans Zimmer")
            )
        }
    }

    @Test
    fun `an ineligible origin inside the tolerance window is still rejected`() {
        val history = events("Hans Zimmer", now + toleranceMs, 6, PlaybackOrigin.RADIO_AUTOPLAY)
        assertTrue(ArtistAffinityCalculator.calculateAffinity(history, now).isEmpty())
        assertFalse(admitted(history, "Hans Zimmer"))
    }
}
