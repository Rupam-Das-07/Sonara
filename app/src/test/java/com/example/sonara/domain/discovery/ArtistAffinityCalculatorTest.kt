package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for Featured Artists local personalization.
 *
 * Verifies that the backend's editorial roster is only RE-ORDERED — never re-populated —
 * and that cold-start / threshold / window / stability rules hold. Fully deterministic:
 * "now" is injected, so no wall-clock or coroutine machinery is involved.
 */
class ArtistAffinityCalculatorTest {

    private val now = 1_700_000_000_000L // fixed reference "now"
    private val dayMs = 24L * 60L * 60L * 1000L

    private fun event(
        artist: String,
        daysAgo: Double,
        completed: Boolean = false,
        trackId: String = "track-$artist-$daysAgo-$completed",
        // Phase 2D: these cases exercise the affinity MATH, which presumes the listening was
        // eligible. Provenance itself is covered by FeaturedArtistProvenanceTest.
        sourceSection: PlaybackOrigin = PlaybackOrigin.SEARCH_SONGS
    ): HistoryItem = HistoryItem(
        track = Track(id = trackId, title = "Song", artist = artist),
        listenedAt = now - (daysAgo * dayMs).toLong(),
        completed = completed,
        sourceSection = sourceSection
    )

    private fun artist(id: String, name: String = id): FeaturedArtist =
        FeaturedArtist(id = id, name = name)

    /** Backend roster fixture: A..F in authoritative editorial order. */
    private val roster = listOf(
        artist("artist-a", "Artist A"),
        artist("artist-b", "Artist B"),
        artist("artist-c", "Artist C"),
        artist("artist-d", "Artist D"),
        artist("artist-e", "Artist E"),
        artist("artist-f", "Artist F")
    )

    private fun ids(list: List<FeaturedArtist>) = list.map { it.id }

    // ---------------------------------------------------------------------------------
    // 1 + 2. Cold start: nothing to personalize -> editorial order preserved
    // ---------------------------------------------------------------------------------

    @Test
    fun `empty history leaves backend order unchanged`() {
        val result = ArtistAffinityCalculator.personalize(roster, emptyList(), now)

        assertEquals(ids(roster), ids(result))
        assertTrue(ArtistAffinityCalculator.calculateAffinity(emptyList(), now).isEmpty())
    }

    @Test
    fun `history with no roster matches leaves backend order unchanged`() {
        val history = listOf(
            event("Some Other Singer", 1.0),
            event("Some Other Singer", 2.0),
            event("Nobody From The Roster", 3.0),
            event("Nobody From The Roster", 4.0)
        )

        val result = ArtistAffinityCalculator.personalize(roster, history, now)

        assertEquals(ids(roster), ids(result))
    }

    // ---------------------------------------------------------------------------------
    // 3. A frequently listened roster artist is promoted
    // ---------------------------------------------------------------------------------

    @Test
    fun `frequently listened artist is promoted while others keep editorial order`() {
        val history = List(5) { event("Artist D", daysAgo = 1.0) }

        val result = ArtistAffinityCalculator.personalize(roster, history, now)

        assertEquals(
            listOf("artist-d", "artist-a", "artist-b", "artist-c", "artist-e", "artist-f"),
            ids(result)
        )
    }

    // ---------------------------------------------------------------------------------
    // 4. Recency beats staleness
    // ---------------------------------------------------------------------------------

    @Test
    fun `recent listening outranks older listening of equal volume`() {
        // B is earlier in editorial order but was listened to 28 days ago;
        // E was listened to today. Equal event counts, so only recency separates them.
        val history = listOf(
            event("Artist B", daysAgo = 28.0),
            event("Artist B", daysAgo = 28.0),
            event("Artist E", daysAgo = 0.0),
            event("Artist E", daysAgo = 0.0)
        )

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
        assertTrue(
            "recent artist must score higher",
            affinity.getValue("artist-e") > affinity.getValue("artist-b")
        )

        val result = ArtistAffinityCalculator.personalize(roster, history, now)
        assertEquals("artist-e", ids(result)[0])
        assertEquals("artist-b", ids(result)[1])
    }

    // ---------------------------------------------------------------------------------
    // 5. Completion quality weighting
    // ---------------------------------------------------------------------------------

    @Test
    fun `completed plays receive the intended quality multiplier`() {
        val history = listOf(
            event("Artist A", daysAgo = 0.0, completed = true),
            event("Artist A", daysAgo = 0.0, completed = true),
            event("Artist B", daysAgo = 0.0, completed = false),
            event("Artist B", daysAgo = 0.0, completed = false)
        )

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)

        // recency weight is exp(0) = 1.0 for both, so only the quality weight differs.
        assertEquals(2f * ArtistAffinityCalculator.COMPLETED_QUALITY_WEIGHT, affinity.getValue("artist-a"), 0.0001f)
        assertEquals(2f * ArtistAffinityCalculator.BASE_QUALITY_WEIGHT, affinity.getValue("artist-b"), 0.0001f)
        assertTrue(affinity.getValue("artist-a") > affinity.getValue("artist-b"))
    }

    // ---------------------------------------------------------------------------------
    // 6. Tie stability -> editorial order wins
    // ---------------------------------------------------------------------------------

    @Test
    fun `equal affinity preserves original backend ordering`() {
        // C and B have identical history, so their scores tie exactly.
        val history = listOf(
            event("Artist C", daysAgo = 2.0),
            event("Artist C", daysAgo = 2.0),
            event("Artist B", daysAgo = 2.0),
            event("Artist B", daysAgo = 2.0)
        )

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
        assertEquals(affinity.getValue("artist-b"), affinity.getValue("artist-c"), 0.0f)

        val result = ArtistAffinityCalculator.personalize(roster, history, now)

        // B precedes C in the backend roster and must still precede it.
        assertTrue(ids(result).indexOf("artist-b") < ids(result).indexOf("artist-c"))
        // The untouched zero-affinity tail also keeps its editorial order.
        assertEquals(
            listOf("artist-a", "artist-d", "artist-e", "artist-f"),
            ids(result).filter { it !in setOf("artist-b", "artist-c") }
        )
    }

    // ---------------------------------------------------------------------------------
    // 7. Slug matching
    // ---------------------------------------------------------------------------------

    @Test
    fun `slugify maps display names to backend-compatible slugs`() {
        assertEquals("arijit-singh", ArtistAffinityCalculator.slugify("Arijit Singh"))
        assertEquals("beyonce", ArtistAffinityCalculator.slugify("Beyoncé"))
        assertEquals("a-r-rahman", ArtistAffinityCalculator.slugify("A.R. Rahman"))
        assertEquals("sia", ArtistAffinityCalculator.slugify("  SIA  "))
        assertEquals("", ArtistAffinityCalculator.slugify(""))
        assertEquals("", ArtistAffinityCalculator.slugify("   "))
        assertEquals("", ArtistAffinityCalculator.slugify("!!!"))
    }

    @Test
    fun `separator variant editorial id receives the matching affinity`() {
        val editorial = listOf(
            artist("artist-a", "Artist A"),
            artist("a-r-rahman", "A.R. Rahman")
        )
        val history = List(2) { event("Artist A", daysAgo = 0.0) } +
            List(4) { event("AR Rahman", daysAgo = 0.0) }

        val result = ArtistAffinityCalculator.personalize(editorial, history, now)

        assertEquals("a-r-rahman", ids(result)[0])
        assertEquals("artist-a", ids(result)[1])
        assertEquals(
            4f,
            requireNotNull(ArtistAffinityCalculator.lookupAffinity(
                ArtistAffinityCalculator.calculateAffinity(history, now),
                "a-r-rahman"
            )),
            0.0001f
        )
    }

    @Test
    fun `affinity keys are slugs that match FeaturedArtist ids`() {
        val history = listOf(event("Artist D", 0.0), event("Artist D", 0.0))

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)

        assertNotNull(affinity["artist-d"])
        assertEquals(setOf("artist-d"), affinity.keys)
    }

    // ---------------------------------------------------------------------------------
    // 8. Time window
    // ---------------------------------------------------------------------------------

    @Test
    fun `history outside the window does not influence affinity`() {
        val stale = List(10) { event("Artist F", daysAgo = 45.0) } // > 30-day window

        val affinity = ArtistAffinityCalculator.calculateAffinity(stale, now)
        assertTrue("stale events must be ignored entirely", affinity.isEmpty())

        val result = ArtistAffinityCalculator.personalize(roster, stale, now)
        assertEquals(ids(roster), ids(result))
    }

    @Test
    fun `only in-window events of a mixed-age artist are counted`() {
        val history = listOf(
            event("Artist F", daysAgo = 45.0),  // ignored
            event("Artist F", daysAgo = 50.0),  // ignored
            event("Artist F", daysAgo = 0.0),   // counted
            event("Artist F", daysAgo = 0.0)    // counted
        )

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)

        // Two in-window, non-completed, zero-age events => 2 * 1.0
        assertEquals(2f, affinity.getValue("artist-f"), 0.0001f)
    }

    // ---------------------------------------------------------------------------------
    // 9. Candidate set is never expanded
    // ---------------------------------------------------------------------------------

    @Test
    fun `an artist absent from the backend roster is never inserted`() {
        val history = List(20) { event("Heavily Played Unlisted Artist", daysAgo = 0.0, completed = true) }

        val result = ArtistAffinityCalculator.personalize(roster, history, now)

        assertEquals(roster.size, result.size)
        assertEquals(ids(roster), ids(result))
        assertTrue(result.none { it.name == "Heavily Played Unlisted Artist" })
    }

    @Test
    fun `re-ranked result is always a permutation of the backend roster`() {
        val history = List(4) { event("Artist E", daysAgo = 0.0) } +
            List(3) { event("Totally Unlisted", daysAgo = 0.0) }

        val result = ArtistAffinityCalculator.personalize(roster, history, now)

        assertEquals(roster.size, result.size)
        assertEquals(roster.toSet(), result.toSet())
    }

    // ---------------------------------------------------------------------------------
    // 10. Accumulation
    // ---------------------------------------------------------------------------------

    @Test
    fun `duplicate listening events accumulate`() {
        val history = List(4) { event("Artist A", daysAgo = 0.0) } +
            List(2) { event("Artist B", daysAgo = 0.0) }

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)

        assertEquals(4f, affinity.getValue("artist-a"), 0.0001f)
        assertEquals(2f, affinity.getValue("artist-b"), 0.0001f)
        assertTrue(affinity.getValue("artist-a") > affinity.getValue("artist-b"))
    }

    // ---------------------------------------------------------------------------------
    // 11. Minimum-signal threshold
    // ---------------------------------------------------------------------------------

    @Test
    fun `a single play is below the minimum threshold and earns no boost`() {
        val history = listOf(event("Artist F", daysAgo = 0.0, completed = true))

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
        assertNull("one event must not qualify", affinity["artist-f"])
        assertTrue(affinity.isEmpty())

        val result = ArtistAffinityCalculator.personalize(roster, history, now)
        assertEquals("editorial order must survive a sub-threshold signal", ids(roster), ids(result))
    }

    @Test
    fun `reaching the minimum threshold activates the boost`() {
        val oneEvent = listOf(event("Artist F", daysAgo = 0.0))
        val twoEvents = oneEvent + event("Artist F", daysAgo = 0.0)

        assertTrue(ArtistAffinityCalculator.calculateAffinity(oneEvent, now).isEmpty())

        val affinity = ArtistAffinityCalculator.calculateAffinity(twoEvents, now)
        assertNotNull(affinity["artist-f"])

        // F is last in the editorial roster and must now lead.
        val result = ArtistAffinityCalculator.personalize(roster, twoEvents, now)
        assertEquals("artist-f", ids(result)[0])
        assertEquals(ArtistAffinityCalculator.MIN_QUALIFYING_EVENTS, 2)
    }

    // ---------------------------------------------------------------------------------
    // 12. No mutation of caller-owned data
    // ---------------------------------------------------------------------------------

    @Test
    fun `personalization does not mutate the backend list`() {
        val backendOrderSnapshot = ids(roster)
        val history = List(3) { event("Artist D", daysAgo = 0.0) }

        val result = ArtistAffinityCalculator.personalize(roster, history, now)

        // Original list object is untouched...
        assertEquals(backendOrderSnapshot, ids(roster))
        // ...and the returned list genuinely differs in order.
        assertEquals("artist-d", ids(result)[0])
        assertTrue(ids(result) != backendOrderSnapshot)
    }

    @Test
    fun `empty roster is handled without error`() {
        val history = List(3) { event("Artist A", daysAgo = 0.0) }

        val result = ArtistAffinityCalculator.personalize(emptyList(), history, now)

        assertTrue(result.isEmpty())
    }
}
