package com.example.sonara.domain.model

/**
 * Domain model representing an artist's catalog response from Sonara backend.
 *
 * Sourced from GET /api/v1/artists/{idOrBrowseId}?name={name}.
 *
 * @property tracks     Top eligible tracks for this artist.
 * @property playlists  Associated artist-focused playlists (if any exist for this artist).
 */
data class ArtistCatalog(
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList()
)
