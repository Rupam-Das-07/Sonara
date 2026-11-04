package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.video.HighResAudioRepresentation
import com.example.sonara.domain.model.video.HighResByteRange
import com.example.sonara.domain.model.video.HighResVideoDiscovery
import com.example.sonara.domain.model.video.HighResVideoRepresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HighResVideoRepositoryTest {

    private fun samplePositiveDiscovery(
        videoId: String = "dQw4w9WgXcQ",
        sessionId: String = "sess_test_123",
        expiresAt: Long = (System.currentTimeMillis() / 1000L) + 3600L
    ): HighResVideoDiscovery.Available {
        return HighResVideoDiscovery.Available(
            videoId = videoId,
            sessionId = sessionId,
            extractedAt = System.currentTimeMillis() / 1000L,
            expiresAt = expiresAt,
            video = HighResVideoRepresentation(
                itag = 571,
                width = 7680,
                height = 4320,
                fps = 60,
                codec = "av01.0.12M.08",
                mimeType = "video/mp4",
                bitrate = 35_000_000L,
                duration = 100.0,
                initRange = HighResByteRange(0, 800),
                indexRange = HighResByteRange(801, 2000),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=$sessionId&track=video"
            ),
            audio = HighResAudioRepresentation(
                itag = 140,
                codec = "mp4a.40.2",
                mimeType = "audio/mp4",
                bitrate = 128_000L,
                duration = 100.0,
                initRange = HighResByteRange(0, 600),
                indexRange = HighResByteRange(601, 1500),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=$sessionId&track=audio"
            )
        )
    }

    private class FakeBackendClient(
        var responseProvider: suspend (String) -> Result<HighResVideoDiscovery>
    ) : SonaraBackendClient("http://10.0.2.2:3002") {
        val callCount = AtomicInteger(0)

        override suspend fun getVideoHighRes(videoId: String): Result<HighResVideoDiscovery> {
            callCount.incrementAndGet()
            return responseProvider(videoId)
        }
    }

    @Test
    fun `discoverHighRes caches positive discovery and reuses cache within TTL`() = runBlocking {
        val fakeClient = FakeBackendClient { videoId ->
            Result.success(samplePositiveDiscovery(videoId = videoId))
        }
        val repo = HighResVideoRepositoryImpl(fakeClient, Dispatchers.Default)

        // First call triggers network
        val result1 = repo.discoverHighRes("dQw4w9WgXcQ", forceRefresh = false)
        assertTrue(result1.isSuccess)
        assertEquals(1, fakeClient.callCount.get())

        // Cached lookup returns immediately without network call
        val cached = repo.getCachedDiscovery("dQw4w9WgXcQ")
        assertNotNull(cached)
        assertTrue(cached is HighResVideoDiscovery.Available)

        val result2 = repo.discoverHighRes("dQw4w9WgXcQ", forceRefresh = false)
        assertTrue(result2.isSuccess)
        assertEquals(1, fakeClient.callCount.get()) // No second network call

        // Force refresh bypasses cache
        val result3 = repo.discoverHighRes("dQw4w9WgXcQ", forceRefresh = true)
        assertTrue(result3.isSuccess)
        assertEquals(2, fakeClient.callCount.get())
    }

    @Test
    fun `discoverHighRes does not serve expired cache entries`() = runBlocking {
        val now = System.currentTimeMillis() / 1000L
        val fakeClient = FakeBackendClient { videoId ->
            Result.success(samplePositiveDiscovery(videoId = videoId, expiresAt = now - 10L)) // already expired
        }
        val repo = HighResVideoRepositoryImpl(fakeClient, Dispatchers.Default)

        val result1 = repo.discoverHighRes("dQw4w9WgXcQ", forceRefresh = false)
        assertTrue(result1.isSuccess)

        // Since it's already expired, getCachedDiscovery must return null
        assertNull(repo.getCachedDiscovery("dQw4w9WgXcQ"))
    }

    @Test
    fun `deduplicates concurrent in-flight requests for the same video`() = runBlocking {
        val holdGate = CompletableDeferred<Unit>()
        val fakeClient = FakeBackendClient { videoId ->
            holdGate.await()
            Result.success(samplePositiveDiscovery(videoId = videoId))
        }
        val repo = HighResVideoRepositoryImpl(fakeClient, Dispatchers.Default)

        val deferred1 = async { repo.discoverHighRes("dQw4w9WgXcQ", forceRefresh = false) }
        val deferred2 = async { repo.discoverHighRes("dQw4w9WgXcQ", forceRefresh = false) }

        delay(50)
        assertEquals(1, fakeClient.callCount.get()) // Only 1 request in flight

        holdGate.complete(Unit)

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals(1, fakeClient.callCount.get())
    }

    @Test
    fun `shared extraction survives caller cancellation and populates cache`() = runBlocking {
        val holdGate = CompletableDeferred<Unit>()
        val fakeClient = FakeBackendClient { videoId ->
            holdGate.await()
            Result.success(samplePositiveDiscovery(videoId = videoId))
        }
        val repo = HighResVideoRepositoryImpl(fakeClient, Dispatchers.Default)

        // Caller 1 launches and then cancels before completion
        val job1 = launch {
            repo.discoverHighRes("dQw4w9WgXcQ", forceRefresh = false)
        }
        delay(50)
        job1.cancelAndJoin()

        // Release the backend task
        holdGate.complete(Unit)
        delay(50)

        // The background extraction ran in repo scope (SupervisorJob) and populated cache
        val cached = repo.getCachedDiscovery("dQw4w9WgXcQ")
        assertNotNull(cached)
        assertEquals("dQw4w9WgXcQ", cached?.videoId)
    }

    @Test
    fun `cancelDiscovery cancels in-flight extraction on track transition`() = runBlocking {
        val startedGate = CompletableDeferred<Unit>()
        val cancelledGate = CompletableDeferred<Boolean>()

        val fakeClient = FakeBackendClient { _ ->
            try {
                startedGate.complete(Unit)
                delay(5000)
                Result.success(samplePositiveDiscovery())
            } catch (e: Exception) {
                cancelledGate.complete(true)
                throw e
            }
        }
        val repo = HighResVideoRepositoryImpl(fakeClient, Dispatchers.Default)

        val callerJob = launch {
            repo.discoverHighRes("dQw4w9WgXcQ", forceRefresh = false)
        }
        startedGate.await()

        // Explicitly cancel discovery for track transition
        repo.cancelDiscovery("dQw4w9WgXcQ")

        val wasCancelled = cancelledGate.await()
        assertTrue(wasCancelled)
        callerJob.cancelAndJoin()
    }
}
