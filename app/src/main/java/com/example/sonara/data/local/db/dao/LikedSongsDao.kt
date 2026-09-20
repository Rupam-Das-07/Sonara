package com.example.sonara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sonara.data.local.db.entity.LikedSongEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LikedSongsDao {
    @Query("""
        SELECT t.* FROM tracks t
        INNER JOIN liked_songs ls ON t.id = ls.trackId
        ORDER BY ls.addedAt DESC
    """)
    fun getLikedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE trackId = :trackId)")
    fun isLiked(trackId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedSong(likedSong: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE trackId = :trackId")
    suspend fun deleteLikedSong(trackId: String)
}
