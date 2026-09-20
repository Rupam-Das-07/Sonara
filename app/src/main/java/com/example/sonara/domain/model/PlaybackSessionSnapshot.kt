package com.example.sonara.domain.model

/**
 * Persisted checkpoint representing the active playback session for cold-start restoration.
 * NOTE: Stream URLs are strictly ephemeral and are NEVER persisted.
 */
data class PlaybackSessionSnapshot(
    val lastTrackId: String?,
    val lastPositionMs: Long = 0L,
    val queueTrackIds: List<String> = emptyList()
)
