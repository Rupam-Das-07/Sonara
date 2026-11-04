package com.example.sonara.domain.model

/**
 * An artist shown on the Home "Featured Artists" shelf.
 *
 * The backend roster supplies the editorial/chart-backed candidates and remains
 * authoritative for all artist METADATA. Membership, however, is no longer
 * editorial-only: [com.example.sonara.domain.discovery.FeaturedArtistSelector] may
 * also admit artists derived from on-device playback history, so a shelf can mix
 * editorial and locally-derived entries. Locally-derived entries carry a slug [id],
 * a display [name], an empty [genre] and a null [imageUrl].
 *
 * This is still a curated selection rather than a trained recommender, and nothing
 * about a user's listening ever leaves the device.
 *
 * @property id       Stable slug identifier (e.g. "arijit-singh"). Used for
 *                    name-based drill-through: GET /api/v1/artists/{id}?name={name}.
 * @property name     Display name.
 * @property genre    Human-authored descriptor (not a provider field). Empty when
 *                    unknown — callers must treat blank as "no descriptor".
 * @property imageUrl Real portrait URL, or null when none could be resolved. A null
 *                    image means the client renders a typographic monogram — never a
 *                    stock/placeholder image.
 * @property browseId Provider artist id when known; null falls back to [id] for
 *                    drill-through, which the backend resolves by name.
 */
data class FeaturedArtist(
    val id: String,
    val name: String,
    val genre: String = "",
    val imageUrl: String? = null,
    val browseId: String? = null
)
