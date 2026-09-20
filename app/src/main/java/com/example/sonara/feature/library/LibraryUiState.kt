package com.example.sonara.feature.library

import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.Track

data class LibraryUiState(
    val likedSongs: List<Track> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val selectedTab: LibraryTab = LibraryTab.LIKED_SONGS
)

enum class LibraryTab {
    LIKED_SONGS,
    HISTORY
}


