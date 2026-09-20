package com.example.sonara.domain.model

/**
 * A resolved curated playlist with its playable tracks
 * (GET /api/v1/playlists/{id}).
 *
 * Every track in [tracks] has already passed the same videoId-required mapping as
 * search results, so each entry is guaranteed playable. Used when a Home playlist
 * tile is expanded inline to reveal its contents.
 *
 * @property id          Playlist definition id (slug).
 * @property name        Display name.
 * @property description Short editorial description (may be empty).
 * @property coverImage  Cover artwork URL, or null.
 * @property tracks      Playable tracks, in curated order (may be empty if the
 *                       provider returned nothing).
 */
data class PlaylistDetail(
    val id: String,
    val name: String,
    val description: String = "",
    val coverImage: String? = null,
    val tracks: List<Track> = emptyList()
)
