package com.example.sonara.domain.model

/**
 * A download paired with the track metadata it refers to.
 *
 * [track] may be null if the cached metadata is unexpectedly missing; consumers should skip
 * rendering identity in that case rather than assume a value.
 */
data class DownloadWithTrack(
    val download: DownloadInfo,
    val track: Track?
)
