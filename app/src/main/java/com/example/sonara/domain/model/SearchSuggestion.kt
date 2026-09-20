package com.example.sonara.domain.model

/**
 * Represents a search suggestion / autocomplete candidate derived from real-time
 * query interpretation (e.g. YouTube / YouTube Music search suggest).
 *
 * Distinct from:
 * - [SearchHistoryEntry] (stored locally in Room, user-submitted past queries)
 * - [Track] (full playable media track entity)
 */
data class SearchSuggestion(
    /** The suggested query string (e.g. "Arijit Singh romantic songs"). */
    val query: String
)
