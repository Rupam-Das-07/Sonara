package com.example.sonara.feature.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.PlaylistRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel managing User Playlists, Dialogs, Sheets, Reordering, and reactive multi-screen synchronization.
 */
class PlaylistViewModel(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val _selectedPlaylist = MutableStateFlow<PlaylistDetail?>(null)
    private val _isReorderMode = MutableStateFlow(false)
    private val _reorderedTracks = MutableStateFlow<List<Track>>(emptyList())
    private val _pendingAddTrack = MutableStateFlow<Track?>(null)
    private val _selectedPlaylistIdForAdd = MutableStateFlow<String?>(null)
    private val _isAddToPlaylistSheetOpen = MutableStateFlow(false)
    private val _isCreateDialogOpen = MutableStateFlow(false)
    private val _playlistToRename = MutableStateFlow<PlaylistSummary?>(null)
    private val _playlistToDelete = MutableStateFlow<PlaylistSummary?>(null)
    private val _trackToRemove = MutableStateFlow<Pair<String, Track>?>(null)
    private val _feedbackMessage = MutableStateFlow<String?>(null)

    private var detailJob: Job? = null

    val uiState: StateFlow<PlaylistUiState> = combine(
        playlistRepository.getUserPlaylists(),
        _selectedPlaylist,
        _isReorderMode,
        _reorderedTracks,
        _pendingAddTrack,
        _selectedPlaylistIdForAdd,
        _isAddToPlaylistSheetOpen,
        _isCreateDialogOpen,
        _playlistToRename,
        _playlistToDelete,
        _trackToRemove,
        _feedbackMessage
    ) { args: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        PlaylistUiState(
            playlists = args[0] as List<PlaylistSummary>,
            selectedPlaylist = args[1] as PlaylistDetail?,
            isReorderMode = args[2] as Boolean,
            reorderedTracks = args[3] as List<Track>,
            pendingAddTrack = args[4] as Track?,
            selectedPlaylistIdForAdd = args[5] as String?,
            isAddToPlaylistSheetOpen = args[6] as Boolean,
            isCreateDialogOpen = args[7] as Boolean,
            playlistToRename = args[8] as PlaylistSummary?,
            playlistToDelete = args[9] as PlaylistSummary?,
            trackToRemove = args[10] as Pair<String, Track>?,
            feedbackMessage = args[11] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlaylistUiState()
    )

    fun selectPlaylist(playlistSummary: PlaylistSummary?) {
        detailJob?.cancel()
        if (playlistSummary == null) {
            _selectedPlaylist.value = null
            _isReorderMode.value = false
            _reorderedTracks.value = emptyList()
            return
        }

            detailJob = viewModelScope.launch {
            playlistRepository.getPlaylistDetail(playlistSummary.id).collect { detail ->
                _selectedPlaylist.value = detail
                if (!_isReorderMode.value && detail != null) {
                    _reorderedTracks.value = detail.tracks
                }
            }
        }
    }

    fun selectPlaylistById(playlistId: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            playlistRepository.getPlaylistDetail(playlistId).collect { detail ->
                _selectedPlaylist.value = detail
                if (!_isReorderMode.value && detail != null) {
                    _reorderedTracks.value = detail.tracks
                }
            }
        }
    }

    fun openAddToPlaylist(track: Track) {
        _pendingAddTrack.value = track
        _selectedPlaylistIdForAdd.value = null
        _isAddToPlaylistSheetOpen.value = true
    }

    fun dismissAddToPlaylist() {
        _isAddToPlaylistSheetOpen.value = false
        _pendingAddTrack.value = null
        _selectedPlaylistIdForAdd.value = null
    }

    fun selectPlaylistForAdd(playlistId: String) {
        _selectedPlaylistIdForAdd.value = playlistId
    }

    fun addPendingTrackToSelectedPlaylist() {
        val track = _pendingAddTrack.value ?: return
        val playlistId = _selectedPlaylistIdForAdd.value ?: return

        viewModelScope.launch {
            val result = playlistRepository.addTrackToPlaylist(playlistId, track)
            if (result.isSuccess) {
                _feedbackMessage.value = "Added to playlist"
                dismissAddToPlaylist()
            } else {
                _feedbackMessage.value = "Failed to add track: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun openCreateDialog() {
        _isCreateDialogOpen.value = true
    }

    fun dismissCreateDialog() {
        _isCreateDialogOpen.value = false
    }

    fun createPlaylist(name: String, andAddPendingTrack: Boolean = false) {
        viewModelScope.launch {
            val result = playlistRepository.createPlaylist(name)
            result.onSuccess { newPlaylistId ->
                _isCreateDialogOpen.value = false
                if (andAddPendingTrack && _pendingAddTrack.value != null) {
                    val track = _pendingAddTrack.value!!
                    playlistRepository.addTrackToPlaylist(newPlaylistId, track)
                    _feedbackMessage.value = "Playlist created and song added"
                    dismissAddToPlaylist()
                } else {
                    _feedbackMessage.value = "Playlist created"
                    if (_isAddToPlaylistSheetOpen.value) {
                        _selectedPlaylistIdForAdd.value = newPlaylistId
                    }
                }
            }.onFailure { e ->
                _feedbackMessage.value = e.message ?: "Failed to create playlist"
            }
        }
    }

    fun requestRename(playlist: PlaylistSummary) {
        _playlistToRename.value = playlist
    }

    fun dismissRenameDialog() {
        _playlistToRename.value = null
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        viewModelScope.launch {
            val result = playlistRepository.renamePlaylist(playlistId, newName)
            result.onSuccess {
                _playlistToRename.value = null
                _feedbackMessage.value = "Playlist renamed"
            }.onFailure { e ->
                _feedbackMessage.value = e.message ?: "Failed to rename playlist"
            }
        }
    }

    fun requestDelete(playlist: PlaylistSummary) {
        _playlistToDelete.value = playlist
    }

    fun dismissDeleteDialog() {
        _playlistToDelete.value = null
    }

    fun confirmDeletePlaylist(playlistId: String) {
        viewModelScope.launch {
            if (_selectedPlaylist.value?.id == playlistId) {
                _selectedPlaylist.value = null
            }
            val result = playlistRepository.deletePlaylist(playlistId)
            result.onSuccess {
                _playlistToDelete.value = null
                _feedbackMessage.value = "Playlist deleted"
            }.onFailure { e ->
                _feedbackMessage.value = e.message ?: "Failed to delete playlist"
            }
        }
    }

    fun requestRemoveTrack(playlistId: String, track: Track) {
        _trackToRemove.value = Pair(playlistId, track)
    }

    fun dismissRemoveTrackDialog() {
        _trackToRemove.value = null
    }

    fun confirmRemoveTrack() {
        val pair = _trackToRemove.value ?: return
        viewModelScope.launch {
            val result = playlistRepository.removeTrackFromPlaylist(pair.first, pair.second.id)
            result.onSuccess {
                _trackToRemove.value = null
                _feedbackMessage.value = "Removed from playlist"
            }.onFailure { e ->
                _feedbackMessage.value = e.message ?: "Failed to remove track"
            }
        }
    }

    fun startReorderMode() {
        _reorderedTracks.value = _selectedPlaylist.value?.tracks ?: emptyList()
        _isReorderMode.value = true
    }

    fun moveTrackInReorder(fromIndex: Int, toIndex: Int) {
        val list = _reorderedTracks.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _reorderedTracks.value = list
        }
    }

    fun commitReorder() {
        val playlistId = _selectedPlaylist.value?.id ?: return
        val currentOrder = _reorderedTracks.value.map { it.id }
        _isReorderMode.value = false

        viewModelScope.launch {
            val result = playlistRepository.reorderTracks(playlistId, currentOrder)
            if (result.isFailure) {
                _feedbackMessage.value = "Failed to save order"
                // Rollback to database state
                _reorderedTracks.value = _selectedPlaylist.value?.tracks ?: emptyList()
            }
        }
    }

    fun cancelReorder() {
        _isReorderMode.value = false
        _reorderedTracks.value = _selectedPlaylist.value?.tracks ?: emptyList()
    }

    fun clearFeedbackMessage() {
        _feedbackMessage.value = null
    }
}
