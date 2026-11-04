package com.example.sonara.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.sonara.domain.model.PlaybackOrigin

/**
 * Entity logging an authoritative track playback event.
 */
@Entity(
    tableName = "playback_history",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("trackId"),
        Index("listenedAt"),
        Index(value = ["sourceSection", "listenedAt"]),
        Index(value = ["playbackSessionId"], unique = true)
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: String,
    val listenedAt: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
    val sourceSection: String = PlaybackOrigin.LEGACY_UNKNOWN.name,
    /** Null is the storage sentinel for a legacy/non-session event. SQLite UNIQUE indexes
     * allow multiple NULLs, while still enforcing one row for every non-empty session ID.
     * The domain model exposes this as its approved empty-string default. */
    val playbackSessionId: String? = null
)
