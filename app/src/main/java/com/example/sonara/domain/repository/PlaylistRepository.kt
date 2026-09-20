package com.example.sonara.domain.repository

import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Domain boundary for user-created playlist management in Sonara.
 * Supports Create, Rename, Delete, Add track, Remove track, and Reorder tracks.
 */
interface PlaylistRepository {
    fun getUserPlaylists(): Flow<List<PlaylistSummary>>
    fun getPlaylistDetail(playlistId: String): Flow<PlaylistDetail?>
    suspend fun createPlaylist(name: String): Result<String>
    suspend fun renamePlaylist(playlistId: String, newName: String): Result<Unit>
    suspend fun deletePlaylist(playlistId: String): Result<Unit>
    suspend fun addTrackToPlaylist(playlistId: String, track: Track): Result<Unit>
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): Result<Unit>
    suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<String>): Result<Unit>
    suspend fun isTrackInPlaylist(playlistId: String, trackId: String): Boolean
    suspend fun importPlaylist(name: String, tracks: List<Track>): Result<com.example.sonara.domain.model.ImportPlaylistResult>
}

