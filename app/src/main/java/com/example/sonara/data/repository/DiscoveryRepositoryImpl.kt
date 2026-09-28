package com.example.sonara.data.repository

import android.util.Log
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.DiscoveryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Production implementation of [DiscoveryRepository] with:
 * 1. Concurrent in-flight request deduplication via atomic mutex + CompletableDeferred.
 * 2. Bounded in-memory session caching with TTL (avoids redundant network roundtrips).
 * 3. Cache bypass on explicit refresh requests.
 */
class DiscoveryRepositoryImpl(
    private val backendClient: SonaraBackendClient = SonaraBackendClient(),
    private val defaultTtlMs: Long = 10 * 60 * 1000L, // 10 minutes default TTL
    private val catalogTtlMs: Long = 15 * 60 * 1000L, // 15 minutes for editorial catalog
    private val maxCacheEntries: Int = 30
) : DiscoveryRepository {

    companion object {
        private const val TAG = "DiscoveryRepoImpl"
    }

    private data class CacheEntry<T>(val data: T, val timestamp: Long)

    // In-flight request deduplication
    private val inFlightMutex = Mutex()
    private val inFlightRequests = HashMap<String, CompletableDeferred<*>>()

    // In-memory caches
    private val relatedTracksCache = ConcurrentHashMap<String, CacheEntry<List<Track>>>()
    private val playlistDetailCache = ConcurrentHashMap<String, CacheEntry<PlaylistDetail>>()
    private val artistCatalogCache = ConcurrentHashMap<String, CacheEntry<ArtistCatalog>>()

    @Volatile
    private var featuredArtistsCache: CacheEntry<List<FeaturedArtist>>? = null

    @Volatile
    private var playlistsCache: CacheEntry<List<PlaylistSummary>>? = null

    @Volatile
    private var quickPicksCache: CacheEntry<List<Track>>? = null

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> executeDeduplicated(
        cacheKey: String,
        fetch: suspend () -> Result<T>
    ): Result<T> {
        val (isLeader, deferred) = inFlightMutex.withLock {
            val existing = inFlightRequests[cacheKey] as? CompletableDeferred<Result<T>>
            if (existing != null) {
                false to existing
            } else {
                val newDeferred = CompletableDeferred<Result<T>>()
                inFlightRequests[cacheKey] = newDeferred
                true to newDeferred
            }
        }

        if (!isLeader) {
            Log.d(TAG, "Joining in-flight request for $cacheKey")
            return deferred.await()
        }

        return try {
            val result = fetch()
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlightMutex.withLock {
                inFlightRequests.remove(cacheKey)
            }
        }
    }

    override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = featuredArtistsCache
        if (cached != null && (now - cached.timestamp < catalogTtlMs)) {
            Log.d(TAG, "Featured artists cache hit (0ms)")
            return@withContext Result.success(cached.data)
        }

        executeDeduplicated("discovery:featured_artists") {
            val result = backendClient.getFeaturedArtists()
            result.onSuccess { list ->
                featuredArtistsCache = CacheEntry(list, System.currentTimeMillis())
            }
            result
        }
    }

    override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> = withContext(Dispatchers.IO) {
        val cleanKey = (artistId.ifBlank { artistName }).trim().lowercase()
        if (cleanKey.isBlank()) return@withContext Result.success(ArtistCatalog())

        val now = System.currentTimeMillis()
        val cached = artistCatalogCache[cleanKey]
        if (cached != null && (now - cached.timestamp < catalogTtlMs)) {
            Log.d(TAG, "Artist catalog cache hit for $cleanKey (0ms)")
            return@withContext Result.success(cached.data)
        }

        executeDeduplicated("discovery:artist:$cleanKey") {
            val result = backendClient.getArtistCatalog(artistId, artistName)
            result.onSuccess { catalog ->
                evictOldest(artistCatalogCache, maxCacheEntries)
                artistCatalogCache[cleanKey] = CacheEntry(catalog, System.currentTimeMillis())
            }
            result
        }
    }

    override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = playlistsCache
        if (cached != null && (now - cached.timestamp < catalogTtlMs)) {
            Log.d(TAG, "Playlists catalog cache hit (0ms)")
            return@withContext Result.success(cached.data)
        }

        executeDeduplicated("discovery:playlists") {
            val result = backendClient.getPlaylists()
            result.onSuccess { list ->
                playlistsCache = CacheEntry(list, System.currentTimeMillis())
            }
            result
        }
    }

    override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> = withContext(Dispatchers.IO) {
        val cleanId = playlistId.trim()
        if (cleanId.isBlank()) return@withContext backendClient.getPlaylistDetail(playlistId)

        val now = System.currentTimeMillis()
        val cached = playlistDetailCache[cleanId]
        if (cached != null && (now - cached.timestamp < defaultTtlMs)) {
            Log.d(TAG, "Playlist detail cache hit for $cleanId (0ms)")
            return@withContext Result.success(cached.data)
        }

        executeDeduplicated("discovery:playlist_detail:$cleanId") {
            val result = backendClient.getPlaylistDetail(cleanId)
            result.onSuccess { detail ->
                evictOldest(playlistDetailCache, maxCacheEntries)
                playlistDetailCache[cleanId] = CacheEntry(detail, System.currentTimeMillis())
            }
            result
        }
    }

    override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = quickPicksCache
        if (!refresh && cached != null && (now - cached.timestamp < defaultTtlMs)) {
            Log.d(TAG, "Quick picks cache hit (0ms)")
            return@withContext Result.success(cached.data)
        }

        executeDeduplicated("discovery:quickpicks:$refresh") {
            val result = backendClient.getQuickPicks(refresh)
            result.onSuccess { list ->
                quickPicksCache = CacheEntry(list, System.currentTimeMillis())
            }
            result
        }
    }

    override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        val cleanSeed = seedVideoId.trim()
        if (cleanSeed.isBlank()) return@withContext Result.success(emptyList())

        val now = System.currentTimeMillis()
        val cached = relatedTracksCache[cleanSeed]
        if (cached != null && (now - cached.timestamp < defaultTtlMs)) {
            Log.d(TAG, "Related tracks cache hit for seed=$cleanSeed (0ms)")
            return@withContext Result.success(cached.data)
        }

        executeDeduplicated("discovery:related:$cleanSeed") {
            Log.d(TAG, "Fetching related tracks over network for seed=$cleanSeed")
            val result = backendClient.getRelatedTracks(cleanSeed)
            result.onSuccess { candidates ->
                evictOldest(relatedTracksCache, maxCacheEntries)
                relatedTracksCache[cleanSeed] = CacheEntry(candidates, System.currentTimeMillis())
            }
            result
        }
    }

    private fun <K, V> evictOldest(map: ConcurrentHashMap<K, CacheEntry<V>>, maxEntries: Int) {
        if (map.size >= maxEntries) {
            val oldestKey = map.minByOrNull { it.value.timestamp }?.key
            if (oldestKey != null) {
                map.remove(oldestKey)
            }
        }
    }

    fun clearCache() {
        Log.d(TAG, "Clearing discovery caches")
        relatedTracksCache.clear()
        playlistDetailCache.clear()
        artistCatalogCache.clear()
        featuredArtistsCache = null
        playlistsCache = null
        quickPicksCache = null
        inFlightRequests.clear()
    }
}
