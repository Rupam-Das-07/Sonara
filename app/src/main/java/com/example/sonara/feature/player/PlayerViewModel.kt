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
import com.example.sonara.playback.controller.TransitionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.CancellationException
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
    val initialPositionMs: Long = 0L,
    val errorMessage: String? = null
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
    val transitionManager: TransitionManager = TransitionManager(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val LOOKAHEAD_DEBOUNCE_MS = 1500L
        private const val RATE_LIMIT_COOLDOWN_MS = 4000L
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

    // Active playback job for cooperative cancellation of stale async resolution
    private var activePlaybackJob: Job? = null

    // Finding 1.E: Dedicated job for debounced speculative lookahead pre-resolution
    private var lookaheadJob: Job? = null

    // Finding 1.E: Rate-limit retry cooldown job & state to throttle tight retry loops
    private var rateLimitCooldownJob: Job? = null
    private var rateLimitCooldownActive: Boolean = false

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

        // Reconcile transitionalTrack once MediaControllerClient confirms target track playback
        viewModelScope.launch {
            client.controllerState.collect { state ->
                val currentMediaId = state.currentMediaItem?.mediaId
                val transitional = _localState.value.transitionalTrack
                if (currentMediaId != null && transitional != null && currentMediaId == transitional.id) {
                    _localState.update { it.copy(transitionalTrack = null, initialPositionMs = 0L, errorMessage = null) }
                }
                if (state.errorMessage != null && transitional != null) {
                    _localState.update { it.copy(transitionalTrack = null, isResolvingStream = false, initialPositionMs = 0L) }
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
            val isError = local.errorMessage != null
            val playbackState = when {
                isError -> androidx.media3.common.Player.STATE_IDLE
                local.isResolvingStream -> androidx.media3.common.Player.STATE_BUFFERING
                else -> androidx.media3.common.Player.STATE_READY
            }
            return PlayerUiState(
                isConnected = raw.isConnected,
                isPlaying = false,
                isBuffering = if (isError) false else local.isResolvingStream,
                trackId = local.transitionalTrack.id,
                trackTitle = local.transitionalTrack.title,
                artistName = local.transitionalTrack.artist,
                albumTitle = local.transitionalTrack.album ?: "",
                artworkUrl = local.transitionalTrack.artworkUrl,
                currentPositionMs = local.initialPositionMs,
                durationMs = local.transitionalTrack.durationMs,
                playbackState = playbackState,
                isShuffled = local.isShuffled,
                repeatMode = local.repeatMode,
                volume = local.volume,
                isMuted = local.isMuted,
                errorMessage = local.errorMessage
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
            errorMessage = local.errorMessage ?: raw.errorMessage
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
            if (_localState.value.isResolvingStream) {
                Log.d(TAG, "play() ignored: already resolving stream")
                return
            }
            if (rateLimitCooldownActive || isRateLimitError(_localState.value.errorMessage)) {
                if (rateLimitCooldownActive) {
                    Log.w(TAG, "play() throttled: rate limit cooldown is active")
                    return
                }
            }
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
                initialPositionMs = initialPositionMs,
                errorMessage = null
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
        lookaheadJob?.cancel()
        lookaheadJob = null
        rateLimitCooldownJob?.cancel()
        rateLimitCooldownActive = false
        val gen = transitionManager.nextGeneration()
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
                _localState.update { it.copy(transitionalTrack = decision.track, isResolvingStream = true, initialPositionMs = 0L, errorMessage = null) }
                activePlaybackJob?.cancel()
                activePlaybackJob = viewModelScope.launch {
                    executeTrackPlay(decision.track, gen)
                }
            }

            is NextTrackDecision.ReplayCurrent -> {
                client.seekTo(0L)
                client.play()
            }

            is NextTrackDecision.NeedRecommendations -> {
                _localState.update { it.copy(isResolvingStream = true, errorMessage = null) }
                activePlaybackJob?.cancel()
                activePlaybackJob = viewModelScope.launch {
                    val recResult = withContext(ioDispatcher) {
                        discoveryRepository.getRelatedTracks(decision.seedTrackId)
                    }

                    if (!transitionManager.isAuthoritative(gen)) {
                        Log.d(TAG, "Dropping stale recommendation response [gen=$gen]")
                        return@launch
                    }

                    recResult.onSuccess { candidates ->
                        queueEngine.ingestRecommendations(candidates)
                        val nextDecision = queueEngine.advance()
                        if (nextDecision is NextTrackDecision.PlayTrack) {
                            _localState.update { it.copy(transitionalTrack = nextDecision.track, initialPositionMs = 0L, errorMessage = null) }
                            executeTrackPlay(nextDecision.track, gen)
                        } else {
                            Log.d(TAG, "No valid candidates after recommendation ingest -> stopping")
                            if (transitionManager.isAuthoritative(gen)) {
                                _localState.update { it.copy(isResolvingStream = false, transitionalTrack = null, initialPositionMs = 0L) }
                                client.pause()
                            }
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Recommendation fetch failed: ${error.message}")
                        if (transitionManager.isAuthoritative(gen)) {
                            _localState.update {
                                it.copy(
                                    isResolvingStream = false,
                                    errorMessage = error.message ?: "Failed to fetch recommendations"
                                )
                            }
                            client.pause()
                        }
                    }
                }
            }

            is NextTrackDecision.StopPlayback -> {
                activePlaybackJob?.cancel()
                _localState.update { it.copy(isResolvingStream = false, transitionalTrack = null, initialPositionMs = 0L) }
                client.pause()
            }
        }
    }

    /**
     * Executes Previous action respecting 3000ms restart threshold and session backstack.
     */
    fun skipToPrevious() {
        lookaheadJob?.cancel()
        lookaheadJob = null
        rateLimitCooldownJob?.cancel()
        rateLimitCooldownActive = false
        val currentPos = client.controllerState.value.currentPositionMs
        val decision = queueEngine.previous(currentPos)
        val gen = transitionManager.nextGeneration()
        Log.d(TAG, "skipToPrevious [pos=${currentPos}ms, gen=$gen]: decision=$decision")

        when (decision) {
            is PreviousTrackDecision.SeekToStart -> {
                client.seekTo(0L)
            }

            is PreviousTrackDecision.PlayTrack -> {
                _localState.update { it.copy(transitionalTrack = decision.track, isResolvingStream = true, initialPositionMs = 0L, errorMessage = null) }
                activePlaybackJob?.cancel()
                activePlaybackJob = viewModelScope.launch {
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
     * Inserts [track] immediately after the currently active track in [PlaybackQueueEngine].
     * If no track is currently playing or loaded, starts playback of [track] immediately.
     */
    fun playNext(track: Track) {
        val currentMediaId = client.controllerState.value.currentMediaItem?.mediaId
        val currTrack = queueEngine.currentTrack
        if (currTrack == null && currentMediaId.isNullOrEmpty()) {
            playTrack(track)
        } else {
            queueEngine.playNext(track)
            triggerLookaheadPreResolution()
        }
    }

    /**
     * Plays a track with optional context queue, updating queue engine and resolving audio source.
     */
    fun playTrack(track: Track, contextQueue: List<Track> = emptyList()) {
        lookaheadJob?.cancel()
        lookaheadJob = null
        val gen = transitionManager.nextGeneration()
        queueEngine.setContext(track, contextQueue)
        _localState.update {
            it.copy(
                transitionalTrack = track,
                isResolvingStream = true,
                initialPositionMs = if (it.transitionalTrack?.id == track.id) it.initialPositionMs else 0L,
                errorMessage = null
            )
        }

        activePlaybackJob?.cancel()
        activePlaybackJob = viewModelScope.launch {
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
        lookaheadJob?.cancel()
        lookaheadJob = null

        val seekPos = if (_localState.value.transitionalTrack?.id == track.id) {
            _localState.value.initialPositionMs
        } else {
            0L
        }

        // 1. Check if track is available as an offline local download (0ms instant playback)
        val localFilePath = downloadRepository?.getDownloadedFileUri(track.id)
        if (localFilePath != null && localFilePath.isNotBlank()) {
            if (!transitionManager.isAuthoritative(generation)) return
            Log.i(TAG, "Playing offline downloaded track for ${track.title} at $localFilePath")
            client.playTrack(track, "file://$localFilePath", generation)
            if (seekPos > 0L) {
                client.seekTo(seekPos)
            }
            _localState.update { it.copy(isResolvingStream = false) }
            if (client.controllerState.value.currentMediaItem?.mediaId == track.id) {
                _localState.update { it.copy(transitionalTrack = null, initialPositionMs = 0L) }
            }
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

        if (!transitionManager.isAuthoritative(generation)) {
            Log.d(TAG, "Dropping stale stream resolution for ${track.title} [gen=$generation]")
            return
        }

        streamResult.onSuccess { streamInfo ->
            if (!transitionManager.isAuthoritative(generation)) {
                Log.d(TAG, "Dropping stale stream commit for ${track.title} [gen=$generation]")
                return@onSuccess
            }
            client.playTrack(track, streamInfo.streamUrl, generation)
            if (seekPos > 0L) {
                client.seekTo(seekPos)
            }
            _localState.update { it.copy(isResolvingStream = false) }
            if (client.controllerState.value.currentMediaItem?.mediaId == track.id) {
                _localState.update { it.copy(transitionalTrack = null, initialPositionMs = 0L) }
            }
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
            if (isRateLimitError(error)) {
                rateLimitCooldownActive = true
                rateLimitCooldownJob?.cancel()
                rateLimitCooldownJob = viewModelScope.launch {
                    delay(RATE_LIMIT_COOLDOWN_MS)
                    rateLimitCooldownActive = false
                }
            }
            if (transitionManager.isAuthoritative(generation)) {
                client.pause()
                _localState.update {
                    it.copy(
                        isResolvingStream = false,
                        transitionalTrack = track,
                        errorMessage = error.message ?: "Failed to resolve stream for ${track.title}"
                    )
                }
            }
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
            lookaheadJob?.cancel()
            lookaheadJob = viewModelScope.launch(ioDispatcher) {
                try {
                    delay(LOOKAHEAD_DEBOUNCE_MS)
                    val candidate = queueEngine.upcomingQueue.firstOrNull()
                        ?: queueEngine.recommendationCache.firstOrNull()
                        ?: nextCandidate

                    val quality = settingsRepository?.getUserPreferences()?.firstOrNull()?.streamingQuality ?: AudioQuality.AUTO
                    val durationSec = if (candidate.durationMs > 0) (candidate.durationMs / 1000).toInt() else 0
                    streamResolverPort.resolveStream(
                        trackId = candidate.id,
                        quality = quality,
                        title = candidate.title,
                        artist = candidate.artist,
                        durationSeconds = durationSec
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Lookahead pre-resolution non-fatal error: ${e.message}")
                }
            }
        }
    }

    private fun isRateLimitError(error: Throwable?): Boolean {
        val msg = error?.message ?: return false
        return msg.contains("429") ||
                msg.contains("Too Many Requests", ignoreCase = true) ||
                msg.contains("rate limit", ignoreCase = true)
    }

    private fun isRateLimitError(errorMessage: String?): Boolean {
        val msg = errorMessage ?: return false
        return msg.contains("429") ||
                msg.contains("Too Many Requests", ignoreCase = true) ||
                msg.contains("rate limit", ignoreCase = true)
    }

    override fun onCleared() {
        super.onCleared()
        activePlaybackJob?.cancel()
        lookaheadJob?.cancel()
        rateLimitCooldownJob?.cancel()
        client.onPlaybackEnded = null
        client.disconnect()
    }
}
