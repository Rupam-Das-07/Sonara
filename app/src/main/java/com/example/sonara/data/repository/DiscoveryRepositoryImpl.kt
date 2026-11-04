package com.example.sonara.data.repository

import android.util.Log
import com.example.sonara.BuildConfig
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.discovery.ArtistIdentity
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
    private val maxCacheEntries: Int = 30,
    private val artistImageTtlMs: Long = 10 * 60 * 1000L,
    private val artistImageMissTtlMs: Long = 30 * 1000L
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
    private val artistImageCache = ConcurrentHashMap<String, CacheEntry<String?>>()

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

    override suspend fun getArtistImage(artistName: String): Result<String?> = withContext(Dispatchers.IO) {
        val cleanName = artistName.trim()
        if (cleanName.isBlank()) return@withContext Result.success(null)
        // Key on the app's identity authority, not on a bare lowercase string (FA-006). Portrait
        // lookups are name-addressed, and `lowercase()` alone treats "Hans  Zimmer", a decomposed
        // "Beyoncé", or "A.R. Rahman" vs "AR Rahman" as different artists — each costing an extra
        // cache slot and an extra upstream request for ONE identity. [ArtistIdentity.nameKey] is
        // the same canonicalisation the selector, the calculator and the backend already use, and
        // it is deliberately NOT lossy (unlike `slugify`), so genuinely different artists such as
        // "Beyoncé" and "Beyonce" keep separate entries. Names it cannot canonicalise — a credit
        // with no letters or digits, e.g. the band "!!!" — fall back to today's key rather than
        // being dropped. The request itself still carries the caller's original spelling.
        val cacheKey = ArtistIdentity.nameKey(cleanName) ?: cleanName.lowercase()
        val bypassPortraitCache = BuildConfig.VERIFY_PORTRAITS
        val now = System.currentTimeMillis()
        val cached = artistImageCache[cacheKey]
        val cachedTtl = if (cached?.data == null) artistImageMissTtlMs else artistImageTtlMs
        if (!bypassPortraitCache && cached != null && now - cached.timestamp < cachedTtl) {
            Log.d(TAG, "Artist image cache hit for $cacheKey (0ms)")
            return@withContext Result.success(cached.data)
        }

        executeDeduplicated("discovery:artist_image:$cacheKey") {
            val result = backendClient.getArtistImage(cleanName)
            if (!bypassPortraitCache) result.onSuccess { imageUrl ->
                evictOldest(artistImageCache, maxCacheEntries)
                artistImageCache[cacheKey] = CacheEntry(imageUrl, System.currentTimeMillis())
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
        artistImageCache.clear()
        featuredArtistsCache = null
        playlistsCache = null
        quickPicksCache = null
        inFlightRequests.clear()
    }
}
