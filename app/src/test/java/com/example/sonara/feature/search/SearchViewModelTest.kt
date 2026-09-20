package com.example.sonara.feature.search

import com.example.sonara.domain.model.SearchHistoryEntry
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.LibraryRepository
import com.example.sonara.domain.repository.SearchHistoryRepository
import com.example.sonara.domain.repository.SearchRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    class TestSearchRepository : SearchRepository {
        var songsHandler: suspend (String) -> Result<List<Track>> = { query ->
            when (query) {
                "empty" -> Result.success(emptyList())
                "error" -> Result.failure(RuntimeException("Songs network failure"))
                "slow" -> Result.success(listOf(Track(id = "s1", title = "Slow Song", artist = "Artist 1")))
                else -> Result.success(listOf(Track(id = "s1", title = "Official Song $query", artist = "Artist 1")))
            }
        }

        var videosHandler: suspend (String) -> Result<List<Track>> = { query ->
            when (query) {
                "empty" -> Result.success(emptyList())
                "error" -> Result.failure(RuntimeException("Videos network failure"))
                "video_error" -> Result.failure(RuntimeException("YouTube service down"))
                else -> Result.success(listOf(Track(id = "v1", title = "YouTube Video $query", artist = "Channel 1")))
            }
        }

        var songsCallCount = 0
        var videosCallCount = 0

        var suggestionsHandler: suspend (String) -> Result<List<SearchSuggestion>> = { query ->
            Result.success(listOf(SearchSuggestion("$query 1"), SearchSuggestion("$query 2")))
        }

        override suspend fun searchSongs(query: String): Result<List<Track>> {
            songsCallCount++
            return songsHandler(query)
        }

        override suspend fun searchVideos(query: String): Result<List<Track>> {
            videosCallCount++
            return videosHandler(query)
        }

        override suspend fun getSuggestions(partialQuery: String): Result<List<SearchSuggestion>> =
            suggestionsHandler(partialQuery)
    }

    private val fakeLibraryRepo = object : LibraryRepository {
        var cachedTrack: Track? = null
        override fun getLikedSongs(): Flow<List<Track>> = flowOf(emptyList())
        override fun isLiked(trackId: String): Flow<Boolean> = flowOf(false)
        override suspend fun setLiked(track: Track, isLiked: Boolean) {}
        override suspend fun cacheTrack(track: Track) {
            cachedTrack = track
        }
        override suspend fun getTrack(trackId: String): Track? = null
        override suspend fun getTracks(trackIds: List<String>): List<Track> = emptyList()
    }

    private lateinit var searchRepo: TestSearchRepository
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        searchRepo = TestSearchRepository()
        viewModel = SearchViewModel(searchRepo, fakeLibraryRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is default Initial with SONGS active mode`() = runTest {
        val state = viewModel.uiState.value
        assertEquals("", state.query)
        assertEquals(SearchMode.SONGS, state.activeMode)
        assertEquals(ModeSearchState.Initial, state.songsState)
        assertEquals(ModeSearchState.Initial, state.videosState)
        assertEquals(ModeSearchState.Initial, state.currentModeState)
        assertTrue(state.relatedSuggestions.isEmpty())
        assertFalse(state.isSearchSubmitted)
        assertEquals(null, state.lastSubmittedQuery)
    }

    @Test
    fun `query editing triggers suggestions after 200ms debounce but does NOT trigger full catalog search`() = runTest {
        viewModel.onQueryChange("a")
        advanceTimeBy(100)
        viewModel.onQueryChange("ar")
        advanceTimeBy(100)
        viewModel.onQueryChange("ari")
        advanceTimeBy(100)
        viewModel.onQueryChange("arijit")

        // Advance past 200ms suggestion debounce
        advanceTimeBy(250)
        advanceUntilIdle()

        // Full search network calls must NOT have fired from typing
        assertEquals(0, searchRepo.songsCallCount)
        assertEquals(0, searchRepo.videosCallCount)
        assertEquals("arijit", viewModel.uiState.value.query)
        assertFalse(viewModel.uiState.value.isSearchSubmitted)
        assertEquals(ModeSearchState.Initial, viewModel.uiState.value.songsState)
        assertEquals(ModeSearchState.Initial, viewModel.uiState.value.videosState)
        // Suggestions should be loaded
        assertEquals(2, viewModel.uiState.value.relatedSuggestions.size)
    }

    @Test
    fun `editing query sets isSearchSubmitted to false while preserving lastSubmittedQuery`() = runTest {
        viewModel.onQueryChange("Starboy")
        viewModel.onQuerySubmit("Starboy")
        advanceUntilIdle()

        val stateAfterSubmit = viewModel.uiState.value
        assertTrue(stateAfterSubmit.isSearchSubmitted)
        assertEquals("Starboy", stateAfterSubmit.lastSubmittedQuery)

        // User edits query to "Starboy remix"
        viewModel.onQueryChange("Starboy remix")
        advanceUntilIdle()

        val stateAfterEdit = viewModel.uiState.value
        assertEquals("Starboy remix", stateAfterEdit.query)
        assertFalse(stateAfterEdit.isSearchSubmitted)
        assertEquals("Starboy", stateAfterEdit.lastSubmittedQuery) // Preserved!
    }

    @Test
    fun `keyboard Enter or onQuerySubmit executes catalog search and updates state`() = runTest {
        val fakeHistory = FakeSearchHistoryRepo()
        val vm = SearchViewModel(
            searchRepository = searchRepo,
            libraryRepository = fakeLibraryRepo,
            searchHistoryRepository = fakeHistory
        )
        advanceUntilIdle()

        vm.onQueryChange("arijit")
        advanceTimeBy(250)
        advanceUntilIdle()

        // Before submit: 0 search calls
        assertEquals(0, searchRepo.songsCallCount)
        assertEquals(0, searchRepo.videosCallCount)

        // User presses Enter / IME search
        vm.onQuerySubmit("arijit")
        advanceUntilIdle()

        assertEquals(1, searchRepo.songsCallCount)
        assertEquals(1, searchRepo.videosCallCount)
        assertTrue(vm.uiState.value.isSearchSubmitted)
        assertEquals("arijit", vm.uiState.value.lastSubmittedQuery)
        assertTrue(vm.uiState.value.songsState is ModeSearchState.Success)
        assertTrue(vm.uiState.value.videosState is ModeSearchState.Success)
        assertEquals(listOf("arijit"), fakeHistory.addedQueries)
        assertTrue(vm.uiState.value.relatedSuggestions.isEmpty())
    }

    @Test
    fun `parallel dual search updates both Songs and Videos states to Success`() = runTest {
        viewModel.onQueryChange("arijit")
        viewModel.onQuerySubmit("arijit")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.songsState is ModeSearchState.Success)
        assertTrue(state.videosState is ModeSearchState.Success)

        val songs = (state.songsState as ModeSearchState.Success).tracks
        val videos = (state.videosState as ModeSearchState.Success).tracks

        assertEquals("s1", songs[0].id)
        assertEquals("v1", videos[0].id)
    }

    @Test
    fun `progressive dual search delivers fast songs first then slower videos`() = runTest {
        val videosDeferred = CompletableDeferred<Result<List<Track>>>()
        searchRepo.videosHandler = { videosDeferred.await() }

        viewModel.onQueryChange("arijit")
        viewModel.onQuerySubmit("arijit")
        testDispatcher.scheduler.runCurrent()

        // Songs is already completed, Videos is still Loading
        val intermediateState = viewModel.uiState.value
        assertTrue(intermediateState.songsState is ModeSearchState.Success)
        assertEquals(ModeSearchState.Loading, intermediateState.videosState)

        // Now complete slower videos request
        videosDeferred.complete(Result.success(listOf(Track(id = "v99", title = "Late Video", artist = "Channel"))))
        advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertTrue(finalState.songsState is ModeSearchState.Success)
        assertTrue(finalState.videosState is ModeSearchState.Success)
        assertEquals("v99", (finalState.videosState as ModeSearchState.Success).tracks[0].id)
    }

    @Test
    fun `failure isolation - Songs error does not break Videos success`() = runTest {
        searchRepo.songsHandler = { Result.failure(RuntimeException("Songs 500 error")) }
        searchRepo.videosHandler = { Result.success(listOf(Track(id = "v1", title = "Video 1", artist = "Artist"))) }

        viewModel.onQueryChange("test")
        viewModel.onQuerySubmit("test")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.songsState is ModeSearchState.Error)
        assertTrue(state.videosState is ModeSearchState.Success)
    }

    @Test
    fun `failure isolation - Videos error does not break Songs success`() = runTest {
        searchRepo.songsHandler = { Result.success(listOf(Track(id = "s1", title = "Song 1", artist = "Artist"))) }
        searchRepo.videosHandler = { Result.failure(RuntimeException("yt-dlp timeout")) }

        viewModel.onQueryChange("test")
        viewModel.onQuerySubmit("test")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.songsState is ModeSearchState.Success)
        assertTrue(state.videosState is ModeSearchState.Error)
    }

    @Test
    fun `empty results properly map to ModeSearchState Empty per mode`() = runTest {
        viewModel.onQueryChange("empty")
        viewModel.onQuerySubmit("empty")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ModeSearchState.Empty("empty"), state.songsState)
        assertEquals(ModeSearchState.Empty("empty"), state.videosState)
    }

    @Test
    fun `tab switching changes activeMode without triggering new network requests`() = runTest {
        viewModel.onQueryChange("kesariya")
        viewModel.onQuerySubmit("kesariya")
        advanceUntilIdle()

        val initialSongsCalls = searchRepo.songsCallCount
        val initialVideosCalls = searchRepo.videosCallCount

        // Switch to Videos
        viewModel.selectMode(SearchMode.VIDEOS)
        val videoTabState = viewModel.uiState.value
        assertEquals(SearchMode.VIDEOS, videoTabState.activeMode)
        assertTrue(videoTabState.currentModeState is ModeSearchState.Success)

        // Switch back to Songs
        viewModel.selectMode(SearchMode.SONGS)
        val songTabState = viewModel.uiState.value
        assertEquals(SearchMode.SONGS, songTabState.activeMode)
        assertTrue(songTabState.currentModeState is ModeSearchState.Success)

        // Verify NO extra network requests were fired on tab toggle
        assertEquals(initialSongsCalls, searchRepo.songsCallCount)
        assertEquals(initialVideosCalls, searchRepo.videosCallCount)
    }

    @Test
    fun `clearing query resets both modes to Initial state and isSearchSubmitted to false`() = runTest {
        viewModel.onQueryChange("kesariya")
        viewModel.onQuerySubmit("kesariya")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.songsState is ModeSearchState.Success)
        assertTrue(viewModel.uiState.value.isSearchSubmitted)

        // Clear query
        viewModel.onQueryChange("")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.query)
        assertFalse(state.isSearchSubmitted)
        assertEquals(ModeSearchState.Initial, state.songsState)
        assertEquals(ModeSearchState.Initial, state.videosState)
    }

    @Test
    fun `cacheTrackMetadata delegates to LibraryRepository`() = runTest {
        val sampleTrack = Track(id = "sample123", title = "Sample Track", artist = "Artist")
        viewModel.cacheTrackMetadata(sampleTrack)
        advanceUntilIdle()

        assertEquals(sampleTrack, fakeLibraryRepo.cachedTrack)
    }

    // ─── Search History & Live Related Suggestions Tests ─────────────────────

    @Test
    fun `related suggestions populate after 200ms debounce when query is typed`() = runTest {
        searchRepo.suggestionsHandler = { query ->
            Result.success(
                listOf(
                    SearchSuggestion("$query songs"),
                    SearchSuggestion("$query latest"),
                    SearchSuggestion("$query romantic")
                )
            )
        }

        viewModel.onQueryChange("arij")
        advanceTimeBy(250) // Suggestion debounce is 200ms
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.relatedSuggestions.size)
        assertEquals("arij songs", state.relatedSuggestions[0].query)
        assertEquals("arij latest", state.relatedSuggestions[1].query)
    }

    @Test
    fun `related suggestions do NOT require user search history`() = runTest {
        val fakeHistory = FakeSearchHistoryRepo()
        val vm = SearchViewModel(
            searchRepository = searchRepo,
            libraryRepository = fakeLibraryRepo,
            searchHistoryRepository = fakeHistory
        )
        advanceUntilIdle()

        // History is completely empty
        assertTrue(fakeHistory.historyFlow.value.isEmpty())

        // User types partial query
        searchRepo.suggestionsHandler = {
            Result.success(listOf(SearchSuggestion("Tum Hi Ho"), SearchSuggestion("Tum Mile")))
        }

        vm.onQueryChange("tum")
        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.relatedSuggestions.size)
        assertEquals("Tum Hi Ho", vm.uiState.value.relatedSuggestions[0].query)
    }

    @Test
    fun `blank query immediately clears related suggestions`() = runTest {
        searchRepo.suggestionsHandler = { Result.success(listOf(SearchSuggestion("Apna Bana Le"))) }

        viewModel.onQueryChange("apna")
        advanceTimeBy(250)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.relatedSuggestions.size)

        // User clears input
        viewModel.onQueryChange("")
        val state = viewModel.uiState.value
        assertTrue(state.relatedSuggestions.isEmpty())
        assertFalse(state.isSuggestionsLoading)
    }

    @Test
    fun `stale suggestion responses are discarded when query changed while in-flight`() = runTest {
        val deferred = CompletableDeferred<Result<List<SearchSuggestion>>>()
        searchRepo.suggestionsHandler = { query ->
            if (query == "arij") deferred.await()
            else Result.success(listOf(SearchSuggestion("fresh response for $query")))
        }

        viewModel.onQueryChange("arij")
        advanceTimeBy(250)

        // User typed further before response returned
        viewModel.onQueryChange("arijit singh")
        advanceTimeBy(250)

        // Complete the old response for "arij"
        deferred.complete(Result.success(listOf(SearchSuggestion("old suggestion"))))
        advanceUntilIdle()

        // Old response should not be set because query is now "arijit singh"
        assertFalse(viewModel.uiState.value.relatedSuggestions.any { it.query == "old suggestion" })
        assertTrue(viewModel.uiState.value.relatedSuggestions.any { it.query == "fresh response for arijit singh" })
    }

    @Test
    fun `suggestion tap populates query, submits search, clears suggestions, and records history`() = runTest {
        val fakeHistory = FakeSearchHistoryRepo()
        val vm = SearchViewModel(
            searchRepository = searchRepo,
            libraryRepository = fakeLibraryRepo,
            searchHistoryRepository = fakeHistory
        )
        advanceUntilIdle()

        searchRepo.suggestionsHandler = {
            Result.success(listOf(SearchSuggestion("Arijit Singh songs")))
        }

        vm.onQueryChange("arij")
        advanceTimeBy(250)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.relatedSuggestions.size)

        // User taps the suggestion
        vm.onSuggestionSelected(SearchSuggestion("Arijit Singh songs"))
        advanceTimeBy(350)
        advanceUntilIdle()

        // 1. Query is updated
        assertEquals("Arijit Singh songs", vm.uiState.value.query)
        // 2. Suggestions are dismissed
        assertTrue(vm.uiState.value.relatedSuggestions.isEmpty())
        // 3. Recorded into search history
        assertEquals(listOf("Arijit Singh songs"), fakeHistory.addedQueries)
        // 4. Search was executed and submission flags set
        assertTrue(vm.uiState.value.songsState is ModeSearchState.Success)
        assertTrue(vm.uiState.value.isSearchSubmitted)
        assertEquals("Arijit Singh songs", vm.uiState.value.lastSubmittedQuery)
    }

    @Test
    fun `tapping search history entry executes catalog search and updates submission state`() = runTest {
        val fakeHistory = FakeSearchHistoryRepo()
        val vm = SearchViewModel(
            searchRepository = searchRepo,
            libraryRepository = fakeLibraryRepo,
            searchHistoryRepository = fakeHistory
        )
        fakeHistory.historyFlow.value = listOf(
            SearchHistoryEntry(id = 1, query = "Believer", searchedAt = 1000L)
        )
        advanceUntilIdle()

        // History entry clicked: UI triggers onQueryChange then onQuerySubmit
        vm.onQueryChange("Believer")
        vm.onQuerySubmit("Believer")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isSearchSubmitted)
        assertEquals("Believer", state.lastSubmittedQuery)
        assertEquals(1, searchRepo.songsCallCount)
        assertEquals(1, searchRepo.videosCallCount)
        assertTrue(state.songsState is ModeSearchState.Success)
    }

    @Test
    fun `provider failure in suggestions does not break search UI`() = runTest {
        searchRepo.suggestionsHandler = { Result.failure(RuntimeException("Autocomplete timeout")) }

        viewModel.onQueryChange("kesariya")
        advanceTimeBy(250)
        advanceUntilIdle()

        // Suggestions empty gracefully
        assertTrue(viewModel.uiState.value.relatedSuggestions.isEmpty())
        assertFalse(viewModel.uiState.value.isSuggestionsLoading)

        // Catalog search proceeds when submitted
        viewModel.onQuerySubmit("kesariya")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.songsState is ModeSearchState.Success)
    }

    @Test
    fun `search history and related suggestions remain completely separate`() = runTest {
        val fakeHistory = FakeSearchHistoryRepo()
        val vm = SearchViewModel(
            searchRepository = searchRepo,
            libraryRepository = fakeLibraryRepo,
            searchHistoryRepository = fakeHistory
        )
        fakeHistory.historyFlow.value = listOf(
            SearchHistoryEntry(id = 1, query = "Queen", searchedAt = 1000L)
        )
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.searchHistory.size)
        assertTrue(vm.uiState.value.relatedSuggestions.isEmpty())

        searchRepo.suggestionsHandler = {
            Result.success(listOf(SearchSuggestion("Radiohead Creep")))
        }

        vm.onQueryChange("rad")
        advanceTimeBy(250)
        advanceUntilIdle()

        // History remains intact (1 item), suggestions has 1 item
        assertEquals(1, vm.uiState.value.searchHistory.size)
        assertEquals("Queen", vm.uiState.value.searchHistory[0].query)
        assertEquals(1, vm.uiState.value.relatedSuggestions.size)
        assertEquals("Radiohead Creep", vm.uiState.value.relatedSuggestions[0].query)
    }

    @Test
    fun `deleteHistoryEntry and clearSearchHistory delegate properly`() = runTest {
        val fakeHistory = FakeSearchHistoryRepo()
        val vm = SearchViewModel(
            searchRepository = searchRepo,
            libraryRepository = fakeLibraryRepo,
            searchHistoryRepository = fakeHistory
        )
        advanceUntilIdle()

        vm.deleteHistoryEntry(10L)
        advanceUntilIdle()
        assertEquals(listOf(10L), fakeHistory.deletedIds)

        vm.clearSearchHistory()
        advanceUntilIdle()
        assertTrue(fakeHistory.clearAllCalled)
    }
}

private class FakeSearchHistoryRepo : SearchHistoryRepository {
    val historyFlow = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
    val addedQueries = mutableListOf<String>()
    val deletedIds = mutableListOf<Long>()
    var clearAllCalled = false

    override fun observeHistory(): Flow<List<SearchHistoryEntry>> = historyFlow

    override suspend fun addQuery(query: String) {
        addedQueries.add(query)
    }

    override suspend fun deleteEntry(id: Long) {
        deletedIds.add(id)
    }

    override suspend fun clearAll() {
        clearAllCalled = true
    }

    override suspend fun getSuggestionsForPrefix(prefix: String, limit: Int): List<String> = emptyList()
}
