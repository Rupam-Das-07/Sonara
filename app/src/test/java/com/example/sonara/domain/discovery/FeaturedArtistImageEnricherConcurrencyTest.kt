package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FA-002 — portrait enrichment must resolve independent artists CONCURRENTLY.
 *
 * Before this change `enrichFeaturedArtistImages` awaited each lookup in turn, so N missing
 * portraits cost N read timeouts and the Featured Artists shelf stayed on its skeleton for the
 * sum of them. These cases pin the concurrency AND the semantics that must NOT change (input
 * order, the skip rule, per-artist fallback, cancellation propagation).
 *
 * Determinism: no test asserts on wall-clock time. Concurrency is observed by holding every
 * lookup on a [CompletableDeferred] gate and measuring peak simultaneous occupancy; the
 * "one timeout, not three" case uses `runTest`'s VIRTUAL clock (`currentTime`), where `delay`
 * advances time only as the scheduler allows, so the figure is exact and machine-independent.
 */
class FeaturedArtistImageEnricherConcurrencyTest {

    /** Mirrors SonaraBackendConfig.DISCOVERY_TIMEOUT_MS; kept local so the test owns its fixture. */
    private val timeoutMs = 20_000L

    private fun needsPortrait(name: String) =
        FeaturedArtist(id = name.lowercase(), name = name, genre = "", imageUrl = null, browseId = null)

    private fun portrait(name: String) = "https://lh3.googleusercontent.com/$name"

    // ------------------------------------------------------------------ 1. concurrency

    @Test
    fun `independent lookups are in flight simultaneously`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var peakInFlight = 0

        val artists = listOf(needsPortrait("A"), needsPortrait("B"), needsPortrait("C"))

        val running = async {
            enrichFeaturedArtistImages(artists) { name ->
                inFlight++
                if (inFlight > peakInFlight) peakInFlight = inFlight
                gate.await()
                inFlight--
                Result.success(portrait(name))
            }
        }

        // Every lookup should now be parked on the gate at the same time.
        advanceUntilIdle()
        assertEquals("all three lookups must be in flight together", 3, peakInFlight)

        gate.complete(Unit)
        val result = running.await()

        assertEquals(0, inFlight)
        assertEquals(listOf("A", "B", "C"), result.map { it.name })
        assertEquals(listOf(portrait("A"), portrait("B"), portrait("C")), result.map { it.imageUrl })
    }

    // --------------------------------------------------------------- 2. one timeout

    @Test
    fun `three slow lookups cost one timeout rather than three`() = runTest {
        val artists = listOf(needsPortrait("A"), needsPortrait("B"), needsPortrait("C"))

        val start = testScheduler.currentTime
        val result = enrichFeaturedArtistImages(artists) { name ->
            delay(timeoutMs)
            Result.success(portrait(name))
        }
        val elapsed = testScheduler.currentTime - start

        assertEquals(
            "sequential enrichment would take 3 x $timeoutMs of virtual time",
            timeoutMs,
            elapsed
        )
        assertEquals(3, result.count { it.imageUrl != null })
    }

    @Test
    fun `a slow lookup does not delay a fast sibling`() = runTest {
        val artists = listOf(needsPortrait("Slow"), needsPortrait("Fast"))
        val finished = mutableListOf<String>()

        enrichFeaturedArtistImages(artists) { name ->
            if (name == "Slow") delay(timeoutMs) else delay(1)
            finished += name
            Result.success(portrait(name))
        }

        assertEquals("the fast lookup must finish first", listOf("Fast", "Slow"), finished)
    }

    // ------------------------------------------------------------------ 3. ordering

    @Test
    fun `result order follows input order regardless of completion order`() = runTest {
        val gates = listOf("A", "B", "C").associateWith { CompletableDeferred<Unit>() }
        val artists = listOf(needsPortrait("A"), needsPortrait("B"), needsPortrait("C"))

        val running = async {
            enrichFeaturedArtistImages(artists) { name ->
                gates.getValue(name).await()
                Result.success(portrait(name))
            }
        }
        advanceUntilIdle()

        // Finish in reverse order on purpose.
        gates.getValue("C").complete(Unit)
        advanceUntilIdle()
        gates.getValue("B").complete(Unit)
        advanceUntilIdle()
        gates.getValue("A").complete(Unit)

        val result = running.await()
        assertEquals(listOf("A", "B", "C"), result.map { it.name })
        assertEquals(portrait("A"), result[0].imageUrl)
        assertEquals(portrait("C"), result[2].imageUrl)
    }

    // ------------------------------------------------------------------- 4. failure

    @Test
    fun `one failing lookup leaves only that artist unchanged`() = runTest {
        val artists = listOf(
            needsPortrait("Throws"),
            needsPortrait("Fails"),
            needsPortrait("Succeeds")
        )

        val result = enrichFeaturedArtistImages(artists) { name ->
            when (name) {
                "Throws" -> throw IllegalStateException("network")
                "Fails" -> Result.failure(IllegalStateException("miss"))
                else -> Result.success(portrait(name))
            }
        }

        assertEquals(listOf("Throws", "Fails", "Succeeds"), result.map { it.name })
        assertNull("a thrown lookup keeps the artist unmodified (monogram)", result[0].imageUrl)
        assertNull("a failed Result keeps the artist unmodified (monogram)", result[1].imageUrl)
        assertEquals(portrait("Succeeds"), result[2].imageUrl)
    }

    // -------------------------------------------------------------- 5. cancellation

    @Test
    fun `a cancellation thrown by a lookup propagates instead of becoming a miss`() = runTest {
        val artists = listOf(needsPortrait("A"), needsPortrait("B"))

        try {
            enrichFeaturedArtistImages(artists) { name ->
                if (name == "B") throw CancellationException("cancelled")
                Result.success(portrait(name))
            }
            fail("cancellation must not be swallowed into a portrait miss")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `a cancellation carried in a failed Result also propagates`() = runTest {
        val artists = listOf(needsPortrait("A"))

        try {
            enrichFeaturedArtistImages(artists) { Result.failure(CancellationException("cancelled")) }
            fail("a Result carrying CancellationException must propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `cancelling the caller cancels the in-flight lookups`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var completed = false
        var started = 0

        val job = launch {
            enrichFeaturedArtistImages(listOf(needsPortrait("A"), needsPortrait("B"))) {
                started++
                gate.await()
                Result.success(portrait("A"))
            }
            completed = true
        }
        advanceUntilIdle()
        assertEquals("both lookups started before cancellation", 2, started)

        job.cancel()
        advanceUntilIdle()

        assertTrue("the caller's job must be cancelled", job.isCancelled)
        assertFalse("enrichment must not complete after cancellation", completed)
    }

    // ---------------------------------------------------------- 6. skip behaviour

    @Test
    fun `artists with a browseId or an existing image are never looked up`() = runTest {
        val looked = mutableListOf<String>()
        val artists = listOf(
            FeaturedArtist(id = "editorial", name = "Editorial", browseId = "UC_editorial"),
            FeaturedArtist(id = "has-image", name = "Has Image", imageUrl = "https://existing/image"),
            FeaturedArtist(id = "blank-browse", name = "Blank Browse", browseId = "   "),
            needsPortrait("Needs")
        )

        val result = enrichFeaturedArtistImages(artists) { name ->
            looked += name
            Result.success(portrait(name))
        }

        assertEquals(
            "only artists lacking both a browseId and an image may be looked up",
            listOf("Blank Browse", "Needs"),
            looked.sorted()
        )
        assertEquals(artists[0], result[0])
        assertEquals(artists[1], result[1])
        assertEquals(portrait("Needs"), result[3].imageUrl)
    }

    // ------------------------------------------------------- 7. empty / mixed input

    @Test
    fun `empty input returns empty without invoking the resolver`() = runTest {
        var calls = 0
        val result = enrichFeaturedArtistImages(emptyList()) { calls++; Result.success("x") }

        assertEquals(emptyList<FeaturedArtist>(), result)
        assertEquals(0, calls)
    }

    @Test
    fun `a fully skipped shelf invokes the resolver zero times`() = runTest {
        var calls = 0
        val artists = listOf(
            FeaturedArtist(id = "a", name = "A", browseId = "UC_a"),
            FeaturedArtist(id = "b", name = "B", imageUrl = "https://existing/b")
        )

        val result = enrichFeaturedArtistImages(artists) { calls++; Result.success("x") }

        assertEquals(0, calls)
        assertEquals(artists, result)
    }

    @Test
    fun `a blank resolved url leaves the artist unchanged`() = runTest {
        val artists = listOf(needsPortrait("A"), needsPortrait("B"))

        val result = enrichFeaturedArtistImages(artists) { name ->
            if (name == "A") Result.success("   ") else Result.success(null)
        }

        assertNull(result[0].imageUrl)
        assertNull(result[1].imageUrl)
        assertEquals(artists, result)
    }
}
