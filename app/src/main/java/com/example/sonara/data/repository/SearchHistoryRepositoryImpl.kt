package com.example.sonara.data.repository

import com.example.sonara.data.local.db.dao.SearchHistoryDao
import com.example.sonara.data.local.db.entity.SearchHistoryEntity
import com.example.sonara.domain.model.SearchHistoryEntry
import com.example.sonara.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Production implementation of [SearchHistoryRepository].
 *
 * Deduplication:
 *   [addQuery] first attempts an IGNORE insert. If the row already exists
 *   (insertIgnore returns -1), it updates the timestamp of the existing row.
 *   This preserves the stable primary key (needed for targeted deletion via
 *   the UI's swipe-to-delete) while refreshing the recency ordering.
 *
 * Normalization:
 *   Queries are stored with their original casing (for display) alongside a
 *   trimmed+lowercase normalizedQuery (for deduplication and prefix matching).
 */
class SearchHistoryRepositoryImpl(
    private val searchHistoryDao: SearchHistoryDao
) : SearchHistoryRepository {

    override fun observeHistory(): Flow<List<SearchHistoryEntry>> =
        searchHistoryDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun addQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        val entity = SearchHistoryEntity.fromQuery(trimmed)
        val inserted = searchHistoryDao.insertIgnore(entity)
        if (inserted == -1L) {
            // Row already existed — refresh its timestamp for recency ordering
            searchHistoryDao.updateTimestamp(
                normalizedQuery = entity.normalizedQuery,
                timestamp = entity.searchedAt
            )
        }
    }

    override suspend fun deleteEntry(id: Long) {
        searchHistoryDao.deleteById(id)
    }

    override suspend fun clearAll() {
        searchHistoryDao.deleteAll()
    }

    override suspend fun getSuggestionsForPrefix(prefix: String, limit: Int): List<String> {
        val trimmed = prefix.trim().lowercase()
        if (trimmed.isBlank()) return emptyList()
        return searchHistoryDao.getQueriesWithPrefix(trimmed, limit)
    }
}
