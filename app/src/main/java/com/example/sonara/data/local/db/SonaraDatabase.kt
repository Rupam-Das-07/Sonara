package com.example.sonara.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.sonara.data.local.db.dao.DownloadDao
import com.example.sonara.data.local.db.dao.HistoryDao
import com.example.sonara.data.local.db.dao.LikedSongsDao
import com.example.sonara.data.local.db.dao.PlaylistDao
import com.example.sonara.data.local.db.dao.SearchHistoryDao
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.DownloadEntity
import com.example.sonara.data.local.db.entity.HistoryEntity
import com.example.sonara.data.local.db.entity.LikedSongEntity
import com.example.sonara.data.local.db.entity.PlaylistEntity
import com.example.sonara.data.local.db.entity.PlaylistTrackCrossRefEntity
import com.example.sonara.data.local.db.entity.SearchHistoryEntity
import com.example.sonara.data.local.db.entity.TrackEntity

/**
 * Authoritative local SQLite Room Database for Sonara Android.
 * Preserves user data across versions with explicit non-destructive schema migrations.
 *
 * Version history:
 *   v1 → v2: Added playlists + playlist_tracks tables.
 *   v2 → v3: Added downloads table.
 *   v3 → v4: Added search_history table (search history persistence).
 *   v4 → v5: Repaired historical `playlist_tracks` schema — restored the `position`
 *            column and the `index_playlist_tracks_playlistId` index that
 *            [MIGRATION_1_2] never created (see [MIGRATION_4_5]).
 *   v5 → v6: Added origin column to `playlists` table to distinguish user playlists
 *            from Spotify imports (see [MIGRATION_5_6]).
 */
@Database(
    entities = [
        TrackEntity::class,
        LikedSongEntity::class,
        HistoryEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRefEntity::class,
        DownloadEntity::class,
        SearchHistoryEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class SonaraDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun likedSongsDao(): LikedSongsDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        private const val DATABASE_NAME = "sonara_database.db"

        /**
         * Explicit zero-loss migration from v1 to v2.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                        `playlistId` TEXT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`playlistId`, `trackId`),
                        FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_trackId` ON `playlist_tracks` (`trackId`)"
                )
            }
        }

        /**
         * Explicit zero-loss migration from v2 to v3.
         * Creates `downloads` table for offline audio caching and preserves all user tracks/playlists.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `trackId` TEXT NOT NULL PRIMARY KEY,
                        `localFilePath` TEXT NOT NULL,
                        `totalBytes` INTEGER NOT NULL,
                        `downloadedBytes` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `quality` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `failureReason` TEXT,
                        FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_trackId` ON `downloads` (`trackId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_downloads_status` ON `downloads` (`status`)"
                )
            }
        }

        /**
         * Explicit zero-loss migration from v3 to v4.
         *
         * Adds `search_history` table for persistent user search history.
         * All existing data (tracks, liked_songs, playback_history, playlists,
         * playlist_tracks, downloads) is fully preserved — only a new table is added.
         *
         * Schema:
         *   id             INTEGER PRIMARY KEY AUTOINCREMENT
         *   query          TEXT NOT NULL      — original user query (display)
         *   normalizedQuery TEXT NOT NULL     — trimmed+lowercase (dedup + prefix matching)
         *   searchedAt     INTEGER NOT NULL   — epoch-ms of last search submission
         *
         * Indices:
         *   UNIQUE on normalizedQuery (deduplication)
         *   INDEX on searchedAt (ordering performance)
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `search_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `query` TEXT NOT NULL,
                        `normalizedQuery` TEXT NOT NULL,
                        `searchedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_search_history_normalizedQuery` ON `search_history` (`normalizedQuery`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_search_history_searchedAt` ON `search_history` (`searchedAt`)"
                )
            }
        }

        /**
         * Explicit corrective migration from v4 to v5.
         *
         * Repairs a historical schema divergence in `playlist_tracks`. The table was
         * first created by [MIGRATION_1_2], which — unlike Room's generated CREATE for
         * [PlaylistTrackCrossRefEntity] — omitted both the `position` column and the
         * `index_playlist_tracks_playlistId` index. Neither [MIGRATION_2_3] nor
         * [MIGRATION_3_4] touches `playlist_tracks`, so any database whose
         * `playlist_tracks` originated from [MIGRATION_1_2] (installs first created at v1
         * and upgraded in place) reaches v4 with a table that no longer matches the
         * current entity, and Room's schema validation rejects it on open.
         *
         * A database that reports version 4 is AMBIGUOUS: it is already correct if it was
         * first created at v2/v3/v4 (Room's generated CREATE for a fresh install includes
         * `position` and both indices), and defective only if `playlist_tracks` came from
         * [MIGRATION_1_2]. This migration therefore inspects the actual table shape and
         * repairs ONLY the defective case, leaving already-correct tables — including any
         * user-defined track order — untouched.
         *
         * Existing-row handling: the defective table preserves no explicit ordering
         * column, so `position` is reconstructed deterministically from `addedAt` (the
         * epoch-ms the track was added to the playlist), with `trackId` as a stable
         * tie-breaker. This yields contiguous, 0-based positions per playlist that
         * reproduce the original chronological add order. Track reordering is the only
         * operation that writes `position`; a table that never had that column could never
         * have been reordered, so add-order is the only order these rows ever had. A
         * correlated subquery is used instead of a window function to remain compatible
         * with the SQLite build shipped on minSdk 26 (< 3.25, no window functions).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Detect whether `playlist_tracks` already has the `position` column.
                var hasPosition = false
                db.query("PRAGMA table_info(`playlist_tracks`)").use { cursor ->
                    val nameColumn = cursor.getColumnIndex("name")
                    if (nameColumn >= 0) {
                        while (cursor.moveToNext()) {
                            if (cursor.getString(nameColumn) == "position") {
                                hasPosition = true
                                break
                            }
                        }
                    }
                }

                if (!hasPosition) {
                    // Defective table (created by MIGRATION_1_2). Add the missing column.
                    // SQLite requires a DEFAULT to add a NOT NULL column to a table that may
                    // already contain rows; the reconstruction UPDATE below replaces this
                    // placeholder for every existing row. The residual column-level default is
                    // validation-safe on Room >= 2.5 (this project uses 2.8.4) because the
                    // entity declares no @ColumnInfo(defaultValue), so Room does not compare it.
                    db.execSQL(
                        "ALTER TABLE `playlist_tracks` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0"
                    )
                    // Reconstruct contiguous, 0-based positions per playlist from addedAt order.
                    db.execSQL(
                        """
                        UPDATE `playlist_tracks`
                        SET `position` = (
                            SELECT COUNT(*)
                            FROM `playlist_tracks` AS pt2
                            WHERE pt2.`playlistId` = `playlist_tracks`.`playlistId`
                              AND (
                                pt2.`addedAt` < `playlist_tracks`.`addedAt`
                                OR (pt2.`addedAt` = `playlist_tracks`.`addedAt`
                                    AND pt2.`trackId` < `playlist_tracks`.`trackId`)
                              )
                        )
                        """.trimIndent()
                    )
                }

                // Restore the index the current schema expects. IF NOT EXISTS keeps this a
                // no-op for databases that already have it (correct tables) and creates it
                // for repaired ones. Name and column match Room's generated schema for
                // Index("playlistId") on PlaylistTrackCrossRefEntity.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)"
                )
            }
        }

        /**
         * Explicit zero-loss migration from v5 to v6.
         *
         * Adds deterministic playlist `origin` column to `playlists` table to distinguish
         * user-created playlists ('USER') from imported Spotify playlists ('SPOTIFY').
         * All existing historical playlists from v1-v5 explicitly default to 'USER'.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `playlists` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'USER'"
                )
            }
        }

        @Volatile
        private var instance: SonaraDatabase? = null

        fun getInstance(context: Context): SonaraDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SonaraDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
