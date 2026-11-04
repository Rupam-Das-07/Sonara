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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private var observationJob: Job? = null
    private var periodicJob: Job? = null
    private var lastRecordedTrackId: String? = null
    private var lastRecordedPosition: Long = -1L
    private var lastRecordedSessionId: String? = null
    private var sessionIdProvider: ((String) -> String?)? = null
    private val writeMutex = Mutex()

    /**
     * Supplies the service-owned identity for the currently active track. Kept optional so
     * legacy/test construction sites continue to checkpoint without a service coordinator.
     */
    fun setSessionIdProvider(provider: ((String) -> String?)?) {
        sessionIdProvider = provider
    }

    fun start() {
        if (observationJob?.isActive == true || periodicJob?.isActive == true) return

        // 1. Reactive observation for terminal/pause transitions
        observationJob = scope.launch {
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
        writeMutex.withLock {
            val existing = settingsRepository.getPlaybackSession()
            // If the service has not connected yet, retain the persisted identity only when
            // this is still the same track. A coordinator-provided null means there is no
            // active session, so a closed/replayed session cannot inherit an old id.
            val provider = sessionIdProvider
            // A null provider result can mean either that the coordinator has not observed the
            // restored player state yet or that lifecycle teardown is in progress. Keep the
            // matching persisted id for that short boundary; genuine close invalidation uses
            // clearPersistedSessionIdentity(expectedSessionId), which is race-safe.
            val sessionId = provider?.invoke(trackId)
                ?: existing?.takeIf { it.lastTrackId == trackId }?.playbackSessionId

            if (trackId == lastRecordedTrackId &&
                positionMs == lastRecordedPosition &&
                sessionId == lastRecordedSessionId
            ) {
                return
            }

            lastRecordedTrackId = trackId
            lastRecordedPosition = positionMs
            lastRecordedSessionId = sessionId
            Log.d(TAG, "Saving playback checkpoint: track=$trackId, pos=${positionMs}ms, session=$sessionId")
            settingsRepository.savePlaybackSession(
                PlaybackSessionSnapshot(
                    lastTrackId = trackId,
                    lastPositionMs = positionMs,
                    queueTrackIds = listOf(trackId), // In Phase 2, single track / current queue IDs
                    playbackSessionId = sessionId
                )
            )
        }
    }

    /** Clears only the persisted identity for a genuine session close; stop() never calls this. */
    fun clearPersistedSessionIdentity(trackId: String? = null, expectedSessionId: String? = null) {
        // The Activity may already have stopped/cancelled this engine while the service keeps
        // running in the background. Use a one-shot fallback scope in that case so a genuine
        // close still invalidates the id; service lifecycle stop itself never invokes this.
        val cleanupScope = if (scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
            scope
        } else {
            CoroutineScope(Dispatchers.IO)
        }
        cleanupScope.launch {
            writeMutex.withLock {
                val current = settingsRepository.getPlaybackSession() ?: return@withLock
                if ((trackId == null || current.lastTrackId == trackId) &&
                    (expectedSessionId == null || current.playbackSessionId == expectedSessionId)
                ) {
                    settingsRepository.savePlaybackSession(current.copy(playbackSessionId = null))
                    lastRecordedSessionId = null
                }
            }
        }
    }

    fun stop() {
        observationJob?.cancel()
        observationJob = null
        periodicJob?.cancel()
        periodicJob = null
    }
}
