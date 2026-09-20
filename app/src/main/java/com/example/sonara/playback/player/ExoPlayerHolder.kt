package com.example.sonara.playback.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Encapsulates ExoPlayer instance creation, audio attributes, and lifecycle.
 * Owned exclusively by SonaraPlaybackService.
 */
class ExoPlayerHolder(
    context: Context,
    dataSourceFactory: DataSource.Factory
) {
    val player: ExoPlayer

    init {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true) // Handles Audio Focus automatically
            .setHandleAudioBecomingNoisy(true)        // Pauses on headset disconnect
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    fun release() {
        player.release()
    }
}
