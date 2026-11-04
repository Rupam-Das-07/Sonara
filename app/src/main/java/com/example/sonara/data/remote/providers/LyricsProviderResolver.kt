package com.example.sonara.data.remote.providers

import androidx.collection.LruCache
import com.example.sonara.domain.model.Lyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves lyrics for a track through a single registered [LyricsProvider], owning the
 * process-lifetime positive in-memory cache and in-flight request de-duplication that
 * previously lived inside LyricsRepositoryImpl (Lyrics Architecture Revision #1, Stage 2).
 *
 * Stage 2 scope: EXACTLY ONE provider is registered (LRCLIB, injected as a [LyricsProvider]).
 * There is deliberately NO second provider, NO fallback between providers, and NO
 * ranking/selection logic — those remain out of scope.
 *
 * Caching contract (preserved verbatim from the previous repository-level cache):
 *  - key: trackId
 *  - capacity: 100 (LRU)
 *  - positive-only: stores a successful [Lyrics.SyncedLyrics] / [Lyrics.PlainLyrics] /
 *    [Lyrics.Instrumental]; never stores [Lyrics.Unavailable] or a failed [Result].
 *
 * The cached value is the provider's returned [Lyrics]; downstream calibration and
 * romanization remain the caller's responsibility (LyricsRepositoryImpl), exactly as before.
 *
 * In-flight de-duplication: concurrent callers requesting the same trackId share a single
 * provider request, so the provider is invoked once per distinct in-flight track.
 */
class LyricsProviderResolver(
    private val provider: LyricsProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        private const val CACHE_SIZE = 100
    }

    // Positive in-memory cache: trackId -> Lyrics (max 100 entries). Moved from LyricsRepositoryImpl.
    private val memoryCache = LruCache<String, Lyrics>(CACHE_SIZE)

    // In-flight de-duplication: trackId -> shared provider fetch. Guarded by [inFlightMutex].
    private val inFlight = mutableMapOf<String, Deferred<Result<Lyrics>>>()
    private val inFlightMutex = Mutex()

    suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long
    ): Result<Lyrics> {
        // Fast path: positive cache hit (no lock, no provider call).
        memoryCache.get(trackId)?.let { return Result.success(it) }

        // Slow path: join the existing shared fetch for this trackId, or start a new one.
        val deferred = inFlightMutex.withLock {
            // Re-check under the lock in case another caller populated the cache meanwhile.
            memoryCache.get(trackId)?.let { return Result.success(it) }
            inFlight[trackId] ?: startFetch(trackId, title, artist, durationMs)
        }

        return deferred.await()
    }

    /**
     * Starts a single shared provider fetch for [trackId] and registers it for de-duplication.
     * Must be called while holding [inFlightMutex]. The successful, cacheable result is stored by
     * the producer itself, so caching does not depend on any individual awaiter's lifecycle.
     */
    private fun startFetch(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long
    ): Deferred<Result<Lyrics>> {
        val deferred = scope.async {
            val result = provider.getLyrics(title, artist, durationMs)
            result.onSuccess { lyrics ->
                if (lyrics is Lyrics.SyncedLyrics ||
                    lyrics is Lyrics.PlainLyrics ||
                    lyrics is Lyrics.Instrumental
                ) {
                    memoryCache.put(trackId, lyrics)
                }
            }
            result
        }
        inFlight[trackId] = deferred
        deferred.invokeOnCompletion {
            // Evict the in-flight entry once the shared fetch settles (success/failure/cancel),
            // guarding against clobbering a newer fetch that may have replaced this one.
            scope.launch {
                inFlightMutex.withLock {
                    if (inFlight[trackId] === deferred) {
                        inFlight.remove(trackId)
                    }
                }
            }
        }
        return deferred
    }
}
