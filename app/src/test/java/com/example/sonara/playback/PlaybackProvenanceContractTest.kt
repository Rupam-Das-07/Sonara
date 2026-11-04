package com.example.sonara.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.feature.search.searchPlaybackOrigin
import com.example.sonara.playback.checkpoint.PlaybackHistoryCoordinator
import com.example.sonara.playback.client.MediaControllerState
import com.example.sonara.playback.controller.NextTrackDecision
import com.example.sonara.playback.controller.PlaybackQueueEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2B — playback provenance and session identity, end to end through the real
 * [PlaybackQueueEngine] and [PlaybackHistoryCoordinator].
 *
 * DESIGN UNDER TEST
 * -----------------
 * Origin is modelled per QUEUE CONTEXT on the engine, not carried in
 * `MediaMetadata.extras`. Two reasons, both verified against the source:
 *  1. `SonaraPlaybackService.buildMediaItem` constructs auto-advanced and gapless-preloaded
 *     items from a bare [Track] and sets no extras, so an extras-only channel would lose
 *     provenance on exactly the paths that must stay ineligible.
 *  2. `isReturnDefaultValues = true` makes `android.os.Bundle` accessors return defaults in
 *     JVM unit tests, so extras round-tripping is not unit-testable at all.
 *
 * Session identity is owned by the coordinator, which already has exactly one session per
 * listen (open/close keyed on track transitions), so retries, reconnections and repeated
 * callbacks reuse the id by construction while a genuine replay reopens and re-mints.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProvenanceContractTest {

    companion object {
        const val QUALIFY_MS = 15_000L
        const val TRACK_A = "track-a"
        const val TRACK_B = "track-b"
    }

    // ----------------------------------------------------------------- harness

    private class FakeControllerClient {
        val states = MutableStateFlow(MediaControllerState())
        fun emit(state: MediaControllerState) { states.value = state }
    }

    /** Faithful to Phase 2A: non-blank session ids are unique and reuse their row id. */
    private class RecordingHistoryRepository : HistoryRepository {
        data class Inserted(
            val rowId: Long,
            val trackId: String,
            val completed: Boolean,
            val origin: PlaybackOrigin,
            val sessionId: String
        )

        val inserts = mutableListOf<Inserted>()
        val markedCompleted = mutableListOf<Long>()
        private var nextRowId = 1L
        private val flow = MutableStateFlow<List<HistoryItem>>(emptyList())

        override fun getRecentHistory(limit: Int): kotlinx.coroutines.flow.Flow<List<HistoryItem>> = flow

        override suspend fun recordHistory(track: Track, completed: Boolean): Long =
            recordHistoryWithProvenance(track, completed, PlaybackOrigin.LEGACY_UNKNOWN, "")

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

        override suspend fun markHistoryCompleted(historyId: Long) { markedCompleted += historyId }

        override suspend fun clearHistory() { inserts.clear(); markedCompleted.clear() }
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    private lateinit var client: FakeControllerClient
    private lateinit var repo: RecordingHistoryRepository
    private lateinit var coordinatorScope: TestScope
    private lateinit var engine: PlaybackQueueEngine

    /** Deterministic session ids so assertions are about identity, not randomness. */
    private var mintCount = 0
    private fun mintId(): String = "session-${++mintCount}"

    @Before
    fun setUp() {
        client = FakeControllerClient()
        repo = RecordingHistoryRepository()
        coordinatorScope = TestScope(dispatcher)
        engine = PlaybackQueueEngine()
        mintCount = 0
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
    }

    /** Builds a coordinator wired to the engine exactly as SonaraPlaybackService does. */
    private fun coordinator(
        originFor: (String?) -> PlaybackOrigin = { engine.originFor(it) },
        newSessionId: () -> String = { mintId() }
    ) = PlaybackHistoryCoordinator(
        playbackState = client.states,
        historyRepository = repo,
        scope = coordinatorScope,
        qualificationThresholdMs = QUALIFY_MS,
        originFor = originFor,
        newSessionId = newSessionId
    )

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

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun startPlaying(trackId: String) {
        client.emit(playing(trackId))
        scheduler.runCurrent()
    }

    private fun track(id: String) = Track(id = id, title = "Title $id", artist = "Artist $id")

    // =========================================================== ORIGIN PROPAGATION

    @Test
    fun songsSearchInitiationYieldsSearchSongs() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        assertEquals(PlaybackOrigin.SEARCH_SONGS, engine.originFor(TRACK_A))
    }

    @Test
    fun likedSongsInitiationYieldsLikedSongs() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.LIKED_SONGS)
        assertEquals(PlaybackOrigin.LIKED_SONGS, engine.originFor(TRACK_A))
    }

    @Test
    fun ineligibleOriginsAreCarriedVerbatimAndNeverUpgraded() {
        for (ineligible in listOf(
            PlaybackOrigin.HOME,
            PlaybackOrigin.PLAYLIST,
            PlaybackOrigin.VIDEO_SEARCH,
            PlaybackOrigin.RADIO_AUTOPLAY,
            PlaybackOrigin.OTHER,
            PlaybackOrigin.LEGACY_UNKNOWN
        )) {
            val fresh = PlaybackQueueEngine()
            fresh.setContext(track(TRACK_A), origin = ineligible)
            assertEquals(
                "$ineligible must be preserved, never promoted",
                ineligible,
                fresh.originFor(TRACK_A)
            )
        }
    }

    @Test
    fun omittingTheOriginFailsClosedToLegacyUnknown() {
        // Any call site that has not opted in keeps today's behaviour and stays ineligible.
        engine.setContext(track(TRACK_A))
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, engine.originFor(TRACK_A))
    }

    @Test
    fun originIsNotAnsweredForATrackThatIsNotCurrent() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        assertEquals(
            "a late write for another track must not be stamped with this context",
            PlaybackOrigin.LEGACY_UNKNOWN,
            engine.originFor(TRACK_B)
        )
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, engine.originFor(null))
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, engine.originFor("   "))
    }

    @Test
    fun queueReplacementDoesNotInheritThePreviousEligibleOrigin() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        engine.setContext(track(TRACK_B), origin = PlaybackOrigin.HOME)

        assertEquals(PlaybackOrigin.HOME, engine.originFor(TRACK_B))
        assertEquals(
            "the replaced track is no longer current, so it answers LEGACY_UNKNOWN",
            PlaybackOrigin.LEGACY_UNKNOWN,
            engine.originFor(TRACK_A)
        )
    }

    @Test
    fun clearResetsProvenanceToLegacyUnknown() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.LIKED_SONGS)
        engine.clear()
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, engine.originFor(TRACK_A))
    }

    @Test
    fun searchOriginMappingIsExhaustiveAndFailsClosed() {
        assertEquals(PlaybackOrigin.SEARCH_SONGS, searchPlaybackOrigin(SearchMode.SONGS))
        assertEquals(PlaybackOrigin.VIDEO_SEARCH, searchPlaybackOrigin(SearchMode.VIDEOS))
        for (mode in SearchMode.entries) {
            val origin = searchPlaybackOrigin(mode)
            if (mode != SearchMode.SONGS) {
                assertNotEquals(
                    "only the Songs tab may be eligible",
                    PlaybackOrigin.SEARCH_SONGS,
                    origin
                )
            }
        }
    }

    // ============================================== QUEUE TRANSITIONS PRESERVE ORIGIN

    @Test
    fun advancingThroughTheUserQueuePreservesTheQueueOrigin() {
        engine.setContext(
            track(TRACK_A),
            contextQueue = listOf(track(TRACK_A), track(TRACK_B)),
            origin = PlaybackOrigin.LIKED_SONGS
        )
        val decision = engine.advance(fromTrackId = TRACK_A)

        assertTrue(decision is NextTrackDecision.PlayTrack)
        assertEquals(TRACK_B, (decision as NextTrackDecision.PlayTrack).track.id)
        assertEquals(
            "advancing inside the same queue keeps its provenance",
            PlaybackOrigin.LIKED_SONGS,
            engine.originFor(TRACK_B)
        )
    }

    @Test
    fun repeatOneReplayPreservesTheQueueOrigin() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        engine.toggleRepeatMode() // Repeat All
        engine.toggleRepeatMode() // Repeat One
        assertEquals(2, engine.repeatMode)

        assertTrue(engine.advance(fromTrackId = TRACK_A) is NextTrackDecision.ReplayCurrent)
        assertEquals(PlaybackOrigin.SEARCH_SONGS, engine.originFor(TRACK_A))
    }

    @Test
    fun repeatAllLoopPreservesTheQueueOrigin() {
        engine.setContext(
            track(TRACK_A),
            contextQueue = listOf(track(TRACK_A), track(TRACK_B)),
            origin = PlaybackOrigin.LIKED_SONGS
        )
        engine.toggleRepeatMode() // Repeat All
        assertEquals(1, engine.repeatMode)

        engine.advance(fromTrackId = TRACK_A) // -> B
        val looped = engine.advance(fromTrackId = TRACK_B) // queue exhausted -> snapshot

        assertTrue(looped is NextTrackDecision.PlayTrack)
        val loopedId = (looped as NextTrackDecision.PlayTrack).track.id
        assertEquals(PlaybackOrigin.LIKED_SONGS, engine.originFor(loopedId))
    }

    @Test
    fun fallingThroughToRadioMakesProvenanceAutoplay() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        engine.ingestRecommendations(listOf(track(TRACK_B)))

        val decision = engine.advance(fromTrackId = TRACK_A)

        assertTrue(decision is NextTrackDecision.PlayTrack)
        assertEquals(TRACK_B, (decision as NextTrackDecision.PlayTrack).track.id)
        assertEquals(
            "a recommendation must never inherit an eligible origin",
            PlaybackOrigin.RADIO_AUTOPLAY,
            engine.originFor(TRACK_B)
        )
    }

    @Test
    fun needingRecommendationsKeepsCurrentSessionProvenanceUntilReplacement() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.LIKED_SONGS)

        val decision = engine.advance(fromTrackId = TRACK_A)

        assertTrue(decision is NextTrackDecision.NeedRecommendations)
        assertEquals(
            "a recommendation request is not yet a replacement track",
            PlaybackOrigin.LIKED_SONGS,
            engine.currentOrigin
        )
        assertEquals(PlaybackOrigin.LIKED_SONGS, engine.originFor(TRACK_A))
    }

    @Test
    fun previousRestoresThePriorContextOriginFromTheBackStack() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        engine.setContext(track(TRACK_B), origin = PlaybackOrigin.HOME)

        val decision = engine.previous(currentPositionMs = 0L)

        assertTrue(decision is com.example.sonara.playback.controller.PreviousTrackDecision.PlayTrack)
        assertEquals(PlaybackOrigin.SEARCH_SONGS, engine.originFor(TRACK_A))
    }

    @Test
    fun playNextUsesTheOriginOfItsInitiatingIntent() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        engine.playNext(track(TRACK_B), origin = PlaybackOrigin.PLAYLIST)

        val decision = engine.advance(fromTrackId = TRACK_A)

        assertTrue(decision is NextTrackDecision.PlayTrack)
        assertEquals(PlaybackOrigin.PLAYLIST, engine.originFor(TRACK_B))
    }

    // ================================================== HISTORY WRITE USES PROVENANCE

    @Test
    fun qualifyingWriteCarriesTheEngineOriginAndANonEmptySessionId() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        coordinator().start()
        startPlaying(TRACK_A)

        advance(QUALIFY_MS)

        assertEquals(1, repo.inserts.size)
        val row = repo.inserts.single()
        assertEquals(TRACK_A, row.trackId)
        assertEquals(PlaybackOrigin.SEARCH_SONGS, row.origin)
        assertTrue("session id must be non-empty", row.sessionId.isNotBlank())
        assertEquals(false, row.completed)
    }

    @Test
    fun anIneligibleOriginIsRecordedVerbatimAndStaysIneligible() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.HOME)
        coordinator().start()
        startPlaying(TRACK_A)

        advance(QUALIFY_MS)

        assertEquals(PlaybackOrigin.HOME, repo.inserts.single().origin)
    }

    @Test
    fun missingProvenanceFailsClosedToLegacyUnknown() {
        // Default coordinator wiring: no origin provider supplied at all.
        PlaybackHistoryCoordinator(
            playbackState = client.states,
            historyRepository = repo,
            scope = coordinatorScope,
            qualificationThresholdMs = QUALIFY_MS,
            newSessionId = { mintId() }
        ).start()
        startPlaying(TRACK_A)

        advance(QUALIFY_MS)

        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, repo.inserts.single().origin)
    }

    @Test
    fun theFifteenSecondThresholdIsUnchanged() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        coordinator().start()
        startPlaying(TRACK_A)

        advance(QUALIFY_MS - 1000L)
        assertEquals("must not qualify before 15s of playing time", 0, repo.inserts.size)

        advance(1000L)
        assertEquals(1, repo.inserts.size)
    }

    // ========================================================= SESSION ID LIFECYCLE

    @Test
    fun repeatedCallbacksForOneSessionProduceOneRowAndOneSessionId() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        coordinator().start()
        startPlaying(TRACK_A)

        advance(QUALIFY_MS)
        // Retries / reconnections / duplicate emissions of the same session.
        repeat(5) { startPlaying(TRACK_A); advance(1000L) }

        assertEquals("one session must yield exactly one row", 1, repo.inserts.size)
        assertEquals(
            "the id must not be re-minted by further callbacks",
            1,
            mintCount
        )
    }

    @Test
    fun aGenuineReplayCreatesADifferentSessionId() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        coordinator().start()

        startPlaying(TRACK_A)
        advance(QUALIFY_MS)
        val first = repo.inserts.single().sessionId

        // End the listen, then deliberately play the same track again.
        client.emit(ended(TRACK_A))
        scheduler.runCurrent()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        assertEquals("a replay is a second listen", 2, repo.inserts.size)
        val second = repo.inserts.last().sessionId
        assertNotEquals("a genuine new session must not reuse the previous id", first, second)
        assertTrue(second.isNotBlank())
    }

    @Test
    fun queueAdvanceGivesEachTrackItsOwnSessionButKeepsTheQueueOrigin() {
        engine.setContext(
            track(TRACK_A),
            contextQueue = listOf(track(TRACK_A), track(TRACK_B)),
            origin = PlaybackOrigin.LIKED_SONGS
        )
        coordinator().start()

        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        // The queue advances, then the new track starts playing.
        engine.advance(fromTrackId = TRACK_A)
        startPlaying(TRACK_B)
        advance(QUALIFY_MS)

        assertEquals(2, repo.inserts.size)
        val (rowA, rowB) = repo.inserts[0] to repo.inserts[1]
        assertEquals(TRACK_A, rowA.trackId)
        assertEquals(TRACK_B, rowB.trackId)
        assertNotEquals("each listen is its own session", rowA.sessionId, rowB.sessionId)
        assertEquals(PlaybackOrigin.LIKED_SONGS, rowA.origin)
        assertEquals(PlaybackOrigin.LIKED_SONGS, rowB.origin)
    }

    @Test
    fun completionUpdatesTheExistingRowRatherThanInsertingAgain() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        coordinator().start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        val rowId = repo.inserts.single().rowId
        client.emit(ended(TRACK_A))
        scheduler.runCurrent()

        assertEquals("completion must not insert a second row", 1, repo.inserts.size)
        assertEquals(listOf(rowId), repo.markedCompleted)
    }

    @Test
    fun aSessionBelowTheThresholdMintsNoRowAndLeavesNoProvenance() {
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        coordinator().start()
        startPlaying(TRACK_A)

        advance(QUALIFY_MS - 1000L)
        client.emit(ended(TRACK_A))
        scheduler.runCurrent()

        assertEquals(0, repo.inserts.size)
        assertEquals(0, repo.markedCompleted.size)
    }

    // ============================================================ CREDIT PRESERVATION

    @Test
    fun theCoordinatorWritesNoStructuredCreditsSoStoredCreditsSurvive() {
        // Media3 metadata carries no structured credits, so the reconstructed Track has an
        // empty list. That must stay empty rather than being invented from the flat artist
        // string; the repository is responsible for preserving credits already stored
        // (TrackEntity.fromDomainPreservingCredits).
        engine.setContext(track(TRACK_A), origin = PlaybackOrigin.SEARCH_SONGS)
        var capturedCredits: Int? = null
        val creditSpy = object : HistoryRepository {
            private val flow = MutableStateFlow<List<HistoryItem>>(emptyList())
            override fun getRecentHistory(limit: Int) = flow
            override suspend fun recordHistory(track: Track, completed: Boolean): Long =
                recordHistoryWithProvenance(track, completed, PlaybackOrigin.LEGACY_UNKNOWN, "")
            override suspend fun recordHistoryWithProvenance(
                track: Track,
                completed: Boolean,
                sourceSection: PlaybackOrigin,
                playbackSessionId: String
            ): Long {
                capturedCredits = track.artists.size
                return 1L
            }
            override suspend fun markHistoryCompleted(historyId: Long) {}
            override suspend fun clearHistory() {}
        }

        PlaybackHistoryCoordinator(
            playbackState = client.states,
            historyRepository = creditSpy,
            scope = coordinatorScope,
            qualificationThresholdMs = QUALIFY_MS,
            originFor = { engine.originFor(it) },
            newSessionId = { mintId() }
        ).start()
        startPlaying(TRACK_A)
        advance(QUALIFY_MS)

        assertEquals(
            "the coordinator must not fabricate credits from Media3 metadata",
            0,
            capturedCredits
        )
    }
}
