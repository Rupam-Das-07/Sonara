package com.example.sonara.data.stream

import android.util.Log
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.ports.StreamResolverPort
import java.util.concurrent.ConcurrentHashMap
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
 * Maintains an in-memory session cache keyed by trackId and quality so pre-fetched
 * or recently resolved streams resolve in 0ms without redundant network latency.
 *
 * Stream URLs are STRICTLY EPHEMERAL — they MUST NOT be persisted to
 * Room, DataStore, files, or any durable storage.
 */
class StreamResolverImpl(
    private val backendClient: SonaraBackendClient = SonaraBackendClient()
) : StreamResolverPort {

    companion object {
        private const val TAG = "StreamResolverImpl"
        private const val EXPIRATION_SAFETY_MARGIN_MS = 60_000L // 1 minute safety margin
    }

    private val streamCache = ConcurrentHashMap<String, StreamInfo>()
    private val inFlightMutex = Mutex()
    private val inFlightRequests = HashMap<String, CompletableDeferred<Result<StreamInfo>>>()

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

        val qualityKey = when (quality) {
            AudioQuality.HIGH, AudioQuality.VERY_HIGH -> "HIGH"
            else -> "STANDARD"
        }
        val cacheKey = "$trackId:$qualityKey"
        val now = System.currentTimeMillis()
        val cached = streamCache[cacheKey]
        if (cached != null && (cached.expiresAt == 0L || cached.expiresAt > now + EXPIRATION_SAFETY_MARGIN_MS)) {
            Log.d(TAG, "Stream cache hit for key=$cacheKey (0ms resolution)")
            return@withContext Result.success(cached)
        }

        // Atomic leader-follower in-flight deduplication
        val (isLeader, deferred, cachedResult) = inFlightMutex.withLock {
            val cachedInside = streamCache[cacheKey]
            if (cachedInside != null && (cachedInside.expiresAt == 0L || cachedInside.expiresAt > System.currentTimeMillis() + EXPIRATION_SAFETY_MARGIN_MS)) {
                return@withLock Triple(false, null, cachedInside)
            }
            val existing = inFlightRequests[cacheKey]
            if (existing != null) {
                Triple(false, existing, null)
            } else {
                val newDeferred = CompletableDeferred<Result<StreamInfo>>()
                inFlightRequests[cacheKey] = newDeferred
                Triple(true, newDeferred, null)
            }
        }

        if (cachedResult != null) {
            Log.d(TAG, "Stream cache hit inside lock for key=$cacheKey")
            return@withContext Result.success(cachedResult)
        }

        if (!isLeader) {
            Log.d(TAG, "Joining in-flight stream resolution for key=$cacheKey")
            return@withContext deferred!!.await()
        }

        try {
            Log.d(TAG, "Resolving stream from backend for trackId=$trackId (quality=$qualityKey, title=$title)")
            val result = backendClient.getStreamUrl(trackId, quality, title, artist, durationSeconds)
            result.onSuccess { info ->
                streamCache[cacheKey] = info
            }
            deferred?.complete(result)
            result
        } catch (t: Throwable) {
            val failure = Result.failure<StreamInfo>(t)
            deferred?.complete(failure)
            failure
        } finally {
            inFlightMutex.withLock {
                inFlightRequests.remove(cacheKey)
            }
        }
    }

    override fun clearCache() {
        Log.d(TAG, "Clearing in-memory stream cache (${streamCache.size} entries)")
        streamCache.clear()
        inFlightRequests.clear()
    }
}
