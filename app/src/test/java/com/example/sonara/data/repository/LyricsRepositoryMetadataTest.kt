package com.example.sonara.data.repository

import com.example.sonara.data.remote.providers.LyricsProvider
import com.example.sonara.data.remote.providers.LyricsProviderResolver
import com.example.sonara.domain.model.LyricLine
import com.example.sonara.domain.model.Lyrics
import com.example.sonara.domain.model.LyricsMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 3 (Change 5) tests: provider [LyricsMetadata] must be propagated into the canonical [Lyrics]
 * result and survive the repository's offset-calibration and Romanization passes — both of which
 * reconstruct Lyrics subtypes and would otherwise reset metadata to the default. The repository
 * captures metadata before processing and re-attaches it afterwards; these tests lock that in.
 *
 * A real [LyricsProviderResolver] is used with a fake [LyricsProvider] (its coroutine scope is the
 * test's backgroundScope for determinism), so the provider → resolver → repository metadata path is
 * exercised end-to-end without any network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRepositoryMetadataTest {

    private class FakeLyricsProvider(private val result: Result<Lyrics>) : LyricsProvider {
        override suspend fun getLyrics(title: String, artist: String, durationMs: Long): Result<Lyrics> = result
    }

    @Test
    fun `synced lyrics metadata is preserved through to the canonical result`() = runTest {
        val meta = LyricsMetadata(source = "LRCLIB", language = "en", matchConfidence = 0.8f)
        val provider = FakeLyricsProvider(
            Result.success(Lyrics.SyncedLyrics(listOf(LyricLine(1000L, "Hello")), metadata = meta))
        )
        val repo = LyricsRepositoryImpl(LyricsProviderResolver(provider, backgroundScope))

        val lyrics = repo.getLyrics("t1", "Title", "Artist", 100_000L).getOrThrow()

        assertTrue(lyrics is Lyrics.SyncedLyrics)
        assertEquals("LRCLIB", lyrics.metadata.source)
        assertEquals("en", lyrics.metadata.language)
        assertEquals(0.8f, lyrics.metadata.matchConfidence!!, 0.0001f)
    }

    @Test
    fun `plain lyrics metadata is preserved through to the canonical result`() = runTest {
        val meta = LyricsMetadata(source = "LRCLIB", language = null, matchConfidence = null)
        val provider = FakeLyricsProvider(
            Result.success(Lyrics.PlainLyrics("line one\nline two", metadata = meta))
        )
        val repo = LyricsRepositoryImpl(LyricsProviderResolver(provider, backgroundScope))

        val lyrics = repo.getLyrics("t2", "Title", "Artist", 100_000L).getOrThrow()

        assertTrue(lyrics is Lyrics.PlainLyrics)
        assertEquals("LRCLIB", lyrics.metadata.source)
        assertEquals(null, lyrics.metadata.language)
        assertEquals(null, lyrics.metadata.matchConfidence)
    }

    @Test
    fun `metadata survives the offset-calibration reconstruction path`() = runTest {
        val meta = LyricsMetadata(source = "LRCLIB", language = "en", matchConfidence = 0.5f)
        // A first timestamp of 40s (> 28s) triggers the "Beat It" recalibration, which rebuilds the
        // SyncedLyrics instance and would drop metadata without the repository's capture-and-reattach.
        val lines = listOf(LyricLine(40_000L, "They told him don't you ever come around here"))
        val provider = FakeLyricsProvider(
            Result.success(Lyrics.SyncedLyrics(lines, metadata = meta))
        )
        val repo = LyricsRepositoryImpl(LyricsProviderResolver(provider, backgroundScope))

        val lyrics = repo.getLyrics("beat-it", "Beat It", "Michael Jackson", 258_000L).getOrThrow()

        assertTrue(lyrics is Lyrics.SyncedLyrics)
        val synced = lyrics as Lyrics.SyncedLyrics
        // Calibration ran (timestamp shifted to the studio-master start), proving the reconstruction path...
        assertEquals(25_200L, synced.lines.first().timestampMs)
        // ...and metadata was still re-attached afterwards.
        assertEquals("LRCLIB", synced.metadata.source)
        assertEquals(0.5f, synced.metadata.matchConfidence!!, 0.0001f)
    }
}
