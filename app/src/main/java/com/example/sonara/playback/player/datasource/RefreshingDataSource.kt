package com.example.sonara.playback.player.datasource

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.example.sonara.domain.ports.StreamResolverPort
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Custom Media3 DataSource for HTTP 403 / 410 stream token refresh.
 * Preserves the exact authoritative component name "RefreshingDataSource" from Phase 4B-1.
 */
class RefreshingDataSource(
    private val upstreamDataSource: DataSource,
    private val streamResolverPort: StreamResolverPort,
    private val uriParser: (String) -> Uri = { Uri.parse(it) }
) : DataSource {

    private var currentDataSpec: DataSpec? = null
    private var refreshAttempts = 0

    companion object {
        const val MAX_REFRESH_ATTEMPTS = 1
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstreamDataSource.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        currentDataSpec = dataSpec
        return try {
            upstreamDataSource.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if ((e.responseCode == 403 || e.responseCode == 410) && refreshAttempts < MAX_REFRESH_ATTEMPTS) {
                refreshAttempts++
                // Expired/revoked stream token. Recover by force-refreshing a FRESH URL for
                // this track (bypassing AND invalidating any stale cache entry), then retrying
                // the open on the new URL. Recovery is scoped to THIS track's DataSource
                // instance and refreshStream() only invalidates this trackId, so it can never
                // affect a different track or a newer generation.
                val trackId = resolveTrackId(dataSpec)
                if (trackId != null) {
                    val refreshed = runBlocking { streamResolverPort.refreshStream(trackId) }
                    val fresh = refreshed.getOrNull()
                    if (fresh != null && fresh.streamUrl.isNotBlank()) {
                        // Preserve every other DataSpec field (position, length, key,
                        // customData, flags, headers); only the URI is swapped to the fresh one.
                        val refreshedDataSpec = dataSpec.buildUpon()
                            .setUri(uriParser(fresh.streamUrl))
                            .build()
                        currentDataSpec = refreshedDataSpec
                        return upstreamDataSource.open(refreshedDataSpec)
                    }
                }
            }
            // Unknown trackId, non-recoverable status, or refresh failed: preserve original failure.
            throw e
        }
    }

    /**
     * Resolves the authoritative trackId for 403/410 recovery, in priority order:
     *  1. [DataSpec.key] — wired from MediaItem.setCustomCacheKey(trackId). This is the
     *     authoritative, backend-URL-independent channel and works for BOTH proxy-path and
     *     direct (googlevideo) stream delivery. No CacheDataSource is present in this chain,
     *     so `key` carries no caching side-effect — it is purely a trackId carrier here.
     *  2. [DataSpec.customData] — honored if a resolver layer ever sets it.
     *  3. URI query params `video_id` / `trackId` — defensive fallback for proxy-path
     *     delivery, where the backend embeds video_id=<trackId> in the proxy URL.
     */
    private fun resolveTrackId(dataSpec: DataSpec): String? {
        dataSpec.key?.takeIf { it.isNotBlank() }?.let { return it }
        (dataSpec.customData as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        val uri = dataSpec.uri
        uri?.getQueryParameter("video_id")?.takeIf { it.isNotBlank() }?.let { return it }
        uri?.getQueryParameter("trackId")?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstreamDataSource.read(buffer, offset, length)
    }

    override fun getUri(): Uri? {
        return upstreamDataSource.uri
    }

    @Throws(IOException::class)
    override fun close() {
        upstreamDataSource.close()
    }

    class Factory(
        private val context: Context,
        private val streamResolverPort: StreamResolverPort,
    private val uriParser: (String) -> Uri = { Uri.parse(it) },
        private val baseHttpDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return RefreshingDataSource(
                upstreamDataSource = baseHttpDataSourceFactory.createDataSource(),
                streamResolverPort = streamResolverPort,
                uriParser = uriParser
            )
        }
    }
}
