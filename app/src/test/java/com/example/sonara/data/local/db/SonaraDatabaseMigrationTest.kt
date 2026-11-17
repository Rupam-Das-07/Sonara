package com.example.sonara.data.local.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class SonaraDatabaseMigrationTest {

    private fun captureSql(block: (SupportSQLiteDatabase) -> Unit): String {
        val executedSql = mutableListOf<String>()
        val handler = InvocationHandler { _, method: Method, args: Array<out Any?>? ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler
        ) as SupportSQLiteDatabase
        block(db)
        return executedSql.joinToString("\n")
    }

    @Test
    fun migration1To2_createsPlaylistsAndCrossRefTablesWithZeroDataLoss() {
        val migration = SonaraDatabase.MIGRATION_1_2
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)

        val allSql = captureSql { migration.migrate(it) }

        assertFalse(allSql.contains("DROP TABLE", ignoreCase = true))
        assertTrue(allSql.contains("CREATE TABLE IF NOT EXISTS `playlists`"))
        assertTrue(allSql.contains("CREATE TABLE IF NOT EXISTS `playlist_tracks`"))
    }

    @Test
    fun migration2To3_createsDownloadsTableWithZeroDataLoss() {
        val migration = SonaraDatabase.MIGRATION_2_3
        assertEquals(2, migration.startVersion)
        assertEquals(3, migration.endVersion)

        val allSql = captureSql { migration.migrate(it) }

        assertFalse(allSql.contains("DROP TABLE", ignoreCase = true))
        assertTrue(allSql.contains("CREATE TABLE IF NOT EXISTS `downloads`"))
        assertTrue(allSql.contains("`trackId` TEXT NOT NULL PRIMARY KEY"))
        assertTrue(allSql.contains("`localFilePath` TEXT NOT NULL"))
        assertTrue(allSql.contains("`totalBytes` INTEGER NOT NULL"))
        assertTrue(allSql.contains("`downloadedBytes` INTEGER NOT NULL"))
        assertTrue(allSql.contains("`status` TEXT NOT NULL"))
        assertTrue(allSql.contains("`quality` TEXT NOT NULL"))
        assertTrue(allSql.contains("FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`)"))
        assertTrue(allSql.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_trackId`"))
        assertTrue(allSql.contains("CREATE INDEX IF NOT EXISTS `index_downloads_status`"))
    }

    // ─── MIGRATION 3 → 4 ──────────────────────────────────────────────────────

    @Test
    fun migration3To4_hasCorrectVersionNumbers() {
        assertEquals(3, SonaraDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, SonaraDatabase.MIGRATION_3_4.endVersion)
    }

    @Test
    fun migration3To4_createsSearchHistoryTable() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_3_4.migrate(it) }
        assertTrue(
            "Must create search_history table",
            allSql.contains("CREATE TABLE IF NOT EXISTS `search_history`")
        )
    }

    @Test
    fun migration3To4_zeroDataLoss_noDropStatements() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_3_4.migrate(it) }
        assertFalse(
            "MIGRATION_3_4 must NEVER drop any table",
            allSql.contains("DROP TABLE", ignoreCase = true)
        )
    }

    @Test
    fun migration3To4_searchHistorySchema_hasRequiredColumns() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_3_4.migrate(it) }
        assertTrue("Must have id column", allSql.contains("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue("Must have query column", allSql.contains("`query` TEXT NOT NULL"))
        assertTrue("Must have normalizedQuery column", allSql.contains("`normalizedQuery` TEXT NOT NULL"))
        assertTrue("Must have searchedAt column", allSql.contains("`searchedAt` INTEGER NOT NULL"))
    }

    @Test
    fun migration3To4_searchHistorySchema_hasUniqueIndexOnNormalizedQuery() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_3_4.migrate(it) }
        assertTrue(
            "Must have UNIQUE index on normalizedQuery for deduplication",
            allSql.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_search_history_normalizedQuery`")
        )
    }

    @Test
    fun migration3To4_searchHistorySchema_hasIndexOnSearchedAt() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_3_4.migrate(it) }
        assertTrue(
            "Must have index on searchedAt for ordering performance",
            allSql.contains("CREATE INDEX IF NOT EXISTS `index_search_history_searchedAt`")
        )
    }

    @Test
    fun migration3To4_doesNotModifyExistingTables() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_3_4.migrate(it) }
        // Verify that none of the pre-existing 6 tables are named in any ALTER or DROP statement
        val existingTables = listOf("tracks", "liked_songs", "playback_history", "playlists", "playlist_tracks", "downloads")
        existingTables.forEach { tableName ->
            assertFalse(
                "MIGRATION_3_4 must not ALTER table: $tableName",
                allSql.contains("ALTER TABLE `$tableName`", ignoreCase = true)
            )
            assertFalse(
                "MIGRATION_3_4 must not DROP table: $tableName",
                allSql.contains("DROP TABLE `$tableName`", ignoreCase = true)
            )
        }
    }

    // ─── MIGRATION 5 → 6 ──────────────────────────────────────────────────────

    @Test
    fun migration5To6_hasCorrectVersionNumbers() {
        assertEquals(5, SonaraDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, SonaraDatabase.MIGRATION_5_6.endVersion)
    }

    @Test
    fun migration5To6_addsOriginColumnWithDefaultUser() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_5_6.migrate(it) }
        assertTrue(
            "Must alter playlists table to add origin column with DEFAULT 'USER'",
            allSql.contains("ALTER TABLE `playlists` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'USER'")
        )
    }

    @Test
    fun migration5To6_zeroDataLoss_noDropStatements() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_5_6.migrate(it) }
        assertFalse(
            "MIGRATION_5_6 must NEVER drop any table",
            allSql.contains("DROP TABLE", ignoreCase = true)
        )
    }

    @Test
    fun migration5To6_doesNotModifyUnrelatedTables() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_5_6.migrate(it) }
        val otherTables = listOf("tracks", "liked_songs", "playback_history", "playlist_tracks", "downloads", "search_history")
        otherTables.forEach { tableName ->
            assertFalse(
                "MIGRATION_5_6 must not ALTER table: $tableName",
                allSql.contains("ALTER TABLE `$tableName`", ignoreCase = true)
            )
            assertFalse(
                "MIGRATION_5_6 must not DROP table: $tableName",
                allSql.contains("DROP TABLE `$tableName`", ignoreCase = true)
            )
        }
    }

    // ─── MIGRATION 6 → 7 ──────────────────────────────────────────────────────
    //
    // Adds `tracks.artistCredits`, the structured artist credit list Featured Artists
    // affinity uses to attribute a listen to a VERIFIED artist identity instead of
    // parsing the flat `artist` display string.

    @Test
    fun migration6To7_hasCorrectVersionNumbers() {
        assertEquals(6, SonaraDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, SonaraDatabase.MIGRATION_6_7.endVersion)
    }

    @Test
    fun migration6To7_addsArtistCreditsColumnWithEmptyDefault() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_6_7.migrate(it) }
        assertTrue(
            "Must alter tracks to add artistCredits TEXT NOT NULL DEFAULT ''",
            allSql.contains("ALTER TABLE `tracks` ADD COLUMN `artistCredits` TEXT NOT NULL DEFAULT ''")
        )
    }

    @Test
    fun migration6To7_defaultIsEmptySoLegacyRowsCarryNoVerifiedIdentity() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_6_7.migrate(it) }
        // The default MUST be the empty string. Any non-empty default would decode to a
        // fabricated credit and could satisfy an identity-gated rule for a pre-7 row.
        assertTrue(
            "artistCredits must default to the empty string",
            allSql.contains("`artistCredits` TEXT NOT NULL DEFAULT ''")
        )
        assertFalse(
            "artistCredits must not be seeded from the flat artist column",
            allSql.contains("UPDATE `tracks`", ignoreCase = true)
        )
    }

    @Test
    fun migration6To7_zeroDataLoss_noDropOrRecreate() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_6_7.migrate(it) }
        assertFalse(
            "MIGRATION_6_7 must NEVER drop a table",
            allSql.contains("DROP TABLE", ignoreCase = true)
        )
        assertFalse(
            "MIGRATION_6_7 must not recreate tracks (a pure ALTER preserves every row)",
            allSql.contains("CREATE TABLE", ignoreCase = true)
        )
        assertFalse(
            "MIGRATION_6_7 must not delete any row",
            allSql.contains("DELETE FROM", ignoreCase = true)
        )
    }

    @Test
    fun migration6To7_doesNotModifyUnrelatedTables() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_6_7.migrate(it) }
        val otherTables = listOf("playlists", "liked_songs", "playback_history", "playlist_tracks", "downloads", "search_history")
        otherTables.forEach { tableName ->
            assertFalse(
                "MIGRATION_6_7 must not ALTER table: $tableName",
                allSql.contains("ALTER TABLE `$tableName`", ignoreCase = true)
            )
            assertFalse(
                "MIGRATION_6_7 must not DROP table: $tableName",
                allSql.contains("DROP TABLE `$tableName`", ignoreCase = true)
            )
        }
    }

    @Test
    fun migration6To7_issuesExactlyOneStatement() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_6_7.migrate(it) }
        assertEquals(
            "A single additive ALTER is the entire migration",
            1,
            allSql.lines().count { it.isNotBlank() }
        )
    }

    // ─── MIGRATION 7 → 8 ──────────────────────────────────────────────────────

    @Test
    fun migration7To8_hasCorrectVersionNumbers() {
        assertEquals(7, SonaraDatabase.MIGRATION_7_8.startVersion)
        assertEquals(8, SonaraDatabase.MIGRATION_7_8.endVersion)
    }

    @Test
    fun migration7To8_addsLegacyDefaultsAndRequiredIndexes() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_7_8.migrate(it) }
        assertTrue(allSql.contains("ALTER TABLE `playback_history` ADD COLUMN `sourceSection` TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'"))
        assertTrue(allSql.contains("ALTER TABLE `playback_history` ADD COLUMN `playbackSessionId` TEXT"))
        assertTrue(allSql.contains("index_playback_history_sourceSection_listenedAt"))
        assertTrue(allSql.contains("index_playback_history_playbackSessionId"))
        assertTrue(allSql.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_history_playbackSessionId`"))
    }

    @Test
    fun migration7To8_isAdditiveAndDoesNotRewriteHistory() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_7_8.migrate(it) }
        assertFalse(allSql.contains("DROP TABLE", ignoreCase = true))
        assertFalse(allSql.contains("DELETE FROM", ignoreCase = true))
        assertFalse(allSql.contains("CREATE TABLE", ignoreCase = true))
    }

    // ─── MIGRATION 8 → 9 ──────────────────────────────────────────────────────

    @Test
    fun migration8To9_hasCorrectVersionNumbers() {
        assertEquals(8, SonaraDatabase.MIGRATION_8_9.startVersion)
        assertEquals(9, SonaraDatabase.MIGRATION_8_9.endVersion)
    }

    @Test
    fun migration8To9_addsVideoDownloadMetadataColumnsWithDefaults() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_8_9.migrate(it) }
        assertTrue(allSql.contains("ALTER TABLE `downloads` ADD COLUMN `mediaType` TEXT NOT NULL DEFAULT 'AUDIO'"))
        assertTrue(allSql.contains("ALTER TABLE `downloads` ADD COLUMN `resolutionHeight` INTEGER"))
        assertTrue(allSql.contains("ALTER TABLE `downloads` ADD COLUMN `frameRate` INTEGER"))
        assertTrue(allSql.contains("ALTER TABLE `downloads` ADD COLUMN `videoCodec` TEXT"))
        assertTrue(allSql.contains("ALTER TABLE `downloads` ADD COLUMN `audioCodec` TEXT"))
        assertTrue(allSql.contains("ALTER TABLE `downloads` ADD COLUMN `container` TEXT NOT NULL DEFAULT 'unknown'"))
        assertTrue(allSql.contains("UPDATE `downloads` SET `container` = 'webm' WHERE LOWER(`localFilePath`) LIKE '%.webm'"))
        assertTrue(allSql.contains("UPDATE `downloads` SET `container` = 'm4a' WHERE LOWER(`localFilePath`) LIKE '%.m4a'"))
        assertTrue(allSql.contains("UPDATE `downloads` SET `container` = 'mp4' WHERE LOWER(`localFilePath`) LIKE '%.mp4'"))
        assertTrue(allSql.contains("UPDATE `downloads` SET `container` = 'mp3' WHERE LOWER(`localFilePath`) LIKE '%.mp3'"))
        assertTrue(allSql.contains("UPDATE `downloads` SET `container` = 'opus' WHERE LOWER(`localFilePath`) LIKE '%.opus'"))
    }

    @Test
    fun migration8To9_isAdditiveAndPreservesExistingDownloads() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_8_9.migrate(it) }
        assertFalse(allSql.contains("DROP TABLE", ignoreCase = true))
        assertFalse(allSql.contains("DELETE FROM", ignoreCase = true))
        assertFalse(allSql.contains("CREATE TABLE", ignoreCase = true))
    }

    @Test
    fun migration8To9_incursNoDataLossForLegacyAudioRows() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_8_9.migrate(it) }
        // Verify all 5 known extension updates are executed
        val extensions = listOf("webm", "m4a", "mp4", "mp3", "opus")
        extensions.forEach { ext ->
            assertTrue(
                "Must backfill container for .$ext",
                allSql.contains("UPDATE `downloads` SET `container` = '$ext' WHERE LOWER(`localFilePath`) LIKE '%.$ext'")
            )
        }
    }

    // ─── MIGRATION 9 → 10 ──────────────────────────────────────────────────────

    @Test
    fun migration9To10_hasCorrectVersionNumbers() {
        assertEquals(9, SonaraDatabase.MIGRATION_9_10.startVersion)
        assertEquals(10, SonaraDatabase.MIGRATION_9_10.endVersion)
    }

    @Test
    fun migration9To10_addsNullableCustomCoverPathColumn() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_9_10.migrate(it) }
        assertTrue(allSql.contains("ALTER TABLE `playlists` ADD COLUMN `customCoverPath` TEXT"))
    }

    @Test
    fun migration9To10_isPurelyAdditiveAndPreservesAllPlaylists() {
        val allSql = captureSql { SonaraDatabase.MIGRATION_9_10.migrate(it) }
        assertFalse(allSql.contains("DROP TABLE", ignoreCase = true))
        assertFalse(allSql.contains("DELETE FROM", ignoreCase = true))
        assertFalse(allSql.contains("CREATE TABLE", ignoreCase = true))
    }
}
