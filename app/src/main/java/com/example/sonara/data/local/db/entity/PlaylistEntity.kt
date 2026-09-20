package com.example.sonara.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.sonara.domain.model.PlaylistOrigin

/**
 * Local Room entity representing user-created or imported playlist metadata.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val origin: String = PlaylistOrigin.USER.name
)
