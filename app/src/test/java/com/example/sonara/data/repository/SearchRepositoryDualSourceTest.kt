package com.example.sonara.data.repository

import com.example.sonara.core.error.SonaraException
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.SoundcloudSearchPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRepositoryDualSourceTest {

    private class FakeBackendClient(
        var songsResult: Result<List<Track>> = Result.success(emptyList()),
        var songsException: Throwable? = null
    ) : SonaraBackendClient() {
        var songsCallCount = 0

        override suspend fun searchSongs(query: String): Result<List<Track>> {
            songsCallCount++
            songsException?.let { throw it }
            return songsResult
        }
    }

    private class FakeSoundcloudSearchPort(
        var songsResult: Result<List<Track>> = Result.success(emptyList()),
        var songsException: Throwable? = null
    ) : SoundcloudSearchPort {
        var callCount = 0

        override suspend fun searchSongs(query: String, limit: Int): Result<List<Track>> {
            callCount++
            songsException?.let { throw it }
            return songsResult
        }
    }

    private fun sampleTrack(id: String, title: String, artist: String, album: String = ""): Track =
        Track(id = id, title = title, artist = artist, album = album, durationMs = 180000L)

    // ─── 1. Dual-Source Concurrency & Combination ─────────────────────────────

    @Test
    fun `both sources succeed and results are interleaved and combined`() = runTest {
        val ytTracks = listOf(
            sampleTrack("yt_1", "Levitating", "Dua Lipa"),
            sampleTrack("yt_2", "Don't Start Now", "Dua Lipa"),
            sampleTrack("yt_3", "Physical", "Dua Lipa")
        )
        val scTracks = listOf(
            sampleTrack("sc:https://soundcloud.com/remixer/levitating-remix", "Levitating (Remix)", "DJ Remix"),
            sampleTrack("sc:https://soundcloud.com/live/physical-live", "Physical (Live)", "Dua Lipa")
        )

        val backend = FakeBackendClient(songsResult = Result.success(ytTracks))
        val scPort = FakeSoundcloudSearchPort(songsResult = Result.success(scTracks))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("Dua Lipa")
        assertTrue(result.isSuccess)
        val combined = result.getOrNull()
        assertNotNull(combined)
        assertEquals(5, combined?.size)

        // Verifies both providers were called
        assertEquals(1, backend.songsCallCount)
        assertEquals(1, scPort.callCount)

        // Verifies order: top 2 YT tracks, then interleaved SC, YT, SC
        assertEquals("yt_1", combined?.get(0)?.id)
        assertEquals("yt_2", combined?.get(1)?.id)
        assertEquals("sc:https://soundcloud.com/remixer/levitating-remix", combined?.get(2)?.id)
        assertEquals("yt_3", combined?.get(3)?.id)
        assertEquals("sc:https://soundcloud.com/live/physical-live", combined?.get(4)?.id)
    }

    @Test
    fun `exact duplicate title and artist across sources is deduplicated preserving official YT track`() = runTest {
        val ytTracks = listOf(
            sampleTrack("yt_official", "Blinding Lights", "The Weeknd")
        )
        val scTracks = listOf(
            sampleTrack("sc:https://soundcloud.com/user/blinding-lights", "Blinding Lights", "The Weeknd"),
            sampleTrack("sc:https://soundcloud.com/user/blinding-lights-slowed", "Blinding Lights (Slowed)", "The Weeknd")
        )

        val backend = FakeBackendClient(songsResult = Result.success(ytTracks))
        val scPort = FakeSoundcloudSearchPort(songsResult = Result.success(scTracks))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("Blinding Lights")
        assertTrue(result.isSuccess)
        val tracks = result.getOrNull()
        assertEquals(2, tracks?.size)
        assertEquals("yt_official", tracks?.get(0)?.id)
        assertEquals("sc:https://soundcloud.com/user/blinding-lights-slowed", tracks?.get(1)?.id)
    }

    // ─── 2. Independent Failure Boundaries ────────────────────────────────────

    @Test
    fun `SoundCloud search fails while YTMusic succeeds - retains YTMusic tracks`() = runTest {
        val ytTracks = listOf(
            sampleTrack("yt_1", "Starboy", "The Weeknd")
        )

        val backend = FakeBackendClient(songsResult = Result.success(ytTracks))
        val scPort = FakeSoundcloudSearchPort(
            songsResult = Result.failure(SonaraException.NetworkException("SoundCloud timed out"))
        )
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("Starboy")
        assertTrue("Repository should succeed when at least one source succeeds", result.isSuccess)
        val tracks = result.getOrNull()
        assertEquals(1, tracks?.size)
        assertEquals("yt_1", tracks?.first()?.id)
    }

    @Test
    fun `YTMusic search fails while SoundCloud succeeds - retains SoundCloud tracks`() = runTest {
        val scTracks = listOf(
            sampleTrack("sc:https://soundcloud.com/indie/lofi-chill", "Lofi Chill", "Indie Artist")
        )

        val backend = FakeBackendClient(
            songsResult = Result.failure(SonaraException.NetworkException("Backend 503"))
        )
        val scPort = FakeSoundcloudSearchPort(songsResult = Result.success(scTracks))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("Lofi Chill")
        assertTrue("Repository should succeed when SoundCloud succeeds despite YTMusic outage", result.isSuccess)
        val tracks = result.getOrNull()
        assertEquals(1, tracks?.size)
        assertEquals("sc:https://soundcloud.com/indie/lofi-chill", tracks?.first()?.id)
    }

    @Test
    fun `both sources fail - returns failure result`() = runTest {
        val backend = FakeBackendClient(
            songsResult = Result.failure(IOException("YT network dead"))
        )
        val scPort = FakeSoundcloudSearchPort(
            songsResult = Result.failure(SonaraException.NetworkException("SC network dead"))
        )
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("Offline Query")
        assertTrue("Repository should fail when both providers fail", result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    // ─── 3. Empty Results Handling ────────────────────────────────────────────

    @Test
    fun `both sources return empty list - returns success empty list`() = runTest {
        val backend = FakeBackendClient(songsResult = Result.success(emptyList()))
        val scPort = FakeSoundcloudSearchPort(songsResult = Result.success(emptyList()))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("obscuresearch12345")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.isEmpty() == true)
    }

    @Test
    fun `SoundCloud returns empty while YTMusic returns tracks - returns YTMusic tracks`() = runTest {
        val ytTracks = listOf(sampleTrack("yt_1", "Song", "Artist"))
        val backend = FakeBackendClient(songsResult = Result.success(ytTracks))
        val scPort = FakeSoundcloudSearchPort(songsResult = Result.success(emptyList()))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("Song")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("yt_1", result.getOrNull()?.first()?.id)
    }

    @Test
    fun `YTMusic returns empty while SoundCloud returns tracks - returns SoundCloud tracks`() = runTest {
        val scTracks = listOf(sampleTrack("sc:url", "Indie Song", "SoundCloud Creator"))
        val backend = FakeBackendClient(songsResult = Result.success(emptyList()))
        val scPort = FakeSoundcloudSearchPort(songsResult = Result.success(scTracks))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("Indie Song")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("sc:url", result.getOrNull()?.first()?.id)
    }

    // ─── 4. Caching & Deduplication ───────────────────────────────────────────

    @Test
    fun `repeated queries hit in-memory cache without calling backend or soundcloud again`() = runTest {
        val ytTracks = listOf(sampleTrack("yt_1", "Cached Song", "Artist"))
        val scTracks = listOf(sampleTrack("sc:url", "Cached SC", "Creator"))

        val backend = FakeBackendClient(songsResult = Result.success(ytTracks))
        val scPort = FakeSoundcloudSearchPort(songsResult = Result.success(scTracks))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result1 = repository.searchSongs("Cached Query")
        assertTrue(result1.isSuccess)
        assertEquals(1, backend.songsCallCount)
        assertEquals(1, scPort.callCount)

        // Second call with different casing and whitespace -> cache hit
        val result2 = repository.searchSongs("  cached   query  ")
        assertTrue(result2.isSuccess)
        assertEquals(1, backend.songsCallCount) // No extra call
        assertEquals(1, scPort.callCount)       // No extra call
        assertEquals(result1.getOrNull()?.size, result2.getOrNull()?.size)
    }

    // ─── 5. Cancellation Behavior ─────────────────────────────────────────────

    @Test(expected = CancellationException::class)
    fun `CancellationException propagates through supervisorScope`() = runTest {
        val backend = FakeBackendClient(songsException = CancellationException("Job was cancelled"))
        val scPort = FakeSoundcloudSearchPort(songsResult = Result.success(emptyList()))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = scPort,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        repository.searchSongs("cancelled")
    }

    // ─── 6. Backward Compatibility When soundcloudSearchPort Is Null ──────────

    @Test
    fun `repository functions cleanly when soundcloudSearchPort is null`() = runTest {
        val ytTracks = listOf(sampleTrack("yt_1", "Solo Song", "Artist"))
        val backend = FakeBackendClient(songsResult = Result.success(ytTracks))
        val repository = SearchRepositoryImpl(
            backendClient = backend,
            soundcloudSearchPort = null,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val result = repository.searchSongs("Solo Song")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("yt_1", result.getOrNull()?.first()?.id)
        assertEquals(1, backend.songsCallCount)
    }
}
