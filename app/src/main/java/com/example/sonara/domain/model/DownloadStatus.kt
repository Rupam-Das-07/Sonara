package com.example.sonara.domain.model

/**
 * Lifecycle states for an offline track download.
 */
enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    CANCELLED
}
