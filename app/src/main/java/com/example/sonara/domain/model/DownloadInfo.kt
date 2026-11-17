package com.example.sonara.domain.model

/**
 * Domain model representing the download state and progress of a track (audio or video).
 */
data class DownloadInfo(
    val trackId: String,
    val localFilePath: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
    val quality: AudioQuality = AudioQuality.VERY_HIGH,
    val mimeType: String = "audio/webm",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val failureReason: String? = null,
    val mediaType: String = "AUDIO",
    val resolutionHeight: Int? = null,
    val frameRate: Int? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val container: String = "unknown"
) {
    val isVideo: Boolean
        get() = mediaType.equals("VIDEO", ignoreCase = true)

    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}
