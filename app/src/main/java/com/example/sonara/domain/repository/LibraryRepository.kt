package com.example.sonara.domain.repository

import com.example.sonara.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Domain boundary for user library (Liked Songs) and local track metadata caching.
 */
interface LibraryRepository {
    fun getLikedSongs(): Flow<List<Track>>
    fun isLiked(trackId: String): Flow<Boolean>
    suspend fun setLiked(track: Track, isLiked: Boolean)
    suspend fun cacheTrack(track: Track)
    suspend fun getTrack(trackId: String): Track?
    suspend fun getTracks(trackIds: List<String>): List<Track>
}
