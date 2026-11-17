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
 * CANDIDATE-MEMBERSHIP contract for Featured Artists.
 *
 * These tests exist because the previous implementation could only REORDER the backend
 * roster. The acceptance criterion here is therefore membership, not score: an artist
 * the user heavily listens to, who is absent from the editorial roster, must be able to
 * ENTER the shelf — and an artist with zero qualifying local history must never receive
 * personal-affinity credit.
 *
 * [ArtistAffinityCalculator] is deliberately untouched by this change, so its own 18
 * tests continue to assert its re-rank-only primitive unchanged.
 */
class FeaturedArtistSelectorTest {

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }

    // ------------------------------------------------------------------ helpers

    private fun artist(name: String, genre: String = "Editorial", image: String? = "https://img/$name.jpg") =
        FeaturedArtist(
            id = ArtistAffinityCalculator.slugify(name),
            name = name,
            genre = genre,
            imageUrl = image,
            browseId = "UC${name.filter { it.isLetterOrDigit() }.padEnd(22, 'x').take(22)}"
        )

    /** `n` listening sessions for [artistName], the newest `daysAgo` days old. */
    private fun sessions(
        artistName: String,
        n: Int,
        daysAgo: Long = 1L,
        completed: Boolean = false,
        // Phase 2D: membership/ranking cases presume eligible listening; provenance rules
        // themselves are covered by FeaturedArtistProvenanceTest.
        sourceSection: PlaybackOrigin = PlaybackOrigin.SEARCH_SONGS
    ): List<HistoryItem> = (0 until n).map { i ->
        HistoryItem(
            id = i.toLong(),
            track = Track(
                id = "vid${artistName.take(3)}$i",
                title = "Track $i",
                artist = artistName,
                album = "",
                durationMs = 200_000L
            ),
            listenedAt = NOW - (daysAgo * DAY_MS) - (i * 60_000L),
            completed = completed,
            sourceSection = sourceSection
        )
    }

    /** Newest-first, as `getRecentHistory` returns. */
    private fun history(vararg groups: List<HistoryItem>): List<HistoryItem> =
        groups.toList().flatten().sortedByDescending { it.listenedAt }

    private fun names(result: List<FeaturedArtist>) = result.map { it.name }

    private val editorialRoster = listOf(
        artist("Taylor Swift"),
        artist("Bad Bunny"),
        artist("Kumar Sanu"),
        artist("Udit Narayan"),
        artist("Alka Yagnik"),
        artist("Sonu Nigam"),
        artist("Arijit Singh"),
        artist("Shreya Ghoshal")
    )

    // ================================================================== CASE 1

    /**
     * CASE 1 — the core regression. A strong personal artist that the backend did not
     * return must become a candidate at all.
     */
    @Test
    fun `strong personal artist absent from the backend enters the candidate pool`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(sessions("Hans Zimmer", n = 12, daysAgo = 1, completed = true)),
            nowMs = NOW
        )

        assertTrue(
            "A heavily listened artist absent from the roster must be able to enter. Got: ${names(result)}",
            names(result).contains("Hans Zimmer")
        )
    }

    // ================================================================== CASE 2

    /**
     * CASE 2 — the exact reported scenario, end to end: Hans Zimmer is the most-streamed
     * artist locally and is NOT on the editorial roster, so he must appear in the final
     * displayed set — and, being the strongest signal, at the front.
     */
    @Test
    fun `Hans Zimmer case - strongest local artist appears in the final featured set`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Hans Zimmer", n = 20, daysAgo = 1, completed = true),
                sessions("Arijit Singh", n = 3, daysAgo = 5)
            ),
            nowMs = NOW
        )

        assertTrue("Hans Zimmer must be present. Got: ${names(result)}", names(result).contains("Hans Zimmer"))
        assertEquals("The strongest local artist should lead the shelf", "Hans Zimmer", result.first().name)

        val zimmer = result.first { it.name == "Hans Zimmer" }
        assertEquals("id must be the backend-compatible slug for drill-through", "hans-zimmer", zimmer.id)
        assertNull("no portrait is known locally; null renders the designed monogram", zimmer.imageUrl)
        assertEquals("genre must stay honestly empty rather than fabricated", "", zimmer.genre)
        assertNull("no browseId is known locally; drill-through resolves by name", zimmer.browseId)
    }

    // ================================================================== CASE 3

    /** CASE 3 — a zero-history editorial artist must receive exactly zero personal affinity. */
    @Test
    fun `zero-history editorial artist receives no personal affinity`() {
        val listened = history(sessions("Hans Zimmer", n = 10, daysAgo = 1))
        val affinity = ArtistAffinityCalculator.calculateAffinity(listened, NOW)

        assertNull("Taylor Swift has no local history and must have no score", affinity["taylor-swift"])
        assertNull("Bad Bunny has no local history and must have no score", affinity["bad-bunny"])
        assertNotNull("the listened artist does have a score", affinity["hans-zimmer"])

        // And she must never outrank a genuinely listened artist.
        val result = FeaturedArtistSelector.select(editorialRoster, listened, NOW)
        val zimmerIdx = result.indexOfFirst { it.name == "Hans Zimmer" }
        val swiftIdx = result.indexOfFirst { it.name == "Taylor Swift" }
        assertTrue("a zero-history editorial artist must not outrank a listened artist",
            zimmerIdx < swiftIdx || swiftIdx == -1)
    }

    // ================================================================== CASE 4

    /**
     * CASE 4 — zero-history editorial candidates still populate the shelf. Membership by
     * editorial policy is preserved; they are simply ranked last.
     */
    @Test
    fun `zero-history editorial candidates remain on the shelf by editorial policy`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(sessions("Hans Zimmer", n = 10, daysAgo = 1)),
            nowMs = NOW
        )

        val editorialSurvivors = result.count { r -> editorialRoster.any { it.id == r.id } }
        assertEquals("the shelf stays full", FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertEquals(
            "at least TARGET_SIZE - MAX_PERSONAL_SLOTS editorial artists must survive",
            FeaturedArtistSelector.TARGET_SIZE - 1,
            editorialSurvivors
        )
        assertTrue("zero-history editorial artists are still present",
            names(result).any { it == "Taylor Swift" || it == "Bad Bunny" })
    }

    // ================================================================== CASE 5

    /** CASE 5 — an artist in BOTH sets appears once, keeping the richer editorial metadata. */
    @Test
    fun `an artist present in both sets is deduplicated and keeps editorial metadata`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(sessions("Arijit Singh", n = 15, daysAgo = 1, completed = true)),
            nowMs = NOW
        )

        assertEquals("Arijit Singh must appear exactly once",
            1, result.count { it.name == "Arijit Singh" })

        val arijit = result.first { it.name == "Arijit Singh" }
        assertEquals("editorial metadata (portrait) must win", "https://img/Arijit Singh.jpg", arijit.imageUrl)
        assertEquals("editorial genre must win", "Editorial", arijit.genre)
        assertNotNull("editorial browseId must be retained", arijit.browseId)
        assertEquals("and the listened artist leads the shelf", "Arijit Singh", result.first().name)
    }

    /** CASE 5b — dedupe tolerates separator differences ("A.R. Rahman" vs "AR Rahman"). */
    @Test
    fun `dedupe is tolerant of separator spelling differences`() {
        val roster = listOf(artist("Taylor Swift"), artist("A.R. Rahman"), artist("Bad Bunny"))
        val result = FeaturedArtistSelector.select(
            editorial = roster,
            history = history(
                sessions("AR Rahman", n = 8, daysAgo = 1),
                sessions("Taylor Swift", n = 2, daysAgo = 1)
            ),
            nowMs = NOW
        )

        assertEquals(
            "the same artist spelled differently must not produce a duplicate tile",
            1,
            result.count { it.name.replace(".", "").replace(" ", "").equals("ARRahman", ignoreCase = true) }
        )
        assertEquals("no personal candidate should have been inserted", roster.size, result.size)
        assertEquals("a-r-rahman", result.first().id)
        assertEquals("A.R. Rahman", result.first().name)
        assertEquals("https://img/A.R. Rahman.jpg", result.first().imageUrl)
    }

    /**
     * CASE 5c — REVIEW REGRESSION: two spellings of the SAME artist, neither on the
     * editorial roster, must collapse to ONE tile.
     *
     * Before the implementation review the editorial dedupe ran only between the
     * personal and editorial sets, so "AR Rahman" and "A.R. Rahman" each qualified
     * independently and both were inserted — two tiles for one person.
     */
    @Test
    fun `two spellings of one personal artist collapse to a single tile`() {
        val roster = listOf(artist("Taylor Swift"), artist("Bad Bunny"), artist("Kumar Sanu"))
        val result = FeaturedArtistSelector.select(
            editorial = roster,
            history = history(
                sessions("AR Rahman", n = 4, daysAgo = 1, completed = true),
                sessions("A.R. Rahman", n = 4, daysAgo = 2, completed = true)
            ),
            nowMs = NOW
        )

        val rahmanTiles = result.count {
            it.id.replace("-", "").equals("arrahman", ignoreCase = true)
        }
        assertEquals("the same artist must occupy exactly one tile, not two", 1, rahmanTiles)
        assertEquals("ids must still be unique", result.map { it.id }.distinct().size, result.size)
    }

    // ================================================================== CASE 6

    /** CASE 6 — five personal candidates plus editorial backfill (5 personal + 3 editorial). */
    @Test
    fun `five personal candidates plus editorial backfill`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Hans Zimmer", n = 20, daysAgo = 1, completed = true),
                sessions("Ludovico Einaudi", n = 15, daysAgo = 2, completed = true),
                sessions("Max Richter", n = 10, daysAgo = 3),
                sessions("Olafur Arnalds", n = 8, daysAgo = 4),
                sessions("Nils Frahm", n = 6, daysAgo = 5)
            ),
            nowMs = NOW
        )

        val personalCount = result.count { r -> editorialRoster.none { it.id == r.id } }
        val editorialCount = result.count { r -> editorialRoster.any { it.id == r.id } }
        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertEquals("5 personal candidates enter without a 3-slot cap", 5, personalCount)
        assertEquals("3 editorial backfill candidates fill the remaining slots", 3, editorialCount)
        assertTrue(
            "all five personal artists win slots",
            names(result).containsAll(listOf("Hans Zimmer", "Ludovico Einaudi", "Max Richter", "Olafur Arnalds", "Nils Frahm"))
        )
    }

    // ================================================================== CASE 7

    /** CASE 7 — eight personal candidates occupy all eight slots with zero editorial backfill. */
    @Test
    fun `eight personal candidates occupying all eight slots`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Hans Zimmer", n = 30, daysAgo = 1, completed = true),
                sessions("Ludovico Einaudi", n = 25, daysAgo = 2, completed = true),
                sessions("Max Richter", n = 20, daysAgo = 3, completed = true),
                sessions("Olafur Arnalds", n = 18, daysAgo = 4, completed = true),
                sessions("Nils Frahm", n = 15, daysAgo = 5, completed = true),
                sessions("Joe Hisaishi", n = 12, daysAgo = 6, completed = true),
                sessions("Yann Tiersen", n = 10, daysAgo = 7, completed = true),
                sessions("Ennio Morricone", n = 8, daysAgo = 8, completed = true)
            ),
            nowMs = NOW
        )

        val personalCount = result.count { r -> editorialRoster.none { it.id == r.id } }
        val editorialCount = result.count { r -> editorialRoster.any { it.id == r.id } }
        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertEquals("all 8 shelf slots belong to qualifying personal artists", 8, personalCount)
        assertEquals("zero editorial backfill when personal candidates reach 8", 0, editorialCount)
    }

    /** CASE 7b — more than eight personal candidates selecting the top eight. */
    @Test
    fun `more than eight personal candidates selecting the top eight`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Artist 1", n = 30, daysAgo = 1, completed = true),
                sessions("Artist 2", n = 28, daysAgo = 1, completed = true),
                sessions("Artist 3", n = 26, daysAgo = 1, completed = true),
                sessions("Artist 4", n = 24, daysAgo = 1, completed = true),
                sessions("Artist 5", n = 22, daysAgo = 1, completed = true),
                sessions("Artist 6", n = 20, daysAgo = 1, completed = true),
                sessions("Artist 7", n = 18, daysAgo = 1, completed = true),
                sessions("Artist 8", n = 16, daysAgo = 1, completed = true),
                sessions("Artist 9", n = 14, daysAgo = 1, completed = true),
                sessions("Artist 10", n = 12, daysAgo = 1, completed = true),
                sessions("Artist 11", n = 10, daysAgo = 1, completed = true),
                sessions("Artist 12", n = 8, daysAgo = 1, completed = true)
            ),
            nowMs = NOW
        )

        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertTrue(names(result).containsAll((1..8).map { "Artist $it" }))
        assertFalse("weaker candidates outside top 8 are omitted", names(result).contains("Artist 9"))
        assertFalse(names(result).contains("Artist 12"))
        val editorialCount = result.count { r -> editorialRoster.any { it.id == r.id } }
        assertEquals(0, editorialCount)
    }

    /** CASE 7c — one personal candidate plus editorial backfill (1 personal + 7 editorial). */
    @Test
    fun `one personal candidate plus editorial backfill`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(sessions("Hans Zimmer", n = 10, daysAgo = 1, completed = true)),
            nowMs = NOW
        )

        val personalCount = result.count { r -> editorialRoster.none { it.id == r.id } }
        val editorialCount = result.count { r -> editorialRoster.any { it.id == r.id } }
        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertEquals(1, personalCount)
        assertEquals(7, editorialCount)
        assertEquals("Hans Zimmer", result.first().name)
    }

    /** CASE 7d — three personal candidates plus editorial backfill (3 personal + 5 editorial). */
    @Test
    fun `three personal candidates plus editorial backfill`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Hans Zimmer", n = 10, daysAgo = 1, completed = true),
                sessions("Ludovico Einaudi", n = 8, daysAgo = 2, completed = true),
                sessions("Max Richter", n = 6, daysAgo = 3, completed = true)
            ),
            nowMs = NOW
        )

        val personalCount = result.count { r -> editorialRoster.none { it.id == r.id } }
        val editorialCount = result.count { r -> editorialRoster.any { it.id == r.id } }
        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertEquals(3, personalCount)
        assertEquals(5, editorialCount)
        assertTrue(names(result).containsAll(listOf("Hans Zimmer", "Ludovico Einaudi", "Max Richter")))
    }

    /** CASE 7e — personal artists not being displaced by editorial popularity. */
    @Test
    fun `personal artists not being displaced by editorial popularity`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Indie Artist 1", n = 5, daysAgo = 1, completed = true),
                sessions("Indie Artist 2", n = 4, daysAgo = 2, completed = true)
            ),
            nowMs = NOW
        )

        // Editorial artists with 0 plays must not push Indie Artist 1 and 2 off the front.
        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertEquals("Indie Artist 1", result[0].name)
        assertEquals("Indie Artist 2", result[1].name)
    }

    /** CASE 7f — one artist alone cannot occupy multiple slots. */
    @Test
    fun `a single dominant artist occupies exactly one slot`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(sessions("Hans Zimmer", n = 60, daysAgo = 1, completed = true)),
            nowMs = NOW
        )
        assertEquals(1, result.count { it.name == "Hans Zimmer" })
        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
    }

    // ================================================================== CASE 8

    /** CASE 8 — cold start must not substitute an editorial roster for personal signals. */
    @Test
    fun `no history produces no featured artists`() {
        val result = FeaturedArtistSelector.select(editorialRoster, emptyList(), NOW)
        assertTrue(result.isEmpty())
    }

    @Test fun `existing affinity threshold gates visibility without changing membership thresholds`() {
        assertEquals(2, ArtistAffinityCalculator.MIN_QUALIFYING_EVENTS)
        assertEquals(3, FeaturedArtistSelector.MIN_PERSONAL_CANDIDATE_EVENTS)
        for (origin in listOf(PlaybackOrigin.SEARCH_SONGS, PlaybackOrigin.LIKED_SONGS)) {
            assertTrue(FeaturedArtistSelector.select(editorialRoster,
                sessions("Arijit Singh", 1, sourceSection = origin), NOW).isEmpty())
            val eligible = sessions("Arijit Singh", 2, sourceSection = origin)
            assertEquals(ArtistAffinityCalculator.personalize(editorialRoster, eligible, NOW),
                FeaturedArtistSelector.select(editorialRoster, eligible, NOW))
        }
    }

    /**
     * CASE 8b — insufficient history: below [FeaturedArtistSelector.MIN_PERSONAL_CANDIDATE_EVENTS]
     * nothing is inserted and the shelf keeps today's re-rank-only behaviour.
     */
    @Test
    fun `history below the membership threshold inserts nothing`() {
        val thin = history(sessions("Hans Zimmer", n = FeaturedArtistSelector.MIN_PERSONAL_CANDIDATE_EVENTS - 1, daysAgo = 1))
        val result = FeaturedArtistSelector.select(editorialRoster, thin, NOW)

        assertFalse("below the membership bar, no insertion", names(result).contains("Hans Zimmer"))
        assertEquals("the shelf is exactly the editorial roster", editorialRoster.size, result.size)
        assertEquals(
            "and it matches the existing re-rank-only result byte for byte",
            ArtistAffinityCalculator.personalize(editorialRoster, thin, NOW),
            result
        )
    }

    /** CASE 8c — reaching the membership threshold exactly does admit the artist. */
    @Test
    fun `history at exactly the membership threshold admits the artist`() {
        val atBar = history(sessions("Hans Zimmer", n = FeaturedArtistSelector.MIN_PERSONAL_CANDIDATE_EVENTS, daysAgo = 1))
        val result = FeaturedArtistSelector.select(editorialRoster, atBar, NOW)
        assertTrue("exactly at the bar must qualify", names(result).contains("Hans Zimmer"))
    }

    // ================================================================== CASE 9

    /** CASE 9 — history outside the configured window does not qualify for membership. */
    @Test
    fun `history outside the window does not qualify an artist`() {
        val stale = history(
            sessions("Hans Zimmer", n = 20, daysAgo = ArtistAffinityCalculator.HISTORY_WINDOW_DAYS + 5, completed = true)
        )
        val result = FeaturedArtistSelector.select(editorialRoster, stale, NOW)

        assertFalse("out-of-window listening must not create membership", names(result).contains("Hans Zimmer"))
        assertTrue(result.isEmpty())
    }

    /** CASE 9b — only in-window sessions count toward the membership threshold. */
    @Test
    fun `only in-window sessions count toward the membership threshold`() {
        val mixed = history(
            sessions("Hans Zimmer", n = 2, daysAgo = 1),                                              // in window
            sessions("Hans Zimmer", n = 20, daysAgo = ArtistAffinityCalculator.HISTORY_WINDOW_DAYS + 2) // stale
        )
        val result = FeaturedArtistSelector.select(editorialRoster, mixed, NOW)
        assertFalse(
            "2 in-window sessions are below the 3-session membership bar despite stale volume",
            names(result).contains("Hans Zimmer")
        )
    }

    // ================================================================= CASE 10

    /** CASE 10 — a single brief/accidental listen never creates a Featured Artist. */
    @Test
    fun `a single listening event never creates a featured artist`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(sessions("Some Random Artist", n = 1, daysAgo = 0)),
            nowMs = NOW
        )
        assertFalse(names(result).contains("Some Random Artist"))
        assertTrue(result.isEmpty())
    }

    /**
     * CASE 10b — a multi-artist credit string is never inserted as a single artist, since
     * it is not one artist and would break the name-based drill-through.
     */
    @Test
    fun `multi-artist credit strings are never inserted as a single artist`() {
        val collabs = history(
            sessions("Arijit Singh, Shreya Ghoshal", n = 20, daysAgo = 1, completed = true),
            sessions("Vishal & Shekhar", n = 15, daysAgo = 1),
            sessions("Diplo feat. MO", n = 12, daysAgo = 1)
        )
        val result = FeaturedArtistSelector.select(editorialRoster, collabs, NOW)

        assertEquals("no combined-credit tile may be inserted", editorialRoster.size, result.size)
        assertFalse(names(result).any { it.contains(",") })
        assertFalse(names(result).any { it.contains("&") })
        assertFalse(names(result).any { it.contains("feat", ignoreCase = true) })
    }

    /**
     * CASE 10c — REVIEW REGRESSION: names that merely LOOK like a separator must still
     * be admitted. Each of these was misclassified as a multi-artist credit before the
     * implementation review.
     */
    @Test
    fun `single artists whose names contain separator-like text are still admitted`() {
        for (name in listOf("AC/DC", "X Ambassadors", "Malcolm X", "Charli XCX")) {
            val result = FeaturedArtistSelector.select(
                editorial = editorialRoster,
                history = history(sessions(name, n = 6, daysAgo = 1, completed = true)),
                nowMs = NOW
            )
            assertTrue(
                "\"$name\" is a single artist and must be able to enter. Got: ${names(result)}",
                names(result).contains(name)
            )
        }
    }

    /**
     * CASE 10d — REVIEW REGRESSION: "A and B" must be excluded exactly like "A & B".
     * Before the review the two spellings behaved differently, so an "and" credit was
     * inserted as a single combined tile with an unnavigable name.
     */
    @Test
    fun `and-joined credits are excluded exactly like ampersand-joined credits`() {
        val ampersand = FeaturedArtistSelector.select(
            editorialRoster,
            history(sessions("Hall & Oates", n = 10, daysAgo = 1)),
            NOW
        )
        val andWord = FeaturedArtistSelector.select(
            editorialRoster,
            history(sessions("Hall and Oates", n = 10, daysAgo = 1)),
            NOW
        )

        // Assertions are on MEMBERSHIP, not on list size: an inserted 9th candidate is
        // truncated back to 8, so a size check alone cannot detect the defect.
        assertFalse("'&' credit must not be inserted", names(ampersand).contains("Hall & Oates"))
        assertFalse("'and' credit must not be inserted either", names(andWord).contains("Hall and Oates"))
        assertEquals("'&' spelling leaves the roster intact", editorialRoster.size, ampersand.size)
        assertEquals("'and' spelling must behave identically", editorialRoster.size, andWord.size)

        // And the real motivating case: a genuine two-artist credit joined by "and".
        val collab = FeaturedArtistSelector.select(
            editorialRoster,
            history(sessions("Sonu Nigam and Alka Yagnik", n = 12, daysAgo = 1, completed = true)),
            NOW
        )
        assertFalse(
            "a two-artist 'and' credit must never become a tile",
            names(collab).contains("Sonu Nigam and Alka Yagnik")
        )
        assertTrue(
            "and the shelf stays entirely editorial",
            names(collab).all { n -> editorialRoster.any { it.name == n } }
        )
    }

    /** CASE 10e — true " x " collaborations are still excluded. */
    @Test
    fun `whitespace-delimited x collaborations are still excluded`() {
        val result = FeaturedArtistSelector.select(
            editorialRoster,
            history(sessions("Martin Garrix x Bebe Rexha", n = 12, daysAgo = 1, completed = true)),
            NOW
        )
        assertEquals(editorialRoster.size, result.size)
        assertFalse(names(result).any { it.contains(" x ") })
    }

    // ================================================================= CASE 11

    /**
     * CASE 11 — metadata "resolution" is inherently deduplicated: many sessions for one
     * artist yield exactly ONE candidate, never one per history row. The selector also
     * performs no I/O at all, so there is nothing to amplify.
     */
    @Test
    fun `many sessions for one artist produce exactly one candidate`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(sessions("Hans Zimmer", n = 12, daysAgo = 1, completed = true)),
            nowMs = NOW
        )
        assertEquals(
            "12 history rows must resolve to ONE artist candidate, not 12",
            1,
            result.count { it.id == "hans-zimmer" }
        )
    }

    // ============================================================ CASE 12 / 13

    /**
     * CASE 12 / 13 — robustness: unusable history rows (blank or punctuation-only artist
     * strings) do not make an editorial-only shelf eligible.
     */
    @Test
    fun `unusable history rows keep the shelf hidden`() {
        val junk = history(
            sessions("", n = 5, daysAgo = 1),
            sessions("   ", n = 5, daysAgo = 1),
            sessions("!!!", n = 5, daysAgo = 1)
        )
        val result = FeaturedArtistSelector.select(editorialRoster, junk, NOW)

        assertTrue(result.isEmpty())
    }

    /** CASE 13b — an empty editorial roster still yields the user's own artists. */
    @Test
    fun `an empty editorial roster still yields qualifying personal artists`() {
        val result = FeaturedArtistSelector.select(
            editorial = emptyList(),
            history = history(sessions("Hans Zimmer", n = 10, daysAgo = 1, completed = true)),
            nowMs = NOW
        )
        assertEquals(1, result.size)
        assertEquals("Hans Zimmer", result.first().name)
    }

    // ================================================================= CASE 14

    /** CASE 14 — equal scores keep a stable, deterministic order: editorial order first. */
    @Test
    fun `equal scores preserve editorial order deterministically`() {
        // Two editorial artists with identical listening, plus one personal artist.
        val even = history(
            sessions("Kumar Sanu", n = 4, daysAgo = 3),
            sessions("Udit Narayan", n = 4, daysAgo = 3),
            sessions("Hans Zimmer", n = 4, daysAgo = 3)
        )

        val first = FeaturedArtistSelector.select(editorialRoster, even, NOW)
        val second = FeaturedArtistSelector.select(editorialRoster, even, NOW)
        assertEquals("the selector must be deterministic", names(first), names(second))

        val sanu = first.indexOfFirst { it.name == "Kumar Sanu" }
        val udit = first.indexOfFirst { it.name == "Udit Narayan" }
        assertTrue("tied editorial artists keep the backend's relative order", sanu < udit)
    }

    // ================================================================= CASE 15

    /** CASE 15 — an expanded result is bounded to the existing UI limit. */
    @Test
    fun `an expanded result is bounded to the UI limit`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Hans Zimmer", n = 20, daysAgo = 1),
                sessions("Ludovico Einaudi", n = 18, daysAgo = 1),
                sessions("Max Richter", n = 16, daysAgo = 1)
            ),
            nowMs = NOW
        )
        assertEquals(FeaturedArtistSelector.TARGET_SIZE, result.size)
        assertTrue(result.size <= FeaturedArtistSelector.TARGET_SIZE)
    }

    // ----------------------------------------------------------- invariants

    /** The selector must never mutate its inputs. */
    @Test
    fun `select does not mutate the editorial roster`() {
        val snapshot = editorialRoster.toList()
        FeaturedArtistSelector.select(
            editorialRoster,
            history(sessions("Hans Zimmer", n = 10, daysAgo = 1)),
            NOW
        )
        assertEquals(snapshot, editorialRoster)
    }

    /** The result must never contain duplicate ids. */
    @Test
    fun `result never contains duplicate ids`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Arijit Singh", n = 10, daysAgo = 1),
                sessions("Hans Zimmer", n = 10, daysAgo = 1),
                sessions("Shreya Ghoshal", n = 10, daysAgo = 1)
            ),
            nowMs = NOW
        )
        assertEquals("ids must be unique", result.map { it.id }.distinct().size, result.size)
    }

    /** Completed sessions carry more weight than incomplete ones for membership ranking. */
    @Test
    fun `completed sessions outrank incomplete sessions of equal volume`() {
        val result = FeaturedArtistSelector.select(
            editorial = editorialRoster,
            history = history(
                sessions("Completed Artist", n = 5, daysAgo = 2, completed = true),
                sessions("Partial Artist", n = 5, daysAgo = 2, completed = false)
            ),
            nowMs = NOW
        )
        val completedIdx = result.indexOfFirst { it.name == "Completed Artist" }
        val partialIdx = result.indexOfFirst { it.name == "Partial Artist" }
        assertTrue("both should be admitted", completedIdx >= 0 && partialIdx >= 0)
        assertTrue("completed listening ranks higher", completedIdx < partialIdx)
    }
}
