package com.example.sonara.data.stream

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamResolverImplTest {

    private class CountingBackendClient(private val streamUrl: String) : SonaraBackendClient() {
        var callCount = 0
        var lastQuality: AudioQuality? = null
        var lastTitle: String? = null
        var lastArtist: String? = null
        var lastDurationSec: Int? = null

        override suspend fun getStreamUrl(
            videoId: String,
            quality: AudioQuality,
            title: String,
            artist: String,
            durationSeconds: Int
        ): Result<StreamInfo> {
            callCount++
            lastQuality = quality
            lastTitle = title
            lastArtist = artist
            lastDurationSec = durationSeconds
            return Result.success(
                StreamInfo(
                    trackId = videoId,
                    streamUrl = streamUrl,
                    expiresAt = System.currentTimeMillis() + 3600_000L,
                    format = if (quality == AudioQuality.HIGH || quality == AudioQuality.VERY_HIGH) "audio/mp4" else "audio/webm",
                    codec = if (quality == AudioQuality.HIGH || quality == AudioQuality.VERY_HIGH) "aac" else "opus",
                    bitrateKbps = if (quality == AudioQuality.HIGH || quality == AudioQuality.VERY_HIGH) 320 else 160,
                    qualityTier = if (quality == AudioQuality.HIGH || quality == AudioQuality.VERY_HIGH) "HIGH" else "STANDARD",
                    provider = if (quality == AudioQuality.HIGH || quality == AudioQuality.VERY_HIGH) "jiosaavn" else "youtube"
                )
            )
        }
    }

    @Test
    fun `resolveStream caches streamInfo in memory and avoids redundant network calls`() = runBlocking {
        val fakeBackend = CountingBackendClient("https://stream/video123")
        val resolver = StreamResolverImpl(fakeBackend)

        // First call -> fetches from backend
        val res1 = resolver.resolveStream("video123")
        assertTrue(res1.isSuccess)
        assertEquals("https://stream/video123", res1.getOrNull()?.streamUrl)
        assertEquals(1, fakeBackend.callCount)

        // Second call with same videoId -> hits in-memory cache with 0ms latency
        val res2 = resolver.resolveStream("video123")
        assertTrue(res2.isSuccess)
        assertEquals("https://stream/video123", res2.getOrNull()?.streamUrl)
        assertEquals("Call count must remain 1 because of in-memory cache hit", 1, fakeBackend.callCount)
    }

    @Test
    fun `resolveStream forwards quality and track metadata to backend client`() = runBlocking {
        val fakeBackend = CountingBackendClient("https://stream/video123")
        val resolver = StreamResolverImpl(fakeBackend)

        val res = resolver.resolveStream(
            trackId = "video123",
            quality = AudioQuality.HIGH,
            title = "Kesariya",
            artist = "Arijit Singh",
            durationSeconds = 268
        )

        assertTrue(res.isSuccess)
        assertEquals(1, fakeBackend.callCount)
        assertEquals(AudioQuality.HIGH, fakeBackend.lastQuality)
        assertEquals("Kesariya", fakeBackend.lastTitle)
        assertEquals("Arijit Singh", fakeBackend.lastArtist)
        assertEquals(268, fakeBackend.lastDurationSec)
    }

    @Test
    fun `resolveStream caches HIGH and STANDARD separately without cross-pollution`() = runBlocking {
        val fakeBackend = CountingBackendClient("https://stream/video123")
        val resolver = StreamResolverImpl(fakeBackend)

        // 1. Resolve STANDARD
        val resStd = resolver.resolveStream("video123", AudioQuality.AUTO)
        assertTrue(resStd.isSuccess)
        assertEquals(1, fakeBackend.callCount)
        assertEquals("STANDARD", resStd.getOrNull()?.qualityTier)

        // 2. Resolve HIGH -> must NOT hit STANDARD cache, must make second backend call
        val resHigh = resolver.resolveStream("video123", AudioQuality.HIGH)
        assertTrue(resHigh.isSuccess)
        assertEquals(2, fakeBackend.callCount)
        assertEquals("HIGH", resHigh.getOrNull()?.qualityTier)

        // 3. Resolve HIGH again -> hits HIGH cache
        val resHighCached = resolver.resolveStream("video123", AudioQuality.HIGH)
        assertTrue(resHighCached.isSuccess)
        assertEquals(2, fakeBackend.callCount)

        // 4. Resolve STANDARD again -> hits STANDARD cache
        val resStdCached = resolver.resolveStream("video123", AudioQuality.AUTO)
        assertTrue(resStdCached.isSuccess)
        assertEquals(2, fakeBackend.callCount)
    }

    @Test
    fun `resolveStream returns failure when trackId is blank`() = runBlocking {
        val fakeBackend = CountingBackendClient("https://stream/video123")
        val resolver = StreamResolverImpl(fakeBackend)

        val res = resolver.resolveStream("")
        assertTrue(res.isFailure)
        assertEquals(0, fakeBackend.callCount)
    }
}
