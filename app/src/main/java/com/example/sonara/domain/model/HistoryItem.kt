package com.example.sonara.domain.model

/**
 * Domain model for a track playback history entry.
 */
data class HistoryItem(
    val id: Long = 0L,
    val track: Track,
    val listenedAt: Long = System.currentTimeMillis(),
    val completed: Boolean = false
)
