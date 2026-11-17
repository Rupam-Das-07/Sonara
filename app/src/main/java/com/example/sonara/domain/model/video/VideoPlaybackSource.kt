package com.example.sonara.domain.model.video

/**
 * Ephemeral Media3 source selected from a verified video extraction.
 *
 * The direct URL is intentionally kept only in memory at the playback boundary. It must not be
 * persisted, placed in durable queue models, or exposed through [VideoStreamDescriptor].
 */
data class VideoPlaybackSource(
    val videoId: String,
    val streamUrl: String,
    val kind: VideoStreamKind,
    val deliveryMethod: VideoDeliveryMethod,
    val progressiveFallbackUrl: String? = null,
    val durationMs: Long? = null,
    val title: String? = null,
    val uploader: String? = null,
    val viewCount: Long? = null,
    val dashUrl: String? = null,
    val hlsUrl: String? = null,
    val videoOnlyStreams: List<VideoPlaybackStream> = emptyList(),
    val audioOnlyStreams: List<VideoPlaybackStream> = emptyList(),
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val progressiveStreams: List<VideoPlaybackStream> = emptyList()
)
