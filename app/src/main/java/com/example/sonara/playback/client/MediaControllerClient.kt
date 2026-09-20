package com.example.sonara.playback.client

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
    val errorMessage: String? = null
)

/**
 * Client-side bridge connecting UI to SonaraPlaybackService via MediaController.
 */
open class MediaControllerClient(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val TAG = "MediaControllerClient"
        const val ACTION_SET_AUDIO_DEVICE = "com.example.sonara.ACTION_SET_AUDIO_DEVICE"
        const val EXTRA_DEVICE_ID = "device_id"
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var positionPollerJob: Job? = null

    protected val _controllerState = MutableStateFlow(MediaControllerState())
    open val controllerState: StateFlow<MediaControllerState> = _controllerState.asStateFlow()

    var onPlaybackEnded: (() -> Unit)? = null

    open fun connect() {
        if (context == null || controller != null || controllerFuture != null) return

        val sessionToken = SessionToken(
            context,
            ComponentName(context, SonaraPlaybackService::class.java)
        )

        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future

        future.addListener({
            try {
                val mediaController = future.get()
                controller = mediaController
                setupListener(mediaController)
                updateState(mediaController)
                startPositionPolling()
                Log.i(TAG, "Connected to SonaraPlaybackService")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to MediaSessionService: ${e.message}")
                _controllerState.value = _controllerState.value.copy(
                    errorMessage = e.message
                )
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupListener(mediaController: MediaController) {
        mediaController.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateState(mediaController)
                if (playbackState == Player.STATE_ENDED) {
                    Log.i(TAG, "Playback reached STATE_ENDED, triggering onPlaybackEnded")
                    onPlaybackEnded?.invoke()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState(mediaController)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateState(mediaController)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Player error: ${error.message}")
                _controllerState.value = _controllerState.value.copy(
                    errorMessage = error.message,
                    isBuffering = false
                )
            }
        })
    }

    private fun updateState(mediaController: MediaController) {
        val playbackState = mediaController.playbackState
        val isBuffering = playbackState == Player.STATE_BUFFERING
        val isPlaying = mediaController.isPlaying
        val mediaItem = mediaController.currentMediaItem
        val duration = mediaController.duration.coerceAtLeast(0L)

        _controllerState.value = MediaControllerState(
            isConnected = true,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            currentMediaItem = mediaItem,
            currentPositionMs = mediaController.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration == androidx.media3.common.C.TIME_UNSET) 0L else duration,
            playbackState = playbackState,
            errorMessage = null
        )
    }

    private fun startPositionPolling() {
        positionPollerJob?.cancel()
        positionPollerJob = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    if (c.isPlaying) {
                        _controllerState.value = _controllerState.value.copy(
                            currentPositionMs = c.currentPosition.coerceAtLeast(0L)
                        )
                    }
                }
                delay(50) // Responsive polling interval for smooth progress & word-level lyrics sync
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
        controller?.seekToNextMediaItem()
    }

    open fun hasNextMediaItem(): Boolean = controller?.hasNextMediaItem() == true

    open fun skipToPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    open fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    open fun playTrack(track: com.example.sonara.domain.model.Track, streamUrl: String) {
        val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)

        track.artworkUrl?.let {
            try {
                metadataBuilder.setArtworkUri(android.net.Uri.parse(it))
            } catch (_: Exception) {}
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(streamUrl)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(streamUrl))
                    .build()
            )
            .setMediaMetadata(metadataBuilder.build())
            .build()

        controller?.setMediaItem(mediaItem)
        controller?.prepare()
        controller?.play()
    }

    fun playSampleTrack(trackId: String, streamUrl: String, title: String, artist: String) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(trackId)
            .setUri(streamUrl)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(streamUrl))
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
        val ctrl = controller ?: return
        val bundle = android.os.Bundle().apply {
            putInt(EXTRA_DEVICE_ID, deviceId)
        }
        val command = androidx.media3.session.SessionCommand(ACTION_SET_AUDIO_DEVICE, android.os.Bundle.EMPTY)
        ctrl.sendCustomCommand(command, bundle)
        Log.d(TAG, "Sent custom command setPreferredAudioDevice: id=$deviceId")
    }

    fun disconnect() {
        positionPollerJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        _controllerState.value = MediaControllerState(isConnected = false)
    }
}
