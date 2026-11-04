package com.example.sonara.feature.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.soundcloud.SoundcloudSourceIdentity
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.domain.repository.DownloadRepository
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.core.util.MediaTitleFormatter
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
    val errorMessage: String? = null,
    val transitionGeneration: Long? = null
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
    val queueEngine: PlaybackQueueEngine = PlaybackQueueEngine(),
    val transitionManager: TransitionManager = TransitionManager(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val LOOKAHEAD_DEBOUNCE_MS = 1500L
        private const val RATE_LIMIT_COOLDOWN_MS = 4000L

        // Point 2 — Area 1: backstop that releases a stuck consume-in-flight guard if the
        // controller never reports the expected track transition (pathological; the guard is
        // normally cleared within tens of ms by the controllerState reconcile collector).
        private const val CONSUME_INFLIGHT_TIMEOUT_MS = 3000L
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

    // Point 2 — Area 1: true from the moment a gapless preload-consume seek is dispatched until
    // the controller reports the target track live (or the backstop timeout fires). While true,
    // Next/Previous intents are DROPPED so a racing dispatch cannot desync the authoritative queue.
    private var consumeInFlight: Boolean = false
    private var consumeTimeoutJob: Job? = null

    init {
        client.connect()

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
                    // Target track is now live on the controller: a pending preload-consume (if any)
                    // has landed, so release the in-flight guard and cancel its backstop.
                    if (state.requestFailureGeneration == null ||
                        state.requestFailureGeneration == _localState.value.transitionGeneration
                    ) {
                        clearConsumeInFlight()
                    }
                    _localState.update {
                        it.copy(
                            transitionalTrack = null,
                            initialPositionMs = 0L,
                            errorMessage = null,
                            transitionGeneration = null
                        )
                    }
                }
                if (state.errorMessage != null && transitional != null) {
                    // Controller surfaced an error for the pending transition: release the guard too,
                    // otherwise a failed consume would wedge skip handling until the timeout fires.
                    clearConsumeInFlight()
                    if (state.requestFailureGeneration != null &&
                        state.requestFailureGeneration == _localState.value.transitionGeneration
                    ) {
                        _localState.update {
                            it.copy(
                                isResolvingStream = false,
                                errorMessage = state.errorMessage
                            )
                        }
                    }
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
                displayTitle = MediaTitleFormatter.format(
                    local.transitionalTrack.title,
                    local.transitionalTrack.artist
                ),
                artistName = local.transitionalTrack.artist,
                albumTitle = local.transitionalTrack.album ?: "",
                artworkUrl = local.transitionalTrack.artworkUrl,
                currentPositionMs = local.initialPositionMs,
                isPositionAvailable = true,
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
        val rawTitle = metadata?.title?.toString() ?: "No Track Selected"
        val rawArtist = metadata?.artist?.toString() ?: "Sonara Music"
        return PlayerUiState(
            isConnected = raw.isConnected,
            isPlaying = raw.isPlaying,
            isBuffering = raw.isBuffering,
            trackId = raw.currentMediaItem?.mediaId ?: "",
            trackTitle = rawTitle,
            displayTitle = MediaTitleFormatter.format(rawTitle, rawArtist),
            artistName = rawArtist,
            albumTitle = metadata?.albumTitle?.toString() ?: "",
            artworkUrl = metadata?.artworkUri?.toString(),
            currentPositionMs = raw.currentPositionMs,
            isPositionAvailable = raw.currentMediaItem != null,
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
     *
     * Provenance is deliberately NOT restored. The persisted snapshot
     * ([com.example.sonara.domain.model.PlaybackSessionSnapshot]) carries only
     * `lastTrackId`, `lastPositionMs` and `queueTrackIds` — no origin and no session id —
     * so there is nothing trustworthy to recover. Rather than invent continuity from the
     * track id or a timestamp, a restored context fails closed to
     * [PlaybackOrigin.LEGACY_UNKNOWN] and the coordinator mints a fresh session id when
     * playback actually resumes.
     */
    fun restoreTrack(track: Track, initialPositionMs: Long = 0L, contextQueue: List<Track> = emptyList()) {
        queueEngine.setContext(track, contextQueue, origin = PlaybackOrigin.LEGACY_UNKNOWN)
        _localState.update {
            it.copy(
                transitionalTrack = track,
                isResolvingStream = false,
                initialPositionMs = initialPositionMs,
                errorMessage = null,
                transitionGeneration = null
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
        // Point 2 — Area 1: never begin a new skip while a gapless preload-consume is still
        // settling. A racing setMediaItem() or a second seek during that window would fire an
        // async SEEK transition that desyncs the authoritative queue, so we DROP (not fall back).
        if (consumeInFlight) {
            Log.d(TAG, "skipToNext dropped: preload-consume still in flight")
            return
        }
        Log.d(TAG, "LATENCY_T0 next intent: fromId=$fromTrackId t=${System.currentTimeMillis()}")
        lookaheadJob?.cancel()
        lookaheadJob = null
        rateLimitCooldownJob?.cancel()
        rateLimitCooldownActive = false

        // Point 2 — Area 1: snapshot the consume preconditions that depend on PRE-advance
        // queue/controller state (advance() mutates the queue). The authoritative-target identity
        // check is completed AFTER advance, against the decision it actually produces.
        //  - presentation settled (no optimistic transition, not resolving)
        //  - the advance originates from the genuinely-current track
        //  - the controller is actually sitting on that same current track (no desync)
        val consumeEligible =
            _localState.value.transitionalTrack == null &&
                !_localState.value.isResolvingStream &&
                fromTrackId != null &&
                fromTrackId == queueEngine.currentTrack?.id &&
                client.currentMediaId() == queueEngine.currentTrack?.id

        val gen = transitionManager.nextGeneration()
        val decision = queueEngine.advance(fromTrackId = fromTrackId)
        Log.d(TAG, "skipToNext [fromTrackId=$fromTrackId, gen=$gen]: decision=$decision")

        // Point 2 — Area 1: if the service has already preloaded EXACTLY the authoritative next
        // track (verified by media-id identity against the ExoPlayer timeline's index+1 item),
        // consume it gaplessly with a controller seek instead of a fresh resolve + setMediaItem.
        // `decision is PlayTrack` already excludes repeat-one (ReplayCurrent); the id-identity
        // check excludes the repeat-all snapshot-refill case (the preload reflects the non-repeat
        // next). Any mismatch / stale / absent preload falls through to the unchanged resolve path.
        if (consumeEligible &&
            decision is NextTrackDecision.PlayTrack &&
            client.peekNextMediaId() == decision.track.id
        ) {
            Log.d(TAG, "skipToNext [gen=$gen]: consuming preloaded ${decision.track.id} via gapless seekToNext")
            _localState.update {
                it.copy(
                    transitionalTrack = decision.track,
                    isResolvingStream = false,
                    initialPositionMs = 0L,
                    errorMessage = null,
                    transitionGeneration = gen
                )
            }
            consumeInFlight = true
            // Abandon any in-flight resolve job; the preloaded item needs no resolution.
            activePlaybackJob?.cancel()
            // Routes through SonaraForwardingPlayer.seekToNextMediaItem(): because the timeline
            // already holds this item at index+1, it is consumed instantly (gapless). The service's
            // resulting SEEK transition sees the shared queue already advanced to this id, so its
            // handleTrackTransition guard does NOT double-advance.
            client.skipToNext()
            // Warm the resolver cache for the NEW next track (same debounced lookahead mechanism the
            // fallback path uses via prefetch; does not change lookahead timing/depth/concurrency).
            triggerLookaheadPreResolution()
            armConsumeTimeout()
            return
        }

        when (decision) {
            is NextTrackDecision.PlayTrack -> {
                val currentMediaId = client.controllerState.value.currentMediaItem?.mediaId
                val isAlreadyCurrent = currentMediaId == decision.track.id || _localState.value.transitionalTrack?.id == decision.track.id
                if (fromTrackId != null && isAlreadyCurrent && (_localState.value.isResolvingStream || client.controllerState.value.isPlaying)) {
                    Log.d(TAG, "skipToNext [gen=$gen]: track ${decision.track.id} already active/resolving, skipping duplicate execution")
                    return
                }
                // The fallback path resolves a new URL asynchronously, so stop the old item
                // before presenting the target track. The preloaded path above intentionally
                // remains gapless and does not pause.
                client.pause()
                _localState.update {
                    it.copy(
                        transitionalTrack = decision.track,
                        isResolvingStream = true,
                        initialPositionMs = 0L,
                        errorMessage = null,
                        transitionGeneration = gen
                    )
                }
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
                client.pause()
                _localState.update {
                    it.copy(
                        isResolvingStream = true,
                        errorMessage = null,
                        transitionGeneration = gen
                    )
                }
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
                            _localState.update {
                                it.copy(
                                    transitionalTrack = nextDecision.track,
                                    initialPositionMs = 0L,
                                    errorMessage = null,
                                    transitionGeneration = gen
                                )
                            }
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
        // Point 2 — Area 1: drop Previous while a gapless preload-consume is settling, so a
        // Next-then-Previous race cannot inject a setMediaItem() that desyncs the pending seek.
        if (consumeInFlight) {
            Log.d(TAG, "skipToPrevious dropped: preload-consume still in flight")
            return
        }
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
                client.pause()
                _localState.update {
                    it.copy(
                        transitionalTrack = decision.track,
                        isResolvingStream = true,
                        initialPositionMs = 0L,
                        errorMessage = null,
                        transitionGeneration = gen
                    )
                }
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
    fun playNext(
        track: Track,
        origin: PlaybackOrigin = PlaybackOrigin.LEGACY_UNKNOWN
    ) {
        val currentMediaId = client.controllerState.value.currentMediaItem?.mediaId
        val currTrack = queueEngine.currentTrack
        if (currTrack == null && currentMediaId.isNullOrEmpty()) {
            playTrack(track, origin = origin)
        } else {
            // Play Next is an explicit intent with its own provenance. Never let the queued
            // entry inherit the currently active context (which may be unrelated).
            queueEngine.playNext(track, origin = origin)
            triggerLookaheadPreResolution()
        }
    }

    /**
     * Plays a track with optional context queue, updating queue engine and resolving audio source.
     *
     * [origin] is the Phase 2B provenance of this deliberate play intent. It is passed
     * explicitly by the initiating UI — never inferred from navigation state or the
     * visible screen — and defaults to [PlaybackOrigin.LEGACY_UNKNOWN] so every existing
     * call site remains source-compatible and ineligible.
     */
    fun playTrack(
        track: Track,
        contextQueue: List<Track> = emptyList(),
        origin: PlaybackOrigin = PlaybackOrigin.LEGACY_UNKNOWN
    ) {
        Log.d(TAG, "LATENCY_T0 play intent: trackId=${track.id} t=${System.currentTimeMillis()}")
        lookaheadJob?.cancel()
        lookaheadJob = null
        val gen = transitionManager.nextGeneration()
        // A manual selection is a real transition even when resolution is remote. Pause the
        // currently loaded item before changing presentation state so the previous track cannot
        // continue underneath the optimistic target.
        client.pause()
        queueEngine.setContext(track, contextQueue, origin = origin)
        _localState.update {
            it.copy(
                transitionalTrack = track,
                isResolvingStream = true,
                initialPositionMs = if (it.transitionalTrack?.id == track.id) it.initialPositionMs else 0L,
                errorMessage = null,
                transitionGeneration = gen
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
        if (SoundcloudSourceIdentity.isSoundcloudTrackId(track.id)) {
            Log.w(TAG, "Offline downloading is not supported for SoundCloud track: ${track.id}")
            return
        }
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

        try {

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
            val mimeType = streamInfo.format.takeIf { it == MimeTypes.APPLICATION_M3U8 }
            if (mimeType != null) {
                client.playTrack(track, streamInfo.streamUrl, generation, mimeType)
            } else {
                client.playTrack(track, streamInfo.streamUrl, generation)
            }
            if (seekPos > 0L) {
                client.seekTo(seekPos)
            }
            _localState.update { it.copy(isResolvingStream = false) }
            if (client.controllerState.value.currentMediaItem?.mediaId == track.id) {
                _localState.update { it.copy(transitionalTrack = null, initialPositionMs = 0L) }
            }
            prefetchRecommendationsAndPreResolve(track.id)
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failTrackTransition(track, generation, error)
        }
    }

    private fun failTrackTransition(track: Track, generation: Long, error: Throwable) {
        if (!transitionManager.isAuthoritative(generation)) return
        Log.e(TAG, "Track transition failed for ${track.title}: ${error.message}")
        client.pause()
        _localState.update {
            it.copy(
                isResolvingStream = false,
                transitionalTrack = track,
                errorMessage = error.message ?: "Failed to play ${track.title}",
                transitionGeneration = generation
            )
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

    /**
     * Point 2 — Area 1: arm the backstop that releases a stuck consume-in-flight guard if the
     * controller never reports the expected track transition. Normally the reconcile collector
     * clears the guard first (within tens of ms) and cancels this job.
     */
    private fun armConsumeTimeout() {
        consumeTimeoutJob?.cancel()
        consumeTimeoutJob = viewModelScope.launch {
            delay(CONSUME_INFLIGHT_TIMEOUT_MS)
            if (consumeInFlight) {
                Log.w(TAG, "Preload-consume timed out without a reported transition; clearing guard")
                consumeInFlight = false
            }
        }
    }

    /**
     * Point 2 — Area 1: release the consume-in-flight guard and cancel its backstop. Idempotent and
     * safe to call for non-consume transitions (guard already false, timeout job already null).
     */
    private fun clearConsumeInFlight() {
        consumeInFlight = false
        consumeTimeoutJob?.cancel()
        consumeTimeoutJob = null
    }

    override fun onCleared() {
        super.onCleared()
        activePlaybackJob?.cancel()
        lookaheadJob?.cancel()
        rateLimitCooldownJob?.cancel()
        consumeTimeoutJob?.cancel()
        client.disconnect()
    }
}
