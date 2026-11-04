package com.example.sonara.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.sonara.domain.model.ArtistCreditCodec
import com.example.sonara.domain.model.Track

/**
 * Local Room entity caching track metadata.
 *
 * [artistCredits] holds the structured credit list encoded by [ArtistCreditCodec] in a
 * single column. It is stored rather than recomputed because Featured Artists affinity
 * reads artist identity back out of `playback_history` joined to this table, long after
 * the originating network response is gone. The column is NOT NULL with a "" default so
 * rows written before schema 7 decode to an empty list — i.e. "no verified identity",
 * never a guessed one.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val artistCredits: String = ""
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        artists = ArtistCreditCodec.decode(artistCredits)
    )

    companion object {
        fun fromDomain(track: Track): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            artworkUrl = track.artworkUrl,
            artistCredits = ArtistCreditCodec.encode(track.artists)
        )

        /**
         * A playback callback may carry only the legacy flat artist label. Do not let that
         * empty credit list erase verified credits already cached for the same track.
         */
        fun fromDomainPreservingCredits(track: Track, existing: TrackEntity?): TrackEntity {
            val incoming = fromDomain(track)
            return if (incoming.artistCredits.isBlank() && !existing?.artistCredits.isNullOrBlank()) {
                incoming.copy(artistCredits = existing!!.artistCredits)
            } else {
                incoming
            }
        }
    }
}
