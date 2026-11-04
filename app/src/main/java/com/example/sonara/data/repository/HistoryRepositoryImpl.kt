package com.example.sonara.data.repository

import com.example.sonara.data.local.db.dao.HistoryDao
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.HistoryEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class HistoryRepositoryImpl(
    private val historyDao: HistoryDao,
    private val trackDao: TrackDao
) : HistoryRepository {

    override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> {
        return historyDao.getRecentHistory(limit).map { items ->
            items.map { it.toDomain() }
        }
    }

    override fun getRecentHistoryBySource(
        sourceSection: PlaybackOrigin,
        limit: Int
    ): Flow<List<HistoryItem>> = historyDao
        .getRecentHistoryBySource(sourceSection.name, limit)
        .map { items -> items.map { it.toDomain() } }

    override fun getRecentHistoryUpTo(newestAllowed: Long, limit: Int): Flow<List<HistoryItem>> =
        historyDao
            .getRecentHistoryUpTo(newestAllowed, limit)
            .map { items -> items.map { it.toDomain() } }

    override fun getRecentHistoryUpToForOrigins(
        newestAllowed: Long,
        origins: Collection<PlaybackOrigin>,
        limit: Int
    ): Flow<List<HistoryItem>> {
        // `IN ()` is a SQL syntax error, so an empty policy would CRASH rather than return nothing.
        // Short-circuiting keeps the documented fail-closed contract real: a misconfigured caller
        // gets no rows, never an exception and never every row.
        if (origins.isEmpty()) return flowOf(emptyList())
        return historyDao
            .getRecentHistoryUpToForOrigins(newestAllowed, origins.map { it.name }, limit)
            .map { items -> items.map { it.toDomain() } }
    }

    override suspend fun recordHistory(track: Track, completed: Boolean): Long =
        recordHistoryWithProvenance(track, completed, PlaybackOrigin.LEGACY_UNKNOWN, "")

    override suspend fun recordHistoryWithProvenance(
        track: Track,
        completed: Boolean,
        sourceSection: PlaybackOrigin,
        playbackSessionId: String
    ): Long {
        val existingTrack = trackDao.getTrackById(track.id)
        trackDao.insertTrack(TrackEntity.fromDomainPreservingCredits(track, existingTrack))
        return historyDao.insertHistoryIdempotent(
            HistoryEntity(
                trackId = track.id,
                listenedAt = System.currentTimeMillis(),
                completed = completed,
                sourceSection = sourceSection.name,
                playbackSessionId = playbackSessionId.takeUnless { it.isBlank() }
            )
        )
    }

    override suspend fun markHistoryCompleted(historyId: Long) {
        historyDao.markCompleted(historyId)
    }

    override suspend fun clearHistory() {
        historyDao.clearHistory()
    }
}
