package com.example.sonara.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.DownloadStatus

/**
 * Local Room entity storing offline track download records.
 * Cascades on delete if the parent track is removed.
 */
@Entity(
    tableName = "downloads",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["trackId"], unique = true),
        Index(value = ["status"])
    ]
)
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val localFilePath: String,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: String = DownloadStatus.QUEUED.name,
    val quality: String = AudioQuality.VERY_HIGH.name,
    val mimeType: String = "audio/webm",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val failureReason: String? = null
) {
    fun toDomain(): DownloadInfo = DownloadInfo(
        trackId = trackId,
        localFilePath = localFilePath,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        status = try { DownloadStatus.valueOf(status) } catch (e: Exception) { DownloadStatus.FAILED },
        quality = try { AudioQuality.valueOf(quality) } catch (e: Exception) { AudioQuality.VERY_HIGH },
        mimeType = mimeType,
        createdAt = createdAt,
        completedAt = completedAt,
        failureReason = failureReason
    )

    companion object {
        fun fromDomain(info: DownloadInfo): DownloadEntity = DownloadEntity(
            trackId = info.trackId,
            localFilePath = info.localFilePath ?: "",
            totalBytes = info.totalBytes,
            downloadedBytes = info.downloadedBytes,
            status = info.status.name,
            quality = info.quality.name,
            mimeType = info.mimeType,
            createdAt = info.createdAt,
            completedAt = info.completedAt,
            failureReason = info.failureReason
        )
    }
}
