package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.ArtistCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * FA-006 — discovery cache keys must use the app's artist-identity contract.
 *
 * Portrait lookups are name-addressed, and the key used to be `cleanName.lowercase()`. That is a
 * weaker notion of sameness than the one the rest of the system uses: `ArtistIdentity.nameKey`
 * (NFC, case-folded, whitespace runs collapsed, plus the single documented A. R. Rahman alias) is
 * what the selector, the calculator and the backend all canonicalise with. So two spellings of
 * ONE artist — "Hans  Zimmer", a decomposed "Beyoncé", "A.R. Rahman" vs "AR Rahman" — each
 * occupied their own cache slot and triggered their own upstream request.
 *
 * These cases pin both directions:
 *  - equivalent spellings must share ONE entry and cost ONE backend call;
 *  - genuinely different artists, and different request types, must NOT be merged.
 *
 * Deterministic: upstream calls are counted, and the one concurrency case uses `runTest`'s virtual
 * clock via `delay`. No sleeps, no wall-clock assertions.
 */
class DiscoveryCacheKeyIdentityTest {

    // Same artist, spelled two ways ------------------------------------------------

    @Test
    fun `a collapsed whitespace run shares one cache entry`() = runTest {
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        repository.getArtistImage("Hans  Zimmer")
        repository.getArtistImage("Hans Zimmer")

        assertEquals("one artist must cost one upstream lookup", 1, backend.imageCalls.get())
    }

    @Test
    fun `a tab or non-breaking space is the same artist as a plain space`() = runTest {
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        repository.getArtistImage("Hans Zimmer")
        repository.getArtistImage("Hans\tZimmer")
        repository.getArtistImage("Hans Zimmer")

        assertEquals(1, backend.imageCalls.get())
    }

    @Test
    fun `a decomposed accent is the same artist as the composed form`() = runTest {
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        repository.getArtistImage("Beyoncé")      // NFC: é
        repository.getArtistImage("Beyoncé")     // NFD: e + combining acute

        assertEquals(
            "Unicode representation must not split one identity",
            1,
            backend.imageCalls.get()
        )
    }

    @Test
    fun `the documented Rahman aliases share one cache entry`() = runTest {
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        repository.getArtistImage("A.R. Rahman")
        repository.getArtistImage("AR Rahman")
        repository.getArtistImage("A. R. Rahman")

        assertEquals(1, backend.imageCalls.get())
    }

    @Test
    fun `case folding still shares one cache entry`() = runTest {
        // Already true before FA-006; pinned so the new key cannot regress it.
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        repository.getArtistImage("HANS ZIMMER")
        repository.getArtistImage("hans zimmer")

        assertEquals(1, backend.imageCalls.get())
    }

    // Over-normalization guards ---------------------------------------------------

    @Test
    fun `an accent difference keeps two distinct artists apart`() = runTest {
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        repository.getArtistImage("Beyoncé")
        repository.getArtistImage("Beyonce")

        assertEquals(
            "nameKey is deliberately not lossy, unlike slugify — these must stay separate",
            2,
            backend.imageCalls.get()
        )
    }

    @Test
    fun `different names are never merged`() = runTest {
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        repository.getArtistImage("Hans Zimmer")
        repository.getArtistImage("Hans Zimmermann")
        repository.getArtistImage("AC/DC")
        repository.getArtistImage("AC DC")

        assertEquals(4, backend.imageCalls.get())
    }

    // In-flight deduplication uses the same key -----------------------------------

    @Test
    fun `concurrent equivalent spellings join one in-flight request`() = runTest {
        val backend = CountingBackend(delayMs = 10)
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        val results = awaitAll(
            async { repository.getArtistImage("A.R. Rahman") },
            async { repository.getArtistImage("AR Rahman") }
        )

        assertEquals(
            "in-flight dedup must key on the same identity as the result cache",
            1,
            backend.imageCalls.get()
        )
        assertEquals(PORTRAIT, results[0].getOrNull())
        assertEquals(PORTRAIT, results[1].getOrNull())
    }

    // Request types must stay separate --------------------------------------------

    @Test
    fun `the catalog and portrait caches do not share a key`() = runTest {
        // A real near-miss: the catalog keys on the artist id and the portrait on the identity
        // key, and for a locally-derived artist both are "hans-zimmer". Separate maps and
        // separate in-flight prefixes must keep them apart.
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        repository.getArtistImage("Hans Zimmer")
        repository.getArtistCatalog("hans-zimmer", "Hans Zimmer")

        assertEquals(1, backend.imageCalls.get())
        assertEquals(1, backend.catalogCalls.get())
    }

    // Names the identity contract cannot canonicalise ------------------------------

    @Test
    fun `a credit with no letters or digits still resolves and caches`() = runTest {
        // nameKey returns null for "!!!" (a real band), so the key falls back to today's
        // behaviour rather than dropping the lookup.
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        assertEquals(PORTRAIT, repository.getArtistImage("!!!").getOrNull())
        assertEquals(PORTRAIT, repository.getArtistImage("!!!").getOrNull())

        assertEquals("the second call must be served from cache", 1, backend.imageCalls.get())
    }

    @Test
    fun `a blank name never reaches the backend`() = runTest {
        val backend = CountingBackend()
        val repository = DiscoveryRepositoryImpl(backendClient = backend)

        assertEquals(null, repository.getArtistImage("   ").getOrNull())

        assertEquals(0, backend.imageCalls.get())
    }

    private companion object {
        const val PORTRAIT = "https://lh3.googleusercontent.com/portrait"
    }

    private class CountingBackend(private val delayMs: Long = 0L) : SonaraBackendClient() {
        val imageCalls = AtomicInteger()
        val catalogCalls = AtomicInteger()

        override suspend fun getArtistImage(artistName: String): Result<String?> {
            imageCalls.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(PORTRAIT)
        }

        override suspend fun getArtistCatalog(
            artistId: String,
            artistName: String
        ): Result<ArtistCatalog> {
            catalogCalls.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return Result.success(ArtistCatalog())
        }
    }
}
