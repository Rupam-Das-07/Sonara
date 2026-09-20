package com.example.sonara.domain.model

/**
 * Curated playlist tile for the Home screen catalog view (GET /api/v1/playlists).
 *
 * This is the lightweight summary used to render a discovery tile. The full track
 * list is fetched on demand as a [PlaylistDetail] when the tile is opened.
 *
 * @property id          Playlist definition id (slug).
 * @property name        Display name.
 * @property description Short editorial description (may be empty).
 * @property coverImage  Cover artwork URL derived from the first eligible track,
 *                       or null when none could be derived (client shows a
 *                       typographic fallback — never a placeholder image).
 * @property size        Declared track count for the definition.
 * @property origin      Source of the playlist ([PlaylistOrigin.USER] or [PlaylistOrigin.SPOTIFY]).
 */
data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String = "",
    val coverImage: String? = null,
    val size: Int = 0,
    val origin: PlaylistOrigin = PlaylistOrigin.USER
)
