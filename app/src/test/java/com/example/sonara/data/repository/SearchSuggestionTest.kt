package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.SearchHistoryEntry
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.SearchHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for SearchRepositoryImpl.getSuggestions() — real-time related search autocomplete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionTest {

    private lateinit var fakeHistoryRepository: FakeSearchHistoryRepository
    private lateinit var fakeBackendClient: FakeSuggestBackendClient
    private lateinit var repository: SearchRepositoryImpl

    @Before
    fun setUp() {
        fakeHistoryRepository = FakeSearchHistoryRepository()
        fakeBackendClient = FakeSuggestBackendClient()
        repository = SearchRepositoryImpl(
            backendClient = fakeBackendClient,
            searchHistoryRepository = fakeHistoryRepository
        )
    }

    @Test
    fun `getSuggestions returns empty for blank query`() = runTest {
        val result = repository.getSuggestions("")
        assertTrue(result.getOrDefault(emptyList()).isEmpty())
        assertEquals(0, fakeBackendClient.suggestCallCount)
    }

    @Test
    fun `getSuggestions returns empty for whitespace-only query`() = runTest {
        val result = repository.getSuggestions("   ")
        assertTrue(result.getOrDefault(emptyList()).isEmpty())
        assertEquals(0, fakeBackendClient.suggestCallCount)
    }

    @Test
    fun `getSuggestions returns live autocomplete results from backend without history`() = runTest {
        fakeBackendClient.suggestionsToReturn = listOf(
            SearchSuggestion("Arijit Singh"),
            SearchSuggestion("Arijit Singh songs"),
            SearchSuggestion("Arijit Singh latest songs")
        )
        val result = repository.getSuggestions("arij")
        assertEquals(3, result.getOrThrow().size)
        assertEquals("Arijit Singh", result.getOrThrow()[0].query)
        assertEquals("Arijit Singh songs", result.getOrThrow()[1].query)
        assertEquals(1, fakeBackendClient.suggestCallCount)
        assertEquals(0, fakeBackendClient.searchCallCount) // Full search never triggered
    }

    @Test
    fun `getSuggestions falls back to local history when backend returns empty`() = runTest {
        fakeBackendClient.suggestionsToReturn = emptyList()
        fakeHistoryRepository.prefixResults = listOf("Tum Hi Ho", "Tum Mile")

        val result = repository.getSuggestions("tum")
        assertEquals(2, result.getOrThrow().size)
        assertEquals("Tum Hi Ho", result.getOrThrow()[0].query)
        assertEquals("Tum Mile", result.getOrThrow()[1].query)
    }

    @Test
    fun `getSuggestions is resilient and returns empty or fallback when backend throws`() = runTest {
        fakeBackendClient.shouldThrow = true
        fakeHistoryRepository.prefixResults = listOf("Apna Bana Le")

        val result = repository.getSuggestions("apna")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("Apna Bana Le", result.getOrThrow()[0].query)
    }

    @Test
    fun `getSuggestions does not interfere with full song and video search`() = runTest {
        fakeBackendClient.suggestionsToReturn = listOf(SearchSuggestion("Coldplay Yellow"))
        val suggestionResult = repository.getSuggestions("cold")

        assertTrue(suggestionResult.isSuccess)
        assertEquals(0, fakeBackendClient.searchCallCount)
    }
}

// ─── Fakes ────────────────────────────────────────────────────────────────────

private class FakeSearchHistoryRepository : SearchHistoryRepository {
    var prefixResults: List<String> = emptyList()
    var shouldThrow: Boolean = false

    override fun observeHistory(): Flow<List<SearchHistoryEntry>> = flowOf(emptyList())
    override suspend fun addQuery(query: String) {}
    override suspend fun deleteEntry(id: Long) {}
    override suspend fun clearAll() {}
    override suspend fun getSuggestionsForPrefix(prefix: String, limit: Int): List<String> {
        if (shouldThrow) throw RuntimeException("Simulated history failure")
        return prefixResults
    }
}

private class FakeSuggestBackendClient : SonaraBackendClient() {
    var searchCallCount = 0
    var suggestCallCount = 0
    var suggestionsToReturn: List<SearchSuggestion> = emptyList()
    var shouldThrow = false

    override suspend fun getSuggestions(query: String): Result<List<SearchSuggestion>> {
        suggestCallCount++
        if (shouldThrow) return Result.failure(RuntimeException("Backend 500 error"))
        return Result.success(suggestionsToReturn)
    }

    override suspend fun searchSongs(query: String): Result<List<Track>> {
        searchCallCount++
        return Result.success(emptyList())
    }

    override suspend fun searchVideos(query: String, limit: Int): Result<List<Track>> {
        searchCallCount++
        return Result.success(emptyList())
    }
}
