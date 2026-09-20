package com.example.sonara.feature.search

import com.example.sonara.domain.model.SearchHistoryEntry
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track

/**
 * State representing a single search mode's lifecycle (Mode 1: Songs or Mode 2: Videos).
 */
sealed class ModeSearchState {
    data object Initial : ModeSearchState()
    data object Loading : ModeSearchState()
    data class Success(val tracks: List<Track>) : ModeSearchState()
    data class Empty(val query: String) : ModeSearchState()
    data class Error(val message: String) : ModeSearchState()
}

/**
 * Composite search screen UI state coordinating both search modes concurrently.
 *
 * SEPARATION OF THREE CONCEPTS:
 * 1. [searchHistory]      = Previously submitted queries by this user (stored in Room DB).
 * 2. [relatedSuggestions] = Real-time search-as-you-type autocomplete suggestions based on query text.
 * 3. [songsState] / [videosState] = Full catalog search results returned upon search execution.
 */
data class SearchUiState(
    val query: String = "",
    val activeMode: SearchMode = SearchMode.SONGS,
    val songsState: ModeSearchState = ModeSearchState.Initial,
    val videosState: ModeSearchState = ModeSearchState.Initial,

    /** Persistent past search queries submitted by the user. */
    val searchHistory: List<SearchHistoryEntry> = emptyList(),

    /** Live related-search suggestions based on user's current partial query. */
    val relatedSuggestions: List<SearchSuggestion> = emptyList(),

    /** Indicates whether related suggestions are currently being fetched. */
    val isSuggestionsLoading: Boolean = false,

    /** Indicates whether a search has been explicitly submitted (keyboard Enter or suggestion click). */
    val isSearchSubmitted: Boolean = false,

    /** The last query string that was explicitly submitted. Preserved during query editing. */
    val lastSubmittedQuery: String? = null
) {
    /**
     * Active state for the currently visible tab.
     */
    val currentModeState: ModeSearchState
        get() = when (activeMode) {
            SearchMode.SONGS -> songsState
            SearchMode.VIDEOS -> videosState
        }
}
