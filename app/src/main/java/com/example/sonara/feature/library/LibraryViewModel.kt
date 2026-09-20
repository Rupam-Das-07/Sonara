package com.example.sonara.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val historyRepository: HistoryRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(LibraryTab.LIKED_SONGS)

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.getLikedSongs(),
        historyRepository.getRecentHistory(50),
        _selectedTab
    ) { liked, history, tab ->
        LibraryUiState(
            likedSongs = liked,
            history = history,
            selectedTab = tab
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    fun selectTab(tab: LibraryTab) {
        _selectedTab.value = tab
    }

    fun toggleLike(track: Track, isCurrentlyLiked: Boolean) {
        viewModelScope.launch {
            libraryRepository.setLiked(track, !isCurrentlyLiked)
        }
    }
}

