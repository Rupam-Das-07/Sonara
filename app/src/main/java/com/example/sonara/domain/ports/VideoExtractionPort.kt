package com.example.sonara.domain.ports

import com.example.sonara.domain.model.video.VideoExtractionResult
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoStatus

/** Playback-facing extraction boundary; implementations must preserve the eligibility gate. */
interface VideoExtractionPort {
    suspend fun extractPlaybackSource(
        input: String,
        status: VideoStatus
    ): Result<VideoPlaybackSource>

    suspend fun extractPlaybackSource(
        input: String,
        status: VideoStatus,
        externalHighRes: com.example.sonara.domain.model.video.ExternalHighResStreamDescriptor?
    ): Result<VideoPlaybackSource> = extractPlaybackSource(input, status)

    suspend fun extract(
        input: String,
        status: VideoStatus
    ): Result<VideoExtractionResult> = Result.failure(UnsupportedOperationException("extract not implemented"))
}
