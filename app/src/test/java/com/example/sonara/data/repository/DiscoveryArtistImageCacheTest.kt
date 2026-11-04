package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryArtistImageCacheTest {
    @Test
    fun `artist image requests are cached and deduplicated`() = runTest {
        val backend = ImageBackend(delayMs = 10)
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        val results = awaitAll(
            async { repository.getArtistImage("Beyoncé") },
            async { repository.getArtistImage("beyoncé") }
        )

        assertEquals(1, backend.calls.get())
        assertEquals("https://lh3.googleusercontent.com/beyonce", results[0].getOrNull())
        assertEquals("https://lh3.googleusercontent.com/beyonce", repository.getArtistImage("Beyoncé").getOrNull())
        assertEquals(1, backend.calls.get())
    }

    @Test
    fun `null image misses use short TTL and can recover`() = runTest {
        val backend = ImageBackend(images = ArrayDeque(listOf(null, "https://lh3.googleusercontent.com/recovered")))
        val repository = DiscoveryRepositoryImpl(
            backendClient = backend,
            artistImageMissTtlMs = 0L,
            artistImageTtlMs = 60_000L
        )

        assertEquals(null, repository.getArtistImage("Unknown").getOrNull())
        assertEquals("https://lh3.googleusercontent.com/recovered", repository.getArtistImage("Unknown").getOrNull())
        assertEquals(2, backend.calls.get())
    }

    private class ImageBackend(
        private val images: ArrayDeque<String?> = ArrayDeque(listOf("https://lh3.googleusercontent.com/beyonce")),
        private val delayMs: Long = 0L
    ) : SonaraBackendClient() {
        val calls = AtomicInteger()

        override suspend fun getArtistImage(artistName: String): Result<String?> {
            calls.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(images.removeFirstOrNull())
        }
    }
}
