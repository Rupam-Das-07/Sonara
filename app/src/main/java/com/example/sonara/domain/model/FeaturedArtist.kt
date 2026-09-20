package com.example.sonara.domain.model

/**
 * Editorial featured artist for the Home screen.
 *
 * This is curated editorial content — a chosen ordering of real artists — and is
 * deliberately NOT personalization. The Home module must label it as an editorial
 * selection, never as "based on your listening".
 *
 * @property id       Stable slug identifier (e.g. "arijit-singh"). Used for
 *                    name-based drill-through: GET /api/v1/artists/{id}?name={name}.
 * @property name     Display name.
 * @property genre    Human-authored descriptor (not a provider field).
 * @property imageUrl Real portrait URL, or null when the server could not resolve
 *                    one. A null image means the client renders a typographic
 *                    monogram — never a stock/placeholder image.
 */
data class FeaturedArtist(
    val id: String,
    val name: String,
    val genre: String = "",
    val imageUrl: String? = null,
    val browseId: String? = null
)
