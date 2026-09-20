package com.example.sonara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sonara.data.local.db.entity.PlaylistEntity
import com.example.sonara.data.local.db.entity.PlaylistTrackCrossRefEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented (real-database) tests for the `playlist_tracks` schema-repair migration
 * ([SonaraDatabase.MIGRATION_4_5]).
 *
 * These tests exercise an ACTUAL SQLite database on the device/emulator rather than
 * inspecting SQL text. The defective pre-v5 state is reproduced with the project's own
 * production migration code: a hand-built v1 base is upgraded through the real
 * [SonaraDatabase.MIGRATION_1_2] / [SonaraDatabase.MIGRATION_2_3] /
 * [SonaraDatabase.MIGRATION_3_4], which genuinely yields a `playlist_tracks` table
 * missing `position` and `index_playlist_tracks_playlistId`. The tests then verify that
 * [SonaraDatabase.MIGRATION_4_5]:
 *   - repairs the schema (column + index) on defective databases,
 *   - reconstructs contiguous, deterministic positions from `addedAt`,
 *   - preserves all rows,
 *   - leaves an already-correct table (and any user reordering) untouched,
 *   - lets Room open + validate the upgraded database and run its real position queries,
 *   - and that a fresh v5 install still produces the correct schema.
 *
 * NOTE: instrumented tests require a connected device/emulator. Run with:
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SonaraMigrationInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase(TEST_DB_FRESH)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
        context.deleteDatabase(TEST_DB_FRESH)
    }

    // ─── Structural: the repair adds the missing column + index (defective branch) ──────

    @Test
    fun migration4to5_onDefectiveDb_addsPositionColumnAndPlaylistIdIndex() {
        buildDefectiveV4Database()
        val helper = openRawHelper(version = 4)
        val db = helper.writableDatabase
        try {
            // Preconditions confirm the real historical chain genuinely produced the defect.
            assertFalse(
                "Precondition: MIGRATION_1_2-origin playlist_tracks must lack `position`",
                db.hasColumn("playlist_tracks", "position")
            )
            assertFalse(
                "Precondition: MIGRATION_1_2-origin playlist_tracks must lack index_playlist_tracks_playlistId",
                db.hasIndex("playlist_tracks", "index_playlist_tracks_playlistId")
            )

            SonaraDatabase.MIGRATION_4_5.migrate(db)

            assertTrue("position column added", db.hasColumn("playlist_tracks", "position"))
            assertTrue(
                "index_playlist_tracks_playlistId restored",
                db.hasIndex("playlist_tracks", "index_playlist_tracks_playlistId")
            )
            assertTrue(
                "pre-existing index_playlist_tracks_trackId preserved",
                db.hasIndex("playlist_tracks", "index_playlist_tracks_trackId")
            )
        } finally {
            helper.close()
        }
    }

    // ─── Data preservation + deterministic reconstruction (defective branch) ────────────

    @Test
    fun migration4to5_onDefectiveDb_reconstructsPositionsFromAddedAt_withNoDataLoss() {
        buildDefectiveV4Database()
        val helper = openRawHelper(version = 4)
        val db = helper.writableDatabase
        try {
            val rowsBefore = db.longOf("SELECT COUNT(*) FROM playlist_tracks")
            assertEquals("seed sanity: 5 cross-ref rows", 5L, rowsBefore)

            SonaraDatabase.MIGRATION_4_5.migrate(db)

            assertEquals("no cross-ref rows lost", rowsBefore, db.longOf("SELECT COUNT(*) FROM playlist_tracks"))

            // p1 seeded (insertion order t3,t1,t2) with addedAt 3000/1000/2000 ->
            // reconstruction must order by addedAt: t1,t2,t3.
            assertEquals(listOf("t1", "t2", "t3"), db.trackOrder("p1"))
            // p2 seeded (t5,t4) with addedAt 5000/4000 -> t4,t5.
            assertEquals(listOf("t4", "t5"), db.trackOrder("p2"))

            // Positions are contiguous and 0-based, per playlist.
            assertEquals(0L, db.longOf("SELECT MIN(position) FROM playlist_tracks WHERE playlistId='p1'"))
            assertEquals(2L, db.longOf("SELECT MAX(position) FROM playlist_tracks WHERE playlistId='p1'"))
            assertEquals(3L, db.longOf("SELECT COUNT(DISTINCT position) FROM playlist_tracks WHERE playlistId='p1'"))
            assertEquals(0L, db.longOf("SELECT MIN(position) FROM playlist_tracks WHERE playlistId='p2'"))
            assertEquals(1L, db.longOf("SELECT MAX(position) FROM playlist_tracks WHERE playlistId='p2'"))

            // Parent data untouched.
            assertEquals(6L, db.longOf("SELECT COUNT(*) FROM tracks"))
            assertEquals(2L, db.longOf("SELECT COUNT(*) FROM playlists"))
        } finally {
            helper.close()
        }
    }

    // ─── End-to-end: Room opens + validates the upgraded DB and its queries work ─────────

    @Test
    fun migration4to5_roomOpensAndValidatesUpgradedDefectiveDb_andQueriesWork() {
        buildDefectiveV4Database()

        val roomDb = Room.databaseBuilder(context, SonaraDatabase::class.java, TEST_DB)
            .addMigrations(
                SonaraDatabase.MIGRATION_1_2,
                SonaraDatabase.MIGRATION_2_3,
                SonaraDatabase.MIGRATION_3_4,
                SonaraDatabase.MIGRATION_4_5,
                SonaraDatabase.MIGRATION_5_6
            )
            .build()
        try {
            runBlocking {
                val dao = roomDb.playlistDao()
                // First DAO access forces Room to run MIGRATION_4_5 (4->5) and validate the
                // resulting schema against the current entities. An invalid schema throws here.
                assertEquals(listOf("t1", "t2", "t3"), dao.getPlaylistTracksSync("p1").map { it.id })
                assertEquals(listOf("t4", "t5"), dao.getPlaylistTracksSync("p2").map { it.id })
                assertEquals(2, dao.getMaxPosition("p1"))
                assertEquals(3, dao.getTrackCount("p1"))

                // Inserting a new track into the repaired table works and orders correctly.
                val nextPos = (dao.getMaxPosition("p1") ?: -1) + 1
                dao.insertPlaylistTrack(PlaylistTrackCrossRefEntity("p1", "t6", nextPos, 6000L))
                assertEquals(listOf("t1", "t2", "t3", "t6"), dao.getPlaylistTracksSync("p1").map { it.id })
                assertEquals(3, dao.getMaxPosition("p1"))
            }
        } finally {
            roomDb.close()
        }
    }

    // ─── Safety: already-correct table (fresh install) is left untouched ─────────────────

    @Test
    fun migration4to5_onAlreadyCorrectDb_isNoOp_andPreservesUserReordering() {
        val roomDb = Room.databaseBuilder(context, SonaraDatabase::class.java, TEST_DB_FRESH)
            .addMigrations(
                SonaraDatabase.MIGRATION_1_2,
                SonaraDatabase.MIGRATION_2_3,
                SonaraDatabase.MIGRATION_3_4,
                SonaraDatabase.MIGRATION_4_5,
                SonaraDatabase.MIGRATION_5_6
            )
            .build()
        try {
            runBlocking {
                val dao = roomDb.playlistDao()
                val support = roomDb.openHelper.writableDatabase // forces fresh create at current version
                insertTrackRaw(support, "t1")
                insertTrackRaw(support, "t2")
                dao.insertPlaylist(PlaylistEntity("p1", "P1", 0L, 0L))
                dao.insertPlaylistTrack(PlaylistTrackCrossRefEntity("p1", "t1", 0, 1000L))
                dao.insertPlaylistTrack(PlaylistTrackCrossRefEntity("p1", "t2", 1, 2000L))

                // User reorders: t2 first, t1 second (differs from addedAt order).
                dao.reorderTracks("p1", listOf("t2", "t1"))
                assertEquals(listOf("t2", "t1"), dao.getPlaylistTracksSync("p1").map { it.id })

                // Running the corrective migration on an already-correct table must NOT throw
                // (no duplicate-column error) and must NOT silently reorder user data.
                SonaraDatabase.MIGRATION_4_5.migrate(support)

                assertEquals(
                    "already-correct table: user reordering must be preserved",
                    listOf("t2", "t1"),
                    dao.getPlaylistTracksSync("p1").map { it.id }
                )
                assertEquals(1, dao.getMaxPosition("p1"))
                assertEquals(2, dao.getTrackCount("p1"))
            }
        } finally {
            roomDb.close()
        }
    }

    // ─── Fresh install reaches the current schema and works ──────────────────────────────

    @Test
    fun freshInstall_v5_hasPositionAndBothIndices_andOrderingWorks() {
        val roomDb = Room.databaseBuilder(context, SonaraDatabase::class.java, TEST_DB_FRESH)
            .addMigrations(
                SonaraDatabase.MIGRATION_1_2,
                SonaraDatabase.MIGRATION_2_3,
                SonaraDatabase.MIGRATION_3_4,
                SonaraDatabase.MIGRATION_4_5,
                SonaraDatabase.MIGRATION_5_6
            )
            .build()
        try {
            runBlocking {
                val dao = roomDb.playlistDao()
                val support = roomDb.openHelper.writableDatabase // fresh v5 create

                assertTrue(support.hasColumn("playlist_tracks", "position"))
                assertTrue(support.hasIndex("playlist_tracks", "index_playlist_tracks_playlistId"))
                assertTrue(support.hasIndex("playlist_tracks", "index_playlist_tracks_trackId"))

                insertTrackRaw(support, "t1")
                insertTrackRaw(support, "t2")
                dao.insertPlaylist(PlaylistEntity("p1", "P1", 0L, 0L))
                dao.insertPlaylistTrack(PlaylistTrackCrossRefEntity("p1", "t1", 0, 1000L))
                dao.insertPlaylistTrack(PlaylistTrackCrossRefEntity("p1", "t2", 1, 2000L))

                assertEquals(listOf("t1", "t2"), dao.getPlaylistTracksSync("p1").map { it.id })
                assertEquals(1, dao.getMaxPosition("p1"))
            }
        } finally {
            roomDb.close()
        }
    }

    // ─── MIGRATION 5 → 6 (Playlist origin: USER vs SPOTIFY) ──────────────────────────────

    @Test
    fun migration5to6_fromRealV5Database_preservesPlaylistsAndAssignsOriginUser() {
        buildV5Database()
        val helper = openRawHelper(version = 5)
        val db = helper.writableDatabase
        try {
            // Precondition: v5 table does NOT have origin column
            assertFalse("Precondition: v5 playlists must lack origin column", db.hasColumn("playlists", "origin"))

            val playlistCountBefore = db.longOf("SELECT COUNT(*) FROM playlists")
            val crossRefCountBefore = db.longOf("SELECT COUNT(*) FROM playlist_tracks")
            assertEquals(2L, playlistCountBefore)
            assertEquals(2L, crossRefCountBefore)

            SonaraDatabase.MIGRATION_5_6.migrate(db)

            assertTrue("origin column added to playlists", db.hasColumn("playlists", "origin"))
            assertEquals("playlist row count preserved", playlistCountBefore, db.longOf("SELECT COUNT(*) FROM playlists"))
            assertEquals("crossRef row count preserved", crossRefCountBefore, db.longOf("SELECT COUNT(*) FROM playlist_tracks"))

            // Verify pl_1 data and that origin explicitly received USER
            db.query("SELECT id, name, createdAt, updatedAt, origin FROM playlists WHERE id = 'pl_1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("pl_1", cursor.getString(0))
                assertEquals("Indie Roadtrip", cursor.getString(1))
                assertEquals(1700000000000L, cursor.getLong(2))
                assertEquals(1700000005000L, cursor.getLong(3))
                assertEquals("USER", cursor.getString(4))
            }

            // Verify pl_2 also received USER
            db.query("SELECT id, name, createdAt, updatedAt, origin FROM playlists WHERE id = 'pl_2'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("pl_2", cursor.getString(0))
                assertEquals("Night Drive", cursor.getString(1))
                assertEquals(1700000010000L, cursor.getLong(2))
                assertEquals(1700000015000L, cursor.getLong(3))
                assertEquals("USER", cursor.getString(4))
            }

            // Verify crossRefs positions and track IDs remain unchanged
            assertEquals(listOf("track_1", "track_2"), db.trackOrder("pl_1"))
        } finally {
            helper.close()
        }
    }

    @Test
    fun migration5to6_roomOpensAndValidatesUpgradedDb_andDaoWorks() {
        buildV5Database()

        val roomDb = Room.databaseBuilder(context, SonaraDatabase::class.java, TEST_DB)
            .addMigrations(
                SonaraDatabase.MIGRATION_1_2,
                SonaraDatabase.MIGRATION_2_3,
                SonaraDatabase.MIGRATION_3_4,
                SonaraDatabase.MIGRATION_4_5,
                SonaraDatabase.MIGRATION_5_6
            )
            .build()
        try {
            runBlocking {
                val dao = roomDb.playlistDao()
                // Force Room to run MIGRATION_5_6 (5->6) and validate the resulting schema against current PlaylistEntity
                val pl1 = dao.getPlaylistByIdSync("pl_1")
                assertNotNull(pl1)
                assertEquals("pl_1", pl1?.id)
                assertEquals("Indie Roadtrip", pl1?.name)
                assertEquals("USER", pl1?.origin)

                val pl2 = dao.getPlaylistByIdSync("pl_2")
                assertNotNull(pl2)
                assertEquals("Night Drive", pl2?.name)
                assertEquals("USER", pl2?.origin)

                // Playlist tracks still intact
                val tracks = dao.getPlaylistTracksSync("pl_1")
                assertEquals(listOf("track_1", "track_2"), tracks.map { it.id })

                // Inserting a new SPOTIFY playlist works through DAO
                val spotifyPlaylist = PlaylistEntity(
                    id = "pl_spotify",
                    name = "Discover Weekly",
                    origin = "SPOTIFY"
                )
                dao.insertPlaylist(spotifyPlaylist)

                val retrievedSpotify = dao.getPlaylistByIdSync("pl_spotify")
                assertNotNull(retrievedSpotify)
                assertEquals("SPOTIFY", retrievedSpotify?.origin)
            }
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun freshInstall_v6_hasOriginColumn_andDefaultsWork() {
        val roomDb = Room.databaseBuilder(context, SonaraDatabase::class.java, TEST_DB_FRESH)
            .addMigrations(
                SonaraDatabase.MIGRATION_1_2,
                SonaraDatabase.MIGRATION_2_3,
                SonaraDatabase.MIGRATION_3_4,
                SonaraDatabase.MIGRATION_4_5,
                SonaraDatabase.MIGRATION_5_6
            )
            .build()
        try {
            runBlocking {
                val dao = roomDb.playlistDao()
                val support = roomDb.openHelper.writableDatabase // fresh v6 create

                assertTrue(support.hasColumn("playlists", "origin"))

                // Insert without explicit origin -> defaults to USER in PlaylistEntity
                dao.insertPlaylist(PlaylistEntity(id = "fresh_user", name = "My Fresh Playlist"))
                val retrieved = dao.getPlaylistByIdSync("fresh_user")
                assertNotNull(retrieved)
                assertEquals("USER", retrieved?.origin)
            }
        } finally {
            roomDb.close()
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds [TEST_DB] as a real v5 database containing realistic playlists and tracks.
     */
    private fun buildV5Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = createV1BaseSchema(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            SonaraDatabase.MIGRATION_1_2.migrate(db)
            SonaraDatabase.MIGRATION_2_3.migrate(db)
            SonaraDatabase.MIGRATION_3_4.migrate(db)
            SonaraDatabase.MIGRATION_4_5.migrate(db)
            seedV5Data(db)
            db.version = 5
        } finally {
            helper.close()
        }
    }

    private fun seedV5Data(db: SupportSQLiteDatabase) {
        insertTrackRaw(db, "track_1")
        insertTrackRaw(db, "track_2")
        db.execSQL("INSERT INTO `playlists` (`id`,`name`,`createdAt`,`updatedAt`) VALUES ('pl_1','Indie Roadtrip',1700000000000,1700000005000)")
        db.execSQL("INSERT INTO `playlists` (`id`,`name`,`createdAt`,`updatedAt`) VALUES ('pl_2','Night Drive',1700000010000,1700000015000)")
        db.execSQL("INSERT INTO `playlist_tracks` (`playlistId`,`trackId`,`position`,`addedAt`) VALUES ('pl_1','track_1',0,1000)")
        db.execSQL("INSERT INTO `playlist_tracks` (`playlistId`,`trackId`,`position`,`addedAt`) VALUES ('pl_1','track_2',1,2000)")
    }

    /**
     * Builds [TEST_DB] as a genuine defective v4 database: create the v1 base tables, run the
     * REAL historical migrations (which produce the defective `playlist_tracks`), stamp
     * user_version = 4, and seed realistic rows. Positions are intentionally NOT present
     * (the defective table has no such column); addedAt values are seeded out of insertion
     * order so reconstruction behaviour is observable.
     */
    private fun buildDefectiveV4Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = createV1BaseSchema(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // Migrations are driven manually below to reproduce the exact historical path.
            }
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(callback)
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase // triggers onCreate at version 1
            SonaraDatabase.MIGRATION_1_2.migrate(db)
            SonaraDatabase.MIGRATION_2_3.migrate(db)
            SonaraDatabase.MIGRATION_3_4.migrate(db)
            seedDefectiveData(db)
            db.version = 4
        } finally {
            helper.close()
        }
    }

    /** Opens [TEST_DB] as a raw SupportSQLiteDatabase at [version] without running any migration. */
    private fun openRawHelper(version: Int): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // Not expected: the file already exists at this version.
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

    /** The v1 base schema: the tables that existed before MIGRATION_1_2 and are never altered. */
    private fun createV1BaseSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tracks` (" +
                "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                "`album` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `artworkUrl` TEXT, " +
                "`lastUpdated` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `liked_songs` (" +
                "`trackId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`trackId`), " +
                "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_liked_songs_trackId` ON `liked_songs` (`trackId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playback_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, " +
                "`listenedAt` INTEGER NOT NULL, `completed` INTEGER NOT NULL, " +
                "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_trackId` ON `playback_history` (`trackId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_history_listenedAt` ON `playback_history` (`listenedAt`)")
    }

    /** Seeds tracks, playlists, and defective (position-less) playlist_tracks rows. */
    private fun seedDefectiveData(db: SupportSQLiteDatabase) {
        for (id in listOf("t1", "t2", "t3", "t4", "t5", "t6")) insertTrackRaw(db, id)
        db.execSQL("INSERT INTO `playlists` (`id`,`name`,`createdAt`,`updatedAt`) VALUES ('p1','P1',0,0)")
        db.execSQL("INSERT INTO `playlists` (`id`,`name`,`createdAt`,`updatedAt`) VALUES ('p2','P2',0,0)")
        // Defective table has only (playlistId, trackId, addedAt). Insertion order is
        // intentionally scrambled relative to addedAt to prove addedAt-based reconstruction.
        db.execSQL("INSERT INTO `playlist_tracks` (`playlistId`,`trackId`,`addedAt`) VALUES ('p1','t3',3000)")
        db.execSQL("INSERT INTO `playlist_tracks` (`playlistId`,`trackId`,`addedAt`) VALUES ('p1','t1',1000)")
        db.execSQL("INSERT INTO `playlist_tracks` (`playlistId`,`trackId`,`addedAt`) VALUES ('p1','t2',2000)")
        db.execSQL("INSERT INTO `playlist_tracks` (`playlistId`,`trackId`,`addedAt`) VALUES ('p2','t5',5000)")
        db.execSQL("INSERT INTO `playlist_tracks` (`playlistId`,`trackId`,`addedAt`) VALUES ('p2','t4',4000)")
    }

    private fun insertTrackRaw(db: SupportSQLiteDatabase, id: String) {
        db.execSQL(
            "INSERT OR IGNORE INTO `tracks` (`id`,`title`,`artist`,`album`,`durationMs`,`artworkUrl`,`lastUpdated`) " +
                "VALUES ('$id','Title $id','Artist $id','',0,NULL,0)"
        )
    }

    private fun SupportSQLiteDatabase.longOf(sql: String): Long =
        query(sql).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    private fun SupportSQLiteDatabase.trackOrder(playlistId: String): List<String> =
        query("SELECT `trackId` FROM `playlist_tracks` WHERE `playlistId`='$playlistId' ORDER BY `position` ASC")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
        query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            if (nameIdx < 0) return false
            while (c.moveToNext()) if (c.getString(nameIdx) == column) return true
            false
        }

    private fun SupportSQLiteDatabase.hasIndex(table: String, indexName: String): Boolean =
        query("PRAGMA index_list(`$table`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            if (nameIdx < 0) return false
            while (c.moveToNext()) if (c.getString(nameIdx) == indexName) return true
            false
        }

    private companion object {
        const val TEST_DB = "migration_test.db"
        const val TEST_DB_FRESH = "migration_test_fresh.db"
    }
}
