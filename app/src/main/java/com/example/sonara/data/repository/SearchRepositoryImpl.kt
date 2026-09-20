package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.SearchHistoryRepository
import com.example.sonara.domain.repository.SearchRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for dual-mode catalog search with bounded in-memory caching.
 *
 * Implements:
 * - Mode 1: searchSongs (YouTube Music with server-side SearchQualityEngine)
 * - Mode 2: searchVideos (YouTube Video-to-Audio discovery via yt-dlp)
 * - 10-minute bounded in-memory cache keyed by normalized query.
 * - getSuggestions: live autocomplete suggestions derived from real-time query interpretation.
 */
class SearchRepositoryImpl(
    private val backendClient: SonaraBackendClient = SonaraBackendClient(),
    private val searchHistoryRepository: SearchHistoryRepository? = null,
    private val cacheTtlMs: Long = 10 * 60 * 1000L, // 10 minutes TTL
    private val maxCacheEntries: Int = 100
) : SearchRepository {

    data class CachedSearchEntry(
        val songs: List<Track>? = null,
        val videos: List<Track>? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val searchCache = ConcurrentHashMap<String, CachedSearchEntry>()

    private fun normalizeQuery(query: String): String =
        query.trim().lowercase().replace(Regex("\\s+"), " ")

    override suspend fun searchSongs(query: String): Result<List<Track>> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return Result.success(emptyList())

        val normKey = normalizeQuery(cleanQuery)
        val now = System.currentTimeMillis()
        val existing = searchCache[normKey]

        if (existing?.songs != null && (now - existing.timestamp < cacheTtlMs)) {
            return Result.success(existing.songs)
        }

        val result = backendClient.searchSongs(cleanQuery)
        result.onSuccess { tracks ->
            putSongsInCache(normKey, tracks, now)
        }
        return result
    }

    override suspend fun searchVideos(query: String): Result<List<Track>> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return Result.success(emptyList())

        val normKey = normalizeQuery(cleanQuery)
        val now = System.currentTimeMillis()
        val existing = searchCache[normKey]

        if (existing?.videos != null && (now - existing.timestamp < cacheTtlMs)) {
            return Result.success(existing.videos)
        }

        val result = backendClient.searchVideos(cleanQuery)
        result.onSuccess { tracks ->
            putVideosInCache(normKey, tracks, now)
        }
        return result
    }

    private fun putSongsInCache(key: String, songs: List<Track>, timestamp: Long) {
        if (searchCache.size >= maxCacheEntries && !searchCache.containsKey(key)) {
            // Evict oldest entry
            val oldestKey = searchCache.minByOrNull { it.value.timestamp }?.key
            if (oldestKey != null) searchCache.remove(oldestKey)
        }
        val current = searchCache[key]
        searchCache[key] = CachedSearchEntry(
            songs = songs,
            videos = current?.videos,
            timestamp = timestamp
        )
    }

    private fun putVideosInCache(key: String, videos: List<Track>, timestamp: Long) {
        if (searchCache.size >= maxCacheEntries && !searchCache.containsKey(key)) {
            // Evict oldest entry
            val oldestKey = searchCache.minByOrNull { it.value.timestamp }?.key
            if (oldestKey != null) searchCache.remove(oldestKey)
        }
        val current = searchCache[key]
        searchCache[key] = CachedSearchEntry(
            songs = current?.songs,
            videos = videos,
            timestamp = timestamp
        )
    }

    /**
     * Suggestions sourced from the real-time search autocomplete / related-search engine.
     *
     * Queries backendClient.getSuggestions(partialQuery).
     * If the provider fails or yields empty, it safely falls back to local history prefix-matching
     * without surfacing errors or delaying search.
     *
     * Returns an empty list on blank input.
     */
    override suspend fun getSuggestions(partialQuery: String): Result<List<SearchSuggestion>> {
        val trimmed = partialQuery.trim()
        if (trimmed.isBlank()) return Result.success(emptyList())

        return try {
            val remoteResult = backendClient.getSuggestions(trimmed)
            val remoteSuggestions = remoteResult.getOrNull()
            if (!remoteSuggestions.isNullOrEmpty()) {
                Result.success(remoteSuggestions)
            } else {
                // Resilient fallback to local history prefix matching if remote is empty
                val fallbackHistory = searchHistoryRepository
                    ?.getSuggestionsForPrefix(trimmed, limit = 5)
                    ?: emptyList()
                Result.success(fallbackHistory.map { SearchSuggestion(query = it) })
            }
        } catch (e: Exception) {
            // Suggestion failures are silent and non-disruptive
            val fallbackHistory = searchHistoryRepository
                ?.getSuggestionsForPrefix(trimmed, limit = 5)
                ?: emptyList()
            Result.success(fallbackHistory.map { SearchSuggestion(query = it) })
        }
    }

    /**
     * Clear in-memory cache (primarily for test isolation).
     */
    fun clearCache() {
        searchCache.clear()
    }
}
