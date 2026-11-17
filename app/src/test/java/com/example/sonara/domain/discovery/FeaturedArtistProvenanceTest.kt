package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2D — playback-provenance eligibility for personalized Featured Artists.
 *
 * Policy under test: ONLY [PlaybackOrigin.SEARCH_SONGS] and [PlaybackOrigin.LIKED_SONGS]
 * influence personalization. HOME, RADIO_AUTOPLAY, PLAYLIST, VIDEO_SEARCH, OTHER and the
 * fail-closed LEGACY_UNKNOWN are excluded from BOTH:
 *
 *   - ORDERING   — affinity scores and the [ArtistAffinityCalculator.MIN_QUALIFYING_EVENTS] count
 *   - MEMBERSHIP — the [FeaturedArtistSelector.MIN_PERSONAL_CANDIDATE_EVENTS] admission count
 *
 * The rules that must SURVIVE this gate are asserted too: an artist the user genuinely plays
 * from Songs can still enter the shelf even when the backend never returned them (the Hans
 * Zimmer case), the editorial baseline remains, and the 8-item / 3-personal caps, stable ties,
 * dedupe and safe fallback are unchanged.
 */
class FeaturedArtistProvenanceTest {

    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 60L * 60L * 1000L

    private fun play(
        artist: String,
        origin: PlaybackOrigin,
        daysAgo: Long = 1L,
        completed: Boolean = false,
        index: Int = 0
    ): HistoryItem = HistoryItem(
        id = index.toLong(),
        track = Track(id = "vid-$artist-$origin-$index", title = "Song $index", artist = artist),
        listenedAt = now - (daysAgo * dayMs) - (index * 60_000L),
        completed = completed,
        sourceSection = origin
    )

    private fun plays(
        artist: String,
        origin: PlaybackOrigin,
        count: Int,
        daysAgo: Long = 1L,
        completed: Boolean = false
    ): List<HistoryItem> = (0 until count).map { play(artist, origin, daysAgo, completed, it) }

    private fun editorial(vararg names: String): List<FeaturedArtist> =
        names.map { FeaturedArtist(id = it.lowercase().replace(' ', '-'), name = it, imageUrl = "https://lh3.googleusercontent.com/$it=w544-h544-l90-rj") }

    private val roster = editorial("Artist A", "Artist B", "Artist C", "Artist D",
        "Artist E", "Artist F", "Artist G", "Artist H")

    private fun names(result: List<FeaturedArtist>) = result.map { it.name }

    private val ineligible = listOf(
        PlaybackOrigin.HOME,
        PlaybackOrigin.RADIO_AUTOPLAY,
        PlaybackOrigin.PLAYLIST,
        PlaybackOrigin.VIDEO_SEARCH,
        PlaybackOrigin.OTHER,
        PlaybackOrigin.LEGACY_UNKNOWN
    )

    // ------------------------------------------------------------- 1,2 [eligible]

    @Test
    fun `two SEARCH_SONGS events satisfy the minimum event threshold`() {
        assertEquals(2, ArtistAffinityCalculator.MIN_QUALIFYING_EVENTS)
        val affinity = ArtistAffinityCalculator.calculateAffinity(
            plays("Artist A", PlaybackOrigin.SEARCH_SONGS, 2), now
        )
        val score = ArtistAffinityCalculator.lookupAffinity(affinity, "Artist A")
        assertTrue("two Songs-search plays must produce a score, got $score", (score ?: 0f) > 0f)
    }

    @Test
    fun `two LIKED_SONGS events satisfy the minimum event threshold`() {
        val affinity = ArtistAffinityCalculator.calculateAffinity(
            plays("Artist A", PlaybackOrigin.LIKED_SONGS, 2), now
        )
        val score = ArtistAffinityCalculator.lookupAffinity(affinity, "Artist A")
        assertTrue("two liked-songs plays must produce a score, got $score", (score ?: 0f) > 0f)
    }

    // ----------------------------------------------------------- 3,4,5 [excluded]

    @Test
    fun `radio autoplay alone can neither score nor admit an artist`() {
        val history = plays("Hans Zimmer", PlaybackOrigin.RADIO_AUTOPLAY, 10)

        assertTrue(
            "radio autoplay must produce no affinity at all",
            ArtistAffinityCalculator.calculateAffinity(history, now).isEmpty()
        )
        assertEquals(
            "radio autoplay must not enable the shelf",
            emptyList<String>(),
            names(FeaturedArtistSelector.select(roster, history, now))
        )
    }

    @Test
    fun `every ineligible origin contributes neither affinity nor candidacy`() {
        for (origin in ineligible) {
            val history = plays("Hans Zimmer", origin, 10)

            assertTrue(
                "$origin must produce no affinity",
                ArtistAffinityCalculator.calculateAffinity(history, now).isEmpty()
            )
            assertNull(
                "$origin must leave the artist unscored",
                ArtistAffinityCalculator.lookupAffinity(
                    ArtistAffinityCalculator.calculateAffinity(history, now), "Hans Zimmer"
                )
            )
            val shelf = FeaturedArtistSelector.select(roster, history, now)
            assertFalse(
                "$origin must never admit a personal artist",
                names(shelf).contains("Hans Zimmer")
            )
            assertTrue("$origin must keep the shelf hidden", shelf.isEmpty())
        }
    }

    @Test
    fun `legacy unknown history fails closed`() {
        val history = plays("Hans Zimmer", PlaybackOrigin.LEGACY_UNKNOWN, 25)

        assertTrue(
            "pre-Phase-2A rows must never be treated as eligible",
            ArtistAffinityCalculator.calculateAffinity(history, now).isEmpty()
        )
        assertEquals(
            "a legacy-only device has no qualifying affinity and keeps the shelf hidden",
            emptyList<String>(),
            names(FeaturedArtistSelector.select(roster, history, now))
        )
    }

    // -------------------------------------------------------------- 6,7 [mixed]

    @Test
    fun `ineligible events cannot help an artist cross the minimum event threshold`() {
        // One eligible play is below MIN_QUALIFYING_EVENTS; padding with ineligible plays of the
        // same artist must not make up the difference.
        val history = plays("Artist A", PlaybackOrigin.SEARCH_SONGS, 1) +
            plays("Artist A", PlaybackOrigin.RADIO_AUTOPLAY, 9)

        assertTrue(
            "1 eligible + 9 ineligible is still below the threshold",
            ArtistAffinityCalculator.calculateAffinity(history, now).isEmpty()
        )
    }

    @Test
    fun `ineligible events cannot help an artist cross the membership threshold`() {
        assertEquals(3, FeaturedArtistSelector.MIN_PERSONAL_CANDIDATE_EVENTS)
        // Two eligible plays order but do not admit; ineligible padding must not promote.
        val history = plays("Hans Zimmer", PlaybackOrigin.SEARCH_SONGS, 2) +
            plays("Hans Zimmer", PlaybackOrigin.HOME, 20)

        assertFalse(
            "ineligible plays must not grant membership",
            names(FeaturedArtistSelector.select(roster, history, now)).contains("Hans Zimmer")
        )
    }

    @Test
    fun `ineligible events cannot increase an already eligible artist's score`() {
        val eligibleOnly = plays("Artist A", PlaybackOrigin.SEARCH_SONGS, 3)
        val withNoise = eligibleOnly + ineligible.flatMap { plays("Artist A", it, 5) }

        val clean = ArtistAffinityCalculator.lookupAffinity(
            ArtistAffinityCalculator.calculateAffinity(eligibleOnly, now), "Artist A"
        )
        val noisy = ArtistAffinityCalculator.lookupAffinity(
            ArtistAffinityCalculator.calculateAffinity(withNoise, now), "Artist A"
        )

        assertEquals(
            "30 ineligible plays must not change the score by even a fraction",
            clean,
            noisy
        )
    }

    // ------------------------------------------------------- 8,9 [admission rules]

    @Test
    fun `a qualifying Songs-search artist absent from the roster still enters the shelf`() {
        // The Hans Zimmer case must survive the provenance gate.
        val history = plays("Hans Zimmer", PlaybackOrigin.SEARCH_SONGS, 4, completed = true)

        val shelf = FeaturedArtistSelector.select(roster, history, now)

        assertTrue(
            "a genuinely played artist must still be admitted: ${names(shelf)}",
            names(shelf).contains("Hans Zimmer")
        )
        assertEquals("the shelf stays bounded", FeaturedArtistSelector.TARGET_SIZE, shelf.size)
    }

    @Test
    fun `liked songs listening can also admit a personal artist`() {
        val history = plays("Hans Zimmer", PlaybackOrigin.LIKED_SONGS, 4)
        assertTrue(
            names(FeaturedArtistSelector.select(roster, history, now)).contains("Hans Zimmer")
        )
    }

    @Test
    fun `an artist below the membership threshold is not admitted`() {
        val history = plays("Hans Zimmer", PlaybackOrigin.SEARCH_SONGS, 2)
        assertFalse(
            "two eligible plays order but must not admit",
            names(FeaturedArtistSelector.select(roster, history, now)).contains("Hans Zimmer")
        )
    }

    @Test
    fun `an admitted personal artist carries no fabricated artwork and only validated urls are accepted`() {
        val history = plays("Hans Zimmer", PlaybackOrigin.SEARCH_SONGS, 4)
        val admitted = FeaturedArtistSelector.select(roster, history, now)
            .first { it.name == "Hans Zimmer" }

        // Admission never invents a portrait: null is the designed monogram state.
        assertNull("no artwork may be fabricated locally", admitted.imageUrl)
        assertNull("no provider id may be fabricated locally", admitted.browseId)

        // The artwork that DOES arrive is validated: untrusted hosts and schemes are rejected.
        assertNull(FeaturedArtistImage.trustedHttpsUrl("https://evilgoogleusercontent.com/a"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("http://lh3.googleusercontent.com/a"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("null"))
        assertEquals(
            "https://lh3.googleusercontent.com/a=w544-h544-l90-rj",
            FeaturedArtistImage.trustedHttpsUrl("https://lh3.googleusercontent.com/a=w544-h544-l90-rj")
        )
    }

    // ---------------------------------------------------------- 10 [invariants]

    @Test
    fun `caps dedupe ties and fallback survive the provenance gate`() {
        val history = plays("Hans Zimmer", PlaybackOrigin.SEARCH_SONGS, 5, daysAgo = 1) +
            plays("Joe Hisaishi", PlaybackOrigin.SEARCH_SONGS, 5, daysAgo = 2) +
            plays("Nobuo Uematsu", PlaybackOrigin.LIKED_SONGS, 5, daysAgo = 3) +
            plays("Ryuichi Sakamoto", PlaybackOrigin.SEARCH_SONGS, 5, daysAgo = 4)

        val shelf = FeaturedArtistSelector.select(roster, history, now)
        val personal = names(shelf).filter { it !in names(roster) }

        assertEquals("8-item cap", FeaturedArtistSelector.TARGET_SIZE, shelf.size)
        assertTrue("at most 3 personal additions, got $personal", personal.size <= FeaturedArtistSelector.MAX_PERSONAL_SLOTS)
        assertTrue(
            "at least ${FeaturedArtistSelector.TARGET_SIZE - FeaturedArtistSelector.MAX_PERSONAL_SLOTS} editorial artists survive",
            shelf.size - personal.size >= FeaturedArtistSelector.TARGET_SIZE - FeaturedArtistSelector.MAX_PERSONAL_SLOTS
        )
        assertEquals("no duplicate ids", shelf.map { it.id }.toSet().size, shelf.size)
        assertEquals("no duplicate names", shelf.map { it.name }.toSet().size, shelf.size)
    }

    @Test
    fun `stable ties keep editorial order when every eligible score is equal`() {
        val history = roster.flatMap { plays(it.name, PlaybackOrigin.SEARCH_SONGS, 2, daysAgo = 1) }
        assertEquals(
            "equal affinity must preserve the backend's editorial order",
            names(roster),
            names(FeaturedArtistSelector.select(roster, history, now))
        )
    }

    @Test
    fun `empty and all-ineligible history both keep the shelf hidden`() {
        assertTrue(FeaturedArtistSelector.select(roster, emptyList(), now).isEmpty())
        val allIneligible = ineligible.flatMap { plays("Someone Else", it, 6) }
        assertTrue(FeaturedArtistSelector.select(roster, allIneligible, now).isEmpty())
    }

    @Test
    fun `the window still applies to eligible history`() {
        val stale = plays("Hans Zimmer", PlaybackOrigin.SEARCH_SONGS, 6, daysAgo = 31)
        assertTrue(
            "eligible but out-of-window listening must not qualify",
            ArtistAffinityCalculator.calculateAffinity(stale, now).isEmpty()
        )
        assertFalse(names(FeaturedArtistSelector.select(roster, stale, now)).contains("Hans Zimmer"))
    }

    // ------------------------------------------------------------- [property]

    @Test
    fun `property - exactly four deliberate origins are eligible and the predicate agrees for every value`() {
        assertEquals(
            setOf(
                PlaybackOrigin.SEARCH_SONGS,
                PlaybackOrigin.LIKED_SONGS,
                PlaybackOrigin.QUICK_PICKS,
                PlaybackOrigin.FEATURED_ARTISTS
            ),
            ArtistAffinityCalculator.ELIGIBLE_ORIGINS
        )

        var eligibleCount = 0
        for (origin in PlaybackOrigin.values()) {
            val item = play("Artist A", origin)
            val expected = origin in ArtistAffinityCalculator.ELIGIBLE_ORIGINS
            assertEquals(
                "eligibility for $origin",
                expected,
                ArtistAffinityCalculator.isEligibleForPersonalization(item)
            )
            // The predicate must agree with observable scoring behaviour, not just itself.
            val scored = ArtistAffinityCalculator
                .calculateAffinity(plays("Artist A", origin, 2), now)
                .isNotEmpty()
            assertEquals("scoring behaviour for $origin", expected, scored)
            if (expected) eligibleCount++
        }
        assertEquals("a new enum value must not become eligible by accident", 4, eligibleCount)
        assertEquals("all ten origins were covered", 10, PlaybackOrigin.values().size)
    }
}
