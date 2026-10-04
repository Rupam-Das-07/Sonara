package com.example.sonara.playback.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

private const val TAG = "ExoPlayerHolder"

/**
 * Encapsulates ExoPlayer instance creation, audio attributes, and lifecycle.
 * Owned exclusively by SonaraPlaybackService.
 */
@OptIn(UnstableApi::class)
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

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(
                eventTime: EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                Log.d(TAG, "C2A_LATENCY_LOAD_START: t=${System.currentTimeMillis()} uri=${loadEventInfo.dataSpec.uri} dataType=${mediaLoadData.dataType}")
            }

            override fun onLoadCompleted(
                eventTime: EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                Log.d(TAG, "C2A_LATENCY_LOAD_COMPLETE: t=${System.currentTimeMillis()} bytesLoaded=${loadEventInfo.bytesLoaded} durationMs=${loadEventInfo.loadDurationMs}")
            }

            override fun onTracksChanged(
                eventTime: EventTime,
                tracks: Tracks
            ) {
                Log.d(TAG, "C2A_LATENCY_TRACKS_CHANGED: t=${System.currentTimeMillis()} groups=${tracks.groups.size}")
            }

            override fun onAudioDecoderInitialized(
                eventTime: EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                Log.d(TAG, "C2A_LATENCY_DECODER_INIT: decoderName=$decoderName initDurationMs=$initializationDurationMs t=${System.currentTimeMillis()}")
            }

            override fun onAudioTrackInitialized(
                eventTime: EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig
            ) {
                Log.d(TAG, "C2A_LATENCY_AUDIO_TRACK_INIT: sampleRate=${audioTrackConfig.sampleRate} channelConfig=${audioTrackConfig.channelConfig} encoding=${audioTrackConfig.encoding} bufferSize=${audioTrackConfig.bufferSize} t=${System.currentTimeMillis()}")
            }

            override fun onRendererReadyChanged(
                eventTime: EventTime,
                rendererIndex: Int,
                rendererTrackType: Int,
                isRendererReady: Boolean
            ) {
                Log.d(TAG, "C2A_LATENCY_RENDERER_READY: rendererIndex=$rendererIndex trackType=$rendererTrackType isReady=$isRendererReady t=${System.currentTimeMillis()}")
            }

            override fun onAudioPositionAdvancing(
                eventTime: EventTime,
                playoutStartSystemTimeMs: Long
            ) {
                Log.d(TAG, "C2A_LATENCY_AUDIO_POSITION: playoutStartSystemTimeMs=$playoutStartSystemTimeMs t=${System.currentTimeMillis()}")
            }
        })
    }

    fun release() {
        player.release()
    }
}
