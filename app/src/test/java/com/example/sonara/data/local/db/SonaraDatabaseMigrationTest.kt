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
}
