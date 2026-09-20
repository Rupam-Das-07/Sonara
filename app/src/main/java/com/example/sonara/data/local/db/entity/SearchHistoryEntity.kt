package com.example.sonara.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.sonara.domain.model.SearchHistoryEntry

/**
 * Room entity for persisted search history.
 *
 * Table: search_history
 *
 * [normalizedQuery] stores the trimmed, lowercase version of the query for fast
 * prefix matching and deduplication. The [query] field stores the user-visible
 * original casing for display.
 *
 * Uniqueness is enforced on [normalizedQuery] so that re-searching the same
 * term (any case) updates [searchedAt] rather than creating a duplicate row.
 *
 * Note: This entity is STRICTLY SEPARATE from [HistoryEntity] (playback history).
 *       Do not add FK to tracks table — a search doesn't imply a track playback.
 */
@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["normalizedQuery"], unique = true),
        Index(value = ["searchedAt"])
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Original query as submitted by the user (for display). */
    val query: String,
    /** Normalized (trimmed + lowercase) query for deduplication and prefix matching. */
    val normalizedQuery: String,
    /** Epoch-millisecond timestamp of the last time this query was searched. */
    val searchedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): SearchHistoryEntry = SearchHistoryEntry(
        id = id,
        query = query,
        searchedAt = searchedAt
    )

    companion object {
        fun fromQuery(rawQuery: String, timestamp: Long = System.currentTimeMillis()): SearchHistoryEntity {
            val normalized = rawQuery.trim().lowercase()
            return SearchHistoryEntity(
                query = rawQuery.trim(),
                normalizedQuery = normalized,
                searchedAt = timestamp
            )
        }
    }
}
