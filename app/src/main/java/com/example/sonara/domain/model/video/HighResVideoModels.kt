package com.example.sonara.domain.model.video

import kotlin.math.abs
import kotlin.math.max

/**
 * Representation of a byte range used for DASH SegmentBase Initialization and IndexRange elements.
 */
data class HighResByteRange(
    val start: Long,
    val end: Long
) {
    val size: Long get() = (end - start + 1).coerceAtLeast(0L)

    init {
        require(start >= 0L) { "Byte range start must be non-negative: $start" }
        require(end >= start) { "Byte range end ($end) must be greater than or equal to start ($start)" }
        require(end <= MAX_REASONABLE_OFFSET) { "Byte range end ($end) exceeds reasonable maximum" }
    }

    companion object {
        const val MAX_REASONABLE_OFFSET = 100_000_000_000L // 100 GB
        const val MAX_REASONABLE_INIT_SIZE = 50_000_000L   // 50 MB
    }
}

/**
 * Metadata for a verified 8K (4320p) video representation extracted via yt-dlp backend.
 */
data class HighResVideoRepresentation(
    val itag: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String,
    val mimeType: String,
    val bitrate: Long,
    val duration: Double,
    val initRange: HighResByteRange,
    val indexRange: HighResByteRange,
    val streamProxyUrl: String
) {
    init {
        require(width == 7680 && height == 4320) {
            "High-resolution video must have 7680x4320 dimensions, got ${width}x${height}"
        }
        require(fps in 1..240) { "Video frame rate must be between 1 and 240 fps, got $fps" }
        require(bitrate > 0L && bitrate <= 1_000_000_000L) {
            "Video bitrate must be positive and reasonable (<= 1Gbps), got $bitrate"
        }
        require(duration > 0.0 && duration.isFinite()) {
            "Video duration must be positive and finite, got $duration"
        }
        require(codec.isNotBlank()) { "Video codec cannot be blank" }
        require(mimeType.isNotBlank() && mimeType.startsWith("video/")) {
            "MIME type must be a video type: $mimeType"
        }
        require(streamProxyUrl.isNotBlank()) { "Stream proxy URL cannot be blank" }
        require(indexRange.start > initRange.end) {
            "indexRange start (${indexRange.start}) must be after initRange end (${initRange.end})"
        }
        require(initRange.end - initRange.start <= HighResByteRange.MAX_REASONABLE_INIT_SIZE) {
            "Video initialization range exceeds reasonable size"
        }
    }
}

/**
 * Metadata for the matching audio representation extracted and bound to the same streaming session.
 */
data class HighResAudioRepresentation(
    val itag: Int,
    val codec: String,
    val mimeType: String,
    val bitrate: Long,
    val duration: Double,
    val initRange: HighResByteRange,
    val indexRange: HighResByteRange,
    val streamProxyUrl: String
) {
    init {
        require(bitrate > 0L && bitrate <= 10_000_000L) {
            "Audio bitrate must be positive and reasonable (<= 10Mbps), got $bitrate"
        }
        require(duration > 0.0 && duration.isFinite()) {
            "Audio duration must be positive and finite, got $duration"
        }
        require(codec.isNotBlank()) { "Audio codec cannot be blank" }
        require(mimeType.isNotBlank() && mimeType.startsWith("audio/")) {
            "MIME type must be an audio type: $mimeType"
        }
        require(streamProxyUrl.isNotBlank()) { "Stream proxy URL cannot be blank" }
        require(indexRange.start > initRange.end) {
            "indexRange start (${indexRange.start}) must be after initRange end (${initRange.end})"
        }
        require(initRange.end - initRange.start <= HighResByteRange.MAX_REASONABLE_INIT_SIZE) {
            "Audio initialization range exceeds reasonable size"
        }
    }
}

/**
 * Discovery result contract for backend high-resolution extraction.
 */
sealed interface HighResVideoDiscovery {
    val videoId: String
    val extractedAt: Long
    val expiresAt: Long
    val hasHighRes: Boolean
        get() = this is Available

    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        nowSeconds >= expiresAt

    /**
     * Genuine 8K streams discovered and bound to an active streaming session.
     */
    data class Available(
        override val videoId: String,
        val sessionId: String,
        override val extractedAt: Long,
        override val expiresAt: Long,
        val video: HighResVideoRepresentation,
        val audio: HighResAudioRepresentation
    ) : HighResVideoDiscovery {
        init {
            require(videoId.isNotBlank()) { "videoId cannot be blank" }
            require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
            require(expiresAt > extractedAt) {
                "expiresAt ($expiresAt) must be greater than extractedAt ($extractedAt)"
            }
            val durationDiff = abs(video.duration - audio.duration)
            val maxAllowedDiff = max(15.0, 0.15 * video.duration)
            require(durationDiff <= maxAllowedDiff) {
                "Video duration (${video.duration}s) and audio duration (${audio.duration}s) mismatch significantly"
            }
            require(video.streamProxyUrl.contains("sessionId=$sessionId")) {
                "Video stream proxy URL is not bound to session $sessionId"
            }
            require(audio.streamProxyUrl.contains("sessionId=$sessionId")) {
                "Audio stream proxy URL is not bound to session $sessionId"
            }
        }
    }

    /**
     * Negative discovery: video does not have genuine 8K representations available.
     * Caching this prevents redundant backend probing.
     */
    data class Unavailable(
        override val videoId: String,
        override val extractedAt: Long,
        override val expiresAt: Long
    ) : HighResVideoDiscovery {
        init {
            require(videoId.isNotBlank()) { "videoId cannot be blank" }
        }
    }
}

/**
 * External high-resolution representations descriptor prepared for subsequent DASH MPD integration.
 * Encapsulates the video and audio representations bound to the same session.
 */
data class ExternalHighResStreamDescriptor(
    val sessionId: String,
    val expiresAt: Long,
    val video: HighResVideoRepresentation,
    val audio: HighResAudioRepresentation
) {
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        nowSeconds >= expiresAt
}

/**
 * Convenience conversion from an [HighResVideoDiscovery.Available] result to an [ExternalHighResStreamDescriptor].
 */
fun HighResVideoDiscovery.Available.toExternalDescriptor(): ExternalHighResStreamDescriptor =
    ExternalHighResStreamDescriptor(
        sessionId = sessionId,
        expiresAt = expiresAt,
        video = video,
        audio = audio
    )
