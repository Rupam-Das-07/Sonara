package com.example.sonara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sonara.data.repository.PlaylistRepositoryImpl
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportTransactionInstrumentedTest {

    private lateinit var database: SonaraDatabase
    private lateinit var repository: PlaylistRepositoryImpl

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, SonaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlaylistRepositoryImpl(
            playlistDao = database.playlistDao(),
            trackDao = database.trackDao(),
            database = database
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun importPlaylist_success_persistsAllEntitiesAtomicallyWithContiguousPositions() = runBlocking {
        val tracks = listOf(
            Track(id = "track_alpha", title = "Alpha Song", artist = "Artist A", durationMs = 200000L),
            Track(id = "track_beta", title = "Beta Song", artist = "Artist B", durationMs = 180000L),
            Track(id = "track_alpha", title = "Alpha Song (Dup)", artist = "Artist A", durationMs = 200000L), // Duplicate
            Track(id = "track_gamma", title = "Gamma Song", artist = "Artist C", durationMs = 240000L)
        )

        val result = repository.importPlaylist("Road Trip Mix", tracks)
        assertTrue("Import must succeed", result.isSuccess)

        val summary = result.getOrThrow()
        assertEquals("Road Trip Mix", summary.playlistName)
        assertEquals(3, summary.totalTracksAdded)
        assertEquals(1, summary.collapsedDuplicates)

        // 1. Verify PlaylistEntity was persisted
        val playlistEntity = database.playlistDao().getPlaylistByIdSync(summary.playlistId)
        assertNotNull("Playlist must exist in database", playlistEntity)
        assertEquals("Road Trip Mix", playlistEntity?.name)
        assertEquals("SPOTIFY", playlistEntity?.origin)

        // 2. Verify TrackEntities were persisted
        val trackEntities = database.trackDao().getTracksByIds(listOf("track_alpha", "track_beta", "track_gamma"))
        assertEquals(3, trackEntities.size)

        // 3. Verify crossRefs and ordering
        val playlistTracks = database.playlistDao().getPlaylistTracksSync(summary.playlistId)
        assertEquals(3, playlistTracks.size)
        assertEquals("track_alpha", playlistTracks[0].id)
        assertEquals("track_beta", playlistTracks[1].id)
        assertEquals("track_gamma", playlistTracks[2].id)

        // Verify position column values directly via raw crossRefs
        val maxPos = database.playlistDao().getMaxPosition(summary.playlistId)
        assertEquals(2, maxPos) // 0, 1, 2 -> max position is 2
    }

    @Test
    fun importPlaylist_transactionRollback_cleansUpAllInsertedEntitiesOnFailure() = runBlocking {
        val tracks = listOf(
            Track(id = "track_fail_1", title = "Fail Track 1", artist = "Artist 1"),
            Track(id = "track_fail_2", title = "Fail Track 2", artist = "Artist 2")
        )

        // Execute a transaction that attempts import and then throws an exception
        var caughtException = false
        try {
            database.withTransaction {
                // Perform the same operations as importPlaylist inside a transaction block
                val playlistEntity = com.example.sonara.data.local.db.entity.PlaylistEntity(
                    id = "pl_rollback_test",
                    name = "Will Rollback",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                database.playlistDao().insertPlaylist(playlistEntity)
                database.trackDao().insertTracks(tracks.map { com.example.sonara.data.local.db.entity.TrackEntity.fromDomain(it) })
                database.playlistDao().insertPlaylistTracks(
                    tracks.mapIndexed { idx, t ->
                        com.example.sonara.data.local.db.entity.PlaylistTrackCrossRefEntity(
                            playlistId = "pl_rollback_test",
                            trackId = t.id,
                            position = idx,
                            addedAt = System.currentTimeMillis() + idx
                        )
                    }
                )

                // Simulate an unexpected error occurring before commit
                throw IllegalStateException("Simulated disk error or constraint failure")
            }
        } catch (e: IllegalStateException) {
            caughtException = true
        }

        assertTrue("Exception should be caught", caughtException)

        // Verify total rollback: NO playlist created, NO crossRefs, NO tracks
        val playlist = database.playlistDao().getPlaylistByIdSync("pl_rollback_test")
        assertNull("Playlist must NOT exist due to rollback", playlist)

        val crossRefTracks = database.playlistDao().getPlaylistTracksSync("pl_rollback_test")
        assertTrue("CrossRef tracks must be empty due to rollback", crossRefTracks.isEmpty())

        val track1 = database.trackDao().getTrackById("track_fail_1")
        assertNull("TrackEntity 1 must NOT exist due to rollback", track1)

        val track2 = database.trackDao().getTrackById("track_fail_2")
        assertNull("TrackEntity 2 must NOT exist due to rollback", track2)
    }
}
