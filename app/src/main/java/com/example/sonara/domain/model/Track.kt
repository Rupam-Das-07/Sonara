package com.example.sonara.domain.model

/**
 * Immutable domain track model as defined in Phase 4B-1 and Audit 01.
 *
 * [artist] stays the authoritative DISPLAY string and is what the UI renders.
 * [artists] is the structured credit list used for artist IDENTITY decisions
 * (notably Featured Artists affinity attribution); it is additive and defaults to
 * empty, so a track from a source that does not supply structured credits behaves
 * exactly as before. Never derive identity by parsing [artist] — see [ArtistCredit].
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null,
    val artists: List<ArtistCredit> = emptyList()
)
