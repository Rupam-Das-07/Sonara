package com.example.sonara.domain.repository

import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Domain boundary for playback history logging and reactive retrieval.
 */
interface HistoryRepository {
    fun getRecentHistory(limit: Int = 50): Flow<List<HistoryItem>>
    suspend fun recordHistory(track: Track, completed: Boolean = false)
    suspend fun clearHistory()
}
