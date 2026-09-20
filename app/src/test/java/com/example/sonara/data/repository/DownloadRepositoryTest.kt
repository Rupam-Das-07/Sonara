package com.example.sonara.data.repository

import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.DownloadWithTrack
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadRepositoryTest {

    private class FakeDownloadRepository : DownloadRepository {
        private val downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())

        override fun observeDownload(trackId: String): Flow<DownloadInfo?> =
            downloads.map { it[trackId] }

        override fun observeAllDownloads(): Flow<List<DownloadInfo>> =
            downloads.map { it.values.toList() }

        override fun observeCompletedDownloads(): Flow<List<DownloadInfo>> =
            downloads.map { it.values.filter { d -> d.status == DownloadStatus.DOWNLOADED } }

        override suspend fun getDownload(trackId: String): DownloadInfo? =
            downloads.value[trackId]

        override suspend fun enqueueDownload(track: Track, quality: AudioQuality?): Result<Unit> {
            val existing = downloads.value[track.id]
            if (existing != null && existing.status == DownloadStatus.DOWNLOADED) {
                return Result.success(Unit)
            }
            val newInfo = DownloadInfo(
                trackId = track.id,
                localFilePath = "/data/user/0/com.example.sonara/files/sonara_downloads/${track.id}.webm",
                totalBytes = 4096000L,
                downloadedBytes = 0L,
                status = DownloadStatus.DOWNLOADING,
                quality = quality ?: AudioQuality.VERY_HIGH
            )
            downloads.value = downloads.value + (track.id to newInfo)
            return Result.success(Unit)
        }

        override suspend fun cancelDownload(trackId: String): Result<Unit> {
            downloads.value = downloads.value - trackId
            return Result.success(Unit)
        }

        override suspend fun removeDownload(trackId: String): Result<Unit> {
            downloads.value = downloads.value - trackId
            return Result.success(Unit)
        }

        override suspend fun removeAllDownloads(): Result<Unit> {
            downloads.value = emptyMap()
            return Result.success(Unit)
        }

        override suspend fun getDownloadedFileUri(trackId: String): String? {
            val d = downloads.value[trackId]
            return if (d?.status == DownloadStatus.DOWNLOADED) d.localFilePath else null
        }

        fun simulateComplete(trackId: String) {
            val d = downloads.value[trackId] ?: return
            downloads.value = downloads.value + (trackId to d.copy(
                status = DownloadStatus.DOWNLOADED,
                downloadedBytes = d.totalBytes,
                completedAt = System.currentTimeMillis()
            ))
        }

        fun simulateProgress(trackId: String, downloaded: Long) {
            val d = downloads.value[trackId] ?: return
            downloads.value = downloads.value + (trackId to d.copy(
                downloadedBytes = downloaded
            ))
        }

        // Stub: maps existing fake DownloadInfo state to DownloadWithTrack(download, null).
        // Track identity is not exercised by DownloadRepositoryTest.
        override fun observeDownloadsWithTrack(): Flow<List<DownloadWithTrack>> =
            downloads.map { map -> map.values.map { DownloadWithTrack(it, null) } }

        // Stub: retry behaviour is tested at the mapper level.
        override suspend fun retryDownload(trackId: String): Result<Unit> = Result.success(Unit)
    }

    private lateinit var downloadRepo: FakeDownloadRepository
    private val testTrack = Track(
        id = "test_track_123",
        title = "Viva La Vida",
        artist = "Coldplay",
        durationMs = 240000L
    )

    @Before
    fun setUp() {
        downloadRepo = FakeDownloadRepository()
    }

    @Test
    fun enqueueDownload_createsDownloadingState() = runTest {
        val result = downloadRepo.enqueueDownload(testTrack)
        assertTrue(result.isSuccess)

        val info = downloadRepo.getDownload(testTrack.id)
        assertNotNull(info)
        assertEquals(DownloadStatus.DOWNLOADING, info?.status)
        assertEquals(AudioQuality.VERY_HIGH, info?.quality)
    }

    @Test
    fun downloadProgress_updatesReactiveFlow() = runTest {
        downloadRepo.enqueueDownload(testTrack)
        downloadRepo.simulateProgress(testTrack.id, 2048000L)

        val info = downloadRepo.observeDownload(testTrack.id).first()
        assertEquals(2048000L, info?.downloadedBytes)
        assertEquals(0.5f, info?.progressFraction ?: 0f, 0.01f)
    }

    @Test
    fun completedDownload_providesLocalFileUri() = runTest {
        downloadRepo.enqueueDownload(testTrack)
        downloadRepo.simulateComplete(testTrack.id)

        val uri = downloadRepo.getDownloadedFileUri(testTrack.id)
        assertNotNull(uri)
        assertTrue(uri?.endsWith("test_track_123.webm") == true)

        val completed = downloadRepo.observeCompletedDownloads().first()
        assertEquals(1, completed.size)
        assertEquals(testTrack.id, completed[0].trackId)
    }

    @Test
    fun removeDownload_cleansUpRecord() = runTest {
        downloadRepo.enqueueDownload(testTrack)
        downloadRepo.simulateComplete(testTrack.id)

        downloadRepo.removeDownload(testTrack.id)

        val info = downloadRepo.getDownload(testTrack.id)
        assertNull(info)
        assertNull(downloadRepo.getDownloadedFileUri(testTrack.id))
    }

    @Test
    fun removeAllDownloads_clearsAllItems() = runTest {
        downloadRepo.enqueueDownload(testTrack)
        downloadRepo.enqueueDownload(testTrack.copy(id = "track_456"))

        downloadRepo.removeAllDownloads()

        val all = downloadRepo.observeAllDownloads().first()
        assertTrue(all.isEmpty())
    }
}
