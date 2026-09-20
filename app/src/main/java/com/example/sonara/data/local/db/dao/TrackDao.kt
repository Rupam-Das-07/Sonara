package com.example.sonara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.sonara.data.local.db.entity.TrackEntity

@Dao
interface TrackDao {
    @Upsert
    suspend fun insertTrack(track: TrackEntity)

    @Upsert
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksByIds(ids: List<String>): List<TrackEntity>
}
