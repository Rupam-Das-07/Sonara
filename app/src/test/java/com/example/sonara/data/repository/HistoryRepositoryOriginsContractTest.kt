package com.example.sonara.data.repository

import com.example.sonara.data.local.db.dao.HistoryDao
import com.example.sonara.data.local.db.dao.HistoryItemWithTrack
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.HistoryEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.discovery.ArtistAffinityCalculator
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I-5 — the [HistoryRepositoryImpl] boundary of the provenance-aware sample.
 *
 * The SQL semantics are pinned on real SQLite by `HistorySampleWindowInstrumentedTest`; what these
 * tests cover is the translation layer, which is where the policy could be lost or a crash could be
 * introduced:
 *
 *  - the authoritative `PlaybackOrigin` policy is converted to the stored `sourceSection` strings
 *    the query binds, and
 *  - an EMPTY policy short-circuits before the query, because `IN ()` is invalid SQL.
 */
class HistoryRepositoryOriginsContractTest {

    // ---------------------------------------------------------------- fakes

    /**
     * Records the exact arguments that reach the DAO, and can be made to fail if it is reached at
     * all — which is how the empty-policy guard is proven to short-circuit rather than rely on
     * SQLite's tolerance.
     */
    private class RecordingHistoryDao : HistoryDao {
        val queriedOrigins = mutableListOf<List<String>>()
        val queriedNewestAllowed = mutableListOf<Long>()
        val queriedLimits = mutableListOf<Int>()

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItemWithTrack>> =
            flowOf(emptyList())

        override fun getRecentHistoryBySource(
            sourceSection: String,
            limit: Int
        ): Flow<List<HistoryItemWithTrack>> = flowOf(emptyList())

        override fun getRecentHistoryUpTo(
            newestAllowed: Long,
            limit: Int
        ): Flow<List<HistoryItemWithTrack>> = flowOf(emptyList())

        override fun getRecentHistoryUpToForOrigins(
            newestAllowed: Long,
            origins: List<String>,
            limit: Int
        ): Flow<List<HistoryItemWithTrack>> {
            queriedNewestAllowed += newestAllowed
            queriedOrigins += origins
            queriedLimits += limit
            return flowOf(emptyList())
        }

        override suspend fun insertHistoryIfAbsent(history: HistoryEntity): Long = 0L
        override suspend fun getHistoryIdBySessionId(playbackSessionId: String): Long? = null
        override suspend fun markCompleted(historyId: Long) {}
        override suspend fun clearHistory() {}
    }

    /** Fails the test if the DAO query is reached, proving a short-circuit happened. */
    private class UnreachableHistoryDao : HistoryDao {
        override fun getRecentHistory(limit: Int): Flow<List<HistoryItemWithTrack>> = unreachable()
        override fun getRecentHistoryBySource(
            sourceSection: String,
            limit: Int
        ): Flow<List<HistoryItemWithTrack>> = unreachable()

        override fun getRecentHistoryUpTo(
            newestAllowed: Long,
            limit: Int
        ): Flow<List<HistoryItemWithTrack>> = unreachable()

        override fun getRecentHistoryUpToForOrigins(
            newestAllowed: Long,
            origins: List<String>,
            limit: Int
        ): Flow<List<HistoryItemWithTrack>> = unreachable()

        override suspend fun insertHistoryIfAbsent(history: HistoryEntity): Long = unreachable()
        override suspend fun getHistoryIdBySessionId(playbackSessionId: String): Long? = unreachable()
        override suspend fun markCompleted(historyId: Long) = unreachable()
        override suspend fun clearHistory() = unreachable()
    }

    private companion object {
        /**
         * Never returns: reaching the DAO with an empty provenance policy would mean the guard in
         * [HistoryRepositoryImpl] is gone and `IN ()` is about to be executed.
         */
        fun unreachable(): Nothing =
            throw AssertionError("the DAO must not be reached: `IN ()` is invalid SQL")
    }

    private class FakeTrackDao : TrackDao {
        override suspend fun insertTrack(track: TrackEntity) {}
        override suspend fun insertTracks(tracks: List<TrackEntity>) {}
        override suspend fun getTrackById(id: String): TrackEntity? = null
        override suspend fun getTracksByIds(ids: List<String>): List<TrackEntity> = emptyList()
    }

    private fun repositoryWith(dao: HistoryDao) = HistoryRepositoryImpl(dao, FakeTrackDao())

    // ---------------------------------------------------------------- tests

    @Test
    fun authoritativeOriginsReachTheQueryAsStoredSourceSectionValues() = runBlocking {
        val dao = RecordingHistoryDao()

        repositoryWith(dao).getRecentHistoryUpToForOrigins(
            newestAllowed = 1_234L,
            origins = ArtistAffinityCalculator.ELIGIBLE_ORIGINS,
            limit = ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT
        ).first()

        assertEquals(
            "the policy must be translated to the stored enum names, none dropped or added",
            ArtistAffinityCalculator.ELIGIBLE_ORIGINS.map { it.name }.sorted(),
            dao.queriedOrigins.single().sorted()
        )
        assertEquals(
            "the I-2 bound and the sample limit must pass through untouched",
            listOf(1_234L),
            dao.queriedNewestAllowed
        )
        assertEquals(
            listOf(ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT),
            dao.queriedLimits
        )
    }

    @Test
    fun anEmptyPolicyReturnsNothingAndNeverReachesTheQuery() = runBlocking {
        // `IN ()` is a syntax error, so without the guard a misconfigured caller would CRASH the
        // shelf rather than fail closed. Ignoring an empty policy instead would be worse: it would
        // silently restore the I-5 defect by making the sample provenance-blind again.
        val repository = repositoryWith(UnreachableHistoryDao())

        val rows: List<HistoryItem> = repository.getRecentHistoryUpToForOrigins(
            newestAllowed = 0L,
            origins = emptySet(),
            limit = ArtistAffinityCalculator.HISTORY_SAMPLE_LIMIT
        ).first()

        assertEquals("an empty policy must yield no rows", emptyList<HistoryItem>(), rows)
    }

    @Test
    fun aSingleOriginIsStillTranslatedAndPassedThrough() = runBlocking {
        val dao = RecordingHistoryDao()

        repositoryWith(dao).getRecentHistoryUpToForOrigins(
            newestAllowed = 99L,
            origins = listOf(PlaybackOrigin.LIKED_SONGS),
            limit = 7
        ).first()

        assertEquals(listOf(PlaybackOrigin.LIKED_SONGS.name), dao.queriedOrigins.single())
        assertEquals(listOf(99L), dao.queriedNewestAllowed)
        assertEquals(listOf(7), dao.queriedLimits)
    }
}
