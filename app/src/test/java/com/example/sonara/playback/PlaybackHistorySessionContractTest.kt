package com.example.sonara.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.playback.checkpoint.PlaybackHistoryCoordinator
import com.example.sonara.playback.client.MediaControllerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TARGET CONTRACT for playback history — the authoritative specification.
 *
 * This suite encodes the APPROVED remediation design:
 *
 *   `playback_history` represents ONE QUALIFIED LISTENING SESSION.
 *   A row is INSERTED once the track reaches 15 s of ACTUAL playing time, and that
 *   SAME row is later UPDATED to completed=true if the track finishes naturally.
 *
 * History: these 14 cases were written test-first in Stage 1 (all 13 behavioural cases
 * failed against the original coordinator) and were satisfied by the Stage 2 state
 * machine. Stage 3 changed only HOW the coordinator is fed: it now consumes a
 * `StateFlow<MediaControllerState>` of authoritative ExoPlayer snapshots published by
 * SonaraPlaybackService, rather than observing MediaControllerClient. The case bodies
 * and assertions below are unchanged.
 *
 * DO NOT weaken these assertions to make an implementation pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistorySessionContractTest {

    private companion object {
        const val QUALIFY_MS = 15_000L
        const val TRACK_A = "track-a"
        const val TRACK_B = "track-b"
        const val TRACK_C = "track-c"
        const val TRACK_D = "track-d"
    }

    /** Feeds authoritative player-state snapshots to the coordinator. */
    private class FakeControllerClient {
        val states = MutableStateFlow(MediaControllerState())
        fun emit(state: MediaControllerState) { states.value = state }
    }

    /** Records every history interaction so we can assert insert/update semantics. */
    private class RecordingHistoryRepository : HistoryRepository {
        data class Inserted(
            val rowId: Long,
            val trackId: String,
            val completed: Boolean,
            val origin: PlaybackOrigin = PlaybackOrigin.LEGACY_UNKNOWN,
            val sessionId: String = ""
        )

        val inserts = mutableListOf<Inserted>()
        val markedCompleted = mutableListOf<Long>()
        private var nextRowId = 1L
        private val flow = MutableStateFlow<List<HistoryItem>>(emptyList())

        override fun getRecentHistory(limit: Int): kotlinx.coroutines.flow.Flow<List<HistoryItem>> = flow

        /**
         * Mirrors the real [com.example.sonara.data.repository.HistoryRepositoryImpl]: the
         * legacy 2-arg form delegates to the provenance-aware one with LEGACY_UNKNOWN and a
         * blank session id. Overriding ONLY the 2-arg form here would make every provenance
         * assertion vacuous, because the interface default delegates back to it.
         */
        override suspend fun recordHistory(track: Track, completed: Boolean): Long =
            recordHistoryWithProvenance(track, completed, PlaybackOrigin.LEGACY_UNKNOWN, "")

        /**
         * Reproduces Phase 2A's `insertHistoryIdempotent` semantics: a non-blank session id
         * is unique, so a repeat write returns the SAME row id without inserting again and
         * without touching the stored row. Blank session ids are the legacy path and always
         * insert a fresh row.
         */
        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long {
            if (playbackSessionId.isNotBlank()) {
                inserts.firstOrNull { it.sessionId == playbackSessionId }?.let { return it.rowId }
            }
            val id = nextRowId++
            inserts += Inserted(id, track.id, completed, sourceSection, playbackSessionId)
            return id
        }

        override suspend fun markHistoryCompleted(historyId: Long) {
            markedCompleted += historyId
        }

        override suspend fun clearHistory() {
            inserts.clear()
            markedCompleted.clear()
        }

        fun insertsFor(trackId: String) = inserts.filter { it.trackId == trackId }
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    private lateinit var client: FakeControllerClient
    private lateinit var repo: RecordingHistoryRepository
    private lateinit var coordinatorScope: TestScope
    private lateinit var coordinator: PlaybackHistoryCoordinator

    @Before
    fun setUp() {
        client = FakeControllerClient()
        repo = RecordingHistoryRepository()
        coordinatorScope = TestScope(dispatcher)
        coordinator = PlaybackHistoryCoordinator(
            playbackState = client.states,
            historyRepository = repo,
            scope = coordinatorScope,
            qualificationThresholdMs = QUALIFY_MS
        )
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
    }

    // ---------------------------------------------------------------- helpers

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle("Title $id").setArtist("Artist $id").build()
        )
        .build()

    private fun playing(trackId: String) = MediaControllerState(
        isConnected = true,
        isPlaying = true,
        currentMediaItem = mediaItem(trackId),
        playbackState = Player.STATE_READY,
        durationMs = 240_000L
    )

    private fun paused(trackId: String) = playing(trackId).copy(isPlaying = false)

    private fun ended(trackId: String) = playing(trackId).copy(
        isPlaying = false,
        playbackState = Player.STATE_ENDED
    )

    /** Advances virtual time and flushes tasks scheduled exactly at the new instant. */
    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun startPlaying(trackId: String) {
        client.emit(playing(trackId))
        scheduler.runCurrent()
    }

    // ------------------------------------------------- harness sanity (should pass)

    /**
     * HARNESS SANITY — must hold BOTH before and after remediation.
     *
     * Proves the fake client actually drives the coordinator, so that any failure in the
     * cases below is a real behavioral failure rather than a broken test harness.
     */
    @Test
    fun harness_coordinatorObservesClientAndWritesSomethingOnceQualified() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        assertTrue(
            "Harness broken: coordinator produced no history interaction at all",
            repo.inserts.isNotEmpty()
        )
    }

    // ------------------------------------------------------------- CASE 1

    /** CASE 1 — below qualification must produce NO row. */
    @Test
    fun case1_belowQualification_writesNothing() {
        coordinator.start()
        startPlaying(TRACK_A)

        advance(QUALIFY_MS - 1_000L) // 14s of actual playing

        assertEquals("No history row may exist before qualification", 0, repo.inserts.size)
        assertEquals(0, repo.markedCompleted.size)
    }

    // ------------------------------------------------------------- CASE 2

    /**
     * CASE 2 — crossing 15 s inserts EXACTLY ONE row with completed=false.
     * (Retention of the returned rowId is asserted in CASE 7 / CASE 12, where the
     * coordinator must reuse that exact id instead of inserting again.)
     */
    @Test
    fun case2_qualification_insertsExactlyOneIncompleteRow() {
        coordinator.start()
        startPlaying(TRACK_A)

        advance(QUALIFY_MS)

        assertEquals("Qualification must insert exactly one row", 1, repo.inserts.size)
        val row = repo.inserts.single()
        assertEquals(TRACK_A, row.trackId)
        assertEquals("Qualified-but-unfinished listen must be completed=false", false, row.completed)
        assertEquals("Qualification must not mark completion", 0, repo.markedCompleted.size)
    }

    // ------------------------------------------------------------- CASE 3

    /** CASE 3 — further ticks/emissions after qualification must not insert again. */
    @Test
    fun case3_repeatedEmissionsAfterQualification_doNotDuplicate() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        assertEquals(1, repo.inserts.size)

        // Keep playing for another minute and re-emit identical state repeatedly.
        repeat(5) {
            client.emit(playing(TRACK_A))
            advance(12_000L)
        }

        assertEquals(
            "Continued playback must never insert additional rows for the same session",
            1,
            repo.inserts.size
        )
    }

    // ------------------------------------------------------------- CASE 4

    /** CASE 4 — pause before qualification; only ACTUAL playing time counts. */
    @Test
    fun case4_pauseBeforeQualification_thenResumeToQualify_insertsOneRow() {
        coordinator.start()
        startPlaying(TRACK_A)

        advance(10_000L) // 10s played
        assertEquals(0, repo.inserts.size)

        client.emit(paused(TRACK_A))
        advance(60_000L) // paused time must NOT accumulate
        assertEquals("Paused time must not qualify a session", 0, repo.inserts.size)

        client.emit(playing(TRACK_A))
        advance(5_000L) // total actual playing time = 15s

        assertEquals("Resuming across the threshold must insert exactly one row", 1, repo.inserts.size)
        assertEquals(false, repo.inserts.single().completed)
    }

    // ------------------------------------------------------------- CASE 5

    /** CASE 5 — skipping before qualification leaves no trace of track A. */
    @Test
    fun case5_skipBeforeQualification_writesNothingForTrackA() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(5_000L)

        startPlaying(TRACK_B)
        advance(1_000L)

        assertEquals("An unqualified track must not be recorded", 0, repo.insertsFor(TRACK_A).size)
    }

    // ------------------------------------------------------------- CASE 6

    /** CASE 6 — skipping after qualification keeps exactly one incomplete row for A. */
    @Test
    fun case6_skipAfterQualification_keepsSingleIncompleteRowForTrackA() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        startPlaying(TRACK_B)
        advance(1_000L)

        val aRows = repo.insertsFor(TRACK_A)
        assertEquals("Track A must have exactly one row after a skip", 1, aRows.size)
        assertEquals("A skipped listen stays completed=false", false, aRows.single().completed)
        assertEquals("Skipping must not mark the row completed", 0, repo.markedCompleted.size)
    }

    // ------------------------------------------------------------- CASE 7

    /** CASE 7 — natural completion UPDATES the qualified row; it never inserts a second. */
    @Test
    fun case7_naturalCompletion_updatesExistingRowInsteadOfInserting() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        assertEquals(1, repo.inserts.size)
        val qualifiedRowId = repo.inserts.single().rowId

        client.emit(ended(TRACK_A))
        advance(1_000L)

        assertEquals("Completion must not insert a second row", 1, repo.inserts.size)
        assertEquals(
            "Completion must update exactly the qualified row id (proves the id was retained)",
            listOf(qualifiedRowId),
            repo.markedCompleted
        )
    }

    // ------------------------------------------------------------- CASE 8

    /** CASE 8 — auto-advance yields one independent session per track. */
    @Test
    fun case8_autoAdvance_producesOneSessionPerTrack() {
        coordinator.start()

        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        client.emit(ended(TRACK_A))
        advance(1_000L)

        startPlaying(TRACK_B)
        advance(QUALIFY_MS)

        assertEquals("Track A must have exactly one row", 1, repo.insertsFor(TRACK_A).size)
        assertEquals("Track B must have exactly one row", 1, repo.insertsFor(TRACK_B).size)
        assertEquals("No extra rows may exist", 2, repo.inserts.size)
    }

    // ------------------------------------------------------------- CASE 9

    /** CASE 9 — replaying the same track is a NEW session, not a duplicate to suppress. */
    @Test
    fun case9_sameTrackReplay_createsTwoDistinctSessions() {
        coordinator.start()

        // Session 1
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        client.emit(ended(TRACK_A))
        advance(1_000L)

        // Session 2 — same track id, genuinely new playback
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        assertEquals(
            "Replaying a track must create a second session row (do not dedupe on trackId)",
            2,
            repo.insertsFor(TRACK_A).size
        )
    }

    // ------------------------------------------------------------- CASE 10

    /** CASE 10 — rapid skipping below the threshold must create no history storm. */
    @Test
    fun case10_rapidSkip_writesNothing() {
        coordinator.start()

        listOf(TRACK_A, TRACK_B, TRACK_C, TRACK_D).forEach { id ->
            startPlaying(id)
            advance(2_000L) // far below qualification
        }

        assertEquals("Rapid skipping must not write any history", 0, repo.inserts.size)
    }

    // ------------------------------------------------------------- CASE 11

    /** CASE 11 — stopping before qualification writes nothing. */
    @Test
    fun case11_stopBeforeQualification_writesNothing() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(5_000L)

        coordinator.stop()
        advance(QUALIFY_MS)

        assertEquals("Stopping an unqualified session must write nothing", 0, repo.inserts.size)
    }

    // ------------------------------------------------------------- CASE 12

    /** CASE 12 — stopping after qualification keeps one incomplete row. */
    @Test
    fun case12_stopAfterQualification_keepsOneIncompleteRow() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        coordinator.stop()
        advance(5_000L)

        assertEquals("The qualified row must survive a stop", 1, repo.inserts.size)
        assertEquals(false, repo.inserts.single().completed)
        assertEquals(
            "An abrupt stop is not a natural completion",
            0,
            repo.markedCompleted.size
        )
    }

    // ------------------------------------------------------------- CASE 13

    /**
     * CASE 13 — lifecycle regression guard: start -> stop -> start must remain functional.
     *
     * Today stop() cancels a scope created once at construction, so a restarted
     * coordinator is permanently dead. This test documents the intended contract; the
     * lifecycle fix itself belongs to a later stage.
     */
    @Test
    fun case13_startStopStart_remainsFunctional() {
        coordinator.start()
        coordinator.stop()
        coordinator.start()

        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        assertEquals(
            "A restarted coordinator must still record qualified sessions",
            1,
            repo.inserts.size
        )
    }
}
