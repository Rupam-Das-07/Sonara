package com.example.sonara.domain.repository

import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.DownloadWithTrack
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.flow.Flow

/**
 * Domain port managing offline track downloads and storage metadata.
 */
interface DownloadRepository {
    fun observeDownload(trackId: String): Flow<DownloadInfo?>
    fun observeAllDownloads(): Flow<List<DownloadInfo>>
    fun observeCompletedDownloads(): Flow<List<DownloadInfo>>

    /** Downloads joined with cached track metadata (title/artist/artwork), newest first. */
    fun observeDownloadsWithTrack(): Flow<List<DownloadWithTrack>>

    suspend fun getDownload(trackId: String): DownloadInfo?
    suspend fun enqueueDownload(track: Track, quality: AudioQuality? = null): Result<Unit>

    /** Re-attempt a failed/cancelled download from cached metadata (real re-enqueue). */
    suspend fun retryDownload(trackId: String): Result<Unit>

    suspend fun cancelDownload(trackId: String): Result<Unit>
    suspend fun removeDownload(trackId: String): Result<Unit>
    suspend fun removeAllDownloads(): Result<Unit>
    suspend fun getDownloadedFileUri(trackId: String): String?

    /** Live in-memory progress for active audio or video download. */
    fun observeActiveProgress(trackId: String): Flow<com.example.sonara.domain.model.video.download.ActiveDownloadProgress?> =
        kotlinx.coroutines.flow.flowOf(null)

    /** All active downloads across the app with live speed and ETA. */
    fun observeAllActiveProgress(): Flow<Map<String, com.example.sonara.domain.model.video.download.ActiveDownloadProgress>> =
        kotlinx.coroutines.flow.flowOf(emptyMap())

    /** Enqueue a video download with structured quality and container parameters. */
    suspend fun enqueueVideoDownload(
        track: Track,
        option: com.example.sonara.domain.model.video.download.VideoDownloadOption
    ): Result<Unit> = Result.success(Unit)
}
