package com.example.sonara.playback.controller

import com.example.sonara.domain.model.Track
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe coordinator managing audio vs video playback route ownership.
 *
 * Coordinates transport commands, queue authority, and capability reporting
 * across SonaraPlaybackService, SonaraForwardingPlayer, and VideoPlaybackViewModel.
 */
class PlaybackRouteCoordinator {
    private val _isIFrameVideoActive = AtomicBoolean(false)

    val isIFrameVideoActive: Boolean
        get() = _isIFrameVideoActive.get()

    /**
     * Listener invoked whenever route or command capability changes,
     * prompting SonaraForwardingPlayer to invalidate its available commands.
     */
    var onRouteChanged: (() -> Unit)? = null

    /**
     * Interface through which the authoritative video playback controller (VideoPlaybackViewModel)
     * exposes capability and navigation callbacks to SonaraPlaybackService and SonaraForwardingPlayer.
     */
    interface VideoPlaybackDelegate {
        val isVideoActive: Boolean
        fun canAdvance(): Boolean
        fun onSeekToNext()
        fun onSeekToPrevious()
        fun currentTrack(): Track?
    }

    private val delegateLock = Any()

    @Volatile
    private var videoDelegate: VideoPlaybackDelegate? = null

    val isVideoActive: Boolean
        get() = videoDelegate?.isVideoActive == true

    fun canVideoAdvance(): Boolean =
        videoDelegate?.takeIf { it.isVideoActive }?.canAdvance() == true

    fun onVideoSeekToNext() {
        videoDelegate?.takeIf { it.isVideoActive }?.onSeekToNext()
    }

    fun onVideoSeekToPrevious() {
        videoDelegate?.takeIf { it.isVideoActive }?.onSeekToPrevious()
    }

    fun currentVideoTrack(): Track? =
        videoDelegate?.takeIf { it.isVideoActive }?.currentTrack()

    fun registerVideoDelegate(delegate: VideoPlaybackDelegate) {
        val changed = synchronized(delegateLock) {
            if (videoDelegate !== delegate) {
                videoDelegate = delegate
                true
            } else {
                false
            }
        }
        if (changed) {
            notifyRouteOrCommandsChanged()
        }
    }

    fun unregisterVideoDelegate(delegate: VideoPlaybackDelegate) {
        val removed = synchronized(delegateLock) {
            if (videoDelegate === delegate) {
                videoDelegate = null
                true
            } else {
                false
            }
        }
        if (removed) {
            notifyRouteOrCommandsChanged()
        }
    }

    fun notifyRouteOrCommandsChanged() {
        onRouteChanged?.invoke()
    }

    fun setIFrameVideoActive(active: Boolean) {
        val changed = _isIFrameVideoActive.getAndSet(active) != active
        if (changed) {
            notifyRouteOrCommandsChanged()
        }
    }

    fun reset() {
        val hadDelegate = synchronized(delegateLock) {
            val had = videoDelegate != null
            videoDelegate = null
            had
        }
        val iFrameChanged = _isIFrameVideoActive.getAndSet(false)
        if (hadDelegate || iFrameChanged) {
            notifyRouteOrCommandsChanged()
        }
    }
}
