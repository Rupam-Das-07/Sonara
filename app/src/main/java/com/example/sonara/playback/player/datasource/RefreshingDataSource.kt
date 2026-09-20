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
    private val streamResolverPort: StreamResolverPort
) : DataSource {

    private var currentDataSpec: DataSpec? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstreamDataSource.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        currentDataSpec = dataSpec
        return try {
            upstreamDataSource.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 403 || e.responseCode == 410) {
                // Token expired mid-stream: extract track ID and resolve fresh URL
                val trackId = dataSpec.customData as? String ?: dataSpec.uri.getQueryParameter("trackId")
                if (trackId != null) {
                    val freshStream = runBlocking { streamResolverPort.resolveStream(trackId) }
                    if (freshStream.isSuccess) {
                        val newUri = Uri.parse(freshStream.getOrThrow().streamUrl)
                        val refreshedDataSpec = dataSpec.buildUpon().setUri(newUri).build()
                        currentDataSpec = refreshedDataSpec
                        return upstreamDataSource.open(refreshedDataSpec)
                    }
                }
            }
            throw e
        }
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
        private val baseHttpDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return RefreshingDataSource(
                upstreamDataSource = baseHttpDataSourceFactory.createDataSource(),
                streamResolverPort = streamResolverPort
            )
        }
    }
}
