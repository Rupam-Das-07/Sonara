package com.example.sonara.domain.model.video.download

import com.example.sonara.domain.model.video.VideoQuality

/**
 * Compatibility status for a downloadable video representation on Android.
 */
enum class VideoDownloadOptionCompatibility {
    /** Can be downloaded and remuxed cleanly without transcoding. */
    COMPATIBLE,
    /** Codec combination unsupported on device or container (e.g. Opus into MP4). */
    UNSUPPORTED_CODEC,
    /** Container unsupported by native muxer. */
    UNSUPPORTED_CONTAINER,
    /** Resolution exceeds supported scope (e.g. 4K / 2160p). */
    UNSUPPORTED_RESOLUTION,
    /** Adaptive video track has no matching compatible audio stream. */
    MISSING_AUDIO,
    /** Extraction or stream metadata is insufficient to verify compatibility safely. */
    UNKNOWN_COMPATIBILITY
}

/**
 * Type of delivery stream backing the download option.
 */
enum class VideoDownloadStreamType {
    /** Single combined stream containing both video and audio. */
    PROGRESSIVE,
    /** Separate video and audio streams requiring remuxing. */
    ADAPTIVE
}

/**
 * Supported target container formats for remuxed video downloads.
 */
enum class VideoDownloadContainer(
    val extension: String,
    val mimeType: String
) {
    WEBM("webm", "video/webm"),
    MP4("mp4", "video/mp4")
}

/**
 * Structured downloadable option generated from verified extracted video and audio streams.
 */
data class VideoDownloadOption(
    val quality: VideoQuality,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val videoCodec: String,
    val audioCodec: String?,
    val container: VideoDownloadContainer,
    val streamType: VideoDownloadStreamType,
    val videoStreamUrl: String,
    val audioStreamUrl: String?,
    val videoItag: Int?,
    val audioItag: Int?,
    val estimatedVideoSizeBytes: Long?,
    val estimatedAudioSizeBytes: Long?,
    val estimatedTotalSizeBytes: Long?,
    val videoBitrateKbps: Int?,
    val audioBitrateKbps: Int?,
    val isMergeRequired: Boolean,
    val compatibility: VideoDownloadOptionCompatibility,
    val compatibilityReason: String? = null,
    val actualFps: Int? = null,
    val audioLocale: String? = null,
    val audioTrackId: String? = null
) {
    val displayLabel: String
        get() {
            val fps = fpsLabel
            return if (fps.isNotBlank()) {
                "${height}p · $fps · ${container.name}"
            } else {
                "${height}p · ${container.name}"
            }
        }

    val resolutionLabel: String
        get() = "${height}p"

    val fpsLabel: String
        get() = when {
            actualFps != null && actualFps > 0 -> "$actualFps FPS"
            frameRate >= 45 -> "60 FPS"
            else -> ""
        }

    val isSupported: Boolean
        get() = compatibility == VideoDownloadOptionCompatibility.COMPATIBLE
}

/**
 * Point-in-time snapshot of active video playback telemetry when user opens download sheet.
 */
data class PlaybackQualitySnapshot(
    val renderedHeight: Int?,
    val renderedWidth: Int?,
    val renderedFps: Int?,
    val isActualTelemetry: Boolean,
    val sourceRoute: String
)

/**
 * Reasoning category behind automatic download quality pre-selection.
 */
enum class DownloadPreselectionReason {
    /** Preselected option matches both rendered height and rendered FPS exactly. */
    EXACT_MATCH,
    /** Exact combination unavailable; fell back to highest compatible resolution preserving FPS. */
    FALLBACK_RESOLUTION_SAME_FPS,
    /** Exact combination and same-FPS fallbacks unavailable; fell back to alternative FPS. */
    FALLBACK_RESOLUTION_LOWER_FPS,
    /** Requested height is below all available; chose highest/closest available. */
    FALLBACK_HIGHEST_AVAILABLE,
    /** Telemetry unavailable; defaulted to configured user quality. */
    DEFAULT_CONFIGURED,
    /** IFrame or bridge playback mode prevents obtaining reliable telemetry. */
    PLAYBACK_QUALITY_UNAVAILABLE
}

/**
 * Outcome of pre-selecting an initial download option based on playback telemetry.
 */
data class DownloadPreselectionResult(
    val selectedOption: VideoDownloadOption,
    val reason: DownloadPreselectionReason,
    val explanation: String? = null
)

/**
 * Comprehensive lifecycle stages for download operations (audio and video).
 */
enum class DownloadStage {
    IDLE,
    QUEUED,
    EXTRACTING_STREAMS,
    DOWNLOADING,
    DOWNLOADING_VIDEO,
    DOWNLOADING_AUDIO,
    REMUXING,
    VALIDATING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Real-time progress model providing live speed, ETA, and stage status for active downloads.
 */
data class ActiveDownloadProgress(
    val trackId: String,
    val stage: DownloadStage = DownloadStage.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressFraction: Float? = null,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val speedFormatted: String? = null,
    val etaFormatted: String? = null,
    val stageDescription: String? = null,
    val errorMessage: String? = null
)
