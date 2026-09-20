package com.example.sonara.feature.lyrics

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.sonara.domain.model.LyricLine
import com.example.sonara.domain.model.Lyrics
import com.example.sonara.domain.repository.LyricsRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.client.MediaControllerState
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

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val controllerStateFlow = MutableStateFlow(MediaControllerState())

    private val fakeClient = object : MediaControllerClient(null) {
        override val controllerState = controllerStateFlow
        var lastSeekPositionMs: Long? = null
        override fun seekTo(positionMs: Long) {
            lastSeekPositionMs = positionMs
        }
    }

    private class FakeLyricsRepository : LyricsRepository {
        var resultToReturn: Result<Lyrics> = Result.success(Lyrics.Unavailable())

        override suspend fun getLyrics(
            trackId: String,
            title: String,
            artist: String,
            durationMs: Long
        ): Result<Lyrics> = resultToReturn
    }

    private val fakeRepo = FakeLyricsRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadLyrics loads synced lyrics and emits Synced state`() = runTest {
        val lines = listOf(
            LyricLine(timestampMs = 1000L, text = "Line 1", romanizedText = "Line 1"),
            LyricLine(timestampMs = 5000L, text = "Line 2", romanizedText = "Line 2")
        )
        fakeRepo.resultToReturn = Result.success(Lyrics.SyncedLyrics(lines))

        val viewModel = LyricsViewModel(fakeRepo, fakeClient)
        viewModel.loadLyrics("track-1", "Song A", "Artist A", 200_000L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State must be Synced", state is LyricsUiState.Synced)
        val synced = state as LyricsUiState.Synced
        assertEquals(2, synced.lines.size)
        assertEquals("Song A", synced.trackTitle)
        assertEquals("Artist A", synced.artistName)
    }

    @Test
    fun `toggleRomanization switches isRomanized flag without refetching`() = runTest {
        val lines = listOf(LyricLine(timestampMs = 1000L, text = "नमस्ते", romanizedText = "namaste"))
        fakeRepo.resultToReturn = Result.success(Lyrics.SyncedLyrics(lines))

        val viewModel = LyricsViewModel(fakeRepo, fakeClient)
        viewModel.loadLyrics("track-1", "Song", "Artist", 100_000L)
        advanceUntilIdle()

        var state = viewModel.uiState.value as LyricsUiState.Synced
        assertEquals(false, state.isRomanized)

        viewModel.toggleRomanization()
        state = viewModel.uiState.value as LyricsUiState.Synced
        assertEquals(true, state.isRomanized)

        viewModel.toggleRomanization()
        state = viewModel.uiState.value as LyricsUiState.Synced
        assertEquals(false, state.isRomanized)
    }

    @Test
    fun `seekTo invokes MediaControllerClient seekTo`() = runTest {
        val viewModel = LyricsViewModel(fakeRepo, fakeClient)
        viewModel.seekTo(15_000L)

        assertEquals(15_000L, fakeClient.lastSeekPositionMs)
    }

    @Test
    fun `playback position update moves activeLineIndex cleanly`() = runTest {
        val lines = listOf(
            LyricLine(timestampMs = 2000L, text = "First line"),
            LyricLine(timestampMs = 6000L, text = "Second line"),
            LyricLine(timestampMs = 10000L, text = "Third line")
        )
        fakeRepo.resultToReturn = Result.success(Lyrics.SyncedLyrics(lines))

        val viewModel = LyricsViewModel(fakeRepo, fakeClient)
        viewModel.loadLyrics("track-1", "Title", "Artist", 60_000L)
        advanceUntilIdle()

        // Position 3000ms -> line 0
        controllerStateFlow.value = controllerStateFlow.value.copy(currentPositionMs = 3000L)
        advanceUntilIdle()
        assertEquals(0, (viewModel.uiState.value as LyricsUiState.Synced).activeLineIndex)

        // Position 7000ms -> line 1
        controllerStateFlow.value = controllerStateFlow.value.copy(currentPositionMs = 7000L)
        advanceUntilIdle()
        assertEquals(1, (viewModel.uiState.value as LyricsUiState.Synced).activeLineIndex)

        // Position 12000ms -> line 2
        controllerStateFlow.value = controllerStateFlow.value.copy(currentPositionMs = 12000L)
        advanceUntilIdle()
        assertEquals(2, (viewModel.uiState.value as LyricsUiState.Synced).activeLineIndex)
    }

    @Test
    fun `loadLyrics with failure emits Unavailable state`() = runTest {
        fakeRepo.resultToReturn = Result.failure(Exception("Network failure"))

        val viewModel = LyricsViewModel(fakeRepo, fakeClient)
        viewModel.loadLyrics("track-err", "Err Song", "Err Artist", 100_000L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State must be Unavailable", state is LyricsUiState.Unavailable)
        assertEquals("Network failure", (state as LyricsUiState.Unavailable).reason)
    }
}
