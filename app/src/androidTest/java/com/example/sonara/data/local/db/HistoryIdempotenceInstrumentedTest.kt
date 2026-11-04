package com.example.sonara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sonara.data.repository.HistoryRepositoryImpl
import com.example.sonara.domain.model.ArtistCredit
import com.example.sonara.domain.model.ArtistCreditCodec
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room/SQLite verification for Phase 2A session idempotence and credit preservation. */
@RunWith(AndroidJUnit4::class)
class HistoryIdempotenceInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: SonaraDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, SonaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repeatedAndConcurrentSessionWritesAreIdempotent_butLegacyAndReplayWritesAreDistinct() = runBlocking {
        val repository = HistoryRepositoryImpl(database.historyDao(), database.trackDao())
        val track = Track(id = "track", title = "Song", artist = "Artist")

        val ids = coroutineScope {
            (0 until 20).map {
                async {
                    repository.recordHistoryWithProvenance(
                        track = track,
                        sourceSection = PlaybackOrigin.SEARCH_SONGS,
                        playbackSessionId = "session-1"
                    )
                }
            }.awaitAll()
        }
        assertEquals(1, ids.distinct().size)

        val legacyA = repository.recordHistory(track)
        val legacyB = repository.recordHistory(track)
        assertNotEquals(legacyA, legacyB)

        val replay = repository.recordHistoryWithProvenance(
            track = track,
            sourceSection = PlaybackOrigin.SEARCH_SONGS,
            playbackSessionId = "session-2"
        )
        assertNotEquals(ids.single(), replay)
        assertEquals(4, database.historyDao().getRecentHistory(20).first().size)
        assertEquals(ids.single(), database.historyDao().getHistoryIdBySessionId("session-1"))
    }

    @Test
    fun recordingPlaybackDoesNotEraseExistingStructuredArtistCredits() = runBlocking {
        val knownCredits = listOf(ArtistCredit("Hans Zimmer", "UC_hz"))
        database.trackDao().insertTrack(
            com.example.sonara.data.local.db.entity.TrackEntity(
                id = "track",
                title = "Song",
                artist = "Hans Zimmer",
                artistCredits = ArtistCreditCodec.encode(knownCredits)
            )
        )
        val repository = HistoryRepositoryImpl(database.historyDao(), database.trackDao())

        repository.recordHistory(Track(id = "track", title = "Song", artist = "Hans Zimmer"))

        assertEquals(knownCredits, database.trackDao().getTrackById("track")?.toDomain()?.artists)
        assertEquals(null, database.historyDao().getHistoryIdBySessionId(""))
    }
}
