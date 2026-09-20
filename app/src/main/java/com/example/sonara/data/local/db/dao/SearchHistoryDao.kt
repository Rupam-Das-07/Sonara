package com.example.sonara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sonara.data.local.db.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for search history persistence.
 *
 * Deduplication strategy:
 *   [upsertQuery] uses INSERT OR REPLACE on the unique [normalizedQuery] index.
 *   Room's REPLACE strategy deletes the conflicting row and inserts a fresh one,
 *   which resets the autoincrement id. To preserve the id (for stable deletion),
 *   we use a manual [updateTimestamp] approach instead — insert if not exists,
 *   update timestamp if already exists.
 *
 *   Implemented via two queries called from [SearchHistoryRepositoryImpl]:
 *     1. [insertIgnore] — inserts the row if normalizedQuery is new (IGNORE on conflict)
 *     2. [updateTimestamp] — refreshes searchedAt when the query already existed
 */
@Dao
interface SearchHistoryDao {

    /**
     * Reactive stream of all search history entries, most-recent-first.
     * Emits automatically whenever the underlying data changes.
     */
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC")
    fun observeAll(): Flow<List<SearchHistoryEntity>>

    /**
     * Insert a new entry. IGNORE if normalizedQuery already exists.
     * Returns the new rowId, or -1 if ignored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: SearchHistoryEntity): Long

    /**
     * Update the timestamp of an existing entry (for deduplication refresh).
     * Called when insertIgnore returns -1, meaning the query already existed.
     */
    @Query("UPDATE search_history SET searchedAt = :timestamp WHERE normalizedQuery = :normalizedQuery")
    suspend fun updateTimestamp(normalizedQuery: String, timestamp: Long)

    /**
     * Delete a single history entry by its stable primary key.
     */
    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete all search history entries.
     */
    @Query("DELETE FROM search_history")
    suspend fun deleteAll()

    /**
     * Return normalizedQuery values that start with [prefix], ordered by recency.
     * Used for local suggestion generation.
     */
    @Query(
        "SELECT query FROM search_history " +
        "WHERE normalizedQuery LIKE :prefix || '%' " +
        "ORDER BY searchedAt DESC LIMIT :limit"
    )
    suspend fun getQueriesWithPrefix(prefix: String, limit: Int): List<String>
}
