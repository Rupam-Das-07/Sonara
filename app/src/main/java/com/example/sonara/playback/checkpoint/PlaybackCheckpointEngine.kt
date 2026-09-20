package com.example.sonara.playback.checkpoint

import android.util.Log
import androidx.media3.common.Player
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.repository.SettingsRepository
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
 * Periodically and reactively checkpoints playback position to DataStore.
 * Consumes the authoritative projection from MediaControllerClient without direct ExoPlayer access.
 */
class PlaybackCheckpointEngine(
    private val client: MediaControllerClient,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val checkpointIntervalMs: Long = 5000L // Recommended initial default, product-tunable
) {
    companion object {
        private const val TAG = "PlaybackCheckpoint"
    }

    private var periodicJob: Job? = null
    private var lastRecordedPosition: Long = -1L

    fun start() {
        // 1. Reactive observation for terminal/pause transitions
        scope.launch {
            client.controllerState.collect { state ->
                val trackId = state.currentMediaItem?.mediaId
                if (trackId != null) {
                    if (state.playbackState == Player.STATE_READY && !state.isPlaying) {
                        // Immediately checkpoint on Pause
                        saveCheckpoint(trackId, state.currentPositionMs)
                    }
                }
            }
        }

        // 2. Periodic throttled checkpoint during active playback
        periodicJob = scope.launch {
            while (isActive) {
                delay(checkpointIntervalMs)
                val state = client.controllerState.value
                val trackId = state.currentMediaItem?.mediaId
                if (state.isPlaying && trackId != null) {
                    saveCheckpoint(trackId, state.currentPositionMs)
                }
            }
        }
    }

    private suspend fun saveCheckpoint(trackId: String, positionMs: Long) {
        if (positionMs != lastRecordedPosition) {
            lastRecordedPosition = positionMs
            Log.d(TAG, "Saving playback checkpoint: track=$trackId, pos=${positionMs}ms")
            settingsRepository.savePlaybackSession(
                PlaybackSessionSnapshot(
                    lastTrackId = trackId,
                    lastPositionMs = positionMs,
                    queueTrackIds = listOf(trackId) // In Phase 2, single track / current queue IDs
                )
            )
        }
    }

    fun stop() {
        scope.cancel()
    }
}
