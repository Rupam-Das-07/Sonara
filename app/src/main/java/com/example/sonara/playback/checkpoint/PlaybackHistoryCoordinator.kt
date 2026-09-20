package com.example.sonara.playback.checkpoint

import android.util.Log
import androidx.media3.common.Player
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.playback.client.MediaControllerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single authoritative history writer for Sonara Android.
 * Records history events exclusively when playback qualifies (e.g. >= 15s or completed).
 * UI components are strictly forbidden from writing history independently.
 */
class PlaybackHistoryCoordinator(
    private val client: MediaControllerClient,
    private val historyRepository: HistoryRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val qualificationThresholdMs: Long = 15000L // Recommended initial default, product-tunable
) {
    companion object {
        private const val TAG = "PlaybackHistory"
    }

    private var activeTrackId: String? = null
    private var activePlayTimeMs: Long = 0L
    private var historyRecordedForCurrentTrack: Boolean = false
    private var tickerJob: Job? = null

    fun start() {
        // Observe track transitions and completion
        scope.launch {
            client.controllerState.collect { state ->
                val currentId = state.currentMediaItem?.mediaId
                if (!currentId.isNullOrEmpty() && currentId != activeTrackId) {
                    // New track started
                    activeTrackId = currentId
                    activePlayTimeMs = 0L
                    historyRecordedForCurrentTrack = false
                    // Record immediately in history repository upon track transition
                    recordCurrentTrack(state, completed = false)
                }

                if (state.playbackState == Player.STATE_ENDED && !historyRecordedForCurrentTrack && currentId != null) {
                    recordCurrentTrack(state, completed = true)
                }
            }
        }

        // Ticker accumulating active play time
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val state = client.controllerState.value
                if (state.isPlaying && activeTrackId != null && !historyRecordedForCurrentTrack) {
                    activePlayTimeMs += 1000L
                    if (activePlayTimeMs >= qualificationThresholdMs) {
                        recordCurrentTrack(state, completed = false)
                    }
                }
            }
        }
    }

    private suspend fun recordCurrentTrack(state: com.example.sonara.playback.client.MediaControllerState, completed: Boolean) {
        val mediaItem = state.currentMediaItem ?: return
        val metadata = mediaItem.mediaMetadata
        val track = Track(
            id = mediaItem.mediaId,
            title = metadata.title?.toString() ?: "Unknown Title",
            artist = metadata.artist?.toString() ?: "Sonara Music",
            album = metadata.albumTitle?.toString() ?: "",
            artworkUrl = metadata.artworkUri?.toString(),
            durationMs = state.durationMs
        )
        if (completed) {
            historyRecordedForCurrentTrack = true
        }
        Log.i(TAG, "Authoritative history recorded for: ${track.title} (completed=$completed)")
        historyRepository.recordHistory(track, completed = completed)
    }

    fun stop() {
        scope.cancel()
    }
}
