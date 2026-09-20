package com.example.sonara.playback.service

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import com.example.sonara.MainActivity
import com.example.sonara.R
import com.example.sonara.SonaraApp
import com.example.sonara.core.notification.NotificationChannels
import com.example.sonara.data.stream.StreamResolverImpl
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.UserPreferences
import com.example.sonara.playback.controller.NextTrackDecision
import com.example.sonara.playback.controller.PlaybackController
import com.example.sonara.playback.controller.PlaybackQueueEngine
import com.example.sonara.playback.controller.PreviousTrackDecision
import com.example.sonara.playback.controller.QueueManager
import com.example.sonara.playback.controller.TransitionManager
import com.example.sonara.playback.player.EqualizerManager
import com.example.sonara.playback.player.ExoPlayerHolder
import com.example.sonara.playback.player.datasource.RefreshingDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Authoritative Android Foreground Service owning ExoPlayer and MediaSession.
 * Implements Phase 4B-1 Playback Architecture.
 *
 * Phase 1 additions:
 *
 * [stopMusicOnTaskClear]:
 *   When this preference is ON, [onTaskRemoved] unconditionally stops the service.
 *   When OFF (default), the existing behaviour applies:
 *     - stop only if player is idle / has no items
 *     - continue if actually playing (user gets background music)
 *
 * [equalizerEnabled / equalizerBandGains]:
 *   [EqualizerManager] is attached to the ExoPlayer audio session in [onCreate].
 *   It is released in [onDestroy] before ExoPlayer.release().
 *   Preferences are observed reactively and applied to the live EQ.
 *   If the device does not support hardware EQ, [EqualizerManager.isSupported]
 *   is false and all operations are no-ops — playback is unaffected.
 */
class SonaraPlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "SonaraPlaybackService"
        const val ACTION_TOGGLE_FAVORITE = "com.example.sonara.ACTION_TOGGLE_FAVORITE"
        const val ACTION_OPEN_EXPANDED_PLAYER = "com.example.sonara.ACTION_OPEN_EXPANDED_PLAYER"
        const val EXTRA_OPEN_EXPANDED_PLAYER = "com.example.sonara.EXTRA_OPEN_EXPANDED_PLAYER"
        private const val ARTWORK_BOUND_PX = 512

        /**
         * Pure controller classification helpers used for authorization decisions
         * in both onConnect() and onCustomCommand().
         */
        internal fun isInternalController(
            controllerPackageName: String,
            controllerUid: Int,
            expectedPackageName: String,
            expectedUid: Int
        ): Boolean {
            return controllerPackageName == expectedPackageName && controllerUid == expectedUid
        }

        internal fun isTrustedPlatformController(
            isNotificationController: Boolean,
            isTrusted: Boolean,
            controllerUid: Int,
            controllerPackageName: String
        ): Boolean {
            if (isNotificationController) return true
            if (isTrusted) return true
            if (controllerUid == android.os.Process.SYSTEM_UID) return true
            if (controllerPackageName == "com.android.systemui" &&
                (isTrusted || controllerUid == android.os.Process.SYSTEM_UID)) {
                return true
            }
            return false
        }
    }

    private var exoPlayerHolder: ExoPlayerHolder? = null
    private var forwardingPlayer: SonaraForwardingPlayer? = null
    private var mediaSession: MediaSession? = null
    private var playbackController: PlaybackController? = null
    private var equalizerManager: EqualizerManager? = null

    /** Bounded in-memory software bitmap cache for IPC MediaMetadata delivery. */
    private val artworkCache = LruCache<String, ByteArray>(10)
    private var artworkLoadJob: kotlinx.coroutines.Job? = null
    private var likedObserverJob: kotlinx.coroutines.Job? = null
    private var isCurrentTrackLiked: Boolean = false

    /** Coroutine scope for preference observation within this service's lifecycle. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Cached value of the stopMusicOnTaskClear preference.
     * Updated reactively via [observePreferences].
     */
    @Volatile
    private var stopMusicOnTaskClear: Boolean = false

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SonaraPlaybackService onCreate")

        val streamResolver = (application as? SonaraApp)?.container?.streamResolver ?: StreamResolverImpl()
        val dataSourceFactory = RefreshingDataSource.Factory(this, streamResolver)

        val playerHolder = ExoPlayerHolder(this, dataSourceFactory)
        exoPlayerHolder = playerHolder

        val forwardingPlayer = SonaraForwardingPlayer(
            player = playerHolder.player,
            queueEngineProvider = { (application as? SonaraApp)?.container?.playbackQueueEngine },
            onSeekToNext = { advanceToNext() },
            onSeekToPrevious = { advanceToPrevious() }
        )
        this.forwardingPlayer = forwardingPlayer

        // Wire PlaybackQueueEngine queue changes to invalidate commands and update preloaded next track
        (application as? SonaraApp)?.container?.playbackQueueEngine?.let { qe ->
            qe.onQueueChanged = {
                serviceScope.launch(Dispatchers.Main) {
                    forwardingPlayer.invalidateAvailableCommands()
                    preloadNextTrackAhead()
                }
            }
        }

        // Auto-advance & transition listener for instant gapless transitions and lookahead buffering
        playerHolder.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    Log.i(TAG, "onMediaItemTransition: mediaId=${mediaItem.mediaId}, reason=$reason")
                    handleArtworkForMediaItem(mediaItem)
                    handleTrackTransition(mediaItem.mediaId)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    Log.i(TAG, "ExoPlayer reached STATE_ENDED -> advanceToNext()")
                    advanceToNext()
                }
            }
        })

        // ─── Equalizer setup ──────────────────────────────────────────────────
        // Bind hardware EQ to ExoPlayer's audio session.
        // audioSessionId is valid immediately after ExoPlayer construction.
        val sessionId = playerHolder.player.audioSessionId
        if (sessionId != 0) {
            equalizerManager = EqualizerManager(sessionId).also {
                if (!it.isSupported) {
                    Log.i(TAG, "Hardware equalizer not available on this device — EQ will be a no-op")
                }
            }
        } else {
            Log.w(TAG, "ExoPlayer returned audioSessionId=0 — equalizer cannot be attached")
        }

        val queueManager = QueueManager(emptyList())
        val transitionManager = TransitionManager()
        val controller = PlaybackController(
            player = playerHolder.player,
            queueManager = queueManager,
            transitionManager = transitionManager,
            streamResolverPort = streamResolver
        )
        playbackController = controller

        val sessionCallback = object : MediaSession.Callback {
            @OptIn(UnstableApi::class)
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val isInternal = isInternalController(controller)
                val isTrustedPlatform = isTrustedPlatformController(session, controller)

                val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                if (isInternal) {
                    sessionCommandsBuilder.add(
                        androidx.media3.session.SessionCommand(
                            com.example.sonara.playback.client.MediaControllerClient.ACTION_SET_AUDIO_DEVICE,
                            android.os.Bundle.EMPTY
                        )
                    )
                    sessionCommandsBuilder.add(
                        androidx.media3.session.SessionCommand(ACTION_TOGGLE_FAVORITE, android.os.Bundle.EMPTY)
                    )
                } else if (isTrustedPlatform) {
                    sessionCommandsBuilder.add(
                        androidx.media3.session.SessionCommand(ACTION_TOGGLE_FAVORITE, android.os.Bundle.EMPTY)
                    )
                }
                val sessionCommands = sessionCommandsBuilder.build()

                val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setAvailablePlayerCommands(playerCommands)
                    .build()
            }

            @Suppress("DEPRECATION")
            override fun onPlayerCommandRequest(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                playerCommand: Int
            ): Int {
                return when (playerCommand) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                        androidx.media3.session.SessionResult.RESULT_SUCCESS
                    }
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                        androidx.media3.session.SessionResult.RESULT_SUCCESS
                    }
                    else -> super.onPlayerCommandRequest(session, controller, playerCommand)
                }
            }

            @OptIn(UnstableApi::class)
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: androidx.media3.session.SessionCommand,
                args: android.os.Bundle
            ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                val isInternal = isInternalController(controller)
                val isTrustedPlatform = isTrustedPlatformController(session, controller)

                if (customCommand.customAction == com.example.sonara.playback.client.MediaControllerClient.ACTION_SET_AUDIO_DEVICE) {
                    if (!isInternal) {
                        Log.w(TAG, "Unauthorized ACTION_SET_AUDIO_DEVICE rejected from UID ${controller.uid}")
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            androidx.media3.session.SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                        )
                    }
                    val deviceId = args.getInt(com.example.sonara.playback.client.MediaControllerClient.EXTRA_DEVICE_ID, 0)
                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                    val targetDevice = if (deviceId > 0 && audioManager != null) {
                        audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).find { it.id == deviceId }
                    } else {
                        null
                    }
                    exoPlayerHolder?.player?.setPreferredAudioDevice(targetDevice)
                    Log.i(TAG, "Applied setPreferredAudioDevice: id=$deviceId, name=${targetDevice?.productName}")
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                    )
                }
                if (customCommand.customAction == ACTION_TOGGLE_FAVORITE) {
                    if (!isInternal && !isTrustedPlatform) {
                        Log.w(TAG, "Unauthorized ACTION_TOGGLE_FAVORITE rejected from UID ${controller.uid}")
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            androidx.media3.session.SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                        )
                    }
                    val container = (application as? SonaraApp)?.container
                    val currentTrack = container?.playbackQueueEngine?.currentTrack
                    if (currentTrack != null) {
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                val currentlyLiked = container.libraryRepository.isLiked(currentTrack.id).firstOrNull() ?: false
                                container.libraryRepository.setLiked(currentTrack, !currentlyLiked)
                                Log.i(TAG, "Toggled favorite for ${currentTrack.title}: was $currentlyLiked -> now ${!currentlyLiked}")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to toggle favorite: ${e.message}")
                            }
                        }
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
                        )
                    }
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        androidx.media3.session.SessionResult(SessionError.ERROR_INVALID_STATE)
                    )
                }
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                )
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<androidx.media3.common.MediaItem>
            ): com.google.common.util.concurrent.ListenableFuture<MutableList<androidx.media3.common.MediaItem>> {
                // The stream URI is always set in requestMetadata.mediaUri by MediaControllerClient
                // before sending the MediaItem to the session. No local fallback is needed.
                val updatedMediaItems = mediaItems.map { item ->
                    val builder = item.buildUpon()
                    if (item.localConfiguration == null) {
                        val uri = item.requestMetadata.mediaUri
                        if (uri != null) {
                            builder.setUri(uri)
                        }
                    }
                    val artworkUrl = item.mediaMetadata.artworkUri?.toString()
                    val cachedBytes = artworkUrl?.let { artworkCache.get(it) }
                    if (cachedBytes != null && cachedBytes.isNotEmpty()) {
                        val updatedMetadata = item.mediaMetadata.buildUpon()
                            .setArtworkData(cachedBytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            .build()
                        builder.setMediaMetadata(updatedMetadata)
                    }
                    builder.build()
                }.toMutableList()
                return com.google.common.util.concurrent.Futures.immediateFuture(updatedMediaItems)
            }
        }

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_EXPANDED_PLAYER
            putExtra(EXTRA_OPEN_EXPANDED_PLAYER, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val session = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(sessionCallback)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
        mediaSession = session

        updateMediaButtons(false)

        // ─── Bind Media3 to Sonara's frozen playback channel & small icon ─────
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NotificationChannels.CHANNEL_PLAYBACK)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_sonara)
        setMediaNotificationProvider(notificationProvider)

        // ─── Observe user preferences reactively ──────────────────────────────
        observePreferences()
    }

    /**
     * Observe the settings repository for EQ and task-clear preferences.
     * Applied immediately on first emission and on every subsequent change.
     */
    private fun observePreferences() {
        val settingsRepository = runCatching {
            (application as? SonaraApp)?.container?.settingsRepository
        }.getOrNull() ?: run {
            Log.w(TAG, "SettingsRepository not available — using defaults for EQ / task-clear")
            return
        }

        settingsRepository.getUserPreferences()
            .map { it.stopMusicOnTaskClear }
            .distinctUntilChanged()
            .onEach { enabled ->
                stopMusicOnTaskClear = enabled
                Log.d(TAG, "stopMusicOnTaskClear = $enabled")
            }
            .launchIn(serviceScope)

        settingsRepository.getUserPreferences()
            .map { Triple(it.equalizerEnabled, it.equalizerBandGains, Unit) }
            .distinctUntilChanged()
            .onEach { (enabled, bandGains, _) ->
                applyEqualizerPreferences(enabled, bandGains)
            }
            .launchIn(serviceScope)
    }

    /**
     * Apply equalizer preferences to the active [EqualizerManager].
     * Safe to call multiple times — idempotent for the same values.
     */
    private fun applyEqualizerPreferences(enabled: Boolean, bandGains: List<Int>) {
        val eq = equalizerManager ?: return
        if (!eq.isSupported) return
        eq.applyBandGains(bandGains)
        eq.setEnabled(enabled)
        Log.d(TAG, "Equalizer applied: enabled=$enabled, gains=$bandGains")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    internal fun isInternalController(controller: MediaSession.ControllerInfo): Boolean {
        return isInternalController(
            controllerPackageName = controller.packageName,
            controllerUid = controller.uid,
            expectedPackageName = packageName,
            expectedUid = android.os.Process.myUid()
        )
    }

    @OptIn(UnstableApi::class)
    internal fun isTrustedPlatformController(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): Boolean {
        val isNotifCtrl = try {
            session.isMediaNotificationController(controller)
        } catch (_: Throwable) {
            false
        }
        return isTrustedPlatformController(
            isNotificationController = isNotifCtrl,
            isTrusted = controller.isTrusted,
            controllerUid = controller.uid,
            controllerPackageName = controller.packageName
        )
    }

    /**
     * Called when the user removes Sonara from the Recents list.
     *
     * [stopMusicOnTaskClear] = true  → unconditionally stop the service.
     * [stopMusicOnTaskClear] = false → original behaviour:
     *   stop only if player is idle; continue if actively playing (background music).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (stopMusicOnTaskClear) {
            Log.i(TAG, "onTaskRemoved: stopMusicOnTaskClear=true — stopping playback")
            exoPlayerHolder?.player?.stop()
            stopSelf()
            return
        }
        // Original behaviour: stop only when idle
        val player = exoPlayerHolder?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "SonaraPlaybackService onDestroy")
        likedObserverJob?.cancel()
        artworkLoadJob?.cancel()
        preloadJob?.cancel()
        artworkCache.evictAll()
        serviceScope.cancel()

        // Release EQ before ExoPlayer to avoid attaching to a dead session
        equalizerManager?.release()
        equalizerManager = null

        playbackController?.release()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayerHolder = null
        forwardingPlayer = null
        super.onDestroy()
    }

    private fun advanceToNext() {
        val qe = (application as? SonaraApp)?.container?.playbackQueueEngine ?: return
        val currentTrackId = exoPlayerHolder?.player?.currentMediaItem?.mediaId
        val decision = qe.advance(fromTrackId = currentTrackId)
        Log.i(TAG, "advanceToNext: currentTrackId=$currentTrackId, decision=$decision")

        when (decision) {
            is NextTrackDecision.PlayTrack -> {
                exoPlayerHolder?.player?.pause()
                playTrackInternal(decision.track)
            }
            is NextTrackDecision.ReplayCurrent -> {
                exoPlayerHolder?.player?.seekTo(0L)
                exoPlayerHolder?.player?.play()
            }
            is NextTrackDecision.NeedRecommendations -> {
                exoPlayerHolder?.player?.pause()
                fetchRecommendationsAndAdvance(decision.seedTrackId)
            }
            is NextTrackDecision.StopPlayback -> {
                exoPlayerHolder?.player?.pause()
            }
        }
    }

    private fun fetchRecommendationsAndAdvance(seedTrackId: String) {
        val discoveryRepo = (application as? SonaraApp)?.container?.discoveryRepository ?: return
        val qe = (application as? SonaraApp)?.container?.playbackQueueEngine ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                discoveryRepo.getRelatedTracks(seedTrackId).onSuccess { candidates ->
                    qe.ingestRecommendations(candidates)
                    val nextDecision = qe.advance()
                    if (nextDecision is NextTrackDecision.PlayTrack) {
                        playTrackInternal(nextDecision.track)
                    } else {
                        Log.i(TAG, "No valid candidates after recommendation fetch -> stopping")
                        withContext(Dispatchers.Main) {
                            exoPlayerHolder?.player?.pause()
                        }
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Recommendation fetch failed: ${err.message}")
                    withContext(Dispatchers.Main) {
                        exoPlayerHolder?.player?.pause()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching recommendations: ${e.message}")
            }
        }
    }

    private fun advanceToPrevious() {
        val player = exoPlayerHolder?.player ?: return
        val qe = (application as? SonaraApp)?.container?.playbackQueueEngine ?: return
        val currentPos = player.currentPosition
        val decision = qe.previous(currentPos)
        Log.i(TAG, "advanceToPrevious: currentPos=$currentPos, decision=$decision")

        when (decision) {
            is PreviousTrackDecision.SeekToStart,
            is PreviousTrackDecision.None -> {
                player.seekTo(0L)
            }
            is PreviousTrackDecision.PlayTrack -> {
                playTrackInternal(decision.track)
            }
        }
    }

    private fun playTrackInternal(track: Track) {
        val container = (application as? SonaraApp)?.container
        val downloadRepo = container?.downloadRepository
        val settingsRepo = container?.settingsRepository
        val historyRepo = container?.historyRepository

        serviceScope.launch(Dispatchers.IO) {
            // 1. Check local download
            val localFilePath = downloadRepo?.getDownloadedFileUri(track.id)
            if (localFilePath != null && localFilePath.isNotBlank()) {
                Log.i(TAG, "playTrackInternal: Playing offline downloaded track ${track.title}")
                withContext(Dispatchers.Main) {
                    setPlayerMediaItem(track, "file://$localFilePath")
                }
                recordHistory(historyRepo, track)
                return@launch
            }

            // 2. Resolve remote stream
            val quality = settingsRepo?.getUserPreferences()?.firstOrNull()?.streamingQuality ?: AudioQuality.AUTO
            val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0
            val streamResolver = (application as? SonaraApp)?.container?.streamResolver ?: StreamResolverImpl()
            val streamResult = streamResolver.resolveStream(
                trackId = track.id,
                quality = quality,
                title = track.title,
                artist = track.artist,
                durationSeconds = durationSec
            )

            streamResult.onSuccess { streamInfo ->
                withContext(Dispatchers.Main) {
                    setPlayerMediaItem(track, streamInfo.streamUrl)
                }
                recordHistory(historyRepo, track)
            }.onFailure { error ->
                Log.e(TAG, "playTrackInternal stream resolution failed for ${track.title}: ${error.message}")
            }
        }
    }

    private fun updateMediaButtons(isLiked: Boolean) {
        val session = mediaSession ?: return
        val favButton = androidx.media3.session.CommandButton.Builder()
            .setSessionCommand(androidx.media3.session.SessionCommand(ACTION_TOGGLE_FAVORITE, android.os.Bundle.EMPTY))
            .setDisplayName(getString(if (isLiked) R.string.control_unfavorite else R.string.control_favorite))
            .setIconResId(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            .setEnabled(true)
            .build()

        val mediaButtons = listOf(
            androidx.media3.session.CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName(getString(R.string.control_previous))
                .setEnabled(true)
                .build(),
            androidx.media3.session.CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .setDisplayName(getString(R.string.control_play_pause))
                .setEnabled(true)
                .build(),
            androidx.media3.session.CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName(getString(R.string.control_next))
                .setEnabled(true)
                .build(),
            favButton
        )
        session.setMediaButtonPreferences(mediaButtons)
    }

    private fun observeLikedState(track: Track?) {
        likedObserverJob?.cancel()
        if (track == null || track.id.isBlank()) {
            isCurrentTrackLiked = false
            updateMediaButtons(false)
            return
        }
        val libraryRepo = (application as? SonaraApp)?.container?.libraryRepository ?: run {
            updateMediaButtons(false)
            return
        }
        likedObserverJob = libraryRepo.isLiked(track.id)
            .distinctUntilChanged()
            .onEach { liked ->
                isCurrentTrackLiked = liked
                updateMediaButtons(liked)
            }
            .launchIn(serviceScope)
    }

    private fun handleArtworkForMediaItem(mediaItem: androidx.media3.common.MediaItem) {
        artworkLoadJob?.cancel()
        val mediaId = mediaItem.mediaId
        val qe = (application as? SonaraApp)?.container?.playbackQueueEngine
        val track = if (qe?.currentTrack?.id == mediaId) qe.currentTrack else null
        val artworkUrl = mediaItem.mediaMetadata.artworkUri?.toString() ?: track?.artworkUrl

        if (artworkUrl.isNullOrBlank()) {
            forwardingPlayer?.setArtworkDataForMediaId(mediaId, null)
            return
        }

        val cached = artworkCache.get(artworkUrl)
        if (cached != null && cached.isNotEmpty()) {
            forwardingPlayer?.setArtworkDataForMediaId(mediaId, cached)
            return
        }

        val existingData = mediaItem.mediaMetadata.artworkData
        if (existingData != null && existingData.isNotEmpty()) {
            artworkCache.put(artworkUrl, existingData)
            forwardingPlayer?.setArtworkDataForMediaId(mediaId, existingData)
            return
        }

        // Reset artwork for new track until fresh bytes arrive
        forwardingPlayer?.setArtworkDataForMediaId(mediaId, null)

        artworkLoadJob = serviceScope.launch(Dispatchers.IO) {
            val bytes = loadArtworkBytes(artworkUrl)
            if (bytes != null && isActive) {
                withContext(Dispatchers.Main) {
                    val currentId = exoPlayerHolder?.player?.currentMediaItem?.mediaId
                    if (currentId == mediaId) {
                        forwardingPlayer?.setArtworkDataForMediaId(mediaId, bytes)
                    }
                }
            }
        }
    }

    private suspend fun fetchBitmapBytes(url: String): ByteArray? {
        return try {
            val request = ImageRequest.Builder(this@SonaraPlaybackService)
                .data(url)
                .allowHardware(false)
                .size(ARTWORK_BOUND_PX, ARTWORK_BOUND_PX)
                .build()
            val result = imageLoader.execute(request)
            val drawable = (result as? SuccessResult)?.drawable ?: return null
            val bitmap = (drawable as? BitmapDrawable)?.bitmap
                ?: drawable.toBitmap()
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val bytes = stream.toByteArray()
            if (bytes.isNotEmpty()) bytes else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch bitmap bytes for $url: ${e.message}")
            null
        }
    }

    private suspend fun loadArtworkBytes(url: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        val cached = artworkCache.get(url)
        if (cached != null) return@withContext cached

        val bytes = fetchBitmapBytes(url)
            ?: if (url.contains("maxresdefault.jpg")) {
                fetchBitmapBytes(url.replace("maxresdefault.jpg", "hqdefault.jpg"))
            } else {
                null
            }

        if (bytes != null && bytes.isNotEmpty()) {
            artworkCache.put(url, bytes)
        }
        bytes
    }

    private fun buildMediaItem(track: Track, streamUrl: String, artworkBytes: ByteArray? = null): androidx.media3.common.MediaItem {
        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)

        val bytes = artworkBytes ?: track.artworkUrl?.let { artworkCache.get(it) }
        if (bytes != null && bytes.isNotEmpty()) {
            metadataBuilder.setArtworkData(bytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        track.artworkUrl?.let {
            try {
                metadataBuilder.setArtworkUri(android.net.Uri.parse(it))
            } catch (_: Exception) {}
        }

        return androidx.media3.common.MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl)
            .setRequestMetadata(
                androidx.media3.common.MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(streamUrl))
                    .build()
            )
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun setPlayerMediaItem(track: Track, streamUrl: String) {
        val player = exoPlayerHolder?.player ?: return
        val cachedBytes = track.artworkUrl?.let { artworkCache.get(it) }
        val mediaItem = buildMediaItem(track, streamUrl, cachedBytes)
        if (cachedBytes != null && cachedBytes.isNotEmpty()) {
            forwardingPlayer?.setArtworkDataForMediaId(track.id, cachedBytes)
        }
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        observeLikedState(track)
        preloadNextTrackAhead()

        if (cachedBytes == null) {
            handleArtworkForMediaItem(mediaItem)
        }
    }

    private fun handleTrackTransition(newTrackId: String) {
        val player = exoPlayerHolder?.player ?: return
        val container = (application as? SonaraApp)?.container
        val qe = container?.playbackQueueEngine ?: return

        if (qe.currentTrack?.id != newTrackId) {
            qe.advance(fromTrackId = qe.currentTrack?.id)
        }
        if (player.currentMediaItemIndex > 0) {
            player.removeMediaItem(0)
        }
        val currentTrack = if (qe.currentTrack?.id == newTrackId) {
            qe.currentTrack
        } else {
            qe.upcomingQueue.find { it.id == newTrackId } ?: qe.currentTrack
        }
        observeLikedState(currentTrack)
        currentTrack?.let { recordHistory(container?.historyRepository, it) }
        preloadNextTrackAhead()
    }

    private var preloadJob: kotlinx.coroutines.Job? = null

    private fun preloadNextTrackAhead() {
        preloadJob?.cancel()
        val player = exoPlayerHolder?.player ?: return
        val container = (application as? SonaraApp)?.container ?: return
        val qe = container.playbackQueueEngine
        val streamResolver = container.streamResolver
        val downloadRepo = container.downloadRepository
        val settingsRepo = container.settingsRepository
        val discoveryRepo = container.discoveryRepository

        preloadJob = serviceScope.launch(Dispatchers.IO) {
            try {
                var nextTrack = qe.upcomingQueue.firstOrNull() ?: qe.recommendationCache.firstOrNull()
                if (nextTrack == null && qe.currentTrack != null) {
                    val currentId = qe.currentTrack!!.id
                    if (currentId.isNotBlank()) {
                        discoveryRepo.getRelatedTracks(currentId).onSuccess { candidates ->
                            qe.ingestRecommendations(candidates)
                        }
                        nextTrack = qe.recommendationCache.firstOrNull()
                    }
                }
                if (nextTrack == null || nextTrack.id.isBlank()) return@launch

                var alreadyPreloaded = false
                withContext(Dispatchers.Main) {
                    if (player.mediaItemCount > 1) {
                        if (player.getMediaItemAt(1).mediaId == nextTrack.id) {
                            alreadyPreloaded = true
                        } else {
                            player.removeMediaItem(1)
                        }
                    }
                }
                if (alreadyPreloaded) {
                    Log.d(TAG, "preloadNextTrackAhead: Track ${nextTrack.id} is already preloaded at index 1")
                    return@launch
                }

                val artworkDeferred = async(Dispatchers.IO) {
                    loadArtworkBytes(nextTrack.artworkUrl)
                }

                val localFilePath = downloadRepo.getDownloadedFileUri(nextTrack.id)
                val streamUrl = if (localFilePath != null && localFilePath.isNotBlank()) {
                    "file://$localFilePath"
                } else {
                    val quality = settingsRepo.getUserPreferences().firstOrNull()?.streamingQuality ?: AudioQuality.AUTO
                    val durationSec = if (nextTrack.durationMs > 0) (nextTrack.durationMs / 1000).toInt() else 0
                    streamResolver.resolveStream(
                        trackId = nextTrack.id,
                        quality = quality,
                        title = nextTrack.title,
                        artist = nextTrack.artist,
                        durationSeconds = durationSec
                    ).getOrNull()?.streamUrl
                }

                val preloadedArtworkBytes = artworkDeferred.await()

                if (streamUrl != null && isActive) {
                    withContext(Dispatchers.Main) {
                        val p = exoPlayerHolder?.player ?: return@withContext
                        if (p.mediaItemCount == 1 && qe.currentTrack?.id != nextTrack.id) {
                            p.addMediaItem(buildMediaItem(nextTrack, streamUrl, preloadedArtworkBytes))
                            Log.i(TAG, "Preloaded next track into ExoPlayer: ${nextTrack.title}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "preloadNextTrackAhead non-fatal: ${e.message}")
            }
        }
    }

    private fun recordHistory(historyRepo: com.example.sonara.domain.repository.HistoryRepository?, track: Track) {
        if (historyRepo == null) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                historyRepo.recordHistory(track, completed = false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record history: ${e.message}")
            }
        }
    }
}
