package com.example.sonara.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.sonara.domain.model.Track

/**
 * Local Room entity caching track metadata.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artworkUrl = artworkUrl
    )

    companion object {
        fun fromDomain(track: Track): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            artworkUrl = track.artworkUrl
        )
    }
}
