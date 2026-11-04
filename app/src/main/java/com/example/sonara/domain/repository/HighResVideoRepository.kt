package com.example.sonara.domain.repository

import com.example.sonara.domain.model.video.HighResVideoDiscovery

/**
 * Domain boundary for asynchronous YouTube 8K high-resolution discovery.
 * Coordinates discovery requests, in-memory caching, TTL expiration, and request deduplication.
 */
interface HighResVideoRepository {
    /**
     * Discovers genuine 8K stream availability for a YouTube video.
     *
     * @param videoId 11-character YouTube video ID.
     * @param forceRefresh If true, bypasses the in-memory cache and queries the backend.
     * @return Result containing either [HighResVideoDiscovery.Available] or [HighResVideoDiscovery.Unavailable].
     */
    suspend fun discoverHighRes(
        videoId: String,
        forceRefresh: Boolean = false
    ): Result<HighResVideoDiscovery>

    /**
     * Returns any valid, non-expired cached discovery result for [videoId], or null.
     */
    fun getCachedDiscovery(videoId: String): HighResVideoDiscovery?

    /**
     * Cancels any active in-flight discovery request for [videoId].
     */
    fun cancelDiscovery(videoId: String)

    /**
     * Clears all cached discovery results and cancels active in-flight requests.
     */
    fun clearCache()

    /**
     * Evicts any cached discovery result for [videoId].
     */
    fun evict(videoId: String) {}
}
