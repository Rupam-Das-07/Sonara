package com.example.sonara.data.repository

import com.example.sonara.data.download.DownloadEngine
import com.example.sonara.data.local.db.dao.DownloadDao
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.DownloadWithTrack
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DownloadRepositoryImpl(
    private val downloadDao: DownloadDao,
    private val downloadEngine: DownloadEngine
) : DownloadRepository {

    override fun observeDownload(trackId: String): Flow<DownloadInfo?> =
        downloadDao.observeDownload(trackId).map { it?.toDomain() }

    override fun observeAllDownloads(): Flow<List<DownloadInfo>> =
        downloadDao.observeAllDownloads().map { list -> list.map { it.toDomain() } }

    override fun observeCompletedDownloads(): Flow<List<DownloadInfo>> =
        downloadDao.observeCompletedDownloads().map { list -> list.map { it.toDomain() } }

    override fun observeDownloadsWithTrack(): Flow<List<DownloadWithTrack>> =
        downloadDao.observeDownloadsWithTracks().map { list ->
            list.map { relation ->
                DownloadWithTrack(
                    download = relation.download.toDomain(),
                    track = relation.track?.toDomain()
                )
            }
        }

    override suspend fun getDownload(trackId: String): DownloadInfo? =
        downloadDao.getDownload(trackId)?.toDomain()

    override suspend fun enqueueDownload(track: Track, quality: AudioQuality?): Result<Unit> =
        downloadEngine.enqueueDownload(track, quality)

    override suspend fun retryDownload(trackId: String): Result<Unit> =
        downloadEngine.retryDownload(trackId)

    override suspend fun cancelDownload(trackId: String): Result<Unit> =
        downloadEngine.cancelDownload(trackId)

    override suspend fun removeDownload(trackId: String): Result<Unit> =
        downloadEngine.removeDownload(trackId)

    override suspend fun removeAllDownloads(): Result<Unit> =
        downloadEngine.removeAllDownloads()

    override suspend fun getDownloadedFileUri(trackId: String): String? =
        downloadEngine.getDownloadedFileUri(trackId)
}
