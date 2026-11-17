package com.example.sonara.feature.library

import com.example.sonara.domain.model.DownloadWithTrack
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.download.ActiveDownloadProgress

data class LibraryUiState(
    val likedSongs: List<Track> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val downloads: List<Track> = emptyList(),
    val activeDownloads: List<DownloadWithTrack> = emptyList(),
    val activeProgressMap: Map<String, ActiveDownloadProgress> = emptyMap(),
    val selectedTab: LibraryTab = LibraryTab.LIKED_SONGS
)

enum class LibraryTab {
    LIKED_SONGS,
    DOWNLOADS,
    HISTORY
}
