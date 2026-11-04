package com.example.sonara.playback.controller

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe coordinator managing audio vs video playback route ownership.
 *
 * Used to suppress background audio transport commands (such as Bluetooth headset,
 * Android notification, or lock-screen Play commands arriving at SonaraPlaybackService)
 * while a YouTube IFrame video is the active playback route.
 */
class PlaybackRouteCoordinator {
    private val _isIFrameVideoActive = AtomicBoolean(false)

    val isIFrameVideoActive: Boolean
        get() = _isIFrameVideoActive.get()

    var onRouteChanged: (() -> Unit)? = null

    fun setIFrameVideoActive(active: Boolean) {
        val changed = _isIFrameVideoActive.getAndSet(active) != active
        if (changed) {
            onRouteChanged?.invoke()
        }
    }

    fun reset() {
        setIFrameVideoActive(false)
    }
}
