package com.example.sonara.domain.repository

import com.example.sonara.domain.model.SearchHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Domain interface for persistent search history.
 *
 * IMPORTANT: This is SEPARATE from [HistoryRepository] (playback history).
 * Do not conflate search queries with track playback events.
 *
 * Ordering: most recent first.
 * Deduplication: submitting an existing query refreshes its timestamp
 *   rather than creating a duplicate row.
 */
interface SearchHistoryRepository {
    /**
     * Reactive stream of all search history entries, ordered by most-recent-first.
     * Emits an updated list whenever the dataset changes.
     */
    fun observeHistory(): Flow<List<SearchHistoryEntry>>

    /**
     * Record or refresh a search query.
     * - If [query] (after normalization) does not exist in history, insert it.
     * - If it already exists, update its [searchedAt] timestamp to now.
     * Blank/whitespace-only queries are silently ignored.
     */
    suspend fun addQuery(query: String)

    /**
     * Delete a single history entry by its stable Room primary key.
     * No-op if the id does not exist.
     */
    suspend fun deleteEntry(id: Long)

    /** Delete all search history. Irreversible. */
    suspend fun clearAll()

    /**
     * Return queries from history that begin with [prefix], ordered by recency.
     * Used to back local search suggestions.
     * Returns an empty list when [prefix] is blank.
     */
    suspend fun getSuggestionsForPrefix(prefix: String, limit: Int = 5): List<String>
}
