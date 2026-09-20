package com.example.sonara.data.stream

import android.util.Log
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.ports.StreamResolverPort
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
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

        Log.d(TAG, "Resolving stream from backend for trackId=$trackId (quality=$qualityKey, title=$title)")
        val result = backendClient.getStreamUrl(trackId, quality, title, artist, durationSeconds)
        result.onSuccess { info ->
            streamCache[cacheKey] = info
        }
        result
    }

    override fun clearCache() {
        Log.d(TAG, "Clearing in-memory stream cache (${streamCache.size} entries)")
        streamCache.clear()
    }
}
