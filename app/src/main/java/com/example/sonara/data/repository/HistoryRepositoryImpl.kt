package com.example.sonara.data.repository

import com.example.sonara.data.local.db.dao.HistoryDao
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.HistoryEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
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

    override suspend fun recordHistory(track: Track, completed: Boolean) {
        trackDao.insertTrack(TrackEntity.fromDomain(track))
        historyDao.insertHistory(
            HistoryEntity(
                trackId = track.id,
                listenedAt = System.currentTimeMillis(),
                completed = completed
            )
        )
    }

    override suspend fun clearHistory() {
        historyDao.clearHistory()
    }
}
