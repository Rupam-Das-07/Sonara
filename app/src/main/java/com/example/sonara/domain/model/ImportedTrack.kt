package com.example.sonara.domain.model

/**
 * Transient in-memory model representing a raw imported track parsed from a Spotify export file.
 *
 * CRITICAL ARCHITECTURAL CONSTRAINTS:
 * - Must NEVER become [Track] or [TrackEntity].
 * - Must NEVER enter Room database.
 * - Must NEVER be persisted.
 * - [spotifyUri] must remain strictly on-device; never transmitted to the backend.
 * - [isrc] is transient/optional and not used for retrieval/scoring in V1.
 */
data class ImportedTrack(
    val sourceOrder: Int,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val isrc: String? = null,
    val spotifyUri: String? = null,
    val isLocalFile: Boolean = false,
    val isEpisode: Boolean = false
)
