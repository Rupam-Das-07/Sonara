package com.example.sonara.feature.video

import androidx.media3.common.Player
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.VideoRepeatMode
import com.example.sonara.domain.model.video.VideoQuality
import com.example.sonara.domain.model.video.VideoQualityOption
import com.example.sonara.feature.player.PlayerUiState
import com.example.sonara.playback.client.MediaControllerState

data class VideoQualityUiState(
    val requestedQuality: VideoQuality = VideoQuality.AUTO,
    val availableHeights: List<Int> = emptyList(),
    val actualHeight: Int? = null,
    val isPreparing: Boolean = false,
    val availableQualities: List<VideoQuality> = emptyList(),
    val availableQualityOptions: List<VideoQualityOption> = emptyList(),
    val actualQuality: VideoQuality? = null
)

/**
 * Projects authoritative YouTube state into the existing player UI contract.
 *
 * A missing YouTube position remains unavailable through [PlayerUiState.isPositionAvailable]; the
 * numeric zero is only the compatibility storage value required by the existing song-player UI.
 */
fun Track.toVideoPlayerUiState(
    youtubeState: YouTubePlayerState,
    isFavorite: Boolean = false,
    volume: Float = 0.85f,
    isMuted: Boolean = false,
    isShuffled: Boolean = false,
    repeatMode: VideoRepeatMode = VideoRepeatMode.OFF,
    viewCount: Long? = null,
    isLive: Boolean = youtubeState.isLive,
    isUpcoming: Boolean = youtubeState.isUpcoming
): PlayerUiState {
    val playbackState = when {
        youtubeState.lastError != null -> Player.STATE_IDLE
        youtubeState.lifecycle != YouTubePlayerLifecycle.READY -> Player.STATE_IDLE
        youtubeState.playbackState == YouTubePlaybackState.ENDED -> Player.STATE_ENDED
        youtubeState.playbackState == YouTubePlaybackState.BUFFERING -> Player.STATE_BUFFERING
        else -> Player.STATE_READY
    }
    return PlayerUiState(
        isConnected = true,
        isPlaying = youtubeState.playbackState == YouTubePlaybackState.PLAYING,
        isBuffering = youtubeState.playbackState == YouTubePlaybackState.BUFFERING,
        trackId = id,
        trackTitle = title,
        artistName = artist,
        albumTitle = album,
        artworkUrl = artworkUrl,
        currentPositionMs = youtubeState.currentPositionMs ?: 0L,
        isPositionAvailable = youtubeState.currentPositionMs != null,
        durationMs = if (isLive) 0L else (youtubeState.durationMs ?: 0L),
        playbackState = playbackState,
        isShuffled = isShuffled,
        repeatMode = when (repeatMode) {
            VideoRepeatMode.OFF -> 0
            VideoRepeatMode.ALL -> 1
            VideoRepeatMode.ONE -> 2
        },
        isFavorite = isFavorite,
        volume = volume,
        isMuted = isMuted,
        errorMessage = youtubeState.lastError?.message,
        viewCount = viewCount,
        formattedViewCount = VideoViewCountFormatter.format(
            viewCount = viewCount,
            isLive = isLive && !isUpcoming
        ),
        displayTitle = VideoTitleFormatter.format(title, artist),
        isLive = isLive,
        isUpcoming = isUpcoming
    )
}

fun Track.toMedia3VideoPlayerUiState(
    mediaState: MediaControllerState,
    isFavorite: Boolean = false,
    volume: Float = 0.85f,
    isMuted: Boolean = false,
    isShuffled: Boolean = false,
    repeatMode: VideoRepeatMode = VideoRepeatMode.OFF,
    viewCount: Long? = null,
    isLive: Boolean = false,
    isUpcoming: Boolean = false
): PlayerUiState = PlayerUiState(
    isConnected = mediaState.isConnected,
    isPlaying = mediaState.isPlaying,
    isBuffering = mediaState.isBuffering,
    trackId = id,
    trackTitle = title,
    artistName = artist,
    albumTitle = album ?: "",
    artworkUrl = artworkUrl,
    currentPositionMs = mediaState.currentPositionMs,
    isPositionAvailable = mediaState.currentMediaItem != null,
    durationMs = if (isLive) 0L else mediaState.durationMs,
    playbackState = mediaState.playbackState,
    isShuffled = isShuffled,
    repeatMode = when (repeatMode) {
        VideoRepeatMode.OFF -> 0
        VideoRepeatMode.ALL -> 1
        VideoRepeatMode.ONE -> 2
    },
    isFavorite = isFavorite,
    volume = volume,
    isMuted = isMuted,
    errorMessage = mediaState.errorMessage,
    viewCount = viewCount,
    formattedViewCount = VideoViewCountFormatter.format(
        viewCount = viewCount,
        isLive = isLive && !isUpcoming
    ),
    displayTitle = VideoTitleFormatter.format(title, artist),
    isLive = isLive,
    isUpcoming = isUpcoming
)
