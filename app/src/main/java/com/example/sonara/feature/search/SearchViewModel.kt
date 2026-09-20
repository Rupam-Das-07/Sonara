package com.example.sonara.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.LibraryRepository
import com.example.sonara.domain.repository.SearchHistoryRepository
import com.example.sonara.domain.repository.SearchRepository
import com.example.sonara.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * SearchViewModel coordinating progressive dual-mode catalog discovery (Mode 1: Songs & Mode 2: Videos),
 * real-time search-as-you-type autocomplete suggestions, and persistent user search history.
 *
 * Implements:
 * - 300ms query debounce with latest-search cancellation for full catalog search.
 * - 200ms debounce for live related-search suggestions.
 * - Strict separation between Search History (past queries) and Related Suggestions (live autocomplete).
 * - Stale-result protection on async suggestion lookups.
 * - Mode failure isolation: errors in one provider do not block or corrupt the other.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository? = null,
    private val searchHistoryRepository: SearchHistoryRepository? = null
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var lastSubmittedQuery: String? = null

    init {
        // Restore default search mode from settings
        if (settingsRepository != null) {
            viewModelScope.launch {
                settingsRepository.getUserPreferences().collect { prefs ->
                    if (_query.value.isEmpty()) {
                        _uiState.update { it.copy(activeMode = prefs.defaultSearchMode) }
                    }
                }
            }
        }

        // Live related-search suggestion pipeline: debounce 200ms, live query autocomplete
        viewModelScope.launch {
            _query
                .debounce(200L)
                .distinctUntilChanged()
                .collect { partial ->
                    loadSuggestions(partial)
                }
        }

        // Observe search history reactively (persisted past queries)
        if (searchHistoryRepository != null) {
            searchHistoryRepository.observeHistory()
                .onEach { history ->
                    _uiState.update { it.copy(searchHistory = history) }
                }
                .launchIn(viewModelScope)
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery

        // When query becomes blank, reset mode states and clear suggestions
        if (newQuery.isBlank()) {
            suggestionJob?.cancel()
            searchJob?.cancel()
            _uiState.update {
                it.copy(
                    query = newQuery,
                    isSearchSubmitted = false,
                    relatedSuggestions = emptyList(),
                    isSuggestionsLoading = false,
                    songsState = ModeSearchState.Initial,
                    videosState = ModeSearchState.Initial
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    query = newQuery,
                    isSearchSubmitted = false
                )
            }
        }
    }

    /**
     * Called when a user submits a search query (keyboard Enter or explicit tap).
     * Records the query in persistent search history, executes dual-mode search,
     * updates submission state, and clears active suggestions.
     */
    fun onQuerySubmit(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        lastSubmittedQuery = trimmed
        _query.value = trimmed
        _uiState.update {
            it.copy(
                query = trimmed,
                isSearchSubmitted = true,
                lastSubmittedQuery = trimmed,
                relatedSuggestions = emptyList(),
                isSuggestionsLoading = false
            )
        }

        // Persist to search history only when submitted
        viewModelScope.launch {
            searchHistoryRepository?.addQuery(trimmed)
        }

        // Dismiss live suggestions immediately upon submission
        suggestionJob?.cancel()

        // Execute full dual-mode catalog search
        executeSearch(trimmed)
    }

    /**
     * Called when the user taps on a related search suggestion.
     * Populates query, executes full search, and records in history.
     */
    fun onSuggestionSelected(suggestion: SearchSuggestion) {
        val selectedQuery = suggestion.query.trim()
        if (selectedQuery.isBlank()) return
        onQuerySubmit(selectedQuery)
    }

    fun selectMode(mode: SearchMode) {
        _uiState.update { it.copy(activeMode = mode) }
    }

    /**
     * Delete a single search history entry by its stable Room id.
     */
    fun deleteHistoryEntry(id: Long) {
        viewModelScope.launch {
            searchHistoryRepository?.deleteEntry(id)
        }
    }

    /**
     * Clear all search history entries.
     */
    fun clearSearchHistory() {
        viewModelScope.launch {
            searchHistoryRepository?.clearAll()
        }
    }

    private fun executeSearch(searchPhrase: String) {
        searchJob?.cancel()
        val trimmed = searchPhrase.trim()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(
                    songsState = ModeSearchState.Initial,
                    videosState = ModeSearchState.Initial
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                songsState = ModeSearchState.Loading,
                videosState = ModeSearchState.Loading
            )
        }

        searchJob = viewModelScope.launch {
            // Mode 1: Official Songs Search (YTMusic + SQE)
            launch {
                val songsResult = searchRepository.searchSongs(trimmed)
                val newSongsState = songsResult.fold(
                    onSuccess = { tracks ->
                        if (tracks.isEmpty()) ModeSearchState.Empty(trimmed)
                        else ModeSearchState.Success(tracks)
                    },
                    onFailure = { error ->
                        ModeSearchState.Error(error.message ?: "Failed to search songs")
                    }
                )
                _uiState.update { it.copy(songsState = newSongsState) }
            }

            // Mode 2: YouTube Videos Search (yt-dlp)
            launch {
                val videosResult = searchRepository.searchVideos(trimmed)
                val newVideosState = videosResult.fold(
                    onSuccess = { tracks ->
                        if (tracks.isEmpty()) ModeSearchState.Empty(trimmed)
                        else ModeSearchState.Success(tracks)
                    },
                    onFailure = { error ->
                        ModeSearchState.Error(error.message ?: "Failed to search videos")
                    }
                )
                _uiState.update { it.copy(videosState = newVideosState) }
            }
        }
    }

    /**
     * Fetch real-time related suggestions for a partial query.
     *
     * Stale-result protection: suggestions are matched against current [_query].
     * If query changed while fetching, the result is safely discarded.
     */
    private fun loadSuggestions(partial: String) {
        suggestionJob?.cancel()
        val trimmed = partial.trim()
        if (trimmed.isBlank() || _uiState.value.isSearchSubmitted) {
            _uiState.update {
                it.copy(
                    relatedSuggestions = emptyList(),
                    isSuggestionsLoading = false
                )
            }
            return
        }

        _uiState.update { it.copy(isSuggestionsLoading = true) }

        suggestionJob = viewModelScope.launch {
            val result = searchRepository.getSuggestions(trimmed)

            // Stale-result guard: discard if user typed something else or search was submitted
            if (_query.value.trim() != trimmed || _uiState.value.isSearchSubmitted) {
                return@launch
            }

            _uiState.update {
                it.copy(
                    relatedSuggestions = result.getOrDefault(emptyList()),
                    isSuggestionsLoading = false
                )
            }
        }
    }

    fun cacheTrackMetadata(track: Track) {
        viewModelScope.launch {
            libraryRepository.cacheTrack(track)
        }
    }
}
