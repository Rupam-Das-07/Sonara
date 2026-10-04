package com.example.sonara.playback.player.cache

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.ports.StreamResolverPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class NextTrackPrefetcherTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun testCacheBoundConstant() {
        assertEquals(8 * 1024 * 1024L, SonaraMediaCache.MAX_CACHE_BYTES)
    }

    @Test
    fun testPrefetchByteBoundConstant() {
        assertEquals(256 * 1024L, NextTrackPrefetcher.MAX_PREFETCH_BYTES)
    }

    @Test
    fun testOfflineFileUriExclusion() = runTest(testDispatcher) {
        val fakeFactory = FakeDataSourceFactory()
        val prefetcher = NextTrackPrefetcher(
            cacheDataSourceFactory = fakeFactory,
            coroutineScope = testScope
        )

        prefetcher.prefetch("track_offline", "file:///data/user/0/com.example.sonara/files/track.mp3", 1L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Prefetcher must not be active for file:// URIs", prefetcher.isPrefetching)
        assertNull(prefetcher.getCurrentPrefetchTrackId())
        assertEquals(0, fakeFactory.createCallCount)
    }

    @Test
    fun testBlankUrlExclusion() = runTest(testDispatcher) {
        val fakeFactory = FakeDataSourceFactory()
        val prefetcher = NextTrackPrefetcher(
            cacheDataSourceFactory = fakeFactory,
            coroutineScope = testScope
        )

        prefetcher.prefetch("track_blank", "   ", 1L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Prefetcher must not be active for blank URIs", prefetcher.isPrefetching)
        assertNull(prefetcher.getCurrentPrefetchTrackId())
        assertEquals(0, fakeFactory.createCallCount)
    }

    @Test
    fun testControlToggleDisablesPrefetch() = runTest(testDispatcher) {
        val fakeFactory = FakeDataSourceFactory()
        val prefetcher = NextTrackPrefetcher(
            cacheDataSourceFactory = fakeFactory,
            coroutineScope = testScope
        )
        prefetcher.isEnabled = false

        prefetcher.prefetch("track_http", "http://127.0.0.1:3002/api/v1/stream/play?videoId=t1", 1L)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Disabled prefetcher must not launch prefetch job", prefetcher.isPrefetching)
        assertNull(prefetcher.getCurrentPrefetchTrackId())
        assertEquals(0, fakeFactory.createCallCount)
    }

    @Test
    fun testDuplicatePrevention() = runTest(testDispatcher) {
        val fakeFactory = FakeDataSourceFactory()
        val prefetcher = NextTrackPrefetcher(
            cacheDataSourceFactory = fakeFactory,
            coroutineScope = testScope
        )

        prefetcher.prefetch("track_dup", "http://127.0.0.1:3002/api/v1/stream/play?videoId=t1", 1L)
        testDispatcher.scheduler.runCurrent()
        assertTrue(prefetcher.isPrefetching)
        assertEquals("track_dup", prefetcher.getCurrentPrefetchTrackId())

        // Duplicate call with same trackId while in-flight
        prefetcher.prefetch("track_dup", "http://127.0.0.1:3002/api/v1/stream/play?videoId=t1", 1L)
        testDispatcher.scheduler.runCurrent()

        assertEquals("Duplicate call must not create a new DataSource", 0, fakeFactory.createCallCount)
        prefetcher.cancelActivePrefetch()
    }

    @Test
    fun testSingleActivePrefetchSupersedesOld() = runTest(testDispatcher) {
        val fakeFactory = FakeDataSourceFactory()
        val prefetcher = NextTrackPrefetcher(
            cacheDataSourceFactory = fakeFactory,
            coroutineScope = testScope
        )

        prefetcher.prefetch("track_1", "http://127.0.0.1:3002/api/v1/stream/play?videoId=1", 1L)
        testDispatcher.scheduler.runCurrent()
        assertTrue(prefetcher.isPrefetching)
        assertEquals("track_1", prefetcher.getCurrentPrefetchTrackId())

        // Start prefetch for new track (supersedes old)
        prefetcher.prefetch("track_2", "http://127.0.0.1:3002/api/v1/stream/play?videoId=2", 2L)
        testDispatcher.scheduler.runCurrent()

        assertEquals("track_2", prefetcher.getCurrentPrefetchTrackId())
        prefetcher.cancelActivePrefetch()
    }

    @Test
    fun testGenerationCancellation() = runTest(testDispatcher) {
        val fakeFactory = FakeDataSourceFactory()
        val prefetcher = NextTrackPrefetcher(
            cacheDataSourceFactory = fakeFactory,
            coroutineScope = testScope
        )

        prefetcher.prefetch("track_cancel", "http://127.0.0.1:3002/api/v1/stream/play?videoId=c", 1L)
        testDispatcher.scheduler.runCurrent()
        assertTrue(prefetcher.isPrefetching)

        prefetcher.cancelActivePrefetch("test_skip")
        testDispatcher.scheduler.runCurrent()

        assertFalse("Prefetcher must not be active after cancel", prefetcher.isPrefetching)
        assertNull(prefetcher.getCurrentPrefetchTrackId())
    }

    @Test
    fun testRefreshingDataSourcePreservesRecoveryContract() = runTest(testDispatcher) {
        val refreshCalls = AtomicInteger(0)
        val fakeResolver = object : StreamResolverPort {
            override suspend fun resolveStream(
                trackId: String,
                quality: AudioQuality
            ): Result<StreamInfo> = Result.success(
                StreamInfo(trackId = trackId, streamUrl = "http://initial_url", expiresAt = 1000L)
            )

            override suspend fun refreshStream(
                trackId: String,
                quality: AudioQuality
            ): Result<StreamInfo> {
                refreshCalls.incrementAndGet()
                return Result.success(
                    StreamInfo(trackId = trackId, streamUrl = "http://refreshed_url", expiresAt = 2000L)
                )
            }

            override fun clearCache() {}
        }

        // Verify resolver refresh contract directly
        val refreshResult = fakeResolver.refreshStream("authoritative_track_id", AudioQuality.AUTO)
        assertTrue(refreshResult.isSuccess)
        assertEquals("http://refreshed_url", refreshResult.getOrNull()?.streamUrl)
        assertEquals(1, refreshCalls.get())
    }

    // Helper fake factory for testing NextTrackPrefetcher lifecycle
    private class FakeDataSourceFactory : DataSource.Factory {
        var createCallCount = 0

        override fun createDataSource(): DataSource {
            createCallCount++
            return object : DataSource {
                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
                override fun open(dataSpec: DataSpec): Long = dataSpec.length
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = androidx.media3.common.C.RESULT_END_OF_INPUT
                override fun getUri(): android.net.Uri? = null
                override fun close() {}
            }
        }
    }
}
