package com.example.sonara.data.stream

import android.util.Log
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.data.remote.extractor.SoundcloudTrackExtractor
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.soundcloud.SoundcloudSourceIdentity
import com.example.sonara.domain.ports.SoundcloudTrackExtractionPort
import com.example.sonara.domain.ports.StreamResolverPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production implementation of StreamResolverPort.
 *
 * Resolves audio streams ephemerally via the Sonara backend with AudioQuality awareness:
 *   Android → Node /api/v1/stream/resolve → Python/yt-dlp → proxy URL
 *
 * Maintains a BOUNDED in-memory session cache keyed by trackId and quality so pre-fetched
 * or recently resolved streams resolve in 0ms without redundant network latency. The cache
 * is LRU-evicted (hard entry cap) and stores ONLY StreamInfo metadata — never audio bytes.
 *
 * Stream URLs are STRICTLY EPHEMERAL — they MUST NOT be persisted to
 * Room, DataStore, files, or any durable storage.
 */
class StreamResolverImpl(
    private val backendClient: SonaraBackendClient = SonaraBackendClient(),
    private val soundcloudExtractor: SoundcloudTrackExtractionPort = SoundcloudTrackExtractor(),
    nowProvider: () -> Long = { System.currentTimeMillis() }
) : StreamResolverPort {

    companion object {
        private const val TAG = "StreamResolverImpl"

        // An entry must remain valid for at least this long beyond "now" to be served from
        // cache, so a URL never expires moments after a hit.
        private const val EXPIRATION_SAFETY_MARGIN_MS = 60_000L // 1 minute safety margin

        // Conservative fallback lifetime used ONLY when the backend supplies no explicit
        // expiry (expiresAt <= 0). Chosen as 15 minutes because:
        //  - It is finite, so a missing-expiry entry is NEVER treated as permanently valid
        //    (this fixes the previous expiresAt == 0 => "cached forever" bug).
        //  - It comfortably spans a typical continuous listening session, so Previous/replay
        //    and rapid re-selection still benefit from 0ms cache hits.
        //  - It is short enough that a silently-dead proxy URL is proactively re-resolved,
        //    and it is additionally backstopped by the 403/410 forced-refresh recovery path.
        private const val DEFAULT_STREAM_TTL_MS = 15 * 60 * 1000L // 15 minutes

        // Hard bound on retained StreamInfo entries. StreamInfo is tiny metadata (no audio
        // bytes), so this is only a few KB, yet it covers a realistic working set (current
        // track + queue + recent back-stack, across HIGH/STANDARD variants). LRU-evicted.
        private const val MAX_CACHE_ENTRIES = 64
    }

    private val streamCache = BoundedStreamInfoCache(
        maxEntries = MAX_CACHE_ENTRIES,
        ttlForUnknownExpiryMs = DEFAULT_STREAM_TTL_MS,
        safetyMarginMs = EXPIRATION_SAFETY_MARGIN_MS,
        nowProvider = nowProvider
    )
    /** Guards the in-flight registry and cache invalidation generation. */
    private val stateLock = Any()
    private val inFlightMutex = Mutex()
    private val inFlightRequests = HashMap<String, CompletableDeferred<Result<StreamInfo>>>()
    private var cacheGeneration = 0L

    private data class ResolutionRegistration(
        val isLeader: Boolean,
        val deferred: CompletableDeferred<Result<StreamInfo>>?,
        val cachedResult: StreamInfo?,
        val generation: Long
    )

    override suspend fun resolveStream(
        trackId: String,
        quality: AudioQuality
    ): Result<StreamInfo> = resolveStream(trackId, quality, "", "", 0)

    override suspend fun resolveStream(
        trackId: String,
        quality: AudioQuality,
        title: String,
        artist: String,
        durationSeconds: Int
    ): Result<StreamInfo> = withContext(Dispatchers.IO) {
        if (trackId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("trackId cannot be blank"))
        }

        val qualityKey = qualityKeyFor(quality)
        val cacheKey = "$trackId:$qualityKey"
        val cached = streamCache.getFresh(cacheKey)
        if (cached != null) {
            Log.d(TAG, "Stream cache hit for key=$cacheKey (0ms resolution)")
            return@withContext Result.success(cached)
        }

        // Atomic leader-follower in-flight deduplication
        val registration = inFlightMutex.withLock {
            synchronized(stateLock) {
                val cachedInside = streamCache.getFresh(cacheKey)
                if (cachedInside != null) {
                    return@synchronized ResolutionRegistration(false, null, cachedInside, cacheGeneration)
                }
                val existing = inFlightRequests[cacheKey]
                if (existing != null) {
                    ResolutionRegistration(false, existing, null, cacheGeneration)
                } else {
                    val newDeferred = CompletableDeferred<Result<StreamInfo>>()
                    inFlightRequests[cacheKey] = newDeferred
                    ResolutionRegistration(true, newDeferred, null, cacheGeneration)
                }
            }
        }

        if (registration.cachedResult != null) {
            Log.d(TAG, "Stream cache hit inside lock for key=$cacheKey")
            return@withContext Result.success(registration.cachedResult)
        }

        if (!registration.isLeader) {
            Log.d(TAG, "Joining in-flight stream resolution for key=$cacheKey")
            return@withContext registration.deferred!!.await()
        }

        val deferred = registration.deferred!!

        try {
            val result = if (SoundcloudSourceIdentity.isSoundcloudTrackId(trackId.trim())) {
                Log.d(TAG, "Resolving SoundCloud stream for trackId=$trackId")
                resolveSoundcloudStream(trackId.trim())
            } else {
                Log.d(TAG, "Resolving stream from backend for trackId=$trackId (quality=$qualityKey, title=$title)")
                Log.d(TAG, "LATENCY_T1 resolve start: key=$cacheKey t=${System.currentTimeMillis()}")
                backendClient.getStreamUrl(trackId, quality, title, artist, durationSeconds)
            }
            result.onSuccess { info ->
                synchronized(stateLock) {
                    // A clearCache() that happened while the backend was working invalidates
                    // this result. Do not let stale work repopulate the cleared cache.
                    if (cacheGeneration == registration.generation &&
                        inFlightRequests[cacheKey] === deferred
                    ) {
                        streamCache.put(cacheKey, info)
                    }
                }
                Log.d(TAG, "LATENCY_T2 resolve done: key=$cacheKey t=${System.currentTimeMillis()}")
            }
            deferred.complete(result)
            result
        } catch (cancelled: CancellationException) {
            deferred.cancel(cancelled)
            throw cancelled
        } catch (t: Throwable) {
            val failure = Result.failure<StreamInfo>(t)
            deferred.complete(failure)
            failure
        } finally {
            inFlightMutex.withLock {
                synchronized(stateLock) {
                    // clearCache() or a newer generation may have replaced this entry. Never
                    // remove a newer request registered under the same key.
                    if (inFlightRequests[cacheKey] === deferred) {
                        inFlightRequests.remove(cacheKey)
                    }
                }
            }
        }
    }

    private suspend fun resolveSoundcloudStream(trackId: String): Result<StreamInfo> {
        val extractionResult = soundcloudExtractor.extract(trackId)
        val extraction = extractionResult.getOrElse { throwable ->
            return Result.failure(throwable)
        }
        val stream = extraction.selectedStream
        return Result.success(
            StreamInfo(
                trackId = trackId,
                streamUrl = stream.streamUrl,
                expiresAt = 0L,
                format = stream.mimeType,
                codec = stream.mediaFormat.lowercase(),
                bitrateKbps = stream.bitrateKbps,
                qualityTier = "STANDARD",
                provider = "soundcloud"
            )
        )
    }

    /**
     * Force a fresh resolution, bypassing (and invalidating) any cached StreamInfo for this
     * track. Invalidates BOTH quality variants so a stale/expired proxy URL can never be
     * served for [trackId], regardless of which quality the recovery resolves at. Delegates
     * to [resolveStream] afterwards, which now cache-misses and performs a real backend
     * resolve — preserving in-flight deduplication.
     */
    override suspend fun refreshStream(
        trackId: String,
        quality: AudioQuality
    ): Result<StreamInfo> {
        // Keys mirror cacheKeyFor(): "$trackId:${qualityKeyFor(quality)}" with keys HIGH / STANDARD.
        streamCache.remove("$trackId:HIGH")
        streamCache.remove("$trackId:STANDARD")
        return resolveStream(trackId, quality)
    }

    override fun clearCache() {
        synchronized(stateLock) {
            Log.d(TAG, "Clearing in-memory stream cache (${streamCache.size()} entries)")
            cacheGeneration++
            streamCache.clear()
            // Existing callers retain their deferred result, but new callers must not join
            // work that was already in progress when the cache was cleared.
            inFlightRequests.clear()
        }
    }

    private fun qualityKeyFor(quality: AudioQuality): String = when (quality) {
        AudioQuality.HIGH, AudioQuality.VERY_HIGH -> "HIGH"
        else -> "STANDARD"
    }
}

/**
 * Bounded, thread-safe LRU cache for ephemeral StreamInfo metadata.
 *
 * Backed by an access-order LinkedHashMap with removeEldestEntry, so retention is hard-capped
 * at [maxEntries] and the least-recently-used entry is evicted first. Every operation is
 * @Synchronized because access-order get() structurally reorders the map.
 *
 * Caches ONLY StreamInfo metadata — NEVER audio bytes. Memory-only; nothing is persisted.
 *
 * Freshness: an entry is served only while it is expected to stay valid for at least
 * [safetyMarginMs] beyond now. When the backend supplies no explicit expiry (expiresAt <= 0),
 * a finite [ttlForUnknownExpiryMs] is applied instead of treating the entry as permanently
 * valid. A stale entry is proactively evicted on read.
 */
private class BoundedStreamInfoCache(
    private val maxEntries: Int,
    private val ttlForUnknownExpiryMs: Long,
    private val safetyMarginMs: Long,
    private val nowProvider: () -> Long
) {
    private data class CacheEntry(val info: StreamInfo, val storedAtMs: Long)

    private val map = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun getFresh(key: String): StreamInfo? {
        val entry = map[key] ?: return null
        return if (isFresh(entry, nowProvider())) {
            Log.d("StreamResolverImpl", "LATENCY_T1_HIT cache hit: key=$key t=${System.currentTimeMillis()}")
            entry.info
        } else {
            // Proactively evict a stale entry so it is neither served now nor retained.
            map.remove(key)
            null
        }
    }

    @Synchronized
    fun put(key: String, info: StreamInfo) {
        map[key] = CacheEntry(info, nowProvider())
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size

    private fun isFresh(entry: CacheEntry, now: Long): Boolean {
        val effectiveExpiry = if (entry.info.expiresAt > 0L) {
            entry.info.expiresAt
        } else {
            entry.storedAtMs + ttlForUnknownExpiryMs
        }
        return effectiveExpiry > now + safetyMarginMs
    }
}
