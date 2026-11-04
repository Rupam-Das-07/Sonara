package com.example.sonara.feature.video

/**
 * Coordinates position resolution and Previous-navigation decision logic for video playback,
 * ensuring consistent behavior across Media3 and YouTube IFrame routes.
 */
object VideoPreviousNavigationCoordinator {
    const val PREVIOUS_RESTART_THRESHOLD_MS = 3000L

    /**
     * Determines whether playback should be paused prior to calling previous().
     * Playback should only be paused when navigating to a genuine previous track,
     * NOT when restarting the current track at or below 3000ms with an empty history.
     */
    fun shouldPauseBeforePrevious(
        currentPositionMs: Long,
        hasPreviousTrack: Boolean,
        thresholdMs: Long = PREVIOUS_RESTART_THRESHOLD_MS
    ): Boolean {
        return currentPositionMs <= thresholdMs && hasPreviousTrack
    }

    /**
     * Resolves the current playback position to use for the Previous threshold check.
     */
    fun resolveCurrentPosition(
        isMedia3: Boolean,
        media3PositionMs: Long,
        iframeStatePositionMs: Long?,
        retainedIFramePositionMs: Long
    ): Long {
        return if (isMedia3) {
            media3PositionMs
        } else {
            iframeStatePositionMs ?: retainedIFramePositionMs
        }
    }

    /**
     * Calculates the target position in milliseconds from a scrub fraction.
     */
    fun calculateSeekTargetMs(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f) * durationMs).toLong()
    }
}
