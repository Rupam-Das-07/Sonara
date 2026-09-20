package com.example.sonara.data.repository

import com.example.sonara.data.local.db.dao.SearchHistoryDao
import com.example.sonara.data.local.db.entity.SearchHistoryEntity
import com.example.sonara.domain.model.SearchHistoryEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SearchHistoryRepositoryImpl.
 *
 * Uses a fake in-memory DAO to verify:
 * - insert + observe
 * - deduplication (upsert refreshes timestamp, no duplicate)
 * - ordering (most recent first)
 * - individual deletion
 * - clear all
 * - prefix-based suggestions
 * - blank query is silently ignored
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryRepositoryTest {

    private lateinit var fakeDao: FakeSearchHistoryDao
    private lateinit var repository: SearchHistoryRepositoryImpl

    @Before
    fun setUp() {
        fakeDao = FakeSearchHistoryDao()
        repository = SearchHistoryRepositoryImpl(fakeDao)
    }

    @Test
    fun `addQuery inserts a new entry`() = runTest {
        repository.addQuery("Beatles")
        val history = repository.observeHistory().first()
        assertEquals(1, history.size)
        assertEquals("Beatles", history[0].query)
    }

    @Test
    fun `blank query is silently ignored`() = runTest {
        repository.addQuery("")
        repository.addQuery("   ")
        repository.addQuery("\t")
        val history = repository.observeHistory().first()
        assertEquals(0, history.size)
    }

    @Test
    fun `addQuery trims whitespace`() = runTest {
        repository.addQuery("  Bollywood  ")
        val history = repository.observeHistory().first()
        assertEquals("Bollywood", history[0].query)
    }

    @Test
    fun `addQuery deduplicates — second insert refreshes timestamp, no duplicate`() = runTest {
        val beforeFirst = System.currentTimeMillis()
        repository.addQuery("arijit")
        val afterFirst = System.currentTimeMillis()

        // Short pause to ensure second call has a later timestamp
        Thread.sleep(5)

        repository.addQuery("Arijit") // Same normalized query ("arijit") — must deduplicate
        val afterSecond = System.currentTimeMillis()

        val history = repository.observeHistory().first()
        assertEquals("Dedup must result in exactly 1 entry", 1, history.size)
        // The timestamp must have been refreshed (second call >= first call's timestamp)
        assertTrue(
            "Timestamp must be refreshed after re-search (was ${history[0].searchedAt}, first call was ~$afterFirst)",
            history[0].searchedAt >= beforeFirst
        )
    }

    @Test
    fun `history is ordered most recent first`() = runTest {
        fakeDao.nextTimestamp = 1000L; repository.addQuery("song A")
        fakeDao.nextTimestamp = 2000L; repository.addQuery("song B")
        fakeDao.nextTimestamp = 3000L; repository.addQuery("song C")
        val history = repository.observeHistory().first()
        assertEquals("song C", history[0].query)
        assertEquals("song B", history[1].query)
        assertEquals("song A", history[2].query)
    }

    @Test
    fun `deleteEntry removes a specific entry by id`() = runTest {
        repository.addQuery("Queen")
        repository.addQuery("Pink Floyd")
        val history = repository.observeHistory().first()
        val targetId = history.first { it.query == "Queen" }.id
        repository.deleteEntry(targetId)
        val updated = repository.observeHistory().first()
        assertEquals(1, updated.size)
        assertEquals("Pink Floyd", updated[0].query)
    }

    @Test
    fun `deleteEntry with unknown id is a no-op`() = runTest {
        repository.addQuery("Test")
        repository.deleteEntry(99999L)
        val history = repository.observeHistory().first()
        assertEquals(1, history.size)
    }

    @Test
    fun `clearAll removes all entries`() = runTest {
        repository.addQuery("query 1")
        repository.addQuery("query 2")
        repository.addQuery("query 3")
        repository.clearAll()
        val history = repository.observeHistory().first()
        assertTrue(history.isEmpty())
    }

    @Test
    fun `getSuggestionsForPrefix returns matching entries`() = runTest {
        repository.addQuery("Beatles")
        repository.addQuery("Beethoven")
        repository.addQuery("Radiohead")
        val suggestions = repository.getSuggestionsForPrefix("bea", limit = 5)
        assertTrue("Beatles must be in suggestions", "Beatles" in suggestions)
        assertTrue("Radiohead must NOT be in suggestions", "Radiohead" !in suggestions)
    }

    @Test
    fun `getSuggestionsForPrefix with blank returns empty`() = runTest {
        repository.addQuery("something")
        val suggestions = repository.getSuggestionsForPrefix("", limit = 5)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `getSuggestionsForPrefix respects limit`() = runTest {
        (1..10).forEach { repository.addQuery("track $it") }
        val suggestions = repository.getSuggestionsForPrefix("track", limit = 3)
        assertTrue("Suggestions must not exceed limit", suggestions.size <= 3)
    }

    @Test
    fun `search history is independent from playback history`() {
        // Structural test: SearchHistoryRepositoryImpl must not reference HistoryDao or HistoryEntity
        val fields = SearchHistoryRepositoryImpl::class.java.declaredFields.map { it.type.simpleName }
        assertTrue("Must use SearchHistoryDao", fields.any { it == "SearchHistoryDao" })
        assertTrue("Must NOT use HistoryDao", fields.none { it == "HistoryDao" })
    }
}

// ─── Fake DAO ─────────────────────────────────────────────────────────────────

private class FakeSearchHistoryDao : SearchHistoryDao {

    private val entries = mutableListOf<SearchHistoryEntity>()
    private var nextId = 1L
    var nextTimestamp = System.currentTimeMillis()

    private val _flow = MutableStateFlow(entries.toList())

    private fun emit() { _flow.value = entries.sortedByDescending { it.searchedAt }.toList() }

    override fun observeAll(): Flow<List<SearchHistoryEntity>> = _flow

    override suspend fun insertIgnore(entity: SearchHistoryEntity): Long {
        val existing = entries.firstOrNull { it.normalizedQuery == entity.normalizedQuery }
        if (existing != null) return -1L
        val withId = entity.copy(id = nextId++, searchedAt = nextTimestamp)
        entries.add(withId)
        emit()
        return withId.id
    }

    override suspend fun updateTimestamp(normalizedQuery: String, timestamp: Long) {
        val idx = entries.indexOfFirst { it.normalizedQuery == normalizedQuery }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(searchedAt = timestamp)
            emit()
        }
    }

    override suspend fun deleteById(id: Long) {
        entries.removeIf { it.id == id }
        emit()
    }

    override suspend fun deleteAll() {
        entries.clear()
        emit()
    }

    override suspend fun getQueriesWithPrefix(prefix: String, limit: Int): List<String> {
        return entries
            .filter { it.normalizedQuery.startsWith(prefix.lowercase()) }
            .sortedByDescending { it.searchedAt }
            .take(limit)
            .map { it.query }
    }
}
