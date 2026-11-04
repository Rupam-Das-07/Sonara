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
 * REGRESSION CONTRACT for completion on an AUTOMATIC (gapless) media-item transition.
 *
 * Physical verification on a Redmi Note 12 5G proved that with next-track prefetching
 * enabled ExoPlayer does NOT emit [Player.STATE_ENDED] when track A runs out and track B
 * begins. It emits only:
 *
 *     onMediaItemTransition(B, MEDIA_ITEM_TRANSITION_REASON_AUTO)
 *
 * while playbackState stays [Player.STATE_READY]. The coordinator's completion logic was
 * driven exclusively by STATE_ENDED, so a naturally finished, qualified track had its row
 * id discarded by the successor's session and stayed completed=false forever.
 *
 * These cases pin the fixed semantics. They complement — and never relax —
 * [PlaybackHistorySessionContractTest], which remains the authoritative session contract.
 *
 * DO NOT weaken these assertions to make an implementation pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackHistoryAutoTransitionContractTest {

    private companion object {
        const val QUALIFY_MS = 15_000L
        const val TRACK_A = "track-a"
        const val TRACK_B = "track-b"
        const val TRACK_C = "track-c"
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

        /** Mirrors HistoryRepositoryImpl: legacy form delegates with LEGACY_UNKNOWN + blank id. */
        override suspend fun recordHistory(track: Track, completed: Boolean): Long =
            recordHistoryWithProvenance(track, completed, PlaybackOrigin.LEGACY_UNKNOWN, "")

        /** Reproduces Phase 2A idempotence: a repeat non-blank session id reuses its row id. */
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

    /**
     * Reproduces SonaraPlaybackService.onMediaItemTransition(reason = REASON_AUTO) exactly:
     * the coordinator is notified FIRST and synchronously, and only afterwards is the
     * successor's authoritative snapshot published. The production ordering matters, so the
     * test must not invent a different one.
     *
     * Note that playbackState stays STATE_READY throughout — no STATE_ENDED is ever
     * delivered, which is precisely the device-observed behaviour being regressed against.
     */
    private fun autoTransitionTo(trackId: String) {
        coordinator.onAutoTransitionTo(trackId)
        client.emit(playing(trackId))
        scheduler.runCurrent()
    }

    /** Models a USER skip: an explicit setMediaItem, i.e. reason != REASON_AUTO. */
    private fun manualSkipTo(trackId: String) {
        client.emit(playing(trackId))
        scheduler.runCurrent()
    }

    // ------------------------------------------------- harness sanity (should pass)

    /**
     * HARNESS SANITY — proves the fake client drives the coordinator and that an AUTO
     * transition is observable at all, so failures below are behavioural, not harness bugs.
     */
    @Test
    fun harness_qualifiedSessionThenAutoTransition_producesSomeCompletion() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        assertTrue("Harness broken: nothing was inserted on qualification", repo.inserts.isNotEmpty())

        autoTransitionTo(TRACK_B)

        assertTrue(
            "Harness broken: the AUTO transition produced no completion interaction at all",
            repo.markedCompleted.isNotEmpty()
        )
    }

    // ------------------------------------------------------------- AUTO CASE 1

    /**
     * AUTO CASE 1 — qualified A, then AUTO transition to B.
     *
     * This is the exact device-verified bug: exactly one row for A, and it MUST be
     * completed, even though STATE_ENDED was never delivered. B has no row yet.
     */
    @Test
    fun auto1_qualifiedTrack_isMarkedCompletedOnAutoTransition() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        val rowA = repo.inserts.single().rowId

        autoTransitionTo(TRACK_B)

        assertEquals("Track A must still have exactly one row", 1, repo.insertsFor(TRACK_A).size)
        assertEquals("The AUTO transition must not insert any row", 1, repo.inserts.size)
        assertEquals(
            "A naturally finished qualified track must be completed via the AUTO transition",
            listOf(rowA),
            repo.markedCompleted
        )
        assertEquals(
            "The successor must not be recorded before it qualifies",
            0,
            repo.insertsFor(TRACK_B).size
        )
    }

    // ------------------------------------------------------------- AUTO CASE 2

    /** AUTO CASE 2 — an UNQUALIFIED track that auto-advances leaves no trace whatsoever. */
    @Test
    fun auto2_unqualifiedTrack_autoTransition_writesNothing() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS - 1_000L) // 14s — below the threshold

        autoTransitionTo(TRACK_B)

        assertEquals("An unqualified session must never be recorded", 0, repo.inserts.size)
        assertEquals(
            "There is no row to complete, so nothing may be marked completed",
            0,
            repo.markedCompleted.size
        )
    }

    // ------------------------------------------------------------- AUTO CASE 3

    /**
     * AUTO CASE 3 — qualified A, AUTO to B, then B qualifies on its own merits.
     *
     * Two independent rows: A completed, B still in flight. B must earn the full
     * qualification threshold after the transition; it inherits nothing from A.
     */
    @Test
    fun auto3_successorEarnsItsOwnRow_predecessorStaysCompleted() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        val rowA = repo.inserts.single().rowId

        autoTransitionTo(TRACK_B)

        advance(QUALIFY_MS - 1_000L)
        assertEquals(
            "The successor must still require a full ${QUALIFY_MS}ms of actual playing time",
            1,
            repo.inserts.size
        )

        advance(1_000L)

        assertEquals("Exactly two rows must exist", 2, repo.inserts.size)
        val aRow = repo.insertsFor(TRACK_A).single()
        val bRow = repo.insertsFor(TRACK_B).single()
        assertEquals("Both rows are inserted as incomplete listens", false, aRow.completed)
        assertEquals("Both rows are inserted as incomplete listens", false, bRow.completed)
        assertEquals(
            "Only the finished predecessor may be completed — never the successor",
            listOf(rowA),
            repo.markedCompleted
        )
    }

    // ------------------------------------------------------------- AUTO CASE 4

    /**
     * AUTO CASE 4 — a chain of automatic transitions (A -> B -> C).
     *
     * Each qualified predecessor is completed exactly once, in order, and no duplicate
     * rows appear anywhere in the chain.
     */
    @Test
    fun auto4_chainedAutoTransitions_completeEachQualifiedSessionExactlyOnce() {
        coordinator.start()

        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        val rowA = repo.inserts.single().rowId

        autoTransitionTo(TRACK_B)
        advance(QUALIFY_MS)
        val rowB = repo.insertsFor(TRACK_B).single().rowId

        autoTransitionTo(TRACK_C)

        assertEquals("Only A and B have rows so far", 2, repo.inserts.size)
        assertEquals(
            "Each qualified session in the chain is completed exactly once, in order",
            listOf(rowA, rowB),
            repo.markedCompleted
        )

        advance(QUALIFY_MS)

        assertEquals("C adds exactly one further row", 3, repo.inserts.size)
        assertEquals("Track A must never gain a second row", 1, repo.insertsFor(TRACK_A).size)
        assertEquals("Track B must never gain a second row", 1, repo.insertsFor(TRACK_B).size)
        assertEquals("Track C must have exactly one row", 1, repo.insertsFor(TRACK_C).size)
        assertEquals(
            "The still-playing track C must not be completed",
            listOf(rowA, rowB),
            repo.markedCompleted
        )
    }

    /** AUTO CASE 4b — an unqualified link in the chain is skipped without breaking it. */
    @Test
    fun auto4b_unqualifiedMiddleTrack_doesNotBreakTheChain() {
        coordinator.start()

        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        val rowA = repo.inserts.single().rowId

        autoTransitionTo(TRACK_B)
        advance(3_000L) // B never qualifies (e.g. a short interlude)

        autoTransitionTo(TRACK_C)
        advance(QUALIFY_MS)
        val rowC = repo.insertsFor(TRACK_C).single().rowId

        assertEquals("Only A and C may have rows", 2, repo.inserts.size)
        assertEquals("The unqualified middle track must have no row", 0, repo.insertsFor(TRACK_B).size)
        assertEquals(
            "Only the qualified predecessor A was completed",
            listOf(rowA),
            repo.markedCompleted
        )
        assertTrue("Sanity: C's row exists and is distinct", rowC != rowA)
    }

    // ------------------------------------------------------------- AUTO CASE 5

    /**
     * AUTO CASE 5 — a MANUAL skip after qualification must be untouched by this fix.
     *
     * A user skip reaches the service as REASON_PLAYLIST_CHANGED / REASON_SEEK, so
     * onAutoTransitionTo is never called and the qualified row stays completed=false.
     */
    @Test
    fun auto5_manualSkipAfterQualification_isNotACompletion() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        manualSkipTo(TRACK_B)
        advance(1_000L)

        val aRows = repo.insertsFor(TRACK_A)
        assertEquals("Track A must have exactly one row after a manual skip", 1, aRows.size)
        assertEquals("A manually skipped listen stays completed=false", false, aRows.single().completed)
        assertEquals(
            "A manual skip must never be treated as natural completion",
            0,
            repo.markedCompleted.size
        )
    }

    /** AUTO CASE 5b — a manual skip BEFORE qualification still writes nothing. */
    @Test
    fun auto5b_manualSkipBeforeQualification_writesNothing() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(5_000L)

        manualSkipTo(TRACK_B)
        advance(1_000L)

        assertEquals(0, repo.inserts.size)
        assertEquals(0, repo.markedCompleted.size)
    }

    // ------------------------------------------------------------- AUTO CASE 6

    /**
     * AUTO CASE 6 — DEFENSIVE: an AUTO transition whose successor has the SAME media id.
     *
     * This cannot occur in Sonara today (ExoPlayer runs REPEAT_MODE_OFF; repeat-one is
     * implemented app-side as ReplayCurrent -> seekTo(0), and the preloader refuses to
     * queue a successor equal to the current track). The case exists to pin the
     * IDENTITY rule: completion keys off the in-flight session, never off trackId. If a
     * future change ever produces a same-id auto transition, the finished play must be
     * completed and the replay must become a second, independent, incomplete row.
     */
    @Test
    fun auto6_sameTrackAutoTransition_completesFirstSessionAndOpensASecond() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        val firstRow = repo.inserts.single().rowId

        autoTransitionTo(TRACK_A) // successor has the same media id

        assertEquals(
            "The finished play must be completed even though the media id did not change",
            listOf(firstRow),
            repo.markedCompleted
        )
        assertEquals("The replay must not be recorded until it qualifies", 1, repo.inserts.size)

        advance(QUALIFY_MS)

        val aRows = repo.insertsFor(TRACK_A)
        assertEquals(
            "A replay is a NEW session: two rows for the same track (no trackId dedup)",
            2,
            aRows.size
        )
        val secondRow = aRows.last().rowId
        assertTrue("The replay must get its own row id", secondRow != firstRow)
        assertEquals(
            "The in-flight replay must not be completed",
            listOf(firstRow),
            repo.markedCompleted
        )
    }

    // ------------------------------------------------------------- AUTO CASE 7

    /**
     * AUTO CASE 7 — STATE_ENDED is still honoured where it is delivered, and an AUTO
     * transition arriving afterwards must not complete the same session a second time.
     */
    @Test
    fun auto7_stateEndedThenAutoTransition_completesExactlyOnce() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        val rowA = repo.inserts.single().rowId

        // Path that DOES deliver a terminal state (single-item playback).
        client.emit(ended(TRACK_A))
        advance(1_000L)
        assertEquals(
            "Existing STATE_ENDED completion behaviour must be preserved",
            listOf(rowA),
            repo.markedCompleted
        )

        // The player then transitions into the successor.
        autoTransitionTo(TRACK_B)

        assertEquals(
            "A session already completed by STATE_ENDED must not be completed twice",
            listOf(rowA),
            repo.markedCompleted
        )
        assertEquals("No row may be inserted by either completion signal", 1, repo.inserts.size)

        advance(QUALIFY_MS)
        assertEquals("B earns its own single row", 1, repo.insertsFor(TRACK_B).size)
        assertEquals(
            "B is still playing and must not be completed",
            listOf(rowA),
            repo.markedCompleted
        )
    }

    /**
     * AUTO CASE 7b — idempotence of the seam itself: a duplicated AUTO signal for one
     * transition must not complete the session twice, nor insert anything.
     */
    @Test
    fun auto7b_duplicateAutoTransitionSignal_isIdempotent() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        val rowA = repo.inserts.single().rowId

        coordinator.onAutoTransitionTo(TRACK_B)
        coordinator.onAutoTransitionTo(TRACK_B) // duplicated signal for the same transition
        client.emit(playing(TRACK_B))
        scheduler.runCurrent()

        assertEquals(
            "A session must be completed exactly once however many signals arrive",
            listOf(rowA),
            repo.markedCompleted
        )
        assertEquals("Duplicate signals must not insert rows", 1, repo.inserts.size)

        advance(QUALIFY_MS)
        assertEquals("B still earns exactly one row", 1, repo.insertsFor(TRACK_B).size)
        assertEquals("No duplicate rows anywhere", 2, repo.inserts.size)
    }

    /**
     * AUTO CASE 7c — an AUTO transition with no session in flight completes nothing.
     *
     * Guards against the fix ever reaching back into arbitrary earlier database rows.
     */
    @Test
    fun auto7c_autoTransitionWithNoOpenSession_writesNothing() {
        coordinator.start()

        coordinator.onAutoTransitionTo(TRACK_B)
        scheduler.runCurrent()

        assertEquals(0, repo.inserts.size)
        assertEquals(
            "With no in-flight session there is nothing to complete",
            0,
            repo.markedCompleted.size
        )
    }

    /**
     * AUTO CASE 7d — after a stop(), an AUTO transition must not resurrect the stopped
     * session's row id. An abrupt stop is not a natural completion.
     */
    @Test
    fun auto7d_autoTransitionAfterStop_doesNotCompleteTheStoppedSession() {
        coordinator.start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        assertEquals(1, repo.inserts.size)

        coordinator.stop()
        coordinator.onAutoTransitionTo(TRACK_B)
        scheduler.runCurrent()

        assertEquals("The qualified row must survive a stop", 1, repo.inserts.size)
        assertEquals(false, repo.inserts.single().completed)
        assertEquals(
            "A stopped session is not completed by a later transition signal",
            0,
            repo.markedCompleted.size
        )
    }
}
