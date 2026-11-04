package com.example.sonara.domain.model

import com.example.sonara.domain.model.soundcloud.SoundcloudSourceIdentity

/**
 * Authoritative content sources recognized for Sonara tracks.
 *
 * Source identity is determined authoritatively from the track identifier or metadata.
 * Missing or unrecognized metadata evaluates to null, avoiding fabricated source attribution.
 */
enum class TrackSource {
    YOUTUBE_MUSIC,
    SOUNDCLOUD;

    companion object {
        /**
         * Resolves the authoritative [TrackSource] for a given [Track].
         */
        fun fromTrack(track: Track?): TrackSource? =
            fromTrackId(track?.id)

        /**
         * Resolves the authoritative [TrackSource] from a track identifier string.
         *
         * Returns null if the identifier is blank, malformed, or not recognized as a
         * supported integrated source.
         */
        fun fromTrackId(trackId: String?): TrackSource? {
            if (trackId.isNullOrBlank()) return null
            val id = trackId.trim()
            return when {
                SoundcloudSourceIdentity.isSoundcloudTrackId(id) -> SOUNDCLOUD
                isYoutubeMusicTrackId(id) -> YOUTUBE_MUSIC
                else -> null
            }
        }

        private fun isYoutubeMusicTrackId(id: String): Boolean {
            if (id.startsWith("yt:") || id.startsWith("yt_") || id.startsWith("youtube:") || id.startsWith("ytmusic:")) {
                return true
            }
            return id.length == 11 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }
}

val Track.source: TrackSource?
    get() = TrackSource.fromTrack(this)
