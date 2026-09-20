package com.example.sonara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sonara.data.local.db.entity.HistoryEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.HistoryItem
import kotlinx.coroutines.flow.Flow

data class HistoryItemWithTrack(
    val historyId: Long,
    val listenedAt: Long,
    val completed: Boolean,
    @Embedded val track: TrackEntity
) {
    fun toDomain(): HistoryItem = HistoryItem(
        id = historyId,
        track = track.toDomain(),
        listenedAt = listenedAt,
        completed = completed
    )
}

@Dao
interface HistoryDao {
    @Query("""
        SELECT h.id as historyId, h.listenedAt, h.completed, t.*
        FROM playback_history h
        INNER JOIN tracks t ON h.trackId = t.id
        ORDER BY h.listenedAt DESC
        LIMIT :limit
    """)
    fun getRecentHistory(limit: Int = 50): Flow<List<HistoryItemWithTrack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}
