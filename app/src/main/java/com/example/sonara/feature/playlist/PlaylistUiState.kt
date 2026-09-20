package com.example.sonara.feature.playlist

import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track

/**
 * UI State model for User Playlist Management.
 */
data class PlaylistUiState(
    val playlists: List<PlaylistSummary> = emptyList(),
    val selectedPlaylist: PlaylistDetail? = null,
    val isLoading: Boolean = false,
    val isReorderMode: Boolean = false,
    val reorderedTracks: List<Track> = emptyList(),
    val pendingAddTrack: Track? = null,
    val selectedPlaylistIdForAdd: String? = null,
    val isAddToPlaylistSheetOpen: Boolean = false,
    val isCreateDialogOpen: Boolean = false,
    val playlistToRename: PlaylistSummary? = null,
    val playlistToDelete: PlaylistSummary? = null,
    val trackToRemove: Pair<String, Track>? = null,
    val feedbackMessage: String? = null
)
