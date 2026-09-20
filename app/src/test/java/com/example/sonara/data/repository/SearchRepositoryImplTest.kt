package com.example.sonara.data.repository

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchRepositoryImplTest {

    class FakeBackendClient : SonaraBackendClient() {
        var songsCallCount = 0
        var videosCallCount = 0

        override suspend fun searchSongs(query: String): Result<List<Track>> {
            songsCallCount++
            return Result.success(listOf(Track(id = "song_$query", title = "Song $query", artist = "Artist")))
        }

        override suspend fun searchVideos(query: String, limit: Int): Result<List<Track>> {
            videosCallCount++
            return Result.success(listOf(Track(id = "vid_$query", title = "Video $query", artist = "Channel")))
        }
    }

    private lateinit var fakeClient: FakeBackendClient
    private lateinit var repository: SearchRepositoryImpl

    @Before
    fun setUp() {
        fakeClient = FakeBackendClient()
        repository = SearchRepositoryImpl(
            backendClient = fakeClient,
            cacheTtlMs = 1000L // 1 second for easy testing
        )
    }

    @Test
    fun `searchSongs calls backend and caches results`() = runTest {
        val result1 = repository.searchSongs("Arijit Singh")
        assertTrue(result1.isSuccess)
        assertEquals(1, fakeClient.songsCallCount)
        assertEquals("song_Arijit Singh", result1.getOrNull()?.first()?.id)

        // Repeat search with different casing/whitespace -> should hit cache
        val result2 = repository.searchSongs("  arijit   singh  ")
        assertTrue(result2.isSuccess)
        assertEquals(1, fakeClient.songsCallCount) // No extra backend call
        assertEquals("song_Arijit Singh", result2.getOrNull()?.first()?.id)
    }

    @Test
    fun `searchVideos calls backend and caches results`() = runTest {
        val result1 = repository.searchVideos("Coldplay Live")
        assertTrue(result1.isSuccess)
        assertEquals(1, fakeClient.videosCallCount)
        assertEquals("vid_Coldplay Live", result1.getOrNull()?.first()?.id)

        // Repeat search -> cache hit
        val result2 = repository.searchVideos("coldplay live")
        assertTrue(result2.isSuccess)
        assertEquals(1, fakeClient.videosCallCount)
        assertEquals("vid_Coldplay Live", result2.getOrNull()?.first()?.id)
    }

    @Test
    fun `cache expires after TTL and re-fetches from backend`() = runTest {
        repository.searchSongs("Taylor Swift")
        assertEquals(1, fakeClient.songsCallCount)

        // Wait for TTL (1000ms)
        Thread.sleep(1100L)

        repository.searchSongs("Taylor Swift")
        assertEquals(2, fakeClient.songsCallCount)
    }
}
