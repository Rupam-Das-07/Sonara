package com.example.sonara.feature.library

import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.LibraryRepository
import com.example.sonara.feature.library.LibraryTab
import com.example.sonara.feature.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
class LibraryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeLibraryRepository : LibraryRepository {
        val likedTracks = MutableStateFlow<List<Track>>(emptyList())

        override fun getLikedSongs(): Flow<List<Track>> = likedTracks
        override fun isLiked(trackId: String): Flow<Boolean> = MutableStateFlow(likedTracks.value.any { it.id == trackId })
        override suspend fun setLiked(track: Track, isLiked: Boolean) {
            val list = likedTracks.value.toMutableList()
            if (isLiked) {
                if (!list.contains(track)) list.add(track)
            } else {
                list.removeAll { it.id == track.id }
            }
            likedTracks.value = list
        }
        override suspend fun cacheTrack(track: Track) {}
        override suspend fun getTrack(trackId: String): Track? = likedTracks.value.find { it.id == trackId }
        override suspend fun getTracks(trackIds: List<String>): List<Track> = likedTracks.value.filter { trackIds.contains(it.id) }
    }

    private class FakeHistoryRepository : HistoryRepository {
        val historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = historyItems
        override suspend fun recordHistory(track: Track, completed: Boolean) {
            val list = historyItems.value.toMutableList()
            list.add(HistoryItem(id = list.size + 1L, track = track, completed = completed))
            historyItems.value = list
        }
        override suspend fun clearHistory() {
            historyItems.value = emptyList()
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun libraryViewModel_tabSelectionUpdatesUiState() = runTest {
        val libraryRepo = FakeLibraryRepository()
        val historyRepo = FakeHistoryRepository()
        val viewModel = LibraryViewModel(libraryRepo, historyRepo)

        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(LibraryTab.LIKED_SONGS, viewModel.uiState.value.selectedTab)

        viewModel.selectTab(LibraryTab.HISTORY)
        advanceUntilIdle()

        assertEquals(LibraryTab.HISTORY, viewModel.uiState.value.selectedTab)

        viewModel.selectTab(LibraryTab.LIKED_SONGS)
        advanceUntilIdle()

        assertEquals(LibraryTab.LIKED_SONGS, viewModel.uiState.value.selectedTab)
        collectJob.cancel()
    }

    @Test
    fun libraryViewModel_togglingLikeUpdatesRepository() = runTest {
        val libraryRepo = FakeLibraryRepository()
        val historyRepo = FakeHistoryRepository()
        val viewModel = LibraryViewModel(libraryRepo, historyRepo)
        val track = Track(id = "track_1", title = "Melody", artist = "Artist")

        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.toggleLike(track, isCurrentlyLiked = false)
        advanceUntilIdle()

        assertTrue(libraryRepo.likedTracks.value.any { it.id == "track_1" })
        collectJob.cancel()
    }
}
