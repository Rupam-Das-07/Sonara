package com.example.sonara.playback.player.cache

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lean, generation-safe prefetcher for the N+1 track audio stream.
 * Prefetches a bounded 256 KB audio head chunk using Media3's [DataSource].
 * When connected to a CacheDataSource, read bytes are automatically committed to the disk cache.
 * Guarantees strictly at most 1 active prefetch, LRU storage, and immediate cancellation on skip.
 */
@OptIn(UnstableApi::class)
class NextTrackPrefetcher(
    private val cacheDataSourceFactory: DataSource.Factory,
    private val coroutineScope: CoroutineScope,
    private val maxPrefetchBytes: Long = MAX_PREFETCH_BYTES
) {
    companion object {
        private const val TAG = "NextTrackPrefetcher"
        const val MAX_PREFETCH_BYTES = 256 * 1024L // 256 KB POC Bound
    }

    @Volatile
    var isEnabled: Boolean = true

    private var activeJob: Job? = null
    private var activeDataSource: DataSource? = null
    private var activeTrackId: String? = null
    private var activeGeneration: Long = -1L

    val isPrefetching: Boolean
        get() = activeJob?.isActive == true

    fun getCurrentPrefetchTrackId(): String? = activeTrackId

    @Synchronized
    fun cancelActivePrefetch(reason: String = "cancelled") {
        if (activeJob?.isActive == true) {
            val trackId = activeTrackId
            val gen = activeGeneration
            Log.d(TAG, "PREFETCH_CANCELLED: trackId=$trackId gen=$gen reason=$reason t=${System.currentTimeMillis()}")
            try {
                activeDataSource?.close()
            } catch (_: Exception) {}
            activeJob?.cancel()
        }
        activeJob = null
        activeDataSource = null
        activeTrackId = null
        activeGeneration = -1L
    }

    @Synchronized
    fun prefetch(
        trackId: String,
        streamUrl: String,
        generation: Long,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!isEnabled) {
            Log.d(TAG, "Prefetch skipped: prefetcher is disabled (control condition)")
            return
        }

        // 1. Offline file:// or empty URL check (do not prefetch local media)
        if (streamUrl.isBlank() || streamUrl.startsWith("file:")) {
            Log.d(TAG, "Prefetch skipped: non-http streamUrl for track $trackId")
            return
        }

        // 2. Duplicate prevention: if already actively prefetching this exact track and generation
        if (activeTrackId == trackId && activeJob?.isActive == true) {
            Log.d(TAG, "Prefetch duplicate ignored: $trackId already in-flight")
            return
        }

        // 3. Cancel any previous prefetch (Strictly 1 active prefetch)
        cancelActivePrefetch("superseded_by_new_track")

        activeTrackId = trackId
        activeGeneration = generation

        activeJob = coroutineScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            var firstByteReceived = false
            var bytesCachedTotal = 0L

            Log.d(TAG, "PREFETCH_START: trackId=$trackId gen=$generation maxBytes=$maxPrefetchBytes t=$t0")

            val uri = runCatching { Uri.parse(streamUrl) }.getOrNull()
            if (uri == null) {
                Log.w(TAG, "Prefetch aborted: Uri.parse returned null for $trackId")
                onComplete?.invoke(false)
                return@launch
            }

            val dataSpec = try {
                DataSpec.Builder()
                    .setUri(uri)
                    .setKey(trackId) // Stable track identity
                    .setPosition(0)
                    .setLength(maxPrefetchBytes)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch aborted: Failed to build DataSpec: ${e.message}")
                onComplete?.invoke(false)
                return@launch
            }

            var dataSource: DataSource? = null
            try {
                val ds = cacheDataSourceFactory.createDataSource()
                synchronized(this@NextTrackPrefetcher) {
                    if (activeTrackId == trackId && activeGeneration == generation) {
                        activeDataSource = ds
                        dataSource = ds
                    } else {
                        return@launch
                    }
                }

                ds.open(dataSpec)
                val buffer = ByteArray(16 * 1024)

                while (isActive && bytesCachedTotal < maxPrefetchBytes) {
                    val toRead = minOf(buffer.size.toLong(), maxPrefetchBytes - bytesCachedTotal).toInt()
                    val read = ds.read(buffer, 0, toRead)
                    if (read == C.RESULT_END_OF_INPUT) break
                    if (read > 0) {
                        if (!firstByteReceived) {
                            firstByteReceived = true
                            val tFirst = System.currentTimeMillis()
                            Log.d(TAG, "PREFETCH_FIRST_BYTE: trackId=$trackId gen=$generation elapsedMs=${tFirst - t0} t=$tFirst")
                        }
                        bytesCachedTotal += read
                        Log.d(TAG, "PREFETCH_BYTES: trackId=$trackId bytes=$bytesCachedTotal of $maxPrefetchBytes t=${System.currentTimeMillis()}")
                    }
                }

                val tDone = System.currentTimeMillis()
                Log.d(TAG, "PREFETCH_COMPLETE: trackId=$trackId gen=$generation bytes=$bytesCachedTotal elapsedMs=${tDone - t0} t=$tDone")
                onComplete?.invoke(true)
            } catch (e: CancellationException) {
                val tCancel = System.currentTimeMillis()
                Log.d(TAG, "PREFETCH_CANCELLED: trackId=$trackId gen=$generation bytesPrefetched=$bytesCachedTotal elapsedMs=${tCancel - t0} reason=job_cancelled")
                throw e
            } catch (e: Exception) {
                val tErr = System.currentTimeMillis()
                Log.w(TAG, "PREFETCH_ERROR: trackId=$trackId gen=$generation error=${e.message} elapsedMs=${tErr - t0}")
                onComplete?.invoke(false)
            } finally {
                try {
                    dataSource?.close()
                } catch (_: Exception) {}
                synchronized(this@NextTrackPrefetcher) {
                    if (activeTrackId == trackId) {
                        activeDataSource = null
                        activeJob = null
                        activeTrackId = null
                        activeGeneration = -1L
                    }
                }
            }
        }
    }
}
