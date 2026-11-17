package com.example.sonara.data.download.remux

import com.example.sonara.domain.model.video.download.VideoDownloadContainer
import java.io.File

/**
 * Contract for native container remuxing of downloaded audio and video streams without transcoding.
 */
interface VideoMediaMuxer {
    /**
     * Remuxes [videoFile] and optional [audioFile] into [outputFile] with container format [container].
     *
     * @param videoFile Path to completed video stream file.
     * @param audioFile Path to completed audio stream file, or null if [videoFile] is progressive.
     * @param outputFile Target file path for the muxed container output.
     * @param container Output container specification (WEBM for VP9+Opus, MP4 for AVC+AAC).
     * @param onProgress Callback receiving progress normalized from 0.0f to 1.0f.
     * @return Result containing [outputFile] on success, or an exception on failure.
     */
    suspend fun remux(
        videoFile: File,
        audioFile: File?,
        outputFile: File,
        container: VideoDownloadContainer,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File>
}
