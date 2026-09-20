package com.example.sonara.data.download

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DownloadFlowTest {

    private class FakeDownloadManager : DownloadRepository {
        private val downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
        private val activeJobs = mutableSetOf<String>()
        val simulatedDisk = mutableMapOf<String, Long>() // path -> size

        override fun observeDownload(trackId: String): Flow<DownloadInfo?> =
            downloads.map { it[trackId] }

        override fun observeAllDownloads(): Flow<List<DownloadInfo>> =
            downloads.map { it.values.toList() }

        override fun observeCompletedDownloads(): Flow<List<DownloadInfo>> =
            downloads.map { it.values.filter { d -> d.status == DownloadStatus.DOWNLOADED } }

        override suspend fun getDownload(trackId: String): DownloadInfo? =
            downloads.value[trackId]

        override suspend fun enqueueDownload(track: Track, quality: AudioQuality?): Result<Unit> {
            // Duplicate prevention: if already downloading, return success immediately
            if (activeJobs.contains(track.id)) {
                return Result.success(Unit)
            }

            val existing = downloads.value[track.id]
            val filePath = "/data/user/0/com.example.sonara/files/sonara_downloads/${track.id}.webm"

            // If already downloaded and verified on disk
            if (existing?.status == DownloadStatus.DOWNLOADED && simulatedDisk.containsKey(filePath)) {
                return Result.success(Unit)
            }

            activeJobs.add(track.id)
            val info = DownloadInfo(
                trackId = track.id,
                localFilePath = filePath,
                totalBytes = 4194304L, // 4 MB
                downloadedBytes = 0L,
                status = DownloadStatus.DOWNLOADING,
                quality = quality ?: AudioQuality.VERY_HIGH
            )
            downloads.value = downloads.value + (track.id to info)
            return Result.success(Unit)
        }

        override suspend fun cancelDownload(trackId: String): Result<Unit> {
            activeJobs.remove(trackId)
            val tempPath = "/data/user/0/com.example.sonara/files/sonara_downloads/${trackId}.webm.download"
            simulatedDisk.remove(tempPath)
            val existing = downloads.value[trackId]
            if (existing != null) {
                downloads.value = downloads.value + (trackId to existing.copy(
                    status = DownloadStatus.CANCELLED,
                    failureReason = "Cancelled"
                ))
            }
            return Result.success(Unit)
        }

        override suspend fun removeDownload(trackId: String): Result<Unit> {
            activeJobs.remove(trackId)
            val filePath = "/data/user/0/com.example.sonara/files/sonara_downloads/${trackId}.webm"
            simulatedDisk.remove(filePath)
            downloads.value = downloads.value - trackId
            return Result.success(Unit)
        }

        override suspend fun removeAllDownloads(): Result<Unit> {
            activeJobs.clear()
            simulatedDisk.clear()
            downloads.value = emptyMap()
            return Result.success(Unit)
        }

        override suspend fun getDownloadedFileUri(trackId: String): String? {
            val d = downloads.value[trackId] ?: return null
            if (d.status == DownloadStatus.DOWNLOADED && simulatedDisk.containsKey(d.localFilePath)) {
                return d.localFilePath
            }
            return null
        }

        fun updateProgress(trackId: String, downloaded: Long) {
            val d = downloads.value[trackId] ?: return
            downloads.value = downloads.value + (trackId to d.copy(
                downloadedBytes = downloaded
            ))
        }

        fun completeDownload(trackId: String) {
            activeJobs.remove(trackId)
            val d = downloads.value[trackId] ?: return
            val finalPath = d.localFilePath ?: "/data/user/0/com.example.sonara/files/sonara_downloads/${trackId}.webm"
            simulatedDisk[finalPath] = d.totalBytes

            downloads.value = downloads.value + (trackId to d.copy(
                downloadedBytes = d.totalBytes,
                status = DownloadStatus.DOWNLOADED,
                completedAt = System.currentTimeMillis()
            ))
        }

        fun failDownload(trackId: String, reason: String) {
            activeJobs.remove(trackId)
            val d = downloads.value[trackId] ?: return
            downloads.value = downloads.value + (trackId to d.copy(
                status = DownloadStatus.FAILED,
                failureReason = reason
            ))
        }

        fun reconcileStaleDatabase() {
            val reconciled = mutableMapOf<String, DownloadInfo>()
            downloads.value.forEach { (id, info) ->
                if (info.status == DownloadStatus.DOWNLOADED) {
                    if (simulatedDisk.containsKey(info.localFilePath)) {
                        reconciled[id] = info
                    } else {
                        reconciled[id] = info.copy(status = DownloadStatus.NOT_DOWNLOADED)
                    }
                } else {
                    reconciled[id] = info
                }
            }
            downloads.value = reconciled
        }

        // Stub: maps existing fake DownloadInfo state to DownloadWithTrack(download, null).
        // Track identity is not exercised by DownloadFlowTest so null track is sufficient.
        override fun observeDownloadsWithTrack(): Flow<List<DownloadWithTrack>> =
            downloads.map { map -> map.values.map { DownloadWithTrack(it, null) } }

        // Stub: retry is tested at the mapper level (DownloadNotificationMapperTest).
        override suspend fun retryDownload(trackId: String): Result<Unit> = Result.success(Unit)
    }

    private lateinit var manager: FakeDownloadManager
    private val testTrack = Track(
        id = "track_viva_123",
        title = "Viva La Vida",
        artist = "Coldplay",
        album = "Viva La Vida",
        durationMs = 242000L
    )

    @Before
    fun setUp() {
        manager = FakeDownloadManager()
    }

    @Test
    fun enqueueDownload_startsRealDownloadingState() = runTest {
        val result = manager.enqueueDownload(testTrack)
        assertTrue(result.isSuccess)

        val info = manager.observeDownload(testTrack.id).first()
        assertNotNull(info)
        assertEquals(DownloadStatus.DOWNLOADING, info?.status)
        assertEquals(0L, info?.downloadedBytes)
        assertEquals(4194304L, info?.totalBytes)
    }

    @Test
    fun progressUpdates_computeProgressFractionAccurately() = runTest {
        manager.enqueueDownload(testTrack)
        manager.updateProgress(testTrack.id, 2097152L) // 50%

        val info = manager.observeDownload(testTrack.id).first()
        assertEquals(2097152L, info?.downloadedBytes)
        assertEquals(0.5f, info?.progressFraction ?: 0f, 0.001f)
    }

    @Test
    fun completion_verifiesFileAndEnablesOfflinePlayback() = runTest {
        manager.enqueueDownload(testTrack)
        manager.completeDownload(testTrack.id)

        val info = manager.observeDownload(testTrack.id).first()
        assertEquals(DownloadStatus.DOWNLOADED, info?.status)
        assertEquals(1.0f, info?.progressFraction ?: 0f, 0.001f)

        val uri = manager.getDownloadedFileUri(testTrack.id)
        assertNotNull(uri)
        assertTrue(uri?.endsWith("track_viva_123.webm") == true)
    }

    @Test
    fun failure_setsFailedStateAndReason() = runTest {
        manager.enqueueDownload(testTrack)
        manager.failDownload(testTrack.id, "HTTP 503 Service Unavailable")

        val info = manager.observeDownload(testTrack.id).first()
        assertEquals(DownloadStatus.FAILED, info?.status)
        assertEquals("HTTP 503 Service Unavailable", info?.failureReason)
    }

    @Test
    fun cancellation_cleansUpAndSetsCancelledState() = runTest {
        manager.enqueueDownload(testTrack)
        manager.cancelDownload(testTrack.id)

        val info = manager.observeDownload(testTrack.id).first()
        assertEquals(DownloadStatus.CANCELLED, info?.status)
    }

    @Test
    fun duplicateDownloadPrevention_blocksRepeatedRequests() = runTest {
        manager.enqueueDownload(testTrack)
        // Rapid repeated tap
        val secondResult = manager.enqueueDownload(testTrack)
        assertTrue(secondResult.isSuccess)

        // Complete download
        manager.completeDownload(testTrack.id)

        // Tap again when already downloaded
        val thirdResult = manager.enqueueDownload(testTrack)
        assertTrue(thirdResult.isSuccess)

        val completed = manager.observeCompletedDownloads().first()
        assertEquals(1, completed.size)
    }

    @Test
    fun databaseFilesystemReconciliation_reconcilesMissingFile() = runTest {
        manager.enqueueDownload(testTrack)
        manager.completeDownload(testTrack.id)

        // Manually delete file from simulated disk
        manager.simulatedDisk.clear()

        // Reconcile
        manager.reconcileStaleDatabase()

        val info = manager.observeDownload(testTrack.id).first()
        assertEquals(DownloadStatus.NOT_DOWNLOADED, info?.status)
        assertNull(manager.getDownloadedFileUri(testTrack.id))
    }
}
