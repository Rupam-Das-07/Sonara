package com.example.sonara.domain.model.soundcloud

import com.example.sonara.domain.model.Track

/**
 * Validated result of a SoundCloud track extraction.
 *
 * Separates persistent source-neutral [Track] metadata (with "sc:" provider prefix)
 * from transient playback stream information ([selectedStream] and [allStreams]).
 */
data class SoundcloudTrackExtraction(
    val track: Track,
    val rawTrackId: String,
    val canonicalUrl: String,
    val selectedStream: SoundcloudAudioStream,
    val allStreams: List<SoundcloudAudioStream>
)

sealed interface SoundcloudNormalizedInput {
    data class Url(val url: String) : SoundcloudNormalizedInput
    data class TrackId(val id: String) : SoundcloudNormalizedInput
}

object SoundcloudSourceIdentity {
    const val PREFIX = "sc:"
    const val LEGACY_PREFIX = "soundcloud:"

    fun isSoundcloudTrackId(id: String): Boolean =
        id.startsWith(PREFIX) || id.startsWith(LEGACY_PREFIX)

    fun toSoundcloudTrackId(rawId: String): String =
        if (isSoundcloudTrackId(rawId)) rawId else "$PREFIX$rawId"

    fun extractRawSoundcloudId(trackId: String): String =
        trackId.removePrefix(LEGACY_PREFIX).removePrefix(PREFIX)
}
