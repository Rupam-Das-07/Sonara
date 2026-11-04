package com.example.sonara.playback.client

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.sonara.domain.model.video.VideoDeliveryMethod
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoQuality
import com.example.sonara.domain.model.video.VideoQualityOption
import com.example.sonara.playback.service.SonaraPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Raw playback state snapshot exposed by MediaControllerClient.
 * Presentation transformation is encapsulated entirely inside PlayerViewModel.
 */
data class MediaControllerState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentMediaItem: MediaItem? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackState: Int = Player.STATE_IDLE,
    val errorMessage: String? = null,
    val availableVideoHeights: List<Int> = emptyList(),
    val selectedVideoHeight: Int? = null,
    val videoSize: VideoSize = VideoSize.UNKNOWN,
    val currentDeliveryMethod: VideoDeliveryMethod? = null,
    val availableVideoQualities: List<VideoQuality> = emptyList(),
    val availableVideoQualityOptions: List<VideoQualityOption> = emptyList(),
    val selectedVideoQuality: VideoQuality? = null,
    /** Generation of the most recently failed media request, when known. */
    val requestFailureGeneration: Long? = null
)

/**
 * Client-side bridge connecting UI to SonaraPlaybackService via MediaController.
 */
open class MediaControllerClient(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val capabilityChecker: VideoDecoderCapabilityChecker = VideoQualityResolver.defaultCapabilityChecker,
    private val uriParser: (String) -> Uri = { Uri.parse(it) }
) {
    companion object {
        private const val TAG = "MediaControllerClient"
        const val ACTION_SET_AUDIO_DEVICE = "app.sonara.music.ACTION_SET_AUDIO_DEVICE"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_REQUEST_GENERATION = "app.sonara.music.extra.REQUEST_GENERATION"
        const val EXTRA_MEDIA_KIND = "app.sonara.music.extra.MEDIA_KIND"
        const val EXTRA_MIME_TYPE = "app.sonara.music.extra.MIME_TYPE"
        const val MEDIA_KIND_EXTRACTED_VIDEO = "extracted_video"

        fun resolveDeliveryMimeType(deliveryMethod: VideoDeliveryMethod?): String? = when (deliveryMethod) {
            VideoDeliveryMethod.HLS -> MimeTypes.APPLICATION_M3U8
            VideoDeliveryMethod.DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private val connectionLock = Any()
    private val lifecycleActionLock = Any()
    private var connectionGeneration: Long = 0L
    @Volatile private var controllerFuture: ListenableFuture<MediaController>? = null
    @Volatile protected var controller: Player? = null
    private var positionPollerJob: Job? = null
    private var pendingVideoQuality: VideoQuality? = VideoQuality.AUTO
    private var pendingPlayAction: ((Player) -> Unit)? = null
    @Volatile private var pendingPlaybackRequestGeneration: Long? = null
    @Volatile private var lastPlaybackRequestFailureGeneration: Long? = null
    private var activeDeliveryMethod: VideoDeliveryMethod? = null
    open val currentDeliveryMethod: VideoDeliveryMethod?
        get() = activeDeliveryMethod

    protected val _controllerState = MutableStateFlow(MediaControllerState())
    open val controllerState: StateFlow<MediaControllerState> = _controllerState.asStateFlow()

    protected val _currentPlayer = MutableStateFlow<Player?>(null)
    open val currentPlayerState: StateFlow<Player?> = _currentPlayer.asStateFlow()

    internal open fun canConnect(): Boolean = context != null

    open fun connect() {
        val (future, gen) = synchronized(connectionLock) {
            if (!canConnect() || controller != null || controllerFuture != null) return
            val ctx = context ?: return

            val sessionToken = SessionToken(
                ctx,
                ComponentName(ctx, SonaraPlaybackService::class.java)
            )

            val f = MediaController.Builder(ctx, sessionToken).buildAsync()
            controllerFuture = f
            val g = ++connectionGeneration
            Pair(f, g)
        }

        future.addListener({
            try {
                val mediaController = future.get()
                handleConnectionSuccess(mediaController, gen, future)
            } catch (e: Exception) {
                handleConnectionFailure(e, gen, future)
            }
        }, MoreExecutors.directExecutor())
    }

    internal open fun onPreStatePublication(player: Player, generation: Long) {}
    internal open fun onPostAdoption(player: Player, generation: Long) {}
    internal open fun onBeforePendingActionExecution(player: Player, generation: Long) {}
    internal open fun onBeforePlayerRelease(player: Player) {}

    internal fun handleConnectionSuccess(
        player: Player,
        generation: Long = synchronized(connectionLock) { connectionGeneration },
        expectedFuture: ListenableFuture<*>? = null
    ): Boolean {
        synchronized(connectionLock) {
            if (controller === player) {
                return false
            }
        }

        onPreStatePublication(player, generation)
        setupListener(player)

        val (adopted, actionToInvoke) = synchronized(connectionLock) {
            val isObsolete = generation != connectionGeneration ||
                (expectedFuture != null && controllerFuture !== expectedFuture) ||
                controller != null
            if (isObsolete) {
                Pair(false, null)
            } else {
                controller = player
                controllerFuture = null
                _currentPlayer.value = player
                updateStateInternal(player)
                val action = pendingPlayAction
                pendingPlayAction = null
                Pair(true, action)
            }
        }

        if (!adopted) {
            Log.w(TAG, "Ignoring obsolete connection success for generation $generation (current: ${synchronized(connectionLock) { connectionGeneration }})")
            try {
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release obsolete player: ${e.message}")
            }
            return false
        }

        onPostAdoption(player, generation)

        Log.i(TAG, "Connected to SonaraPlaybackService")
        pendingVideoQuality?.let { applyVideoQualityInternal(player, it) }
        startPositionPolling(generation)

        synchronized(lifecycleActionLock) {
            onBeforePendingActionExecution(player, generation)

            val activePlayer = synchronized(connectionLock) {
                if (generation == connectionGeneration && controller === player) controller else null
            }
            if (activePlayer != null && actionToInvoke != null) {
                actionToInvoke(activePlayer)
            }
        }

        return true
    }

    internal fun handleConnectionFailure(
        e: Exception,
        generation: Long = synchronized(connectionLock) { connectionGeneration },
        expectedFuture: ListenableFuture<*>? = null
    ): Boolean {
        val isCurrent = synchronized(connectionLock) {
            val isObsolete = generation != connectionGeneration ||
                (expectedFuture != null && controllerFuture !== expectedFuture)
            if (isObsolete) {
                false
            } else {
                pendingPlayAction = null
                lastPlaybackRequestFailureGeneration = pendingPlaybackRequestGeneration
                controllerFuture = null
                connectionGeneration++
                true
            }
        }

        if (!isCurrent) {
            Log.w(TAG, "Ignoring obsolete connection failure for generation $generation: ${e.message}")
            return false
        }

        Log.e(TAG, "Failed to connect to MediaSessionService: ${e.message}")
        _controllerState.value = _controllerState.value.copy(
            errorMessage = e.message,
            requestFailureGeneration = lastPlaybackRequestFailureGeneration
        )
        return true
    }

    internal fun hasPendingPlayAction(): Boolean = synchronized(connectionLock) { pendingPlayAction != null }

    internal fun currentGeneration(): Long = synchronized(connectionLock) { connectionGeneration }

    internal fun nextGeneration(): Long = synchronized(connectionLock) { ++connectionGeneration }

    private fun setupListener(mediaController: Player) {
        mediaController.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateState(mediaController)
                if (playbackState == Player.STATE_BUFFERING) {
                    Log.d(TAG, "LATENCY_T4 STATE_BUFFERING: t=${System.currentTimeMillis()}")
                }
                if (playbackState == Player.STATE_READY && mediaController.isPlaying) {
                    Log.d(TAG, "LATENCY_T5 STATE_READY isPlaying: t=${System.currentTimeMillis()}")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState(mediaController)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val qualityToApply = pendingVideoQuality ?: VideoQuality.AUTO
                applyVideoQualityInternal(mediaController, qualityToApply)
                updateState(mediaController)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                updateState(mediaController)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "LATENCY_TX transition: mediaId=${mediaItem?.mediaId} reason=$reason t=${System.currentTimeMillis()}")
                if (mediaItem == null) {
                    clearVideoTrackOverrides(mediaController)
                } else if (mediaController.currentTracks.groups.isNotEmpty()) {
                    val qualityToApply = pendingVideoQuality ?: VideoQuality.AUTO
                    applyVideoQualityInternal(mediaController, qualityToApply)
                }
                updateState(mediaController)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Player error: ${error.message} (errorCode=${error.errorCode})")

                val isDecoderFailure = isDecoderRelatedError(error)
                val activeManualQuality = pendingVideoQuality

                if (isDecoderFailure && activeManualQuality != null && activeManualQuality != VideoQuality.AUTO) {
                    Log.w(TAG, "Manual quality ${activeManualQuality.label} failed with decoder error. Initiating controlled recovery to Auto.")
                    // Prevent recursive retry loops
                    pendingVideoQuality = VideoQuality.AUTO

                    val resumePosition = mediaController.currentPosition.coerceAtLeast(0L)
                    val playWhenReady = mediaController.playWhenReady

                    applyVideoQualityInternal(mediaController, VideoQuality.AUTO)
                    mediaController.seekTo(resumePosition)
                    mediaController.prepare()
                    mediaController.playWhenReady = playWhenReady

                    synchronized(connectionLock) {
                        if (controller !== mediaController) return
                        _controllerState.value = _controllerState.value.copy(
                            errorMessage = "Playback reverted to Auto due to decoder limitation at ${activeManualQuality.label}.",
                            isBuffering = false
                        )
                    }
                    return
                }

                synchronized(connectionLock) {
                    if (controller !== mediaController) return
                    lastPlaybackRequestFailureGeneration = pendingPlaybackRequestGeneration
                    _controllerState.value = _controllerState.value.copy(
                        errorMessage = error.message,
                        isBuffering = false,
                        requestFailureGeneration = lastPlaybackRequestFailureGeneration
                    )
                }
            }
        })
    }

    private fun updateState(mediaController: Player) {
        synchronized(connectionLock) {
            if (controller !== mediaController) return
            updateStateInternal(mediaController)
        }
    }

    private fun updateStateInternal(mediaController: Player) {
        val playbackState = mediaController.playbackState
        val isBuffering = playbackState == Player.STATE_BUFFERING
        val isPlaying = mediaController.isPlaying
        val mediaItem = mediaController.currentMediaItem
        val duration = mediaController.duration.coerceAtLeast(0L)

        val allTracks = VideoQualityResolver.allAvailableTracks(mediaController.currentTracks)
        val selectedQuality = selectedTrackQuality(mediaController, allTracks)
        val qualityOptions = VideoQualityResolver.availableQualityOptions(allTracks, capabilityChecker)

        _controllerState.value = MediaControllerState(
            isConnected = true,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentMediaItem = mediaItem,
            currentPositionMs = mediaController.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration == androidx.media3.common.C.TIME_UNSET) 0L else duration,
            playbackState = playbackState,
            errorMessage = null,
            availableVideoHeights = VideoQualityResolver.availableHeights(allTracks),
            selectedVideoHeight = mediaController.videoSize.height.takeIf { it > 0 }
                ?: selectedTrackHeight(mediaController, allTracks),
            videoSize = mediaController.videoSize,
            currentDeliveryMethod = activeDeliveryMethod,
            availableVideoQualities = qualityOptions.map { it.quality },
            availableVideoQualityOptions = qualityOptions,
            selectedVideoQuality = selectedQuality,
            requestFailureGeneration = lastPlaybackRequestFailureGeneration
        )
    }

    private fun selectedTrackQuality(
        mediaController: Player,
        selectable: List<SelectableVideoTrack>
    ): VideoQuality? {
        val selected = selectable.filter { option ->
            mediaController.currentTracks.groups.any { group ->
                group.mediaTrackGroup == option.group && group.isTrackSelected(option.trackIndex)
            }
        }
        return selected.singleOrNull()?.let { VideoQuality.fromTrack(it.width, it.height, it.frameRate) }
    }

    private fun selectedTrackHeight(
        mediaController: Player,
        selectable: List<SelectableVideoTrack> = VideoQualityResolver.allAvailableTracks(mediaController.currentTracks)
    ): Int? {
        val selected = selectable
            .filter { option ->
                mediaController.currentTracks.groups.any { group ->
                    group.mediaTrackGroup == option.group && group.isTrackSelected(option.trackIndex)
                }
            }
            .map { it.height }
            .distinct()
        return selected.singleOrNull()
    }

    private fun startPositionPolling(expectedGen: Long) {
        synchronized(connectionLock) {
            if (connectionGeneration != expectedGen || controller == null) return
            positionPollerJob?.cancel()
            positionPollerJob = scope.launch {
                while (isActive) {
                    val isPlaying = synchronized(connectionLock) {
                        if (connectionGeneration != expectedGen || controller == null) {
                            return@launch
                        }
                        controller?.isPlaying == true
                    }
                    if (isPlaying) {
                        val pos = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                        synchronized(connectionLock) {
                            if (connectionGeneration == expectedGen && controller != null) {
                                _controllerState.value = _controllerState.value.copy(
                                    currentPositionMs = pos
                                )
                            }
                        }
                    }
                    delay(50) // Responsive polling interval for smooth progress & word-level lyrics sync
                }
            }
        }
    }

    open fun play() {
        controller?.play()
    }

    open fun pause() {
        controller?.pause()
    }

    open fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    open fun skipToNext() {
        Log.d(TAG, "LATENCY_T3_GAPLESS seekToNext dispatched t=${System.currentTimeMillis()}")
        controller?.seekToNextMediaItem()
    }

    open fun hasNextMediaItem(): Boolean = controller?.hasNextMediaItem() == true

    /**
     * Media id of the item ExoPlayer currently has loaded (timeline index
     * `currentMediaItemIndex`), read LIVE from the controller. Null when disconnected —
     * and, in unit-test fakes constructed as `MediaControllerClient(null)` that do not
     * override this, likewise null, so the Point 2 preload-consume gate stays dormant on
     * the legacy fallback path.
     */
    open fun currentMediaId(): String? = controller?.currentMediaItem?.mediaId

    /**
     * Media id of the item ExoPlayer has PRELOADED immediately after the current one
     * (structural timeline index `currentMediaItemIndex + 1`), or null when nothing is
     * preloaded / disconnected.
     *
     * Read LIVE from the controller timeline — never from the cached [controllerState]
     * snapshot, which carries no next-item field. ExoPlayer runs REPEAT_MODE_OFF with
     * shuffle disabled, so `index + 1` is an unambiguous structural "next". Used by
     * [PlayerViewModel] to verify the preloaded item's identity before consuming it with a
     * gapless [skipToNext]; a stale/mismatched preload must never be consumed.
     */
    open fun peekNextMediaId(): String? {
        val c = controller ?: return null
        return try {
            val count = c.mediaItemCount
            val nextIndex = c.currentMediaItemIndex + 1
            if (nextIndex in 1 until count) c.getMediaItemAt(nextIndex).mediaId else null
        } catch (e: Exception) {
            null
        }
    }

    open fun skipToPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    open fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    open fun playTrack(track: com.example.sonara.domain.model.Track, streamUrl: String, generation: Long = 0L) {
        playMediaItem(track, streamUrl, generation, mediaKind = null, explicitMimeType = null)
    }

    open fun playTrack(
        track: com.example.sonara.domain.model.Track,
        streamUrl: String,
        generation: Long,
        mimeType: String?
    ) {
        if (mimeType == null) {
            playTrack(track, streamUrl, generation)
        } else {
            playMediaItem(track, streamUrl, generation, mediaKind = null, explicitMimeType = mimeType)
        }
    }

    /** Routes an ephemeral extracted video URL through the existing Media3 service/player. */
    open fun playExtractedVideo(
        track: com.example.sonara.domain.model.Track,
        source: VideoPlaybackSource,
        generation: Long = 0L,
        startPositionMs: Long? = null,
        autoPlay: Boolean = true
    ) {
        if (source.videoId != track.id || source.streamUrl.isBlank()) return
        playMediaItem(
            track = track,
            streamUrl = source.streamUrl,
            generation = generation,
            mediaKind = MEDIA_KIND_EXTRACTED_VIDEO,
            deliveryMethod = source.deliveryMethod,
            startPositionMs = startPositionMs,
            autoPlay = autoPlay
        )
    }

    private fun playMediaItem(
        track: com.example.sonara.domain.model.Track,
        streamUrl: String,
        generation: Long,
        mediaKind: String?,
        deliveryMethod: VideoDeliveryMethod? = null,
        explicitMimeType: String? = null,
        startPositionMs: Long? = null,
        autoPlay: Boolean = true
    ) {
        pendingPlaybackRequestGeneration = generation.takeIf { it > 0L }
        lastPlaybackRequestFailureGeneration = null
        activeDeliveryMethod = deliveryMethod
        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)

        track.artworkUrl?.let {
            try {
                metadataBuilder.setArtworkUri(android.net.Uri.parse(it))
            } catch (_: Exception) {}
        }

        val resolvedMimeType = explicitMimeType ?: resolveDeliveryMimeType(deliveryMethod)

        val requestExtras = android.os.Bundle().apply {
            if (generation > 0L) {
                putLong(EXTRA_REQUEST_GENERATION, generation)
            }
            if (mediaKind != null) {
                putString(EXTRA_MEDIA_KIND, mediaKind)
            }
            if (resolvedMimeType != null) {
                putString(EXTRA_MIME_TYPE, resolvedMimeType)
            }
        }
        metadataBuilder.setExtras(requestExtras)

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriParser(streamUrl))
            .apply {
                if (mediaKind != null) setTag(mediaKind)
                if (resolvedMimeType != null) {
                    setMimeType(resolvedMimeType)
                }
            }
            // Authoritative trackId carrier → propagates to DataSpec.key, enabling
            // RefreshingDataSource 403/410 recovery without touching the proxy URL.
            .setCustomCacheKey(track.id)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uriParser(streamUrl))
                    .setExtras(requestExtras)
                    .build()
            )
            .setMediaMetadata(metadataBuilder.build())
            .build()

        val (ctrl, shouldConnect) = synchronized(connectionLock) {
            val c = controller
            if (c == null) {
                if (canConnect()) {
                    Log.i(TAG, "MediaController is connecting; queueing pending play for trackId=${track.id}")
                    pendingPlayAction = { targetPlayer ->
                        Log.d(TAG, "LATENCY_T3 setMediaItem: trackId=${track.id} t=${System.currentTimeMillis()}")
                        clearVideoTrackOverrides(targetPlayer)
                        if (startPositionMs != null && startPositionMs > 0L) {
                            targetPlayer.setMediaItem(mediaItem, startPositionMs)
                        } else {
                            targetPlayer.setMediaItem(mediaItem)
                        }
                        targetPlayer.prepare()
                        if (autoPlay) {
                            targetPlayer.play()
                        } else {
                            targetPlayer.pause()
                        }
                    }
                    Pair(null, true)
                } else {
                    Pair(null, false)
                }
            } else {
                Pair(c, false)
            }
        }

        if (ctrl == null) {
            if (shouldConnect) {
                connect()
            }
            return
        }

        Log.d(TAG, "LATENCY_T3 setMediaItem: trackId=${track.id} t=${System.currentTimeMillis()}")
        clearVideoTrackOverrides(ctrl)
        if (startPositionMs != null && startPositionMs > 0L) {
            ctrl.setMediaItem(mediaItem, startPositionMs)
        } else {
            ctrl.setMediaItem(mediaItem)
        }
        ctrl.prepare()
        if (autoPlay) {
            ctrl.play()
        } else {
            ctrl.pause()
        }
    }

    /** Clears the current Media3 item without creating a second player or service. */
    open fun clearMediaItems() {
        activeDeliveryMethod = null
        val ctrl = synchronized(connectionLock) {
            pendingPlayAction = null
            controller
        }
        ctrl?.let { player ->
            player.clearMediaItems()
            clearVideoTrackOverrides(player)
        }
    }

    private fun clearVideoTrackOverrides(player: Player) {
        val currentParams = player.trackSelectionParameters
        player.trackSelectionParameters = currentParams.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearVideoSizeConstraints()
            .setPreferredAudioRoleFlags(currentParams.preferredAudioRoleFlags.takeIf { it != 0 } ?: C.ROLE_FLAG_MAIN)
            .build()
    }

    /**
     * Queues a target video quality to be applied when the player loads or transitions tracks.
     * Prevents race conditions during asynchronous DASH manifest reloads.
     */
    open fun setPendingVideoQuality(quality: VideoQuality) {
        pendingVideoQuality = quality
    }

    /** Applies a manual quality override or restores Media3 adaptive selection for Auto. */
    open fun applyVideoQuality(quality: VideoQuality): VideoQualitySelection {
        pendingVideoQuality = quality
        val mediaController = controller ?: return VideoQualitySelection(quality, null)
        if (mediaController.currentTracks.groups.isEmpty()) {
            return VideoQualitySelection(quality, null)
        }
        return applyVideoQualityInternal(mediaController, quality)
    }

    private fun applyVideoQualityInternal(
        mediaController: Player,
        quality: VideoQuality
    ): VideoQualitySelection {
        val currentAudioRoleFlags = mediaController.trackSelectionParameters.preferredAudioRoleFlags
        val parameters = mediaController.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearVideoSizeConstraints()
            .setPreferredAudioRoleFlags(currentAudioRoleFlags.takeIf { it != 0 } ?: C.ROLE_FLAG_MAIN)

        if (quality == VideoQuality.AUTO) {
            val autoOptions = VideoQualityResolver.selectableTracks(mediaController.currentTracks, capabilityChecker)
            val autoGroup = VideoQualityResolver.resolveAutoGroup(autoOptions)
            if (autoGroup != null) {
                val groupBelongsToCurrentTracks = mediaController.currentTracks.groups.any {
                    it.mediaTrackGroup == autoGroup.targetGroup
                }
                if (groupBelongsToCurrentTracks && autoGroup.eligibleTrackIndices.isNotEmpty()) {
                    parameters.setMaxVideoSize(autoGroup.maxEligibleWidth, autoGroup.maxEligibleHeight)
                    parameters.setOverrideForType(
                        TrackSelectionOverride(autoGroup.targetGroup, autoGroup.eligibleTrackIndices)
                    )
                }
            }
            mediaController.trackSelectionParameters = parameters.build()
            updateState(mediaController)
            return VideoQualitySelection(quality, null)
        }

        val allOptions = VideoQualityResolver.allAvailableTracks(mediaController.currentTracks)
        val selection = VideoQualityResolver.resolve(quality, allOptions)

        val selected = selection.selected
        if (selected == null || !selected.isPlayable) {
            mediaController.trackSelectionParameters = parameters.build()
            updateState(mediaController)
            return selection.copy(selected = null)
        }

        // UNIVERSAL DEVICE-ADAPTIVE CAPABILITY ENFORCEMENT:
        // Manual quality selection evaluates whether the device's decoders can physically
        // handle the track (including viable surface rendering on hardware decoders).
        val isDecoderSupported = if (selected.isManualEligible) {
            capabilityChecker?.isManuallySelectable(
                selected.mimeType,
                selected.codecs,
                selected.width,
                selected.height,
                selected.frameRate
            ) ?: false
        } else {
            // Track is genuinely unsupported (e.g. FORMAT_UNSUPPORTED_SUBTYPE / TYPE)
            false
        }

        if (!isDecoderSupported) {
            Log.w(TAG, "Selected track ${selected.width}x${selected.height} (${selected.codecs}) exceeds decoder capabilities. Disallowing manual override.")
            mediaController.trackSelectionParameters = parameters.build()
            updateState(mediaController)
            return selection.copy(selected = null)
        }

        val groupBelongsToCurrentTracks = mediaController.currentTracks.groups.any {
            it.mediaTrackGroup == selected.group
        }
        if (!groupBelongsToCurrentTracks) {
            mediaController.trackSelectionParameters = parameters.build()
            updateState(mediaController)
            return selection.copy(selected = null)
        }

        parameters.setOverrideForType(
            TrackSelectionOverride(selected.group, selected.trackIndex)
        )

        // AUDIO/VIDEO PAIRING:
        // When 8K video (height == 4320) is selected, bind the paired external audio track
        // (identified by format ID ending with "_ext" from yt-dlp session proxy)
        if (selected.height == 4320) {
            val audioPair = findExternalAudioTrack(mediaController.currentTracks)
            if (audioPair != null) {
                parameters.setOverrideForType(
                    TrackSelectionOverride(audioPair.first, audioPair.second)
                )
            }
        }

        mediaController.trackSelectionParameters = parameters.build()
        updateState(mediaController)
        return selection
    }

    private fun findExternalAudioTrack(tracks: androidx.media3.common.Tracks): Pair<androidx.media3.common.TrackGroup, Int>? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val formatId = format.id
                if (formatId != null && (formatId.endsWith("_ext") || formatId.contains("_ext"))) {
                    return Pair(group.mediaTrackGroup, index)
                }
            }
        }
        return null
    }

    private fun isDecoderRelatedError(error: androidx.media3.common.PlaybackException): Boolean {
        val isDecoderCode = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
            else -> false
        }
        if (isDecoderCode) return true

        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is android.media.MediaCodec.CodecException) return true
            val causeName = cause.javaClass.name
            if (causeName.contains("MediaCodec") || causeName.contains("Decoder")) return true
            cause = cause.cause
        }
        return false
    }

    /** Exposes the existing service-owned player for attaching a video rendering surface. */
    open fun currentPlayer(): Player? = controller ?: _currentPlayer.value

    fun playSampleTrack(trackId: String, streamUrl: String, title: String, artist: String) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(trackId)
            .setUri(uriParser(streamUrl))
            .setCustomCacheKey(trackId)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uriParser(streamUrl))
                    .build()
            )
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()

        controller?.setMediaItem(mediaItem)
        controller?.prepare()
        controller?.play()
    }

    open fun setPreferredAudioDevice(deviceId: Int) {
        val ctrl = controller as? MediaController ?: return
        val bundle = android.os.Bundle().apply {
            putInt(EXTRA_DEVICE_ID, deviceId)
        }
        val command = androidx.media3.session.SessionCommand(ACTION_SET_AUDIO_DEVICE, android.os.Bundle.EMPTY)
        ctrl.sendCustomCommand(command, bundle)
        Log.d(TAG, "Sent custom command setPreferredAudioDevice: id=$deviceId")
    }

    fun disconnect() {
        val (futureToRelease, playerToRelease) = synchronized(connectionLock) {
            connectionGeneration++
            pendingPlayAction = null
            val f = controllerFuture
            controllerFuture = null
            val p = controller
            controller = null
            _currentPlayer.value = null
            _controllerState.value = MediaControllerState(isConnected = false)
            Pair(f, p)
        }
        positionPollerJob?.cancel()
        if (futureToRelease != null) {
            MediaController.releaseFuture(futureToRelease)
        } else if (playerToRelease != null) {
            onBeforePlayerRelease(playerToRelease)
            synchronized(lifecycleActionLock) {
                try {
                    playerToRelease.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release player on disconnect: ${e.message}")
                }
            }
        }
    }
}
