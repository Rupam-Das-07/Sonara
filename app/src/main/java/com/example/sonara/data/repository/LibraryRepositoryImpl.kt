package com.example.sonara.data.repository

import com.example.sonara.data.local.db.dao.LikedSongsDao
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.LikedSongEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepositoryImpl(
    private val likedSongsDao: LikedSongsDao,
    private val trackDao: TrackDao
) : LibraryRepository {

    override fun getLikedSongs(): Flow<List<Track>> {
        return likedSongsDao.getLikedTracks().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun isLiked(trackId: String): Flow<Boolean> {
        return likedSongsDao.isLiked(trackId)
    }

    override suspend fun setLiked(track: Track, isLiked: Boolean) {
        // Ensure track is cached in tracks table first to satisfy foreign key
        trackDao.insertTrack(TrackEntity.fromDomain(track))
        if (isLiked) {
            likedSongsDao.insertLikedSong(LikedSongEntity(trackId = track.id))
        } else {
            likedSongsDao.deleteLikedSong(track.id)
        }
    }

    override suspend fun cacheTrack(track: Track) {
        trackDao.insertTrack(TrackEntity.fromDomain(track))
    }

    override suspend fun getTrack(trackId: String): Track? {
        return trackDao.getTrackById(trackId)?.toDomain()
    }

    override suspend fun getTracks(trackIds: List<String>): List<Track> {
        return trackDao.getTracksByIds(trackIds).map { it.toDomain() }
    }
}
