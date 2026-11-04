package com.example.sonara.domain.model.video

/**
 * Stream classification exposed by an extractor without retaining a direct media URL.
 */
enum class VideoStreamKind {
    COMBINED,
    VIDEO_ONLY,
    AUDIO_ONLY
}

/**
 * Delivery method reported by an extractor such as NewPipe Extractor.
 */
enum class VideoDeliveryMethod {
    PROGRESSIVE_HTTP,
    DASH,
    HLS,
    OTHER
}

/**
 * Ephemeral stream metadata for a future video-extraction boundary.
 *
 * Direct stream URLs are deliberately not part of this contract. They are short-lived provider
 * data and must remain in memory only during a later playback phase.
 */
data class VideoStreamDescriptor(
    val kind: VideoStreamKind,
    val deliveryMethod: VideoDeliveryMethod,
    val container: String? = null,
    val mimeType: String? = null,
    val formatId: Int? = null,
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val bitrateKbps: Int? = null,
    val audioTrackId: String? = null,
    val audioTrackName: String? = null,
    val audioLocale: String? = null,
    val audioTrackType: String? = null
)
