package com.example.sonara.domain.model

/**
 * Immutable domain track model as defined in Phase 4B-1 and Audit 01.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null
)
