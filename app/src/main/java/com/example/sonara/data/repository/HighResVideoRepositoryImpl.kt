package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.video.HighResVideoDiscovery
import com.example.sonara.domain.repository.HighResVideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Thread-safe implementation of [HighResVideoRepository].
 *
 * Enforces:
 * - In-memory positive and negative caching bounded by backend `expiresAt` timestamps.
 * - In-flight request deduplication per video ID.
 * - Non-blocking asynchronous query execution on [ioDispatcher] with [SupervisorJob].
 * - Cancellation of obsolete requests on track transitions without cancelling shared dependencies.
 */
class HighResVideoRepositoryImpl(
    private val backendClient: SonaraBackendClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(ioDispatcher + SupervisorJob())
) : HighResVideoRepository {

    private val cacheLock = Any()
    private val discoveryCache = mutableMapOf<String, HighResVideoDiscovery>()
    private val inFlightRequests = mutableMapOf<String, Deferred<Result<HighResVideoDiscovery>>>()

    override suspend fun discoverHighRes(
        videoId: String,
        forceRefresh: Boolean
    ): Result<HighResVideoDiscovery> = withContext(ioDispatcher) {
        val cleanVideoId = videoId.removePrefix("yt-").trim()
        if (cleanVideoId.length != 11) {
            return@withContext Result.failure(
                IllegalArgumentException("Invalid YouTube video ID: $cleanVideoId")
            )
        }

        val nowSeconds = System.currentTimeMillis() / 1000L

        // 1. Check in-memory cache unless forceRefresh is requested
        if (!forceRefresh) {
            val cached = synchronized(cacheLock) {
                discoveryCache[cleanVideoId]
            }
            if (cached != null) {
                if (nowSeconds < cached.expiresAt) {
                    return@withContext Result.success(cached)
                } else {
                    synchronized(cacheLock) {
                        discoveryCache.remove(cleanVideoId)
                    }
                }
            }
        }

        // 2. Request deduplication: join existing in-flight request if available and not forceRefresh
        var deferred: Deferred<Result<HighResVideoDiscovery>>? = null
        synchronized(cacheLock) {
            if (forceRefresh) {
                inFlightRequests.remove(cleanVideoId)?.cancel()
            } else {
                deferred = inFlightRequests[cleanVideoId]
            }

            if (deferred == null) {
                lateinit var newDeferred: Deferred<Result<HighResVideoDiscovery>>
                newDeferred = scope.async {
                    try {
                        val result = backendClient.getVideoHighRes(cleanVideoId)
                        result.onSuccess { discovery ->
                            synchronized(cacheLock) {
                                val currentNow = System.currentTimeMillis() / 1000L
                                if (discovery.expiresAt > currentNow) {
                                    discoveryCache[cleanVideoId] = discovery
                                }
                            }
                        }
                        result
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Result.failure(e)
                    } finally {
                        synchronized(cacheLock) {
                            // Only remove if this exact Deferred instance is still registered
                            if (inFlightRequests[cleanVideoId] === newDeferred) {
                                inFlightRequests.remove(cleanVideoId)
                            }
                        }
                    }
                }
                inFlightRequests[cleanVideoId] = newDeferred
                deferred = newDeferred
            }
        }

        try {
            deferred!!.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getCachedDiscovery(videoId: String): HighResVideoDiscovery? {
        val cleanVideoId = videoId.removePrefix("yt-").trim()
        val nowSeconds = System.currentTimeMillis() / 1000L
        synchronized(cacheLock) {
            val cached = discoveryCache[cleanVideoId] ?: return null
            if (nowSeconds < cached.expiresAt) {
                return cached
            } else {
                discoveryCache.remove(cleanVideoId)
                return null
            }
        }
    }

    override fun cancelDiscovery(videoId: String) {
        val cleanVideoId = videoId.removePrefix("yt-").trim()
        synchronized(cacheLock) {
            inFlightRequests.remove(cleanVideoId)?.cancel()
        }
    }

    override fun clearCache() {
        synchronized(cacheLock) {
            inFlightRequests.values.forEach { it.cancel() }
            inFlightRequests.clear()
            discoveryCache.clear()
        }
    }

    override fun evict(videoId: String) {
        val cleanVideoId = videoId.removePrefix("yt-").trim()
        synchronized(cacheLock) {
            inFlightRequests.remove(cleanVideoId)?.cancel()
            discoveryCache.remove(cleanVideoId)
        }
    }
}
