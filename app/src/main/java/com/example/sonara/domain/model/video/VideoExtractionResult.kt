package com.example.sonara.domain.model.video

/**
 * Synthetic-testable metadata contract for a future isolated video extractor.
 *
 * This model intentionally contains no direct stream URL and no persistence annotations. A
 * NewPipe-backed implementation must map its response into this result only after the licensing
 * and platform-policy gate is cleared.
 */
data class VideoExtractionResult(
    val videoId: String,
    val title: String? = null,
    val uploader: String? = null,
    val durationMs: Long? = null,
    val thumbnails: List<String> = emptyList(),
    val streams: List<VideoStreamDescriptor> = emptyList(),
    val viewCount: Long? = null,
    val dashUrl: String? = null,
    val hlsUrl: String? = null,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false
)
