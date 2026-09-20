package com.example.sonara.feature.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.domain.repository.DownloadRepository
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.client.MediaControllerState
import com.example.sonara.playback.controller.NextTrackDecision
import com.example.sonara.playback.controller.PlaybackQueueEngine
import com.example.sonara.playback.controller.PreviousTrackDecision
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encapsulates local presentation configurations and optimistic transitional state.
 */
private data class LocalPlayerState(
    val isShuffled: Boolean = false,
    val repeatMode: Int = 0, // 0 = off, 1 = repeat all, 2 = repeat one
    val volume: Float = 0.85f,
    val isMuted: Boolean = false,
    val transitionalTrack: Track? = null,
    val isResolvingStream: Boolean = false,
    val initialPositionMs: Long = 0L
)

/**
 * PlayerViewModel acts as the single playback coordinator for Sonara.
 *
 * Coordinates between presentation state (PlayerUiState), user intents,
 * stream resolution (with quality policy), offline downloaded media playback,
 * lookahead stream pre-resolution, and MediaControllerClient.
 */
class PlayerViewModel(
    private val client: MediaControllerClient,
    private val discoveryRepository: DiscoveryRepository,
    private val streamResolverPort: StreamResolverPort,
    private val downloadRepository: DownloadRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val audioOutputRepository: com.example.sonara.domain.repository.AudioOutputRepository? = null,
    private val historyRepository: com.example.sonara.domain.repository.HistoryRepository? = null,
    val queueEngine: PlaybackQueueEngine = PlaybackQueueEngine(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
    }

    private val _localState = MutableStateFlow(
        LocalPlayerState(
            isShuffled = queueEngine.isShuffled,
            repeatMode = queueEngine.repeatMode
        )
    )

    // Reactive map of trackId -> DownloadInfo
    private val _downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadInfo>> = _downloads.asStateFlow()

    // Observable audio output routing state (authoritative device list and active route)
    val audioOutputState: StateFlow<com.example.sonara.domain.model.AudioOutputState> =
        audioOutputRepository?.outputState
            ?: MutableStateFlow(com.example.sonara.domain.model.AudioOutputState()).asStateFlow()

    // Generation counter preventing stale async stream resolutions during rapid Next/Previous actions
    private val transitionGeneration = AtomicLong(0L)

    init {
        client.connect()

        // Convergence: Automatic playback completion uses the exact same advance pathway
        client.onPlaybackEnded = {
            Log.d(TAG, "onPlaybackEnded -> triggering skipToNext()")
            skipToNext()
        }

        // Observe downloads if repository available
        downloadRepository?.let { repo ->
            viewModelScope.launch {
                repo.observeAllDownloads().collect { list ->
                    _downloads.value = list.associateBy { it.trackId }
                }
            }
        }
    }

    /**
     * Presentation state projection mapped within ViewModel.
     * Combines raw MediaControllerState with LocalPlayerState.
     */
    val uiState: StateFlow<PlayerUiState> = combine(
        client.controllerState,
        _localState
    ) { rawState, localState ->
        mapToUiState(rawState, localState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = mapToUiState(client.controllerState.value, _localState.value)
    )

    private fun mapToUiState(
        raw: MediaControllerState,
        local: LocalPlayerState
    ): PlayerUiState {
        // If an optimistic transition is active or restored track is loaded but not yet reported by raw state
        if (local.transitionalTrack != null && raw.currentMediaItem?.mediaId != local.transitionalTrack.id) {
            return PlayerUiState(
                isConnected = raw.isConnected,
                isPlaying = false,
                isBuffering = local.isResolvingStream,
                trackId = local.transitionalTrack.id,
                trackTitle = local.transitionalTrack.title,
                artistName = local.transitionalTrack.artist,
                albumTitle = local.transitionalTrack.album ?: "",
                artworkUrl = local.transitionalTrack.artworkUrl,
                currentPositionMs = local.initialPositionMs,
                durationMs = local.transitionalTrack.durationMs,
                playbackState = if (local.isResolvingStream) androidx.media3.common.Player.STATE_BUFFERING else androidx.media3.common.Player.STATE_READY,
                isShuffled = local.isShuffled,
                repeatMode = local.repeatMode,
                volume = local.volume,
                isMuted = local.isMuted,
                errorMessage = null
            )
        }

        val metadata = raw.currentMediaItem?.mediaMetadata
        return PlayerUiState(
            isConnected = raw.isConnected,
            isPlaying = raw.isPlaying,
            isBuffering = raw.isBuffering,
            trackId = raw.currentMediaItem?.mediaId ?: "",
            trackTitle = metadata?.title?.toString() ?: "No Track Selected",
            artistName = metadata?.artist?.toString() ?: "Sonara Music",
            albumTitle = metadata?.albumTitle?.toString() ?: "",
            artworkUrl = metadata?.artworkUri?.toString(),
            currentPositionMs = raw.currentPositionMs,
            durationMs = raw.durationMs,
            playbackState = raw.playbackState,
            isShuffled = local.isShuffled,
            repeatMode = local.repeatMode,
            volume = local.volume,
            isMuted = local.isMuted,
            errorMessage = raw.errorMessage
        )
    }

    fun toggleShuffle() {
        val shuffled = queueEngine.toggleShuffle()
        _localState.update { it.copy(isShuffled = shuffled) }
        triggerLookaheadPreResolution()
    }

    fun toggleRepeatMode() {
        val mode = queueEngine.toggleRepeatMode()
        _localState.update { it.copy(repeatMode = mode) }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _localState.update { state ->
            state.copy(
                volume = clamped,
                isMuted = if (clamped > 0f && state.isMuted) false else state.isMuted
            )
        }
        client.setVolume(if (_localState.value.isMuted) 0f else clamped)
    }

    fun toggleMute() {
        val newMuted = !_localState.value.isMuted
        _localState.update { it.copy(isMuted = newMuted) }
        client.setVolume(if (newMuted) 0f else _localState.value.volume)
    }

    fun play() {
        val currentMediaId = client.controllerState.value.currentMediaItem?.mediaId
        val transitional = _localState.value.transitionalTrack
        if ((currentMediaId.isNullOrEmpty() || currentMediaId != transitional?.id) && transitional != null) {
            playTrack(transitional)
        } else {
            client.play()
        }
    }

    fun pause() {
        client.pause()
    }

    fun seekTo(positionMs: Long) {
        client.seekTo(positionMs)
    }

    /**
     * Restores a track session into the queue and presentation state without auto-starting audio playback.
     */
    fun restoreTrack(track: Track, initialPositionMs: Long = 0L, contextQueue: List<Track> = emptyList()) {
        queueEngine.setContext(track, contextQueue)
        _localState.update {
            it.copy(
                transitionalTrack = track,
                isResolvingStream = false,
                initialPositionMs = initialPositionMs
            )
        }
        prefetchRecommendationsAndPreResolve(track.id)
    }

    /**
     * Route playback audio output to a specific [AudioOutputDevice].
     */
    fun selectAudioOutput(device: com.example.sonara.domain.model.AudioOutputDevice) {
        audioOutputRepository?.selectDevice(device)
    }

    /**
     * Force a refresh of the connected audio device routes.
     */
    fun refreshAudioOutputs() {
        audioOutputRepository?.refreshDevices()
    }

    /**
     * Advances to next track in queue or resolves next recommendation candidate.
     */
    fun skipToNext(fromTrackId: String? = queueEngine.currentTrack?.id) {
        val gen = transitionGeneration.incrementAndGet()
        val decision = queueEngine.advance(fromTrackId = fromTrackId)
        Log.d(TAG, "skipToNext [fromTrackId=$fromTrackId, gen=$gen]: decision=$decision")

        when (decision) {
            is NextTrackDecision.PlayTrack -> {
                val currentMediaId = client.controllerState.value.currentMediaItem?.mediaId
                val isAlreadyCurrent = currentMediaId == decision.track.id || _localState.value.transitionalTrack?.id == decision.track.id
                if (fromTrackId != null && isAlreadyCurrent && (_localState.value.isResolvingStream || client.controllerState.value.isPlaying)) {
                    Log.d(TAG, "skipToNext [gen=$gen]: track ${decision.track.id} already active/resolving, skipping duplicate execution")
                    return
                }
                _localState.update { it.copy(transitionalTrack = decision.track, isResolvingStream = true, initialPositionMs = 0L) }
                viewModelScope.launch {
                    executeTrackPlay(decision.track, gen)
                }
            }

            is NextTrackDecision.ReplayCurrent -> {
                client.seekTo(0L)
                client.play()
            }

            is NextTrackDecision.NeedRecommendations -> {
                _localState.update { it.copy(isResolvingStream = true) }
                viewModelScope.launch {
                    val recResult = withContext(ioDispatcher) {
                        discoveryRepository.getRelatedTracks(decision.seedTrackId)
                    }

                    if (transitionGeneration.get() != gen) {
                        Log.d(TAG, "Dropping stale recommendation response [gen=$gen]")
                        return@launch
                    }

                    recResult.onSuccess { candidates ->
                        queueEngine.ingestRecommendations(candidates)
                        val nextDecision = queueEngine.advance()
                        if (nextDecision is NextTrackDecision.PlayTrack) {
                            _localState.update { it.copy(transitionalTrack = nextDecision.track, initialPositionMs = 0L) }
                            executeTrackPlay(nextDecision.track, gen)
                        } else {
                            Log.d(TAG, "No valid candidates after recommendation ingest -> stopping")
                            _localState.update { it.copy(isResolvingStream = false, transitionalTrack = null, initialPositionMs = 0L) }
                            client.pause()
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Recommendation fetch failed: ${error.message}")
                        _localState.update { it.copy(isResolvingStream = false, transitionalTrack = null, initialPositionMs = 0L) }
                        client.pause()
                    }
                }
            }

            is NextTrackDecision.StopPlayback -> {
                _localState.update { it.copy(isResolvingStream = false, transitionalTrack = null, initialPositionMs = 0L) }
                client.pause()
            }
        }
    }

    /**
     * Executes Previous action respecting 3000ms restart threshold and session backstack.
     */
    fun skipToPrevious() {
        val currentPos = client.controllerState.value.currentPositionMs
        val decision = queueEngine.previous(currentPos)
        val gen = transitionGeneration.incrementAndGet()
        Log.d(TAG, "skipToPrevious [pos=${currentPos}ms, gen=$gen]: decision=$decision")

        when (decision) {
            is PreviousTrackDecision.SeekToStart -> {
                client.seekTo(0L)
            }

            is PreviousTrackDecision.PlayTrack -> {
                _localState.update { it.copy(transitionalTrack = decision.track, isResolvingStream = true, initialPositionMs = 0L) }
                viewModelScope.launch {
                    executeTrackPlay(decision.track, gen)
                }
            }

            is PreviousTrackDecision.None -> {
                client.seekTo(0L)
            }
        }
    }

    /**
     * Pure, side-effect-free read of the track a forward advance would most likely surface next.
     * Mirrors the lookahead ordering used by [triggerLookaheadPreResolution] (explicit upcoming queue
     * first, then staged recommendations) and never mutates queue state.
     *
     * Returns null when the next track is not yet known — e.g. the queue is exhausted and the next
     * candidate must be fetched from the network (`NeedRecommendations`), or repeat-one would replay
     * the current track. The Expanded Player uses this to decide whether a real neighbour exists to
     * reveal underneath the current artwork during a swipe; a null result falls back to a plain swap.
     */
    fun peekNextTrack(): Track? =
        queueEngine.upcomingQueue.firstOrNull()
            ?: queueEngine.recommendationCache.firstOrNull()

    /**
     * Pure, side-effect-free read of the track a Previous action would pop from session history.
     * Never mutates queue state. Returns null when history is empty.
     *
     * Note: the 3000ms restart threshold is NOT applied here (this method has no position context).
     * Callers that want the "restart current vs. play previous" distinction must gate on playback
     * position themselves, exactly as [skipToPrevious] does via [PlaybackQueueEngine.previous].
     */
    fun peekPreviousTrack(): Track? =
        queueEngine.sessionBackStack.lastOrNull()

    /**
     * Plays a track with optional context queue, updating queue engine and resolving audio source.
     */
    fun playTrack(track: Track, contextQueue: List<Track> = emptyList()) {
        val gen = transitionGeneration.incrementAndGet()
        queueEngine.setContext(track, contextQueue)
        _localState.update {
            it.copy(
                transitionalTrack = track,
                isResolvingStream = true,
                initialPositionMs = if (it.transitionalTrack?.id == track.id) it.initialPositionMs else 0L
            )
        }

        viewModelScope.launch {
            executeTrackPlay(track, gen)
        }
    }

    /**
     * Toggle download state for a track:
     * - If not downloaded -> enqueues download
     * - If downloading -> cancels download
     * - If downloaded -> removes downloaded file
     */
        /**
     * Request an offline download for a track.
     */
    fun requestDownload(track: Track) {
        val downloadRepo = downloadRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            downloadRepo.enqueueDownload(track)
        }
    }

    /**
     * Cancel an ongoing download for a track.
     */
    fun cancelDownload(trackId: String) {
        val downloadRepo = downloadRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            downloadRepo.cancelDownload(trackId)
        }
    }

    /**
     * Remove an offline downloaded file and its metadata.
     */
    fun removeDownload(trackId: String) {
        val downloadRepo = downloadRepository ?: return
        viewModelScope.launch(ioDispatcher) {
            downloadRepo.removeDownload(trackId)
        }
    }

    private suspend fun executeTrackPlay(track: Track, generation: Long) {
        val seekPos = if (_localState.value.transitionalTrack?.id == track.id) {
            _localState.value.initialPositionMs
        } else {
            0L
        }

        // 1. Check if track is available as an offline local download (0ms instant playback)
        val localFilePath = downloadRepository?.getDownloadedFileUri(track.id)
        if (localFilePath != null && localFilePath.isNotBlank()) {
            if (transitionGeneration.get() != generation) return
            Log.i(TAG, "Playing offline downloaded track for ${track.title} at $localFilePath")
            client.playTrack(track, "file://$localFilePath")
            if (seekPos > 0L) {
                client.seekTo(seekPos)
            }
            _localState.update { it.copy(isResolvingStream = false, transitionalTrack = null, initialPositionMs = 0L) }
            prefetchRecommendationsAndPreResolve(track.id)
            viewModelScope.launch(ioDispatcher) {
                try {
                    historyRepository?.recordHistory(track, completed = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record history: ${e.message}")
                }
            }
            return
        }

        // 2. Resolve remote stream respecting streaming quality preference
        val quality = settingsRepository?.getUserPreferences()?.firstOrNull()?.streamingQuality ?: AudioQuality.AUTO
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0
        val streamResult = withContext(ioDispatcher) {
            streamResolverPort.resolveStream(
                trackId = track.id,
                quality = quality,
                title = track.title,
                artist = track.artist,
                durationSeconds = durationSec
            )
        }

        if (transitionGeneration.get() != generation) {
            Log.d(TAG, "Dropping stale stream resolution for ${track.title} [gen=$generation]")
            return
        }

        streamResult.onSuccess { streamInfo ->
            client.playTrack(track, streamInfo.streamUrl)
            if (seekPos > 0L) {
                client.seekTo(seekPos)
            }
            _localState.update { it.copy(isResolvingStream = false, transitionalTrack = null, initialPositionMs = 0L) }
            prefetchRecommendationsAndPreResolve(track.id)
            viewModelScope.launch(ioDispatcher) {
                try {
                    historyRepository?.recordHistory(track, completed = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record history: ${e.message}")
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Stream resolution failed for ${track.title}: ${error.message}")
            _localState.update { it.copy(isResolvingStream = false, transitionalTrack = null, initialPositionMs = 0L) }
        }
    }

    private fun prefetchRecommendationsAndPreResolve(seedTrackId: String) {
        triggerLookaheadPreResolution()

        if (seedTrackId.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            try {
                discoveryRepository.getRelatedTracks(seedTrackId).onSuccess { candidates ->
                    queueEngine.ingestRecommendations(candidates)
                    triggerLookaheadPreResolution()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Background recommendation prefetch ignored error: ${e.message}")
            }
        }
    }

    private fun triggerLookaheadPreResolution() {
        val nextCandidate = queueEngine.upcomingQueue.firstOrNull()
            ?: queueEngine.recommendationCache.firstOrNull()

        if (nextCandidate != null && nextCandidate.id.isNotBlank()) {
            viewModelScope.launch(ioDispatcher) {
                try {
                    val quality = settingsRepository?.getUserPreferences()?.firstOrNull()?.streamingQuality ?: AudioQuality.AUTO
                    val durationSec = if (nextCandidate.durationMs > 0) (nextCandidate.durationMs / 1000).toInt() else 0
                    streamResolverPort.resolveStream(
                        trackId = nextCandidate.id,
                        quality = quality,
                        title = nextCandidate.title,
                        artist = nextCandidate.artist,
                        durationSeconds = durationSec
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Lookahead pre-resolution non-fatal error: ${e.message}")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        client.onPlaybackEnded = null
        client.disconnect()
    }
}
