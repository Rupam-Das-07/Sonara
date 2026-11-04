package com.example.sonara.data.stream

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * POINT 2 — STAGE A focused tests for the bounded stream-resolver cache (Area 3) and the
 * resolver-seam half of 403/410 recovery (Area 2): force-refresh invalidation, fresh-URL
 * production, failure preservation, and cross-track isolation.
 *
 * These are additive and do NOT modify the pre-existing [StreamResolverImplTest] (cache hit,
 * metadata forwarding, HIGH/STANDARD separation, blank-trackId failure).
 *
 * SCOPE NOTE — why the DataSource-level 403/410 catch-and-retry is NOT unit-tested here:
 * [com.example.sonara.playback.player.datasource.RefreshingDataSource.open] catches
 * `HttpDataSource.InvalidResponseCodeException` and, on the success-retry branch, calls the
 * STATIC `android.net.Uri.parse(freshUrl)`. In this project's plain-JVM unit-test setup
 * (`isReturnDefaultValues = true`, no Robolectric, no mockito-static), `Uri.parse` returns
 * null, and constructing a media3 1.5.1 `InvalidResponseCodeException` is not verifiable
 * without the Android toolchain — so an end-to-end DataSource test would require a prohibited
 * new test dependency. That path is therefore covered by (a) these resolver-seam tests, which
 * exercise exactly the cache-invalidation / fresh-URL / failure logic the DataSource delegates
 * to via [StreamResolverPort.refreshStream], and (b) an instrumented/on-device test.
 */
class StreamResolverCacheAndRefreshTest {

    /**
     * Programmable backend double. Returns a success whose streamUrl embeds the call index
     * (`#v$n`) so that a refresh/re-resolve is observable by URL change, with a settable
     * [expiresAt] (to drive deterministic expiry via the resolver's injected nowProvider) and
     * a [failing] switch (to drive refresh-failure). Counts backend hits atomically.
     */
    private class ProgrammableBackend(
        val expiresAt: Long,
        var failing: Boolean = false
    ) : SonaraBackendClient() {
        val calls = AtomicInteger(0)

        override suspend fun getStreamUrl(
            videoId: String,
            quality: AudioQuality,
            title: String,
            artist: String,
            durationSeconds: Int
        ): Result<StreamInfo> {
            val n = calls.incrementAndGet()
            if (failing) {
                return Result.failure(RuntimeException("backend unavailable"))
            }
            return Result.success(
                StreamInfo(
                    trackId = videoId,
                    streamUrl = "https://stream/$videoId#v$n",
                    expiresAt = expiresAt,
                    qualityTier = if (quality == AudioQuality.HIGH || quality == AudioQuality.VERY_HIGH) "HIGH" else "STANDARD"
                )
            )
        }
    }

    // A far-future expiry (real wall-clock) keeps entries fresh for tests that exercise
    // eviction / refresh / isolation rather than expiration.
    private fun farFuture(): Long = System.currentTimeMillis() + 3_600_000L

    // ---------------------------------------------------------------------------------------
    // Area 3 — expiration semantics
    // ---------------------------------------------------------------------------------------

    @Test
    fun `explicit expiresAt is honored - entry goes stale after expiry and is re-resolved`() = runBlocking {
        var fakeNow = 0L
        // Expires at t=120_000; resolver safety margin is 60_000, so it must be treated stale
        // once now + 60_000 >= 120_000 (i.e. now >= 60_000).
        val backend = ProgrammableBackend(expiresAt = 120_000L)
        val resolver = StreamResolverImpl(backend, nowProvider = { fakeNow })

        // First resolve at t=0 -> miss -> backend call #1, entry cached.
        val first = resolver.resolveStream("vidExp")
        assertTrue(first.isSuccess)
        assertEquals(1, backend.calls.get())

        // Still well within validity (t=0) -> cache HIT, no new backend call.
        resolver.resolveStream("vidExp")
        assertEquals("Fresh entry must be served from cache", 1, backend.calls.get())

        // Advance past (expiry - safetyMargin) -> entry is stale -> miss -> backend call #2.
        fakeNow = 70_000L
        val afterExpiry = resolver.resolveStream("vidExp")
        assertTrue(afterExpiry.isSuccess)
        assertEquals("Expired entry must trigger a fresh backend resolve", 2, backend.calls.get())
    }

    @Test
    fun `expiresAt == 0 uses a finite default TTL and is NOT cached forever`() = runBlocking {
        var fakeNow = 0L
        // expiresAt == 0 -> resolver must fall back to its finite DEFAULT_STREAM_TTL_MS
        // (15 min = 900_000 ms) rather than treating the entry as permanently valid.
        val backend = ProgrammableBackend(expiresAt = 0L)
        val resolver = StreamResolverImpl(backend, nowProvider = { fakeNow })

        // t=0 -> miss -> backend call #1.
        assertTrue(resolver.resolveStream("vidZero").isSuccess)
        assertEquals(1, backend.calls.get())

        // t=2min -> still within the finite default TTL -> cache HIT (benefits the session).
        fakeNow = 120_000L
        resolver.resolveStream("vidZero")
        assertEquals("Within default TTL the zero-expiry entry must still be a cache hit", 1, backend.calls.get())

        // t=15min -> default TTL has elapsed (minus safety margin) -> stale -> backend call #2.
        // This is the core regression guard for the old expiresAt==0 => "cached forever" bug.
        fakeNow = 900_000L
        assertTrue(resolver.resolveStream("vidZero").isSuccess)
        assertEquals("Zero-expiry entry must expire under the finite default TTL", 2, backend.calls.get())
    }

    // ---------------------------------------------------------------------------------------
    // Area 3 — bounded capacity + LRU eviction
    // ---------------------------------------------------------------------------------------

    @Test
    fun `cache is bounded - least-recently-used entries are evicted while recent ones are retained`() = runBlocking {
        val backend = ProgrammableBackend(expiresAt = farFuture())
        val resolver = StreamResolverImpl(backend)

        // Resolve far more distinct tracks than the hard cap (MAX_CACHE_ENTRIES = 64).
        // Fill is pure insertion (each id is a first-time miss), so access-order == insertion
        // order and the EARLIEST ids are evicted first.
        val total = 80
        for (i in 0 until total) {
            assertTrue(resolver.resolveStream("pad_$i").isSuccess)
        }
        val afterFill = backend.calls.get()
        assertEquals("Every distinct first-time resolve is a backend call", total, afterFill)

        // The very first id must have been evicted during the overflow -> re-resolve = a MISS.
        resolver.resolveStream("pad_0")
        assertEquals(
            "Oldest entry must have been evicted once capacity was exceeded",
            afterFill + 1,
            backend.calls.get()
        )

        // A most-recently-inserted id must still be retained -> re-resolve = a cache HIT.
        resolver.resolveStream("pad_${total - 1}")
        assertEquals(
            "Most-recent entry must be retained within the bounded cache",
            afterFill + 1,
            backend.calls.get()
        )
    }

    // ---------------------------------------------------------------------------------------
    // Area 3 — in-flight request deduplication preserved
    // ---------------------------------------------------------------------------------------

    @Test
    fun `concurrent resolves for the same key deduplicate to a single backend call`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val backend = object : SonaraBackendClient() {
            override suspend fun getStreamUrl(
                videoId: String,
                quality: AudioQuality,
                title: String,
                artist: String,
                durationSeconds: Int
            ): Result<StreamInfo> {
                calls.incrementAndGet()
                entered.complete(Unit)   // signal: leader is inside the backend (in-flight registered)
                release.await()          // hold the single resolution open until the test releases it
                return Result.success(
                    StreamInfo(
                        trackId = videoId,
                        streamUrl = "https://stream/$videoId",
                        expiresAt = System.currentTimeMillis() + 3_600_000L
                    )
                )
            }
        }
        val resolver = StreamResolverImpl(backend)

        val leader = async(Dispatchers.Default) { resolver.resolveStream("vidDedup") }
        entered.await()                               // leader has registered the in-flight entry
        val follower = async(Dispatchers.Default) { resolver.resolveStream("vidDedup") }
        delay(250)                                    // allow follower to join the shared in-flight await
        release.complete(Unit)                        // let the single backend call return

        val r1 = leader.await()
        val r2 = follower.await()
        assertTrue(r1.isSuccess)
        assertTrue(r2.isSuccess)
        assertEquals("https://stream/vidDedup", r1.getOrNull()?.streamUrl)
        assertEquals("https://stream/vidDedup", r2.getOrNull()?.streamUrl)
        assertEquals(
            "Second concurrent caller must join the in-flight request, not trigger a 2nd backend call",
            1,
            calls.get()
        )
    }

    @Test
    fun `clearCache isolates newer in-flight work and prevents stale completion from repopulating cache`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val backend = object : SonaraBackendClient() {
            override suspend fun getStreamUrl(
                videoId: String,
                quality: AudioQuality,
                title: String,
                artist: String,
                durationSeconds: Int
            ): Result<StreamInfo> {
                val callNumber = calls.incrementAndGet()
                when (callNumber) {
                    1 -> {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                    2 -> {
                        secondEntered.complete(Unit)
                        releaseSecond.await()
                    }
                }
                return Result.success(
                    StreamInfo(
                        trackId = videoId,
                        streamUrl = "https://stream/$videoId#v$callNumber",
                        expiresAt = System.currentTimeMillis() + 3_600_000L
                    )
                )
            }
        }
        val resolver = StreamResolverImpl(backend)

        val first = async(Dispatchers.Default) { resolver.resolveStream("clearRace") }
        firstEntered.await()

        resolver.clearCache()
        val second = async(Dispatchers.Default) { resolver.resolveStream("clearRace") }
        secondEntered.await()

        // Finish the pre-clear request first. Its result must not enter the cleared cache,
        // and its cleanup must not remove the post-clear request from the registry.
        releaseFirst.complete(Unit)
        val firstResult = first.await()

        // The third caller is started while the post-clear request is still blocked. It must
        // join request #2 rather than see stale #1 data or start request #3.
        val joined = async(Dispatchers.Default) { resolver.resolveStream("clearRace") }
        releaseSecond.complete(Unit)

        assertTrue(firstResult.isSuccess)
        assertEquals("https://stream/clearRace#v1", firstResult.getOrNull()?.streamUrl)
        assertEquals("https://stream/clearRace#v2", second.await().getOrNull()?.streamUrl)
        assertEquals("https://stream/clearRace#v2", joined.await().getOrNull()?.streamUrl)
        assertEquals(
            "clearCache must isolate the replacement request without breaking deduplication",
            2,
            calls.get()
        )
        assertEquals(
            "the replacement result is the only value cached after invalidation",
            "https://stream/clearRace#v2",
            resolver.resolveStream("clearRace").getOrNull()?.streamUrl
        )
        assertEquals(2, calls.get())
    }

    @Test
    fun `cancelled leader is cleaned up so a later resolve can retry`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val backend = object : SonaraBackendClient() {
            override suspend fun getStreamUrl(
                videoId: String,
                quality: AudioQuality,
                title: String,
                artist: String,
                durationSeconds: Int
            ): Result<StreamInfo> {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                return Result.success(
                    StreamInfo(
                        trackId = videoId,
                        streamUrl = "https://stream/$videoId#retry",
                        expiresAt = System.currentTimeMillis() + 3_600_000L
                    )
                )
            }
        }
        val resolver = StreamResolverImpl(backend)

        val cancelled = async(Dispatchers.Default) { resolver.resolveStream("cancelRace") }
        entered.await()
        cancelled.cancelAndJoin()

        val retry = resolver.resolveStream("cancelRace")
        assertTrue(retry.isSuccess)
        assertEquals("https://stream/cancelRace#retry", retry.getOrNull()?.streamUrl)
        assertEquals("the cancelled request must not leave a stuck in-flight entry", 2, calls.get())
    }

    // ---------------------------------------------------------------------------------------
    // Area 2 (resolver seam) — force-refresh invalidation + fresh URL
    // ---------------------------------------------------------------------------------------

    @Test
    fun `refreshStream invalidates the cached URL and resolves a fresh one - a normal resolve would have returned the stale URL`() = runBlocking {
        val backend = ProgrammableBackend(expiresAt = farFuture())
        val resolver = StreamResolverImpl(backend)

        // Prime the cache.
        val initial = resolver.resolveStream("vidRefresh")
        val staleUrl = initial.getOrNull()?.streamUrl
        assertEquals(1, backend.calls.get())
        assertNotNull(staleUrl)

        // A NORMAL resolve returns the SAME cached (potentially-dead) URL -> this is exactly
        // the stale-reuse the 403/410 path must avoid.
        val normal = resolver.resolveStream("vidRefresh")
        assertEquals("Normal resolve must serve the cached URL (no backend call)", 1, backend.calls.get())
        assertEquals(staleUrl, normal.getOrNull()?.streamUrl)

        // refreshStream must BYPASS + INVALIDATE the cache and return a genuinely fresh URL.
        val refreshed = resolver.refreshStream("vidRefresh")
        val freshUrl = refreshed.getOrNull()?.streamUrl
        assertTrue(refreshed.isSuccess)
        assertEquals("Refresh must perform a real backend resolve", 2, backend.calls.get())
        assertNotEquals("Refreshed URL must differ from the stale cached URL", staleUrl, freshUrl)

        // The fresh URL (not the stale one) is now what a subsequent normal resolve serves.
        val afterRefresh = resolver.resolveStream("vidRefresh")
        assertEquals("Refreshed URL must now be the cached value", freshUrl, afterRefresh.getOrNull()?.streamUrl)
        assertEquals("Post-refresh resolve is a cache hit", 2, backend.calls.get())
    }

    @Test
    fun `refreshStream preserves failure when the backend refresh fails`() = runBlocking {
        val backend = ProgrammableBackend(expiresAt = farFuture())
        val resolver = StreamResolverImpl(backend)

        // Prime a good entry first.
        assertTrue(resolver.resolveStream("vidFail").isSuccess)
        assertEquals(1, backend.calls.get())

        // Backend now fails; refresh must attempt a real resolve and propagate the failure
        // (the DataSource layer then preserves the original 403/410 by rethrowing).
        backend.failing = true
        val refreshed = resolver.refreshStream("vidFail")
        assertTrue("A failed backend refresh must surface as a failure", refreshed.isFailure)
        assertEquals("Refresh must have attempted a real backend resolve", 2, backend.calls.get())
    }

    // ---------------------------------------------------------------------------------------
    // Area 2 (resolver seam) — a refresh for one track cannot disturb another track
    // ---------------------------------------------------------------------------------------

    @Test
    fun `refreshing one track does not evict or overwrite another track's cached stream`() = runBlocking {
        val backend = ProgrammableBackend(expiresAt = farFuture())
        val resolver = StreamResolverImpl(backend)

        val aInitial = resolver.resolveStream("trackA")
        val bInitial = resolver.resolveStream("trackB")
        val bUrl = bInitial.getOrNull()?.streamUrl
        assertEquals(2, backend.calls.get())

        // Recover trackA (as a stale-token recovery would).
        val aRefreshed = resolver.refreshStream("trackA")
        assertTrue(aRefreshed.isSuccess)
        assertNotEquals("trackA must have been re-resolved", aInitial.getOrNull()?.streamUrl, aRefreshed.getOrNull()?.streamUrl)
        assertEquals(3, backend.calls.get())

        // trackB's cache entry must be untouched by the recovery of trackA -> still a HIT, same URL.
        val bAfter = resolver.resolveStream("trackB")
        assertEquals("A recovery on trackA must not disturb trackB's cache", 3, backend.calls.get())
        assertEquals("trackB's cached stream must be unchanged by trackA recovery", bUrl, bAfter.getOrNull()?.streamUrl)
    }
}
