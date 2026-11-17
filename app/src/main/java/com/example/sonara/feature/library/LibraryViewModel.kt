package com.example.sonara.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.DownloadWithTrack
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.download.ActiveDownloadProgress
import com.example.sonara.domain.repository.DownloadRepository
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val historyRepository: HistoryRepository,
    private val downloadRepository: DownloadRepository? = null
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(LibraryTab.LIKED_SONGS)

    private val downloadedTracksFlow: Flow<List<Track>> = if (downloadRepository == null) {
        flowOf(emptyList())
    } else {
        downloadRepository.observeCompletedDownloads().map { downloads ->
            val trackIds = downloads.map { it.trackId }
            val tracksById = libraryRepository.getTracks(trackIds).associateBy { it.id }
            val validTracks = mutableListOf<Track>()
            val seenIds = mutableSetOf<String>()
            for (download in downloads) {
                if (download.trackId.isBlank()) continue
                if (download.status != DownloadStatus.DOWNLOADED) continue
                val path = download.localFilePath
                val isAvailable = if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists() && file.canRead() && file.length() > 0L) {
                        true
                    } else {
                        downloadRepository.getDownloadedFileUri(download.trackId) != null
                    }
                } else {
                    downloadRepository.getDownloadedFileUri(download.trackId) != null
                }
                if (!isAvailable) continue
                val track = tracksById[download.trackId] ?: continue
                if (seenIds.add(track.id)) {
                    validTracks.add(track)
                }
            }
            validTracks
        }
    }

    private val activeDownloadsFlow: Flow<List<DownloadWithTrack>> = if (downloadRepository == null) {
        flowOf(emptyList())
    } else {
        downloadRepository.observeDownloadsWithTrack().map { list ->
            list.filter {
                it.download.status == DownloadStatus.DOWNLOADING || it.download.status == DownloadStatus.QUEUED
            }
        }
    }

    private val activeProgressFlow: Flow<Map<String, ActiveDownloadProgress>> = if (downloadRepository == null) {
        flowOf(emptyMap())
    } else {
        downloadRepository.observeAllActiveProgress()
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.getLikedSongs(),
        historyRepository.getRecentHistory(50),
        combine(downloadedTracksFlow, activeDownloadsFlow, activeProgressFlow) { completed, active, progress ->
            Triple(completed, active, progress)
        },
        _selectedTab
    ) { liked, history, downloadData, tab ->
        LibraryUiState(
            likedSongs = liked,
            history = history,
            downloads = downloadData.first,
            activeDownloads = downloadData.second,
            activeProgressMap = downloadData.third,
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
        if (track.id.isBlank()) return
        viewModelScope.launch {
            libraryRepository.setLiked(track, !isCurrentlyLiked)
        }
    }

    fun removeDownload(trackId: String) {
        if (trackId.isBlank()) return
        val downloadRepo = downloadRepository ?: return
        viewModelScope.launch {
            downloadRepo.removeDownload(trackId)
        }
    }
}
