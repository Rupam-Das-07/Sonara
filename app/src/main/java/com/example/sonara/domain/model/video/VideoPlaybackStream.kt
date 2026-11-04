package com.example.sonara.domain.model.video

/**
 * Ephemeral direct playback stream for a single audio or video representation.
 *
 * Direct stream URLs are short-lived provider data and must remain in memory only during playback.
 */
data class VideoPlaybackStream(
    val url: String,
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
