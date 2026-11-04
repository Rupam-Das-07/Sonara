package com.example.sonara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sonara.data.local.db.dao.HistoryItemWithTrack
import com.example.sonara.data.local.db.entity.HistoryEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.discovery.ArtistAffinityCalculator
import com.example.sonara.domain.model.PlaybackOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I-2 — real Room/SQLite verification that future-dated rows cannot crowd genuine listens out of
 * the bounded personalization sample.
 *
 * `getRecentHistory` is `ORDER BY listenedAt DESC LIMIT :limit`, so rows written while the device
 * clock was ahead sort FIRST and can fill every slot before the in-memory FA-001 policy discards
 * them. `getRecentHistoryUpTo` filters them out before the limit.
 *
 * The oracle is the production policy itself, [ArtistAffinityCalculator.scoringAgeMsOrNull], so
 * "usable" here means exactly what the fold would keep — not a re-implementation of it.
 *
 * All timestamps are explicit; nothing reads the system clock.
 */
@RunWith(AndroidJUnit4::class)
class HistorySampleWindowInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: SonaraDatabase

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val day = 24L * 60L * minute
    private val tolerance = ArtistAffinityCalculator.FUTURE_TOLERANCE_MS
    private val limit = ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, SonaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seed(listenedAt: List<Long>) {
        database.trackDao().insertTrack(TrackEntity(id = "track", title = "Song", artist = "Artist"))
        for (at in listenedAt) {
            database.historyDao().insertHistoryIfAbsent(
                HistoryEntity(
                    trackId = "track",
                    listenedAt = at,
                    sourceSection = PlaybackOrigin.SEARCH_SONGS.name
                )
            )
        }
    }

    /** Seeds each origin's timestamps, sharing one track row as production would. */
    private suspend fun seedByOrigin(vararg rows: Pair<PlaybackOrigin, List<Long>>) {
        database.trackDao().insertTrack(TrackEntity(id = "track", title = "Song", artist = "Artist"))
        for ((origin, stamps) in rows) {
            for (at in stamps) {
                database.historyDao().insertHistoryIfAbsent(
                    HistoryEntity(
                        trackId = "track",
                        listenedAt = at,
                        sourceSection = origin.name
                    )
                )
            }
        }
    }

    private fun future(count: Int) = List(count) { now + 400 * day + it }
    private fun genuine(count: Int) = List(count) { now - (it + 1) * minute }
    private fun stale(count: Int) = List(count) { now - 30 * day - (it + 1) * minute }

    /** Ineligible-by-provenance rows, all newer than [genuine], for the I-5 crowding scenario. */
    private fun radio(count: Int) = List(count) { now - it * 1_000L }
    private fun liked(count: Int) = List(count) { now - (it + 1) * 2 * minute }

    /** Six genuine listens spread across the window, as a real listening pattern looks. */
    private fun spread(count: Int) = List(count) { now - (it + 1) * 3L * 60L * minute }

    /** Eligible listening spread wide enough to be crowded out by dense recent autoplay. */
    private fun spreadWide(count: Int) = List(count) { now - (it + 1) * 60L * minute }

    private fun usable(rows: List<HistoryItemWithTrack>) =
        rows.filter { ArtistAffinityCalculator.scoringAgeMsOrNull(now, it.listenedAt) != null }

    /** The production eligibility policy, applied to raw rows exactly as [usable] applies FA-001. */
    private val eligibleOriginNames = ArtistAffinityCalculator.ELIGIBLE_ORIGINS.map { it.name }.toSet()

    private fun eligible(rows: List<HistoryItemWithTrack>) =
        rows.filter { it.sourceSection in eligibleOriginNames }

    private suspend fun shared() = database.historyDao().getRecentHistory(limit).first()
    private suspend fun upTo() = database.historyDao().getRecentHistoryUpTo(now + tolerance, limit).first()
    private suspend fun upToEligible() = database.historyDao()
        .getRecentHistoryUpToForOrigins(
            newestAllowed = now + tolerance,
            origins = ArtistAffinityCalculator.ELIGIBLE_ORIGINS.map { it.name },
            limit = limit
        )
        .first()

    @Test
    fun futureDatedRowsCannotCrowdGenuineListensOutOfTheSample() = runBlocking {
        seed(future(600) + genuine(400))

        // The defect, on real SQLite: the shared query hands the fold nothing it can use.
        val before = shared()
        assertEquals(limit, before.size)
        assertEquals("the shared query is filled entirely by future-dated rows", 0, usable(before).size)

        // The repair: every genuine listen survives, and no future-dated row takes a slot.
        val after = upTo()
        assertEquals(400, after.size)
        assertEquals("every genuine listen must reach the policy", 400, usable(after).size)
        assertTrue(after.all { it.listenedAt <= now + tolerance })
    }

    @Test
    fun toleranceEdgeIsInclusiveExactlyLikeThePolicy() = runBlocking {
        seed(listOf(now, now + tolerance, now + tolerance + 1))

        val stamps = upTo().map { it.listenedAt }

        assertTrue("now + 5 min is accepted by the policy, so SQL must keep it", now + tolerance in stamps)
        assertTrue(now in stamps)
        assertFalse("one millisecond past the tolerance is rejected", now + tolerance + 1 in stamps)
    }

    @Test
    fun aHealthyDeviceGetsExactlyTheRowsTheSharedQueryReturns() = runBlocking {
        // No clock problem: the limit binds (900 > 500) and stale rows are present. Only a
        // future bound is in SQL, so nothing a healthy device sees may change — same rows,
        // same order.
        seed(genuine(900) + stale(300))

        assertEquals(shared().map { it.historyId }, upTo().map { it.historyId })
    }

    @Test
    fun orderingStaysNewestFirstAndTheLimitStillApplies() = runBlocking {
        seed(genuine(700) + future(100))

        val rows = upTo()

        assertEquals(limit, rows.size)
        assertEquals(rows.sortedByDescending { it.listenedAt }, rows)
        assertEquals("the 500 newest genuine listens", genuine(limit), rows.map { it.listenedAt })
    }

    // ------------------------------------------------------------------ I-5

    @Test
    fun ineligibleProvenanceCannotCrowdEligibleHistoryOutOfTheSample() = runBlocking {
        // The eligibility policy's real shape: a heavy radio-autoplay device writes far more
        // RADIO_AUTOPLAY rows than the sample holds, and writes them DENSER than the user's own
        // listening, so every one of the newest 500 slots belongs to an ineligible row.
        seedByOrigin(
            PlaybackOrigin.RADIO_AUTOPLAY to radio(600),
            PlaybackOrigin.SEARCH_SONGS to spreadWide(400),
            PlaybackOrigin.LIKED_SONGS to List(1) { now - 2 * day }
        )

        // The defect, on real SQLite: the provenance-blind query is filled entirely by rows the
        // in-memory policy must discard, so eligible listening never reaches the fold.
        val before = upTo()
        assertEquals(limit, before.size)
        assertEquals(
            "the provenance-blind sample is filled entirely by ineligible rows",
            0,
            eligible(before).size
        )

        // The repair: every eligible row survives, and no ineligible row takes a slot.
        val after = upToEligible()
        assertEquals("every eligible row must reach the policy", 401, eligible(after).size)
        assertEquals(400 + 1, after.size)
        assertTrue(
            "only eligible origins may be returned: ${after.map { it.sourceSection }.distinct()}",
            after.all { it.sourceSection in eligibleOriginNames }
        )
    }

    @Test
    fun provenanceFilteringKeepsTheOriginsItWasGivenAndRefusesAnythingElse() = runBlocking {
        seedByOrigin(
            PlaybackOrigin.SEARCH_SONGS to genuine(4),
            PlaybackOrigin.LIKED_SONGS to liked(3),
            PlaybackOrigin.RADIO_AUTOPLAY to radio(5),
            PlaybackOrigin.VIDEO_SEARCH to radio(10),
            PlaybackOrigin.LEGACY_UNKNOWN to radio(6),
            PlaybackOrigin.HOME to radio(7),
            PlaybackOrigin.PLAYLIST to radio(8),
            PlaybackOrigin.OTHER to radio(9)
        )

        val rows = upToEligible()

        assertEquals(
            "only the origins passed in may come back",
            setOf(PlaybackOrigin.SEARCH_SONGS.name, PlaybackOrigin.LIKED_SONGS.name),
            rows.map { it.sourceSection }.toSet()
        )
        assertEquals("every eligible row is returned", 7, rows.size)
        assertEquals(rows.sortedByDescending { it.listenedAt }, rows)
    }

    @Test
    fun theProvenanceBoundStillKeepsI2sInclusiveFutureEdge() = runBlocking {
        // now + tolerance is accepted by the policy, so SQL must keep it; one millisecond past it
        // must not, and the provenance filter must not widen that bound.
        seedByOrigin(
            PlaybackOrigin.SEARCH_SONGS to
                listOf(now, now + tolerance, now + tolerance + 1, now - 30 * day),
            PlaybackOrigin.RADIO_AUTOPLAY to
                listOf(now, now + tolerance, now - 60 * minute)
        )

        val stamps = upToEligible().map { it.listenedAt }

        assertTrue("the inclusive tolerance edge must survive provenance filtering", now + tolerance in stamps)
        assertTrue(now in stamps)
        assertFalse("one millisecond past the tolerance is still rejected", now + tolerance + 1 in stamps)
        assertTrue(
            "I-2 deliberately leaves the LOWER bound to the in-memory policy, and provenance " +
                "filtering must not start filtering by age either",
            now - 30 * day in stamps
        )
    }

    @Test
    fun theProvenanceAwareQueryRestrictsOnlyTheSampleItFeedsNotTheSharedQuery() = runBlocking {
        // Guard the I-2 contract: the shared and provenance-blind queries are untouched, so every
        // existing consumer (Library history, BYLT seed, hero restoration) sees identical rows.
        seedByOrigin(
            PlaybackOrigin.RADIO_AUTOPLAY to radio(200),
            PlaybackOrigin.SEARCH_SONGS to spread(300)
        )

        val shared = database.historyDao().getRecentHistory(limit).first()

        assertEquals(limit, shared.size)
        assertTrue(
            "the shared query must still return every origin, unfiltered",
            shared.any { it.sourceSection == PlaybackOrigin.RADIO_AUTOPLAY.name }
        )
        assertEquals(
            "I-2's future bound must be unchanged: with no future-dated rows in the data, the " +
                "provenance-blind sample is exactly the shared query's rows",
            shared.map { it.historyId },
            upTo().map { it.historyId }
        )
        // And the provenance-aware query, over the same data, is genuinely narrower.
        assertTrue(
            "the I-5 query must exclude the ineligible rows the shared query keeps",
            upToEligible().none { it.sourceSection == PlaybackOrigin.RADIO_AUTOPLAY.name }
        )
    }
}
