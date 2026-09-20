package com.example.sonara.data.remote.backend

import com.example.sonara.data.remote.mapper.ArtworkUrlUpgrader
import com.example.sonara.domain.model.Track

/**
 * Pure-Kotlin mapper: typed intermediate fields → Android [Track] domain model.
 *
 * Isolated from org.json so it can be fully unit-tested on the JVM without
 * Android stubs. JSON parsing is done in [SonaraBackendClient]; the parsed
 * values are passed here as plain Kotlin types.
 */
internal object TrackMapper {

    /**
     * Maps parsed backend fields into an Android [Track].
     *
     * @param videoId    YouTube video ID (primary identity). Returns null if blank.
     * @param title      Track title. Falls back to "Unknown Title".
     * @param artist     Primary artist display name. Falls back to "Unknown Artist".
     * @param album      Album name (may be empty).
     * @param durationSec Duration in seconds (as returned by the server). 0 if unknown.
     * @param rawArtworkUrl Raw artwork URL before CDN upgrading (nullable).
     */
    fun map(
        videoId: String?,
        title: String?,
        artist: String?,
        album: String?,
        durationSec: Int,
        rawArtworkUrl: String?
    ): Track? {
        val id = videoId.takeIf { !it.isNullOrBlank() } ?: return null
        return Track(
            id = id,
            title = title.takeIf { !it.isNullOrBlank() } ?: "Unknown Title",
            artist = artist.takeIf { !it.isNullOrBlank() } ?: "Unknown Artist",
            album = album.orEmpty(),
            durationMs = if (durationSec > 0) durationSec * 1000L else 0L,
            artworkUrl = ArtworkUrlUpgrader.upgrade(rawArtworkUrl)
        )
    }
}
