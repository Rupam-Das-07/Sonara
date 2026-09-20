package com.example.sonara.domain.model

/**
 * Domain model for a single search history entry.
 *
 * Intentionally separate from [HistoryItem] (playback history) to prevent
 * any future conflation between what the user searched for vs. what they played.
 *
 * [id]         — stable Room primary key for targeted deletion.
 * [query]      — the normalized search query string (trimmed, lowercase).
 * [searchedAt] — epoch-millisecond timestamp of when the search was submitted.
 */
data class SearchHistoryEntry(
    val id: Long,
    val query: String,
    val searchedAt: Long
)
