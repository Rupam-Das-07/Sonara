package com.example.sonara.feature.lyrics

import com.example.sonara.domain.model.LyricLine
import com.example.sonara.domain.model.Lyrics
import com.example.sonara.domain.repository.LyricsRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.client.MediaControllerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 3 (Changes 3 & 4) tests for LyricsViewModel request / track-identity protection.
 *
 * These cover the two externally observable guarantees:
 *  1. The stale-content window is removed — a playback-position tick arriving while a new track's
 *     request is in flight must NOT republish the previous track's lyrics over the Loading state.
 *  2. A slower, older request can never overwrite a newer request's lyrics.
 *
 * Note on the generation guard: on a single-threaded test dispatcher, structured cancellation
 * (fetchJob.cancel on every new request) already stops a superseded request's completion handler from
 * running, so the monotonic generation guard added in Stage 3 is defense-in-depth and cannot be
 * isolated as the *sole* actor in a single-thread unit test. These tests therefore assert the
 * observable behavior that the cancellation and the guard jointly guarantee.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsViewModelStaleContentTest {

    private val testDispatcher = StandardTestDispatcher()
    private val controllerStateFlow = MutableStateFlow(MediaControllerState())

    private val fakeClient = object : MediaControllerClient(null) {
        override val controllerState = controllerStateFlow
        override fun seekTo(positionMs: Long) {}
    }

    /** A repository whose per-track responses can be scripted and individually gated (held in flight). */
    private class GatedLyricsRepository : LyricsRepository {
        private val results = mutableMapOf<String, Result<Lyrics>>()
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var default: Result<Lyrics> = Result.success(Lyrics.Unavailable())

        fun setResult(trackId: String, result: Result<Lyrics>) { results[trackId] = result }
        fun gate(trackId: String) { gates[trackId] = CompletableDeferred() }
        fun release(trackId: String) { gates.remove(trackId)?.complete(Unit) }

        override suspend fun getLyrics(
            trackId: String,
            title: String,
            artist: String,
            durationMs: Long
        ): Result<Lyrics> {
            gates[trackId]?.await()
            return results[trackId] ?: default
        }
    }

    private val repo = GatedLyricsRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `position tick during a new request does not resurrect previous track lyrics`() = runTest {
        repo.setResult(
            "A",
            Result.success(
                Lyrics.SyncedLyrics(listOf(LyricLine(1000L, "A line 1"), LyricLine(5000L, "A line 2")))
            )
        )
        val viewModel = LyricsViewModel(repo, fakeClient)

        // Track A loads fully.
        viewModel.loadLyrics("A", "Song A", "Artist A", 100_000L)
        advanceUntilIdle()
        assertTrue("Track A should be showing", viewModel.uiState.value is LyricsUiState.Synced)

        // Track B begins loading, but keep its request in flight (gated).
        repo.gate("B")
        repo.setResult("B", Result.success(Lyrics.SyncedLyrics(listOf(LyricLine(2000L, "B line 1")))))
        viewModel.loadLyrics("B", "Song B", "Artist B", 120_000L)

        // A playback-position tick arrives while B is still loading.
        controllerStateFlow.value = controllerStateFlow.value.copy(currentPositionMs = 3000L)
        advanceUntilIdle()

        assertTrue(
            "Must remain Loading while B loads — the previous track's lyrics must not reappear",
            viewModel.uiState.value is LyricsUiState.Loading
        )

        // Once B completes, B's lyrics are shown.
        repo.release("B")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue("B should now be showing", state is LyricsUiState.Synced)
        assertEquals(1, (state as LyricsUiState.Synced).lines.size)
        assertEquals("Song B", state.trackTitle)
    }

    @Test
    fun `a slow older request cannot overwrite a newer request's lyrics`() = runTest {
        repo.setResult("A", Result.success(Lyrics.SyncedLyrics(listOf(LyricLine(1000L, "OLD A")))))
        repo.setResult(
            "B",
            Result.success(Lyrics.SyncedLyrics(listOf(LyricLine(2000L, "NEW B1"), LyricLine(4000L, "NEW B2"))))
        )
        repo.gate("A") // the older request will be slow

        val viewModel = LyricsViewModel(repo, fakeClient)
        viewModel.loadLyrics("A", "Song A", "Artist A", 100_000L) // generation 1 (blocked)
        viewModel.loadLyrics("B", "Song B", "Artist B", 120_000L) // generation 2 (completes)
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertTrue("Newer track B should be showing", state is LyricsUiState.Synced)
        assertEquals(2, (state as LyricsUiState.Synced).lines.size)

        // The stale older request finally completes.
        repo.release("A")
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertTrue("Older request must not overwrite newer lyrics", state is LyricsUiState.Synced)
        assertEquals(2, (state as LyricsUiState.Synced).lines.size)
        assertEquals("Song B", state.trackTitle)
    }
}
