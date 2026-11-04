package com.example.sonara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sonara.data.local.db.entity.HistoryEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import kotlinx.coroutines.flow.Flow

data class HistoryItemWithTrack(
    val historyId: Long,
    val listenedAt: Long,
    val completed: Boolean,
    @Embedded val track: TrackEntity,
    val sourceSection: String = PlaybackOrigin.LEGACY_UNKNOWN.name,
    val playbackSessionId: String? = null
) {
    fun toDomain(): HistoryItem = HistoryItem(
        id = historyId,
        track = track.toDomain(),
        listenedAt = listenedAt,
        completed = completed,
        sourceSection = PlaybackOrigin.fromStorage(sourceSection),
        playbackSessionId = playbackSessionId.orEmpty()
    )
}

@Dao
interface HistoryDao {
    @Query("""
        SELECT h.id as historyId, h.listenedAt, h.completed, h.sourceSection, h.playbackSessionId, t.*
        FROM playback_history h
        INNER JOIN tracks t ON h.trackId = t.id
        ORDER BY h.listenedAt DESC
        LIMIT :limit
    """)
    fun getRecentHistory(limit: Int = 50): Flow<List<HistoryItemWithTrack>>

    @Query("""
        SELECT h.id as historyId, h.listenedAt, h.completed, h.sourceSection, h.playbackSessionId, t.*
        FROM playback_history h
        INNER JOIN tracks t ON h.trackId = t.id
        WHERE h.sourceSection = :sourceSection
        ORDER BY h.listenedAt DESC
        LIMIT :limit
    """)
    fun getRecentHistoryBySource(sourceSection: String, limit: Int = 50): Flow<List<HistoryItemWithTrack>>

    /**
     * Newest-first history, excluding rows stamped later than [newestAllowed] BEFORE the limit
     * is applied (I-2).
     *
     * [getRecentHistory] applies `LIMIT` to every row, so rows written while the device clock
     * was ahead sort FIRST under `listenedAt DESC` and can fill the entire sample, crowding out
     * genuine listens that the personalization policy would have accepted. Filtering here keeps
     * those rows from occupying sample slots.
     *
     * Only the FUTURE bound belongs in SQL. Rows older than the history window sort LAST, so they
     * can never displace a newer row; they only fill slots that would otherwise be empty, and the
     * in-memory policy ([com.example.sonara.domain.discovery.ArtistAffinityCalculator.scoringAgeMsOrNull])
     * still rejects them. Leaving the lower bound out also guarantees that on a device whose clock
     * was never wrong this returns exactly the rows [getRecentHistory] returns.
     *
     * The comparison is inclusive (`<=`), matching the policy's inclusive tolerance edge.
     */
    @Query("""
        SELECT h.id as historyId, h.listenedAt, h.completed, h.sourceSection, h.playbackSessionId, t.*
        FROM playback_history h
        INNER JOIN tracks t ON h.trackId = t.id
        WHERE h.listenedAt <= :newestAllowed
        ORDER BY h.listenedAt DESC
        LIMIT :limit
    """)
    fun getRecentHistoryUpTo(newestAllowed: Long, limit: Int): Flow<List<HistoryItemWithTrack>>

    /**
     * Newest-first history restricted to [origins], with BOTH the future bound and the provenance
     * restriction applied BEFORE the limit (I-5).
     *
     * [getRecentHistoryUpTo] still limits over every row, so a device that autoplays radio heavily
     * writes enough `RADIO_AUTOPLAY` rows into the newest slots to fill the whole personalization
     * sample. Provenance eligibility is enforced later, in memory
     * ([com.example.sonara.domain.discovery.ArtistAffinityCalculator.isEligibleForPersonalization]),
     * so those rows are discarded only AFTER older eligible listening has already been crowded out
     * of the sample and can never be recovered.
     *
     * Filtering `sourceSection` here is therefore not just an optimization: it is what keeps a
     * bounded sample a sample of USABLE rows. Callers pass the authoritative origin list; this
     * query never invents or hard-codes a provenance policy of its own.
     *
     * Semantics:
     *  - The comparison is inclusive (`<=`), matching the policy's inclusive tolerance edge, so
     *    I-2's bound is preserved exactly.
     *  - `IN` requires a non-null match, so NULL/unknown stored values and any origin outside
     *    [origins] are excluded — the same fail-closed outcome the in-memory predicate produces
     *    (`PlaybackOrigin.fromStorage` maps a NULL or unrecognised value to LEGACY_UNKNOWN).
     *  - The limit is applied after filtering, so it effectively means "the N newest eligible rows".
     *  - [origins] is a plain `List<String>` of stored `sourceSection` values, matching the
     *    existing collection-parameter convention in [TrackDao.getTracksByIds].
     */
    @Query("""
        SELECT h.id as historyId, h.listenedAt, h.completed, h.sourceSection, h.playbackSessionId, t.*
        FROM playback_history h
        INNER JOIN tracks t ON h.trackId = t.id
        WHERE h.listenedAt <= :newestAllowed AND h.sourceSection IN (:origins)
        ORDER BY h.listenedAt DESC
        LIMIT :limit
    """)
    fun getRecentHistoryUpToForOrigins(
        newestAllowed: Long,
        origins: List<String>,
        limit: Int
    ): Flow<List<HistoryItemWithTrack>>

    /**
     * Inserts a playback-history row and returns its generated primary key.
     *
     * The returned rowId is the session identity used by the authoritative history
     * writer to later mark this exact row completed (see [markCompleted]). The schema
     * is unchanged — `id` is still an autogenerated primary key.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistoryIfAbsent(history: HistoryEntity): Long

    @Query("SELECT id FROM playback_history WHERE :playbackSessionId <> '' AND playbackSessionId = :playbackSessionId LIMIT 1")
    suspend fun getHistoryIdBySessionId(playbackSessionId: String): Long?

    /**
     * Database-enforced idempotence for non-empty session IDs. The unique index on the nullable
     * session column allows multiple NULL legacy values, so each legacy row remains a distinct
     * history event while every real session ID can occur only once.
     */
    @androidx.room.Transaction
    suspend fun insertHistoryIdempotent(history: HistoryEntity): Long {
        val normalizedHistory = history.copy(
            playbackSessionId = history.playbackSessionId?.takeUnless { it.isBlank() }
        )
        val insertedId = insertHistoryIfAbsent(normalizedHistory)
        if (insertedId != -1L || normalizedHistory.playbackSessionId == null) return insertedId
        return requireNotNull(getHistoryIdBySessionId(normalizedHistory.playbackSessionId)) {
            "History insert conflict had no row for session ${normalizedHistory.playbackSessionId}"
        }
    }

    /**
     * Marks one existing history row as a completed listen, by primary key.
     *
     * Used instead of inserting a second row when a track finishes naturally, so a
     * single listening session remains a single history row.
     */
    @Query("UPDATE playback_history SET completed = 1 WHERE id = :historyId")
    suspend fun markCompleted(historyId: Long)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}
