package com.example.sonara.feature.video

import android.util.Log
import androidx.media3.common.Player
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.data.remote.extractor.NewPipeExtractionMapper
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.VideoNextDecision
import com.example.sonara.domain.model.video.VideoPlaybackQueueEngine
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoQuality
import com.example.sonara.domain.model.video.VideoPreviousDecision
import com.example.sonara.domain.model.video.VideoQueueState
import com.example.sonara.domain.model.video.VideoRepeatMode
import com.example.sonara.domain.model.video.VideoDiscoveryResult
import com.example.sonara.domain.model.video.evaluateVideoEmbeddingEligibility
import com.example.sonara.domain.model.video.HighResVideoDiscovery
import com.example.sonara.domain.model.video.toExternalDescriptor
import com.example.sonara.domain.model.video.download.DownloadPreselectionResult
import com.example.sonara.domain.model.video.download.PlaybackQualitySnapshot
import com.example.sonara.domain.model.video.download.VideoDownloadOption
import com.example.sonara.domain.model.video.download.VideoStreamOptionResolver
import com.example.sonara.domain.ports.VideoExtractionPort
import com.example.sonara.domain.repository.DownloadRepository
import com.example.sonara.domain.repository.HighResVideoRepository
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.client.MediaControllerState
import com.example.sonara.playback.client.VideoQualityResolver
import com.example.sonara.playback.controller.TransitionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-scoped owner for video queue state; it does not retain a WebView. */
enum class VideoPlaybackRoute {
    IFRAME,
    RESOLVING,
    MEDIA3,
    IDLE
}

data class VideoPlaybackIntegrationState(
    val route: VideoPlaybackRoute = VideoPlaybackRoute.IDLE,
    val trackId: String? = null,
    val errorMessage: String? = null,
    val requestedQuality: VideoQuality = VideoQuality.AUTO,
    val userPlayIntent: Boolean = true
)

class VideoPlaybackViewModel(
    private val backendClient: SonaraBackendClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val videoExtractor: VideoExtractionPort? = null,
    private val mediaControllerClient: MediaControllerClient? = null,
    private val transitionManager: TransitionManager? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val playbackRouteCoordinator: com.example.sonara.playback.controller.PlaybackRouteCoordinator? = null,
    private val highResRepository: HighResVideoRepository? = null,
    private val downloadRepository: DownloadRepository? = null
) : ViewModel() {
    private companion object {
        const val TAG = "VideoPlaybackViewModel"
    }
    private val queueEngine = VideoPlaybackQueueEngine()
    private val coordinator = VideoRecommendationCoordinator(backendClient, queueEngine, ioDispatcher)
    private val _state = MutableStateFlow(queueEngine.snapshot())
    val state: StateFlow<VideoQueueState> = _state.asStateFlow()

    private var highResDiscoveryJob: Job? = null
    private var highResDiscoveryGeneration = 0L
    private val _activeHighResDiscovery = MutableStateFlow<HighResVideoDiscovery?>(null)
    val activeHighResDiscovery: StateFlow<HighResVideoDiscovery?> = _activeHighResDiscovery.asStateFlow()

    private data class ReplenishmentRequest(
        val generation: Long,
        val deferred: Deferred<VideoDiscoveryResult>
    )

    private var replenishmentRequest: ReplenishmentRequest? = null
    private var advancementJob: Job? = null
    private var advancementSerial = 0L
    private var endedForTrackKey: Pair<Long, String>? = null
    private var media3PlaybackJob: Job? = null
    private var activeMedia3Source: VideoPlaybackSource? = null
    private var activeMediaGeneration = 0L
    private var attemptedHlsFallback = false
    private var attemptedProgressiveFallback = false
    private var attempted8kRecovery = false
    private var defaultVideoQuality = VideoQuality.AUTO
    private var trackReadyCallback: ((Track) -> Unit)? = null
    private val videoPositions = mutableMapOf<String, Long>()
    private val videoViewCounts = mutableMapOf<String, Long>()
    private val videoIsLive = mutableMapOf<String, Boolean>()
    private val videoIsUpcoming = mutableMapOf<String, Boolean>()
    private val _activeViewCount = MutableStateFlow<Long?>(null)
    val activeViewCount: StateFlow<Long?> = _activeViewCount.asStateFlow()
    private val _activeIsLive = MutableStateFlow(false)
    val activeIsLive: StateFlow<Boolean> = _activeIsLive.asStateFlow()
    private val _activeIsUpcoming = MutableStateFlow(false)
    val activeIsUpcoming: StateFlow<Boolean> = _activeIsUpcoming.asStateFlow()
    private val _videoVolume = MutableStateFlow(1f)
    val videoVolume: StateFlow<Float> = _videoVolume.asStateFlow()
    private val _videoMuted = MutableStateFlow(false)
    val videoMuted: StateFlow<Boolean> = _videoMuted.asStateFlow()
    private val _playback = MutableStateFlow(VideoPlaybackIntegrationState())
    val playback: StateFlow<VideoPlaybackIntegrationState> = _playback.asStateFlow()
    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    fun clearFeedbackMessage() {
        _feedbackMessage.value = null
    }

    private val _isVideoDownloadSheetOpen = MutableStateFlow(false)
    val isVideoDownloadSheetOpen: StateFlow<Boolean> = _isVideoDownloadSheetOpen.asStateFlow()

    private val _availableDownloadOptions = MutableStateFlow<List<VideoDownloadOption>>(emptyList())
    val availableDownloadOptions: StateFlow<List<VideoDownloadOption>> = _availableDownloadOptions.asStateFlow()

    private val _selectedDownloadOption = MutableStateFlow<VideoDownloadOption?>(null)
    val selectedDownloadOption: StateFlow<VideoDownloadOption?> = _selectedDownloadOption.asStateFlow()

    private val _downloadPreselectionResult = MutableStateFlow<DownloadPreselectionResult?>(null)
    val downloadPreselectionResult: StateFlow<DownloadPreselectionResult?> = _downloadPreselectionResult.asStateFlow()

    private val _isExtractingDownloadOptions = MutableStateFlow(false)
    val isExtractingDownloadOptions: StateFlow<Boolean> = _isExtractingDownloadOptions.asStateFlow()

    fun onOpenDownloadSheet() {
        val track = queueEngine.snapshot().current ?: return
        _isVideoDownloadSheetOpen.value = true
        _isExtractingDownloadOptions.value = true

        val currentRoute = _playback.value.route
        val isIFrame = currentRoute == VideoPlaybackRoute.IFRAME
        val ctrlState = media3State.value
        val renderedH = if (isIFrame) null else ctrlState.selectedVideoHeight
        val renderedF = if (isIFrame) null else ctrlState.selectedVideoFps

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = renderedH,
            renderedWidth = ctrlState.videoSize.width.takeIf { it > 0 },
            renderedFps = renderedF,
            isActualTelemetry = !isIFrame && renderedH != null && renderedH > 0,
            sourceRoute = if (isIFrame) "IFRAME" else "MEDIA3"
        )

        viewModelScope.launch(ioDispatcher) {
            try {
                val source = activeMedia3Source ?: videoExtractor?.let { extractor ->
                    val status = backendClient.getVideoStatus(track.id).getOrNull()
                    if (status != null) extractor.extractPlaybackSource(track.id, status).getOrNull() else null
                }

                val options = if (source != null) {
                    VideoStreamOptionResolver.resolveDownloadOptions(source, track.durationMs)
                } else {
                    emptyList()
                }

                _availableDownloadOptions.value = options

                if (options.isNotEmpty()) {
                    val preselected = VideoStreamOptionResolver.preselectOption(
                        options = options,
                        snapshot = snapshot,
                        defaultQuality = defaultVideoQuality
                    )
                    _downloadPreselectionResult.value = preselected
                    _selectedDownloadOption.value = preselected.selectedOption
                } else {
                    _downloadPreselectionResult.value = null
                    _selectedDownloadOption.value = null
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error resolving download options for ${track.id}: ${t.message}", t)
            } finally {
                _isExtractingDownloadOptions.value = false
            }
        }
    }

    fun selectDownloadOption(option: VideoDownloadOption) {
        _selectedDownloadOption.value = option
    }

    fun dismissDownloadSheet() {
        _isVideoDownloadSheetOpen.value = false
    }

    fun confirmVideoDownload() {
        val track = queueEngine.snapshot().current ?: return
        val option = _selectedDownloadOption.value ?: return
        _isVideoDownloadSheetOpen.value = false
        viewModelScope.launch(ioDispatcher) {
            downloadRepository?.enqueueVideoDownload(track, option)
        }
    }

    val media3State: StateFlow<MediaControllerState> = mediaControllerClient?.controllerState
        ?: MutableStateFlow(MediaControllerState()).asStateFlow()
    val currentMedia3PlayerFlow: StateFlow<Player?> = mediaControllerClient?.currentPlayerState
        ?: MutableStateFlow<Player?>(null).asStateFlow()

    private val videoBridge = object : com.example.sonara.playback.controller.PlaybackRouteCoordinator.VideoPlaybackDelegate {
        override val isVideoActive: Boolean
            get() = queueEngine.snapshot().current != null &&
                _playback.value.route != VideoPlaybackRoute.IDLE

        override fun canAdvance(): Boolean {
            val snap = queueEngine.snapshot()
            if (snap.current == null) return false
            if (snap.upcoming.isNotEmpty()) return true
            if (snap.repeatMode != VideoRepeatMode.OFF) return true
            return !snap.noCandidatesAvailable
        }

        override fun onSeekToNext() {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                next(trackReadyCallback ?: {})
            }
        }

        override fun onSeekToPrevious() {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val currentPos = mediaControllerClient?.currentPlayer()?.currentPosition?.coerceAtLeast(0L)
                    ?: media3State.value.currentPositionMs.coerceAtLeast(0L)
                val callback = trackReadyCallback
                previous(
                    currentPositionMs = currentPos,
                    onRestartCurrent = { seekMedia3(0L) },
                    onTrackReady = callback ?: {}
                )
            }
        }

        override fun currentTrack(): Track? = queueEngine.snapshot().current
    }

    private fun updatePlaybackState(newState: VideoPlaybackIntegrationState) {
        _playback.value = newState
        val isIFrameActive = newState.route == VideoPlaybackRoute.IFRAME && !newState.trackId.isNullOrBlank()
        playbackRouteCoordinator?.setIFrameVideoActive(isIFrameActive)
        playbackRouteCoordinator?.notifyRouteOrCommandsChanged()
    }

    init {
        playbackRouteCoordinator?.registerVideoDelegate(videoBridge)
        val initialIFrameActive = _playback.value.route == VideoPlaybackRoute.IFRAME && !_playback.value.trackId.isNullOrBlank()
        playbackRouteCoordinator?.setIFrameVideoActive(initialIFrameActive)
        settingsRepository?.let { repository ->
            viewModelScope.launch {
                repository.getUserPreferences().collect { preferences ->
                    defaultVideoQuality = preferences.defaultVideoQuality
                }
            }
        }
        mediaControllerClient?.let { client ->
            viewModelScope.launch {
                client.controllerState.collect { controllerState ->
                    val integration = _playback.value
                    if (controllerState.currentMediaItem?.mediaId != integration.trackId) return@collect

                    if (integration.route == VideoPlaybackRoute.RESOLVING &&
                        controllerState.playbackState == Player.STATE_READY
                    ) {
                        updatePlaybackState(integration.copy(route = VideoPlaybackRoute.MEDIA3))
                        return@collect
                    }
                    if (integration.route != VideoPlaybackRoute.MEDIA3 &&
                        integration.route != VideoPlaybackRoute.RESOLVING
                    ) return@collect

                    when {
                        controllerState.errorMessage != null -> {
                            handleMedia3Error(controllerState.errorMessage)
                        }
                        controllerState.playbackState == Player.STATE_ENDED -> {
                            if (integration.route == VideoPlaybackRoute.MEDIA3) {
                                onEnded(trackReadyCallback ?: return@collect)
                            }
                        }
                    }
                }
            }
        }
    }

    fun seed(track: Track, onTrackReady: (Track) -> Unit) {
        cancelAdvancement()
        trackReadyCallback = onTrackReady
        endedForTrackKey = null
        val generation = queueEngine.seed(track)
        publish()
        onTrackReady(track)
        beginPlayback(track, generation)
        requestReplenishment(track, generation, force = true)
    }

    fun next(onTrackReady: (Track) -> Unit) {
        if (advancementJob?.isActive == true) return
        trackReadyCallback = onTrackReady
        val serial = ++advancementSerial
        advancementJob = viewModelScope.launch {
            try {
                advanceUntilPlayable(onTrackReady)
            } finally {
                if (advancementSerial == serial) advancementJob = null
            }
        }
    }

    fun previous(
        currentPositionMs: Long,
        onRestartCurrent: () -> Unit,
        onTrackReady: (Track) -> Unit
    ) {
        cancelAdvancement()
        trackReadyCallback = onTrackReady
        endedForTrackKey = null
        when (val decision = queueEngine.previous(currentPositionMs)) {
            VideoPreviousDecision.RestartCurrent -> {
                queueEngine.snapshot().current?.let { clearIFramePosition(it.id) }
                onRestartCurrent()
            }
            is VideoPreviousDecision.Play -> {
                publish()
                onTrackReady(decision.track)
                beginPlayback(decision.track, queueEngine.snapshot().generation)
                replenishIfNeeded()
            }
            VideoPreviousDecision.None -> Unit
        }
    }

    fun onEnded(onTrackReady: (Track) -> Unit) {
        trackReadyCallback = onTrackReady
        queueEngine.snapshot().current?.let { clearIFramePosition(it.id) }
        handleEnded(onTrackReady)
    }

    fun updateIFramePosition(videoId: String, positionMs: Long, durationMs: Long? = null) {
        if (videoId.isBlank() || positionMs < 0L) return
        val clampedPosition = if (durationMs != null && durationMs > 0L) {
            positionMs.coerceAtMost(durationMs)
        } else {
            positionMs
        }
        synchronized(videoPositions) {
            videoPositions[videoId] = clampedPosition
        }
    }

    fun getIFramePosition(videoId: String): Long {
        if (videoId.isBlank()) return 0L
        return synchronized(videoPositions) {
            videoPositions[videoId] ?: 0L
        }
    }

    fun clearIFramePosition(videoId: String) {
        if (videoId.isBlank()) return
        synchronized(videoPositions) {
            videoPositions.remove(videoId)
        }
    }

    fun getViewCount(videoId: String): Long? {
        if (videoId.isBlank()) return null
        return synchronized(videoViewCounts) {
            videoViewCounts[videoId]
        }
    }

    fun updateViewCount(videoId: String, viewCount: Long?) {
        if (videoId.isBlank() || viewCount == null || viewCount < 0L) return
        synchronized(videoViewCounts) {
            videoViewCounts[videoId] = viewCount
        }
        if (queueEngine.snapshot().current?.id == videoId) {
            _activeViewCount.value = viewCount
        }
    }

    private fun fetchViewCountMetadata(videoId: String, queueGeneration: Long) {
        val extractor = videoExtractor ?: return
        if (synchronized(videoViewCounts) { videoViewCounts.containsKey(videoId) }) return
        viewModelScope.launch {
            try {
                val status = withContext(ioDispatcher) {
                    backendClient.getVideoStatus(videoId)
                }.getOrNull() ?: return@launch
                if (!evaluateVideoEmbeddingEligibility(status).isAllowed) return@launch
                val result = extractor.extract(videoId, status).getOrNull()
                if (queueEngine.isCurrentGeneration(queueGeneration)) {
                    result?.viewCount?.let { count ->
                        updateViewCount(videoId, count)
                    }
                    result?.let {
                        synchronized(videoIsLive) { videoIsLive[videoId] = it.isLive }
                        synchronized(videoIsUpcoming) { videoIsUpcoming[videoId] = it.isUpcoming }
                        if (queueEngine.snapshot().current?.id == videoId) {
                            _activeIsLive.value = it.isLive
                            _activeIsUpcoming.value = it.isUpcoming
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Non-fatal: failures retrieving view count must never interrupt playback
            }
        }
    }

    private fun handleEnded(onTrackReady: (Track) -> Unit) {
        val snapshot = queueEngine.snapshot()
        val current = snapshot.current ?: return
        val key = snapshot.generation to current.id
        if (endedForTrackKey == key) return
        endedForTrackKey = key
        next(onTrackReady)
    }

    fun toggleShuffle() {
        queueEngine.toggleShuffle()
        publish()
    }

    fun toggleRepeat(): VideoRepeatMode {
        val mode = queueEngine.toggleRepeat()
        publish()
        return mode
    }

    fun stop() {
        cancelAdvancement()
        val currentTrackId = queueEngine.snapshot().current?.id ?: _playback.value.trackId
        if (currentTrackId != null) {
            highResRepository?.cancelDiscovery(currentTrackId)
        }
        highResDiscoveryJob?.cancel()
        highResDiscoveryJob = null
        highResDiscoveryGeneration++
        _activeHighResDiscovery.value = null
        media3PlaybackJob?.cancel()
        media3PlaybackJob = null
        activeMedia3Source = null
        attemptedHlsFallback = false
        attemptedProgressiveFallback = false
        attempted8kRecovery = false
        mediaControllerClient?.pause()
        mediaControllerClient?.clearMediaItems()
        replenishmentRequest?.deferred?.cancel()
        replenishmentRequest = null
        endedForTrackKey = null
        queueEngine.clear()
        _feedbackMessage.value = null
        publish()
        _activeViewCount.value = null
        _activeIsLive.value = false
        _activeIsUpcoming.value = false
        updatePlaybackState(VideoPlaybackIntegrationState(route = VideoPlaybackRoute.IDLE))
    }

    /** Extracted video is foreground-only; native song playback is not affected by this method. */
    fun stopMedia3ForBackground() {
        if (_playback.value.route != VideoPlaybackRoute.MEDIA3 &&
            _playback.value.route != VideoPlaybackRoute.RESOLVING
        ) return
        highResDiscoveryJob?.cancel()
        highResDiscoveryJob = null
        highResDiscoveryGeneration++
        _activeHighResDiscovery.value = null
        media3PlaybackJob?.cancel()
        media3PlaybackJob = null
        mediaControllerClient?.pause()
        mediaControllerClient?.clearMediaItems()
        updatePlaybackState(
            _playback.value.copy(
                route = VideoPlaybackRoute.IFRAME,
                errorMessage = "Extracted video playback stopped when Sonara left the foreground"
            )
        )
    }

    fun play() {
        updatePlaybackState(_playback.value.copy(userPlayIntent = true))
        if (_playback.value.route == VideoPlaybackRoute.MEDIA3) {
            mediaControllerClient?.play()
        }
    }

    fun pause() {
        updatePlaybackState(_playback.value.copy(userPlayIntent = false))
        if (_playback.value.route == VideoPlaybackRoute.MEDIA3) {
            mediaControllerClient?.pause()
        }
    }

    fun playMedia3() {
        play()
    }

    fun pauseMedia3() {
        pause()
    }

    fun seekMedia3(positionMs: Long) {
        if (_playback.value.route == VideoPlaybackRoute.MEDIA3) mediaControllerClient?.seekTo(positionMs)
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        val unmuted = clamped > 0f && _videoMuted.value
        _videoVolume.value = clamped
        if (unmuted) {
            _videoMuted.value = false
        }
        if (_playback.value.route == VideoPlaybackRoute.MEDIA3) {
            mediaControllerClient?.setVolume(if (_videoMuted.value) 0f else clamped)
        }
    }

    fun toggleMute(): Boolean {
        val newMuted = !_videoMuted.value
        _videoMuted.value = newMuted
        if (_playback.value.route == VideoPlaybackRoute.MEDIA3) {
            mediaControllerClient?.setVolume(if (newMuted) 0f else _videoVolume.value)
        }
        return newMuted
    }

    fun peekNextTrack(): Track? = queueEngine.snapshot().upcoming.firstOrNull()

    fun peekPreviousTrack(): Track? = queueEngine.snapshot().previous.lastOrNull()

    fun currentMedia3Player(): Player? = currentMedia3PlayerFlow.value ?: mediaControllerClient?.currentPlayer()

    private suspend fun advanceUntilPlayable(onTrackReady: (Track) -> Unit) {
        var decision = queueEngine.next()
        if (decision is VideoNextDecision.NeedRecommendations) {
            val generation = queueEngine.snapshot().generation
            val result = requestReplenishment(decision.seed, generation, force = false).await()
            if (result.stale || !queueEngine.isCurrentGeneration(generation)) {
                publish()
                return
            }
            decision = queueEngine.next()
        }
        when (decision) {
            is VideoNextDecision.Play -> {
                publish()
                endedForTrackKey = null
                onTrackReady(decision.track)
                beginPlayback(decision.track, queueEngine.snapshot().generation)
                replenishIfNeeded()
            }
            VideoNextDecision.ReplayCurrent -> {
                publish()
                endedForTrackKey = null
                queueEngine.snapshot().current?.let { current ->
                    clearIFramePosition(current.id)
                    if (_playback.value.route == VideoPlaybackRoute.MEDIA3 && mediaControllerClient != null) {
                        mediaControllerClient.seekTo(0L)
                        mediaControllerClient.play()
                    } else {
                        onTrackReady(current)
                        beginPlayback(current, queueEngine.snapshot().generation)
                    }
                }
            }
            is VideoNextDecision.NeedRecommendations,
            VideoNextDecision.Stop -> {
                val noCandidates = queueEngine.upcomingCount() == 0
                publish(noCandidatesAvailable = noCandidates)
                if (noCandidates) {
                    _feedbackMessage.value = "No next video recommendations available"
                }
            }
        }
    }

    private fun replenishIfNeeded() {
        val snapshot = queueEngine.snapshot()
        val seed = snapshot.current ?: return
        if (queueEngine.shouldReplenish()) {
            requestReplenishment(seed, snapshot.generation, force = true)
        }
    }

    private fun requestReplenishment(
        seed: Track,
        generation: Long,
        force: Boolean = false
    ): Deferred<VideoDiscoveryResult> {
        val existing = replenishmentRequest
        if (existing != null && existing.generation == generation) {
            if (existing.deferred.isActive && !force) return existing.deferred
            if (!existing.deferred.isActive && !force && queueEngine.upcomingCount() > 0) return existing.deferred
        }

        existing?.deferred?.cancel()
        publish(isReplenishing = true, noCandidatesAvailable = false)
        val deferred = viewModelScope.async {
            val result = try {
                coordinator.replenish(seed, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                VideoDiscoveryResult(
                    generation = generation,
                    accepted = emptyList(),
                    attemptedSources = emptyList(),
                    stale = !queueEngine.isCurrentGeneration(generation),
                    noCandidatesAvailable = queueEngine.isCurrentGeneration(generation) &&
                        queueEngine.upcomingCount() == 0
                )
            }

            if (queueEngine.isCurrentGeneration(generation)) {
                publish(
                    isReplenishing = false,
                    noCandidatesAvailable = result.noCandidatesAvailable
                )
            }
            result
        }
        replenishmentRequest = ReplenishmentRequest(generation, deferred)
        return deferred
    }

    private fun cancelAdvancement() {
        advancementSerial++
        advancementJob?.cancel()
        advancementJob = null
    }

    private fun beginPlayback(track: Track, queueGeneration: Long) {
        _activeViewCount.value = synchronized(videoViewCounts) { videoViewCounts[track.id] }
        _activeIsLive.value = synchronized(videoIsLive) { videoIsLive[track.id] ?: false }
        _activeIsUpcoming.value = synchronized(videoIsUpcoming) { videoIsUpcoming[track.id] ?: false }

        // Intent-driven discovery: Cancel previous discovery and seed with cached results if available.
        // Background extraction is never triggered unconditionally here to avoid blocking or delaying standard playback.
        val previousTrackId = activeMedia3Source?.videoId ?: _playback.value.trackId
        if (previousTrackId != null && previousTrackId != track.id) {
            highResRepository?.cancelDiscovery(previousTrackId)
        }
        highResDiscoveryJob?.cancel()
        highResDiscoveryJob = null
        highResDiscoveryGeneration++
        val cachedHighRes = highResRepository?.getCachedDiscovery(track.id)
        _activeHighResDiscovery.value = cachedHighRes

        val extractor = videoExtractor
        val client = mediaControllerClient
        val transition = transitionManager
        if (extractor == null || client == null || transition == null) {
            updatePlaybackState(
                VideoPlaybackIntegrationState(
                    route = VideoPlaybackRoute.IFRAME,
                    trackId = track.id,
                    userPlayIntent = true
                )
            )
            if (extractor != null) {
                fetchViewCountMetadata(track.id, queueGeneration)
            }
            return
        }

        media3PlaybackJob?.cancel()
        val mediaGeneration = transition.nextGeneration()
        updatePlaybackState(
            VideoPlaybackIntegrationState(
                route = VideoPlaybackRoute.RESOLVING,
                trackId = track.id,
                requestedQuality = defaultVideoQuality,
                userPlayIntent = true
            )
        )
        media3PlaybackJob = viewModelScope.launch {
            try {
                val preferredQuality = settingsRepository?.getUserPreferences()?.first()
                    ?.defaultVideoQuality ?: defaultVideoQuality
                val status = withContext(ioDispatcher) {
                    backendClient.getVideoStatus(track.id)
                }.getOrElse { throw it }
                if (!evaluateVideoEmbeddingEligibility(status).isAllowed) {
                    throw IllegalStateException("Video is not eligible for extracted playback")
                }
                val externalDescriptor = (_activeHighResDiscovery.value as? HighResVideoDiscovery.Available)?.toExternalDescriptor()
                val source = extractor.extractPlaybackSource(track.id, status, externalDescriptor).getOrElse { throw it }
                if (source.videoId != track.id || source.streamUrl.isBlank()) {
                    throw IllegalStateException("Extractor returned an invalid playback source")
                }

                if (!transition.isAuthoritative(mediaGeneration)) return@launch

                if (queueEngine.snapshot().current?.id != track.id) {
                    if (queueEngine.snapshot().current == null) {
                        updatePlaybackState(VideoPlaybackIntegrationState(route = VideoPlaybackRoute.IDLE))
                    }
                    return@launch
                }

                source.viewCount?.let { updateViewCount(track.id, it) }
                synchronized(videoIsLive) { videoIsLive[track.id] = source.isLive }
                synchronized(videoIsUpcoming) { videoIsUpcoming[track.id] = source.isUpcoming }
                _activeIsLive.value = source.isLive
                _activeIsUpcoming.value = source.isUpcoming

                val shouldPlay = _playback.value.userPlayIntent
                updatePlaybackState(
                    VideoPlaybackIntegrationState(
                        route = VideoPlaybackRoute.MEDIA3,
                        trackId = track.id,
                        requestedQuality = preferredQuality,
                        userPlayIntent = shouldPlay
                    )
                )
                activeMedia3Source = source
                activeMediaGeneration = mediaGeneration
                attemptedHlsFallback = false
                attemptedProgressiveFallback = false
                attempted8kRecovery = false
                client.playExtractedVideo(track, source, mediaGeneration)
                if (!shouldPlay) {
                    client.pause()
                }
                client.applyVideoQuality(preferredQuality)
                client.setVolume(if (_videoMuted.value) 0f else _videoVolume.value)

                // Intent-driven discovery: auto-trigger background 8K discovery ONLY when NewPipe
                // discovers at least one >= 2160p (4K) representation. Playback of standard resolutions
                // has already begun immediately without waiting for high-res discovery.
                val maxNewPipeHeight = source.videoOnlyStreams.maxOfOrNull { it.height ?: 0 } ?: 0
                if (maxNewPipeHeight >= 2160 && _activeHighResDiscovery.value == null) {
                    launchHighResDiscovery(track.id, forceRefresh = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Video extraction failed for ${track.id}: ${error.message}", error)
                if (transition.isAuthoritative(mediaGeneration)) {
                    if (queueEngine.snapshot().current?.id == track.id) {
                        client.pause()
                        updatePlaybackState(
                            VideoPlaybackIntegrationState(
                                route = VideoPlaybackRoute.IFRAME,
                                trackId = track.id,
                                errorMessage = error.message,
                                userPlayIntent = _playback.value.userPlayIntent
                            )
                        )
                        fetchViewCountMetadata(track.id, queueGeneration)
                    } else if (queueEngine.snapshot().current == null) {
                        updatePlaybackState(VideoPlaybackIntegrationState(route = VideoPlaybackRoute.IDLE))
                    }
                }
            }
        }
    }

    fun selectVideoQuality(quality: VideoQuality) {
        val integration = _playback.value
        if (integration.route != VideoPlaybackRoute.MEDIA3 &&
            integration.route != VideoPlaybackRoute.RESOLVING
        ) return

        val currentTrack = queueEngine.snapshot().current
        val source = activeMedia3Source
        if (quality.requestedHeight == 4320 && currentTrack != null && source != null &&
            source.deliveryMethod == com.example.sonara.domain.model.video.VideoDeliveryMethod.DASH
        ) {
            val playerTracks = mediaControllerClient?.currentPlayer()?.currentTracks
            val has8KInPlayer = playerTracks != null && VideoQualityResolver.allAvailableTracks(playerTracks).any { it.height == 4320 }
            if (!has8KInPlayer) {
                val currentPos = media3State.value.currentPositionMs.takeIf { it > 0L }
                    ?: mediaControllerClient?.currentPlayer()?.currentPosition?.coerceAtLeast(0L)
                    ?: 0L
                val shouldPlay = mediaControllerClient?.currentPlayer()?.playWhenReady ?: _playback.value.userPlayIntent
                mediaControllerClient?.setPendingVideoQuality(quality)
                mediaControllerClient?.playExtractedVideo(
                    track = currentTrack,
                    source = source,
                    generation = activeMediaGeneration,
                    startPositionMs = currentPos,
                    autoPlay = shouldPlay
                )
                updatePlaybackState(integration.copy(requestedQuality = quality))
                return
            }
        }

        val selection = mediaControllerClient?.applyVideoQuality(quality)
        val effectiveQuality = selection?.actualQuality ?: quality
        updatePlaybackState(integration.copy(requestedQuality = effectiveQuality))
    }

    /**
     * Triggered on-demand when the user opens the video quality selector sheet in the player UI.
     * If discovery is not completed or in-flight, initiates discovery or serves cached results.
     */
    fun onOpenQualitySelector() {
        val currentTrack = queueEngine.snapshot().current ?: return
        if (_activeHighResDiscovery.value == null) {
            val cached = highResRepository?.getCachedDiscovery(currentTrack.id)
            if (cached != null) {
                _activeHighResDiscovery.value = cached
            } else {
                launchHighResDiscovery(currentTrack.id, forceRefresh = false)
            }
        }
    }

    /**
     * Triggers a high-resolution discovery probe on demand.
     */
    fun refreshHighResDiscovery(forceRefresh: Boolean = true) {
        val currentTrack = queueEngine.snapshot().current ?: return
        launchHighResDiscovery(currentTrack.id, forceRefresh = forceRefresh)
    }

    private fun launchHighResDiscovery(videoId: String, forceRefresh: Boolean) {
        val repo = highResRepository ?: return
        if (!forceRefresh && highResDiscoveryJob?.isActive == true) {
            return // Shared in-flight discovery already running
        }
        highResDiscoveryJob?.cancel()
        val generation = ++highResDiscoveryGeneration
        highResDiscoveryJob = viewModelScope.launch {
            try {
                val result = repo.discoverHighRes(videoId, forceRefresh = forceRefresh)
                if (highResDiscoveryGeneration == generation && queueEngine.snapshot().current?.id == videoId) {
                    val discovery = result.getOrNull()
                    _activeHighResDiscovery.value = discovery
                    if (discovery is HighResVideoDiscovery.Available) {
                        val currentSource = activeMedia3Source
                        if (currentSource != null && currentSource.videoId == videoId &&
                            currentSource.deliveryMethod == com.example.sonara.domain.model.video.VideoDeliveryMethod.DASH
                        ) {
                            val updatedDash = NewPipeExtractionMapper.injectExternalIntoDashMpd(
                                currentSource.streamUrl,
                                discovery.toExternalDescriptor()
                            )
                            if (updatedDash != null && updatedDash != currentSource.streamUrl) {
                                val newSource = currentSource.copy(
                                    streamUrl = updatedDash,
                                    dashUrl = updatedDash
                                )
                                activeMedia3Source = newSource

                                // D4 & D5: Update the active player with the refreshed manifest
                                // so Media3's currentTracks receives the 8K representation,
                                // exposing it reactively to the UI quality selector without interrupting playback.
                                val currentTrack = queueEngine.snapshot().current
                                if (currentTrack != null && currentTrack.id == videoId &&
                                    _playback.value.route == VideoPlaybackRoute.MEDIA3
                                ) {
                                    val currentPos = media3State.value.currentPositionMs.takeIf { it > 0L }
                                        ?: mediaControllerClient?.currentPlayer()?.currentPosition?.coerceAtLeast(0L)
                                        ?: 0L
                                    val shouldPlay = mediaControllerClient?.currentPlayer()?.playWhenReady ?: _playback.value.userPlayIntent
                                    // Preserve the user's selected/requested quality (e.g. AUTO) across manifest reload.
                                    // onTracksChanged will apply this once Media3 finishes parsing the updated DASH MPD.
                                    mediaControllerClient?.setPendingVideoQuality(_playback.value.requestedQuality)
                                    mediaControllerClient?.playExtractedVideo(
                                        track = currentTrack,
                                        source = newSource,
                                        generation = activeMediaGeneration,
                                        startPositionMs = currentPos,
                                        autoPlay = shouldPlay
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.d(TAG, "High-res discovery ended: ${e.message}")
            }
        }
    }

    private fun handleMedia3Error(message: String?) {
        val integration = _playback.value
        val source = activeMedia3Source
        val track = queueEngine.snapshot().current?.takeIf { it.id == integration.trackId }

        // D7: If high-resolution (8K) playback encounters an error (e.g. session expiration 403/410, 404, or 416),
        // recover by clearing the 8K override and re-preparing the player on ordinary NewPipe representations.
        val is8kPlaybackFailure = integration.requestedQuality.requestedHeight == 4320 ||
            (mediaControllerClient?.currentPlayer()?.let { player ->
                val currentTracks = player.currentTracks
                currentTracks.groups.any { group ->
                    group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
                        group.isTrackSelected(index) && group.getTrackFormat(index).height == 4320
                    }
                }
            } ?: false)

        if (is8kPlaybackFailure && source != null && track != null && !attempted8kRecovery) {
            attempted8kRecovery = true
            Log.w(TAG, "High-resolution 8K stream failed ($message). Recovering to Auto quality on ordinary streams.")

            // Evict failed/expired session from repository cache so it is not retried
            highResRepository?.evict(track.id)
            _activeHighResDiscovery.value = null

            // Reset requested quality state
            updatePlaybackState(integration.copy(requestedQuality = VideoQuality.AUTO))
            mediaControllerClient?.applyVideoQuality(VideoQuality.AUTO)

            // If the player is in STATE_IDLE or errored, changing track selection parameters alone
            // does not restart playback. Re-prepare the player with the ordinary DASH source.
            val currentPos = media3State.value.currentPositionMs.takeIf { it > 0L }
                ?: mediaControllerClient?.currentPlayer()?.currentPosition?.coerceAtLeast(0L)
                ?: 0L
            val shouldPlay = mediaControllerClient?.currentPlayer()?.playWhenReady ?: integration.userPlayIntent

            mediaControllerClient?.playExtractedVideo(
                track = track,
                source = source,
                generation = activeMediaGeneration,
                startPositionMs = currentPos,
                autoPlay = shouldPlay
            )
            mediaControllerClient?.applyVideoQuality(VideoQuality.AUTO)
            return
        }
        if (source != null && track != null) {
            val hlsUrl = source.hlsUrl?.trim()?.takeIf {
                it.isNotBlank() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
            }
            val progressiveUrl = source.progressiveFallbackUrl?.trim()?.takeIf {
                it.isNotBlank() && (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true))
            }

            // 1. Fall back from DASH to HLS if available and distinct
            if (source.deliveryMethod == com.example.sonara.domain.model.video.VideoDeliveryMethod.DASH &&
                !attemptedHlsFallback &&
                !hlsUrl.isNullOrBlank() &&
                hlsUrl != source.streamUrl
            ) {
                attemptedHlsFallback = true
                val fallbackSource = source.copy(
                    streamUrl = hlsUrl,
                    deliveryMethod = com.example.sonara.domain.model.video.VideoDeliveryMethod.HLS
                )
                activeMedia3Source = fallbackSource
                updatePlaybackState(integration.copy(route = VideoPlaybackRoute.RESOLVING))
                mediaControllerClient?.playExtractedVideo(track, fallbackSource, activeMediaGeneration)
                mediaControllerClient?.applyVideoQuality(integration.requestedQuality)
                return
            }

            // 2. Fall back to combined progressive stream if available and distinct
            if (!attemptedProgressiveFallback &&
                !progressiveUrl.isNullOrBlank() &&
                progressiveUrl != source.streamUrl
            ) {
                attemptedProgressiveFallback = true
                val fallbackSource = source.copy(
                    streamUrl = progressiveUrl,
                    deliveryMethod = com.example.sonara.domain.model.video.VideoDeliveryMethod.PROGRESSIVE_HTTP,
                    progressiveFallbackUrl = null
                )
                activeMedia3Source = fallbackSource
                updatePlaybackState(integration.copy(route = VideoPlaybackRoute.RESOLVING))
                mediaControllerClient?.playExtractedVideo(track, fallbackSource, activeMediaGeneration)
                mediaControllerClient?.applyVideoQuality(integration.requestedQuality)
                return
            }
        }
        fallbackToIFrame(message)
    }

    private fun fallbackToIFrame(message: String?) {
        val integration = _playback.value
        if (integration.route != VideoPlaybackRoute.MEDIA3 &&
            integration.route != VideoPlaybackRoute.RESOLVING
        ) return
        val currentTrackId = integration.trackId
        val lastMedia3Pos = media3State.value.currentPositionMs
        if (currentTrackId != null && lastMedia3Pos > 0L) {
            updateIFramePosition(
                videoId = currentTrackId,
                positionMs = lastMedia3Pos,
                durationMs = media3State.value.durationMs.takeIf { it > 0L }
            )
        }
        media3PlaybackJob?.cancel()
        highResDiscoveryJob?.cancel()
        highResDiscoveryJob = null
        highResDiscoveryGeneration++
        activeMedia3Source = null
        mediaControllerClient?.pause()
        mediaControllerClient?.clearMediaItems()
        updatePlaybackState(
            integration.copy(
                route = VideoPlaybackRoute.IFRAME,
                errorMessage = message
            )
        )
    }

    private fun publish(
        isReplenishing: Boolean = _state.value.isReplenishing,
        noCandidatesAvailable: Boolean = _state.value.noCandidatesAvailable
    ) {
        _state.value = queueEngine.snapshot(isReplenishing, noCandidatesAvailable)
        playbackRouteCoordinator?.notifyRouteOrCommandsChanged()
    }

    override fun onCleared() {
        playbackRouteCoordinator?.unregisterVideoDelegate(videoBridge)
        media3PlaybackJob?.cancel()
        highResDiscoveryJob?.cancel()
        highResDiscoveryJob = null
        highResDiscoveryGeneration++
        _activeHighResDiscovery.value = null
        highResRepository?.clearCache()
        if (_playback.value.route == VideoPlaybackRoute.MEDIA3 ||
            _playback.value.route == VideoPlaybackRoute.RESOLVING
        ) {
            mediaControllerClient?.pause()
            mediaControllerClient?.clearMediaItems()
        }
        updatePlaybackState(VideoPlaybackIntegrationState(route = VideoPlaybackRoute.IDLE))
        super.onCleared()
    }
}
