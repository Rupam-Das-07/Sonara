package com.example.sonara.feature.player

import androidx.media3.common.Player

/**
 * Immutable UI state projection for Player screen / Verification UI.
 * Complies strictly with Phase 4B-2 state projection architecture.
 */
data class PlayerUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val trackId: String = "",
    val trackTitle: String = "No Track Selected",
    val artistName: String = "Unknown Artist",
    val albumTitle: String = "",
    val artworkUrl: String? = null,
    val currentPositionMs: Long = 0L,
    val isPositionAvailable: Boolean = false,
    val durationMs: Long = 0L,
    val playbackState: Int = Player.STATE_IDLE,
    val isShuffled: Boolean = false,
    val repeatMode: Int = 0, // 0 = off, 1 = repeat all, 2 = repeat one
    val isFavorite: Boolean = false,
    val volume: Float = 0.85f,
    val isMuted: Boolean = false,
    val errorMessage: String? = null,
    val viewCount: Long? = null,
    val formattedViewCount: String? = null,
    val displayTitle: String? = null,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false
) {
    val effectiveTitle: String
        get() = displayTitle ?: trackTitle

    val progress: Float
        get() = if (!isLive && durationMs > 0L) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
