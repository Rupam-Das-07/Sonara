package com.example.sonara.data.repository

import android.util.Log
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.SearchSuggestion
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.SearchHistoryRepository
import com.example.sonara.domain.repository.SearchRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for dual-mode catalog search with bounded in-memory caching and request deduplication.
 *
 * Implements:
 * - Mode 1: searchSongs (YouTube Music with server-side SearchQualityEngine)
 * - Mode 2: searchVideos (YouTube Video-to-Audio discovery via yt-dlp)
 * - 10-minute bounded in-memory cache keyed by normalized query.
 * - getSuggestions: live autocomplete suggestions with 5-minute bounded LRU caching,
 *   in-flight deduplication, and >= 2 character remote gating (1-char queries use 0ms local history).
 */
class SearchRepositoryImpl(
    private val backendClient: SonaraBackendClient = SonaraBackendClient(),
    private val searchHistoryRepository: SearchHistoryRepository? = null,
    private val cacheTtlMs: Long = 10 * 60 * 1000L, // 10 minutes TTL for search results
    private val suggestionsTtlMs: Long = 5 * 60 * 1000L, // 5 minutes TTL for suggestions
    private val maxCacheEntries: Int = 100
) : SearchRepository {

    companion object {
        private const val TAG = "SearchRepositoryImpl"
        private const val MIN_REMOTE_SUGGESTION_LENGTH = 2
    }

    data class CachedSearchEntry(
        val songs: List<Track>? = null,
        val videos: List<Track>? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private data class CachedSuggestionEntry(
        val suggestions: List<SearchSuggestion>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val searchCache = ConcurrentHashMap<String, CachedSearchEntry>()
    private val suggestionsCache = ConcurrentHashMap<String, CachedSuggestionEntry>()
    private val inFlightMutex = Mutex()
    private val inFlightSuggestions = HashMap<String, CompletableDeferred<Result<List<SearchSuggestion>>>>()

    private fun normalizeQuery(query: String): String =
        query.trim().lowercase().replace(Regex("\\s+"), " ")

    override suspend fun searchSongs(query: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext Result.success(emptyList())

        val normKey = normalizeQuery(cleanQuery)
        val now = System.currentTimeMillis()
        val existing = searchCache[normKey]

        if (existing?.songs != null && (now - existing.timestamp < cacheTtlMs)) {
            Log.d(TAG, "Search songs cache hit for '$normKey' (0ms)")
            return@withContext Result.success(existing.songs)
        }

        val result = backendClient.searchSongs(cleanQuery)
        result.onSuccess { tracks ->
            putSongsInCache(normKey, tracks, now)
        }
        result
    }

    override suspend fun searchVideos(query: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext Result.success(emptyList())

        val normKey = normalizeQuery(cleanQuery)
        val now = System.currentTimeMillis()
        val existing = searchCache[normKey]

        if (existing?.videos != null && (now - existing.timestamp < cacheTtlMs)) {
            Log.d(TAG, "Search videos cache hit for '$normKey' (0ms)")
            return@withContext Result.success(existing.videos)
        }

        val result = backendClient.searchVideos(cleanQuery)
        result.onSuccess { tracks ->
            putVideosInCache(normKey, tracks, now)
        }
        result
    }

    private fun putSongsInCache(key: String, songs: List<Track>, timestamp: Long) {
        if (searchCache.size >= maxCacheEntries && !searchCache.containsKey(key)) {
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
     * Queries backendClient.getSuggestions(partialQuery) with:
     * 1. Single-character remote bypass (length < 2 queries resolve from local search history without network).
     * 2. 5-minute in-memory cache.
     * 3. In-flight request deduplication for concurrent keystrokes.
     * 4. Resilient fallback to local history prefix matching if remote fails or is empty.
     */
    override suspend fun getSuggestions(partialQuery: String): Result<List<SearchSuggestion>> = withContext(Dispatchers.IO) {
        val trimmed = partialQuery.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())

        // Optimization: Single-character queries bypass remote network and resolve instantly from local history
        if (trimmed.length < MIN_REMOTE_SUGGESTION_LENGTH) {
            val fallbackHistory = searchHistoryRepository
                ?.getSuggestionsForPrefix(trimmed, limit = 5)
                ?: emptyList()
            return@withContext Result.success(fallbackHistory.map { SearchSuggestion(query = it) })
        }

        val normKey = normalizeQuery(trimmed)
        val now = System.currentTimeMillis()

        // 1. Check in-memory suggestion cache
        val cached = suggestionsCache[normKey]
        if (cached != null && (now - cached.timestamp < suggestionsTtlMs)) {
            Log.d(TAG, "Search suggestions cache hit for '$normKey' (0ms)")
            return@withContext Result.success(cached.suggestions)
        }

        val (isLeader, deferred) = inFlightMutex.withLock {
            val existing = inFlightSuggestions[normKey]
            if (existing != null) {
                false to existing
            } else {
                val newDeferred = CompletableDeferred<Result<List<SearchSuggestion>>>()
                inFlightSuggestions[normKey] = newDeferred
                true to newDeferred
            }
        }

        if (!isLeader) {
            Log.d(TAG, "Joining in-flight suggestion request for '$normKey'")
            return@withContext deferred.await()
        }

        try {
            val remoteResult = try {
                backendClient.getSuggestions(trimmed)
            } catch (e: Exception) {
                Result.failure(e)
            }
            val remoteSuggestions = remoteResult.getOrNull()
            val result = if (!remoteSuggestions.isNullOrEmpty()) {
                if (suggestionsCache.size >= maxCacheEntries && !suggestionsCache.containsKey(normKey)) {
                    val oldestKey = suggestionsCache.minByOrNull { it.value.timestamp }?.key
                    if (oldestKey != null) suggestionsCache.remove(oldestKey)
                }
                suggestionsCache[normKey] = CachedSuggestionEntry(remoteSuggestions, System.currentTimeMillis())
                Result.success(remoteSuggestions)
            } else {
                val fallbackHistory = searchHistoryRepository
                    ?.getSuggestionsForPrefix(trimmed, limit = 5)
                    ?: emptyList()
                Result.success(fallbackHistory.map { SearchSuggestion(query = it) })
            }
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlightMutex.withLock {
                inFlightSuggestions.remove(normKey)
            }
        }
    }

    /**
     * Clear in-memory caches (primarily for test isolation and user data clearing).
     */
    fun clearCache() {
        searchCache.clear()
        suggestionsCache.clear()
        inFlightSuggestions.clear()
    }
}
