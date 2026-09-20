package com.example.sonara.playback.controller

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates playback intent, async stream resolution, and ExoPlayer commands.
 * Uses TransitionManager to guarantee that stale async results are never committed.
 */
class PlaybackController(
    private val player: ExoPlayer,
    val queueManager: QueueManager,
    private val transitionManager: TransitionManager,
    private val streamResolverPort: StreamResolverPort,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    companion object {
        private const val TAG = "PlaybackController"
    }

    fun playTrack(track: Track) {
        val generationId = transitionManager.nextGeneration()
        Log.d(TAG, "playTrack [gen=$generationId]: ${track.title}")

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                streamResolverPort.resolveStream(track.id)
            }

            if (!transitionManager.isAuthoritative(generationId)) {
                Log.w(TAG, "Dropping stale stream resolution for track ${track.title} [gen=$generationId]")
                return@launch
            }

            result.onSuccess { streamInfo ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(streamInfo.streamUrl)
                    .setMediaMetadata(metadata)
                    .build()

                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }.onFailure { error ->
                Log.e(TAG, "Stream resolution failed for track ${track.title}: ${error.message}")
            }
        }
    }

    fun play() {
        if (player.mediaItemCount == 0) {
            queueManager.currentTrack()?.let { playTrack(it) }
        } else {
            player.play()
        }
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        // Intra-track seek increments transition generation to invalidate stale position callbacks
        // but does NOT trigger stream resolution.
        transitionManager.nextGeneration()
        player.seekTo(positionMs)
    }

    fun release() {
        scope.cancel()
    }
}
