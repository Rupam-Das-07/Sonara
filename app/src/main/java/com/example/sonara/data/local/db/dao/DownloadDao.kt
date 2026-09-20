package com.example.sonara.data.local.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.example.sonara.data.local.db.entity.DownloadEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * A download row joined with the cached track metadata it refers to.
 *
 * [track] is nullable defensively; in practice enqueueDownload always caches the [TrackEntity]
 * before inserting the download row, so a matching track is expected to exist.
 */
data class DownloadedTrackRelation(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "trackId", entityColumn = "id")
    val track: TrackEntity?
)

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads WHERE trackId = :trackId LIMIT 1")
    suspend fun getDownload(trackId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE trackId = :trackId LIMIT 1")
    fun observeDownload(trackId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADED' ORDER BY completedAt DESC")
    fun observeCompletedDownloads(): Flow<List<DownloadEntity>>

    /**
     * All download rows joined with their cached track metadata (title/artist/artwork),
     * newest first. Used by the download notifier to render track identity without threading
     * a Track object through the download layer. @Transaction guarantees a consistent read
     * across the download + track tables.
     */
    @Transaction
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeDownloadsWithTracks(): Flow<List<DownloadedTrackRelation>>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun getActiveDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADED'")
    suspend fun getDownloadedRecords(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(download: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, status = :status WHERE trackId = :trackId")
    suspend fun updateProgress(trackId: String, downloadedBytes: Long, totalBytes: Long, status: String)

    @Query("UPDATE downloads SET localFilePath = :localFilePath, mimeType = :mimeType, quality = :quality WHERE trackId = :trackId")
    suspend fun updateFileInfo(trackId: String, localFilePath: String, mimeType: String, quality: String)

    @Query("UPDATE downloads SET localFilePath = :localFilePath, mimeType = :mimeType WHERE trackId = :trackId")
    suspend fun updateFileInfoLegacy(trackId: String, localFilePath: String, mimeType: String)

    @Query("UPDATE downloads SET status = :status, completedAt = :completedAt WHERE trackId = :trackId")
    suspend fun markCompleted(trackId: String, completedAt: Long, status: String = "DOWNLOADED")

    @Query("UPDATE downloads SET status = :status, failureReason = :reason WHERE trackId = :trackId")
    suspend fun markFailed(trackId: String, reason: String?, status: String = "FAILED")

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun delete(trackId: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}
