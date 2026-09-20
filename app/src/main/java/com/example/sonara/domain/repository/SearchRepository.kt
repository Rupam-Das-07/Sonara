package com.example.sonara.domain.repository

import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track

/**
 * Domain interface for catalog and video query search discovery.
 * Exposes distinct operations for Mode 1 (Official Songs) and Mode 2 (YouTube Videos),
 * plus real-time search-as-you-type autocomplete suggestions.
 */
interface SearchRepository {
    suspend fun searchSongs(query: String): Result<List<Track>>
    suspend fun searchVideos(query: String): Result<List<Track>>

    /**
     * Backward-compatible alias defaulting to Mode 1 (Official Songs).
     */
    suspend fun search(query: String): Result<List<Track>> = searchSongs(query)

    /**
     * Return real-time search autocomplete / related-search suggestions for [partialQuery].
     * Suggestions are generated from live query interpretation independently from
     * whether the user has previously searched for the terms.
     *
     * Returns an empty list on blank input or on any failure — suggestion failures
     * must never disrupt or delay full search results.
     */
    suspend fun getSuggestions(partialQuery: String): Result<List<SearchSuggestion>>
}
