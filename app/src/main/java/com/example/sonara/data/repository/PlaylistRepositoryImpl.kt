package com.example.sonara.data.repository

import com.example.sonara.data.local.db.dao.PlaylistDao
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.PlaylistEntity
import com.example.sonara.data.local.db.entity.PlaylistTrackCrossRefEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistOrigin
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import androidx.room.withTransaction
import com.example.sonara.data.local.db.SonaraDatabase
import com.example.sonara.domain.model.ImportPlaylistResult
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class PlaylistRepositoryImpl(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val database: SonaraDatabase? = null
) : PlaylistRepository {

    override fun getUserPlaylists(): Flow<List<PlaylistSummary>> {
        return playlistDao.getAllPlaylists().map { entities ->
            entities.map { entity ->
                val trackCount = playlistDao.getTrackCount(entity.id)
                val firstArtwork = playlistDao.getFirstTrackArtwork(entity.id)
                PlaylistSummary(
                    id = entity.id,
                    name = entity.name,
                    description = "",
                    coverImage = firstArtwork,
                    size = trackCount,
                    origin = PlaylistOrigin.fromString(entity.origin)
                )
            }
        }
    }

    override fun getPlaylistDetail(playlistId: String): Flow<PlaylistDetail?> {
        return combine(
            playlistDao.getPlaylistById(playlistId),
            playlistDao.getPlaylistTracks(playlistId)
        ) { playlistEntity, trackEntities ->
            if (playlistEntity == null) {
                null
            } else {
                val tracks = trackEntities.map { it.toDomain() }
                PlaylistDetail(
                    id = playlistEntity.id,
                    name = playlistEntity.name,
                    description = "",
                    coverImage = tracks.firstOrNull()?.artworkUrl,
                    tracks = tracks
                )
            }
        }
    }

    override suspend fun createPlaylist(name: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Playlist name cannot be empty"))
        }
        if (trimmed.length > 60) {
            return@withContext Result.failure(IllegalArgumentException("Playlist name cannot exceed 60 characters"))
        }
        try {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val entity = PlaylistEntity(
                id = id,
                name = trimmed,
                createdAt = now,
                updatedAt = now,
                origin = PlaylistOrigin.USER.name
            )
            playlistDao.insertPlaylist(entity)
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun renamePlaylist(playlistId: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Playlist name cannot be empty"))
        }
        if (trimmed.length > 60) {
            return@withContext Result.failure(IllegalArgumentException("Playlist name cannot exceed 60 characters"))
        }
        try {
            playlistDao.updatePlaylistName(playlistId, trimmed, System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deletePlaylist(playlistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            playlistDao.deletePlaylist(playlistId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addTrackToPlaylist(playlistId: String, track: Track): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Cache track first for foreign key integrity
            trackDao.insertTrack(TrackEntity.fromDomain(track))

            // Prevent duplicate addition
            if (playlistDao.isTrackInPlaylist(playlistId, track.id)) {
                return@withContext Result.success(Unit)
            }

            val maxPos = playlistDao.getMaxPosition(playlistId) ?: -1
            val nextPos = maxPos + 1
            playlistDao.insertPlaylistTrack(
                PlaylistTrackCrossRefEntity(
                    playlistId = playlistId,
                    trackId = track.id,
                    position = nextPos,
                    addedAt = System.currentTimeMillis()
                )
            )
            playlistDao.updatePlaylistTimestamp(playlistId, System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            playlistDao.deletePlaylistTrack(playlistId, trackId)
            // Re-normalize positions
            val remaining = playlistDao.getPlaylistTracksSync(playlistId)
            playlistDao.reorderTracks(playlistId, remaining.map { it.id })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reorderTracks(playlistId: String, orderedTrackIds: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            playlistDao.reorderTracks(playlistId, orderedTrackIds)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isTrackInPlaylist(playlistId: String, trackId: String): Boolean = withContext(Dispatchers.IO) {
        playlistDao.isTrackInPlaylist(playlistId, trackId)
    }

    override suspend fun importPlaylist(name: String, tracks: List<Track>): Result<ImportPlaylistResult> = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Playlist name cannot be empty"))
        }
        if (trimmed.length > 60) {
            return@withContext Result.failure(IllegalArgumentException("Playlist name cannot exceed 60 characters"))
        }

        try {
            // Deduplicate tracks by video ID, preserving first-seen order (V1 PK constraint)
            val seenIds = mutableSetOf<String>()
            val dedupedTracks = mutableListOf<Track>()
            var collapsedDuplicates = 0

            for (track in tracks) {
                if (seenIds.add(track.id)) {
                    dedupedTracks.add(track)
                } else {
                    collapsedDuplicates++
                }
            }

            val playlistId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val playlistEntity = PlaylistEntity(
                id = playlistId,
                name = trimmed,
                createdAt = now,
                updatedAt = now,
                origin = PlaylistOrigin.SPOTIFY.name
            )

            val trackEntities = dedupedTracks.map { TrackEntity.fromDomain(it) }
            val crossRefs = dedupedTracks.mapIndexed { index, track ->
                PlaylistTrackCrossRefEntity(
                    playlistId = playlistId,
                    trackId = track.id,
                    position = index,
                    addedAt = now + index // Monotonic timestamp tie-breaker
                )
            }

            // Atomic Room Transaction
            if (database != null) {
                database.withTransaction {
                    playlistDao.insertPlaylist(playlistEntity)
                    trackDao.insertTracks(trackEntities)
                    playlistDao.insertPlaylistTracks(crossRefs)
                }
            } else {
                playlistDao.insertPlaylist(playlistEntity)
                trackDao.insertTracks(trackEntities)
                playlistDao.insertPlaylistTracks(crossRefs)
            }

            Result.success(
                ImportPlaylistResult(
                    playlistId = playlistId,
                    playlistName = trimmed,
                    totalTracksAdded = dedupedTracks.size,
                    collapsedDuplicates = collapsedDuplicates
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

