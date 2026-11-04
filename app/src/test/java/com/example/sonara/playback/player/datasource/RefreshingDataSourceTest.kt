package com.example.sonara.playback.player.datasource

import android.net.TestUri
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.ports.StreamResolverPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class RefreshingDataSourceTest {

    private class CustomTestUri(private val uriString: String) : TestUri() {
        override fun toString(): String = uriString
    }

    private class FakeUpstreamDataSource(
        var openBehavior: (DataSpec) -> Long = { 1024L }
    ) : DataSource {
        val openedSpecs = mutableListOf<DataSpec>()

        override fun open(dataSpec: DataSpec): Long {
            openedSpecs.add(dataSpec)
            return openBehavior(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = openedSpecs.lastOrNull()?.uri
        override fun close() {}
        override fun addTransferListener(transferListener: TransferListener) {}
    }

    private class FakeStreamResolver(
        var freshStreamUrl: String = "https://cf-media.sndcdn.com/fresh.m3u8",
        var refreshFailure: Exception? = null
    ) : StreamResolverPort {
        val refreshCalls = AtomicInteger(0)
        val refreshedTrackIds = mutableListOf<String>()

        override suspend fun resolveStream(trackId: String, quality: AudioQuality): Result<StreamInfo> =
            refreshStream(trackId, quality)

        override suspend fun resolveStream(
            trackId: String,
            quality: AudioQuality,
            title: String,
            artist: String,
            durationSeconds: Int
        ): Result<StreamInfo> = refreshStream(trackId, quality)

        override suspend fun refreshStream(trackId: String, quality: AudioQuality): Result<StreamInfo> {
            refreshCalls.incrementAndGet()
            refreshedTrackIds.add(trackId)
            refreshFailure?.let { return Result.failure(it) }
            return Result.success(
                StreamInfo(
                    trackId = trackId,
                    streamUrl = freshStreamUrl,
                    provider = if (trackId.startsWith("sc:")) "soundcloud" else "youtube"
                )
            )
        }

        override fun clearCache() {}
    }

    private fun createInvalidResponseException(responseCode: Int, dataSpec: DataSpec): HttpDataSource.InvalidResponseCodeException {
        return HttpDataSource.InvalidResponseCodeException(
            responseCode,
            "HTTP $responseCode",
            null,
            emptyMap(),
            dataSpec,
            ByteArray(0)
        )
    }

    @Test
    fun `HTTP 403 on sc ID triggers refreshStream once and retries open with fresh URL`() {
        val initialUri = CustomTestUri("https://cf-media.sndcdn.com/expired.m3u8")
        val initialSpec = DataSpec.Builder()
            .setUri(initialUri)
            .setKey("sc:123456")
            .build()

        var callCount = 0
        val upstream = FakeUpstreamDataSource { spec ->
            callCount++
            if (callCount == 1) {
                throw createInvalidResponseException(403, spec)
            }
            2048L // Success on second attempt
        }
        val resolver = FakeStreamResolver(freshStreamUrl = "https://cf-media.sndcdn.com/fresh_token.m3u8")
        val dataSource = RefreshingDataSource(
            upstreamDataSource = upstream,
            streamResolverPort = resolver,
            uriParser = { CustomTestUri(it) }
        )

        val bytes = dataSource.open(initialSpec)

        assertEquals("Should return length from retried open", 2048L, bytes)
        assertEquals("Refresh must be called exactly once", 1, resolver.refreshCalls.get())
        assertEquals("Refreshed track ID must match sc:123456", "sc:123456", resolver.refreshedTrackIds.first())
        assertEquals("Upstream must have opened twice", 2, upstream.openedSpecs.size)
        assertEquals("https://cf-media.sndcdn.com/fresh_token.m3u8", upstream.openedSpecs[1].uri.toString())
    }

    @Test
    fun `HTTP 410 on sc ID triggers refreshStream once and retries open with fresh URL`() {
        val initialUri = CustomTestUri("https://cf-media.sndcdn.com/gone.m3u8")
        val initialSpec = DataSpec.Builder()
            .setUri(initialUri)
            .setKey("sc:789012")
            .build()

        var callCount = 0
        val upstream = FakeUpstreamDataSource { spec ->
            callCount++
            if (callCount == 1) {
                throw createInvalidResponseException(410, spec)
            }
            4096L
        }
        val resolver = FakeStreamResolver(freshStreamUrl = "https://cf-media.sndcdn.com/fresh_410.m3u8")
        val dataSource = RefreshingDataSource(
            upstreamDataSource = upstream,
            streamResolverPort = resolver,
            uriParser = { CustomTestUri(it) }
        )

        val bytes = dataSource.open(initialSpec)

        assertEquals(4096L, bytes)
        assertEquals(1, resolver.refreshCalls.get())
        assertEquals("sc:789012", resolver.refreshedTrackIds.first())
        assertEquals("https://cf-media.sndcdn.com/fresh_410.m3u8", upstream.openedSpecs[1].uri.toString())
    }

    @Test
    fun `repeated failure on retried open throws exception without infinite retry loop`() {
        val initialUri = CustomTestUri("https://cf-media.sndcdn.com/expired.m3u8")
        val initialSpec = DataSpec.Builder()
            .setUri(initialUri)
            .setKey("sc:fail_loop")
            .build()

        val upstream = FakeUpstreamDataSource { spec ->
            // Always throw 403, even on refreshed URL
            throw createInvalidResponseException(403, spec)
        }
        val resolver = FakeStreamResolver(freshStreamUrl = "https://cf-media.sndcdn.com/fresh_but_still_403.m3u8")
        val dataSource = RefreshingDataSource(
            upstreamDataSource = upstream,
            streamResolverPort = resolver,
            uriParser = { CustomTestUri(it) }
        )

        try {
            dataSource.open(initialSpec)
            fail("Expected InvalidResponseCodeException")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }

        assertEquals("Must only attempt refresh once", 1, resolver.refreshCalls.get())
        assertEquals("Upstream must have opened at most twice (initial + 1 retry)", 2, upstream.openedSpecs.size)

        // Subsequent call on same DataSource instance must not retry again because budget is exhausted
        try {
            dataSource.open(initialSpec)
            fail("Expected InvalidResponseCodeException on subsequent open")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }
        assertEquals("Refresh call count must remain 1 (retry budget exhausted)", 1, resolver.refreshCalls.get())
    }

    @Test
    fun `refresh failure surfaces original InvalidResponseCodeException`() {
        val initialUri = CustomTestUri("https://cf-media.sndcdn.com/expired.m3u8")
        val initialSpec = DataSpec.Builder()
            .setUri(initialUri)
            .setKey("sc:refresh_fails")
            .build()

        val upstream = FakeUpstreamDataSource { spec ->
            throw createInvalidResponseException(403, spec)
        }
        val resolver = FakeStreamResolver(refreshFailure = IOException("SoundCloud API unreachable"))
        val dataSource = RefreshingDataSource(
            upstreamDataSource = upstream,
            streamResolverPort = resolver,
            uriParser = { CustomTestUri(it) }
        )

        try {
            dataSource.open(initialSpec)
            fail("Expected original InvalidResponseCodeException to be thrown")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }

        assertEquals(1, resolver.refreshCalls.get())
        assertEquals(1, upstream.openedSpecs.size)
    }

    @Test
    fun `non-403 non-410 HTTP error does not trigger refresh and rethrows immediately`() {
        val initialUri = CustomTestUri("https://cf-media.sndcdn.com/server_error.m3u8")
        val initialSpec = DataSpec.Builder()
            .setUri(initialUri)
            .setKey("sc:server_err")
            .build()

        val upstream = FakeUpstreamDataSource { spec ->
            throw createInvalidResponseException(500, spec)
        }
        val resolver = FakeStreamResolver()
        val dataSource = RefreshingDataSource(
            upstreamDataSource = upstream,
            streamResolverPort = resolver,
            uriParser = { CustomTestUri(it) }
        )

        try {
            dataSource.open(initialSpec)
            fail("Expected 500 InvalidResponseCodeException")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(500, e.responseCode)
        }

        assertEquals("No refresh should be attempted on 500", 0, resolver.refreshCalls.get())
        assertEquals(1, upstream.openedSpecs.size)
    }

    @Test
    fun `HTTP 403 on non-SoundCloud YouTube track also refreshes correctly via streamResolverPort`() {
        val initialUri = CustomTestUri("https://googlevideo.com/videoplayback?expire=123")
        val initialSpec = DataSpec.Builder()
            .setUri(initialUri)
            .setKey("yt_normal_video_id")
            .build()

        var callCount = 0
        val upstream = FakeUpstreamDataSource { spec ->
            callCount++
            if (callCount == 1) {
                throw createInvalidResponseException(403, spec)
            }
            8192L
        }
        val resolver = FakeStreamResolver(freshStreamUrl = "https://googlevideo.com/videoplayback?expire=456")
        val dataSource = RefreshingDataSource(
            upstreamDataSource = upstream,
            streamResolverPort = resolver,
            uriParser = { CustomTestUri(it) }
        )

        val bytes = dataSource.open(initialSpec)

        assertEquals(8192L, bytes)
        assertEquals(1, resolver.refreshCalls.get())
        assertEquals("yt_normal_video_id", resolver.refreshedTrackIds.first())
        assertEquals("https://googlevideo.com/videoplayback?expire=456", upstream.openedSpecs[1].uri.toString())
    }
}
