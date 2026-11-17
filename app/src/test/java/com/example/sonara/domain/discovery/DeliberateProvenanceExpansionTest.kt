package com.example.sonara.domain.discovery

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.playback.checkpoint.PlaybackHistoryCoordinator
import com.example.sonara.playback.client.MediaControllerState
import com.example.sonara.playback.controller.NextTrackDecision
import com.example.sonara.playback.controller.PlaybackQueueEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression and integration test suite for Deliberate Playback Provenance Expansion:
 *
 * Verifies that explicit user-initiated selections from Quick Picks and Featured Artists
 * qualify for Featured Artists personalization, while automatic queue advancement, radio
 * autoplay, and background playback remain strictly excluded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliberateProvenanceExpansionTest {

    private val now = 1_700_000_000_000L
    private val dayMs = 24L * 60L * 60L * 1000L

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var coordinatorScope: TestScope
    private lateinit var engine: PlaybackQueueEngine
    private lateinit var repo: RecordingHistoryRepository
    private val controllerStateFlow = MutableStateFlow(MediaControllerState())

    @Before
    fun setUp() {
        coordinatorScope = TestScope(dispatcher)
        engine = PlaybackQueueEngine()
        repo = RecordingHistoryRepository()
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
    }

    private fun track(id: String, artist: String = "Artist $id", title: String = "Title $id") =
        Track(id = id, title = title, artist = artist)

    private fun play(
        artist: String,
        origin: PlaybackOrigin,
        daysAgo: Long = 1L,
        completed: Boolean = true,
        index: Int = 0
    ) = HistoryItem(
        id = index.toLong(),
        track = track("vid-$artist-$origin-$index", artist, "Song $index"),
        listenedAt = now - (daysAgo * dayMs) - (index * 60_000L),
        completed = completed,
        sourceSection = origin
    )

    private fun plays(
        artist: String,
        origin: PlaybackOrigin,
        count: Int,
        daysAgo: Long = 1L,
        completed: Boolean = true
    ) = (0 until count).map { play(artist, origin, daysAgo, completed, it) }

    private fun coordinator() = PlaybackHistoryCoordinator(
        playbackState = controllerStateFlow,
        historyRepository = repo,
        scope = coordinatorScope,
        qualificationThresholdMs = 15_000L,
        originFor = { engine.originFor(it) },
        newSessionId = { "session-${System.nanoTime()}" }
    )

    private fun mediaItem(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .build()
        )
        .build()

    private fun playingState(track: Track) = MediaControllerState(
        isConnected = true,
        isPlaying = true,
        currentMediaItem = mediaItem(track),
        playbackState = Player.STATE_READY,
        durationMs = 240_000L
    )

    // =========================================================================
    // 1. QUICK PICKS PROVENANCE & QUEUE ADVANCEMENT
    // =========================================================================

    @Test
    fun `explicitly selecting Quick Picks track produces QUICK_PICKS origin and upcoming tracks default to HOME`() {
        val trackA = track("qp-1", "Arijit Singh")
        val trackB = track("qp-2", "Pritam")

        engine.setContext(
            track = trackA,
            contextQueue = listOf(trackA, trackB),
            origin = PlaybackOrigin.QUICK_PICKS
        )

        // The explicitly clicked track carries QUICK_PICKS provenance
        assertEquals(PlaybackOrigin.QUICK_PICKS, engine.originFor(trackA.id))

        // When track A finishes, automatic queue advance moves to track B
        val decision = engine.advance(fromTrackId = trackA.id)
        assertTrue(decision is NextTrackDecision.PlayTrack)
        assertEquals(trackB.id, (decision as NextTrackDecision.PlayTrack).track.id)

        // Track B is an automatic continuation and must NOT retain deliberate QUICK_PICKS origin
        assertEquals(
            "Automatic queue advancement from Quick Picks must default to HOME (ineligible)",
            PlaybackOrigin.HOME,
            engine.originFor(trackB.id)
        )
    }

    @Test
    fun `Quick Picks qualifying listening history contributes to personal artist affinity`() {
        val history = plays("Pritam", PlaybackOrigin.QUICK_PICKS, 3)

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
        val score = ArtistAffinityCalculator.lookupAffinity(affinity, "Pritam")
        assertTrue("Quick Picks plays must produce positive affinity score", (score ?: 0f) > 0f)

        val shelf = FeaturedArtistSelector.select(emptyList(), history, now)
        assertEquals(1, shelf.size)
        assertEquals("Pritam", shelf[0].name)
    }

    @Test
    fun `automatic Quick Picks queue advancement with HOME origin does not contribute to artist affinity`() {
        // Track A was explicitly clicked (QUICK_PICKS); Track B auto-advanced (HOME)
        val history = plays("Selected Artist", PlaybackOrigin.QUICK_PICKS, 3) +
            plays("Auto Advancer", PlaybackOrigin.HOME, 10)

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
        assertNull(
            "Automatic queue advancement (HOME) must yield no affinity",
            ArtistAffinityCalculator.lookupAffinity(affinity, "Auto Advancer")
        )

        val shelf = FeaturedArtistSelector.select(emptyList(), history, now)
        assertEquals(listOf("Selected Artist"), shelf.map { it.name })
    }

    // =========================================================================
    // 2. FEATURED ARTISTS PROVENANCE & TRACK ARTIST PRESERVATION
    // =========================================================================

    @Test
    fun `explicitly selecting Featured Artists track produces FEATURED_ARTISTS origin and upcoming tracks default to HOME`() {
        val track1 = track("fa-1", "Shreya Ghoshal")
        val track2 = track("fa-2", "Sonu Nigam")

        engine.setContext(
            track = track1,
            contextQueue = listOf(track1, track2),
            origin = PlaybackOrigin.FEATURED_ARTISTS
        )

        assertEquals(PlaybackOrigin.FEATURED_ARTISTS, engine.originFor(track1.id))

        val decision = engine.advance(fromTrackId = track1.id)
        assertTrue(decision is NextTrackDecision.PlayTrack)
        assertEquals(track2.id, (decision as NextTrackDecision.PlayTrack).track.id)

        assertEquals(
            "Automatic queue advancement from Featured Artists must default to HOME (ineligible)",
            PlaybackOrigin.HOME,
            engine.originFor(track2.id)
        )
    }

    @Test
    fun `Featured Artists listening history credits the actual track artist not the shelf artist`() {
        // Shelf artist opened was "Pritam", but track played was sung by "Shreya Ghoshal"
        val history = plays("Shreya Ghoshal", PlaybackOrigin.FEATURED_ARTISTS, 3)

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
        val shreyaScore = ArtistAffinityCalculator.lookupAffinity(affinity, "Shreya Ghoshal")
        assertTrue("Actual track artist must receive affinity", (shreyaScore ?: 0f) > 0f)

        assertNull(
            "Shelf artist must not be credited if track artist differs",
            ArtistAffinityCalculator.lookupAffinity(affinity, "Pritam")
        )

        val shelf = FeaturedArtistSelector.select(emptyList(), history, now)
        assertEquals(listOf("Shreya Ghoshal"), shelf.map { it.name })
    }

    @Test
    fun `collaborative multi-artist credit from Featured Artists does not admit composite tile`() {
        // Track has multi-artist credit
        val history = plays("Pritam & Arijit Singh", PlaybackOrigin.FEATURED_ARTISTS, 5)

        val shelf = FeaturedArtistSelector.select(emptyList(), history, now)
        assertTrue(
            "Multi-artist collaborative credit must not create a composite tile",
            shelf.none { it.name.contains("&") }
        )
    }

    // =========================================================================
    // 3. EXISTING PROTECTIONS (SEARCH, LIKED, RADIO AUTOPLAY, UNKNOWN)
    // =========================================================================

    @Test
    fun `SEARCH_SONGS and LIKED_SONGS remain fully eligible`() {
        val history = plays("Search Artist", PlaybackOrigin.SEARCH_SONGS, 3) +
            plays("Liked Artist", PlaybackOrigin.LIKED_SONGS, 3)

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
        assertTrue((ArtistAffinityCalculator.lookupAffinity(affinity, "Search Artist") ?: 0f) > 0f)
        assertTrue((ArtistAffinityCalculator.lookupAffinity(affinity, "Liked Artist") ?: 0f) > 0f)

        val shelf = FeaturedArtistSelector.select(emptyList(), history, now).map { it.name }
        assertTrue("Search Artist" in shelf)
        assertTrue("Liked Artist" in shelf)
    }

    @Test
    fun `all non-deliberate origins remain strictly excluded`() {
        val ineligibleOrigins = listOf(
            PlaybackOrigin.RADIO_AUTOPLAY,
            PlaybackOrigin.PLAYLIST,
            PlaybackOrigin.HOME,
            PlaybackOrigin.VIDEO_SEARCH,
            PlaybackOrigin.OTHER,
            PlaybackOrigin.LEGACY_UNKNOWN
        )

        for (origin in ineligibleOrigins) {
            val history = plays("Ineligible $origin", origin, 10)
            val affinity = ArtistAffinityCalculator.calculateAffinity(history, now)
            assertTrue(
                "Origin $origin must produce no affinity",
                affinity.isEmpty()
            )
            val shelf = FeaturedArtistSelector.select(emptyList(), history, now)
            assertTrue(
                "Origin $origin must not admit candidates onto shelf",
                shelf.isEmpty()
            )
        }
    }

    // =========================================================================
    // 4. END-TO-END INTEGRATION: ENGINE -> COORDINATOR -> ROOM -> SELECTOR
    // =========================================================================

    @Test
    fun `end-to-end integration Quick Picks explicit tap qualifies while auto-advance remains ineligible`() {
        val coord = coordinator()
        coord.start()

        val trackA = track("qp-track-1", "Deliberate QP Artist")
        val trackB = track("qp-track-2", "Auto Advance QP Artist")

        // 1. User taps track A in Quick Picks
        engine.setContext(
            track = trackA,
            contextQueue = listOf(trackA, trackB),
            origin = PlaybackOrigin.QUICK_PICKS
        )

        // Track A plays for 15 seconds (qualifies)
        controllerStateFlow.value = playingState(trackA)
        scheduler.advanceTimeBy(15_000L)
        scheduler.runCurrent()

        assertEquals(1, repo.inserts.size)
        assertEquals(PlaybackOrigin.QUICK_PICKS, repo.inserts[0].origin)
        assertEquals(trackA.id, repo.inserts[0].trackId)

        // 2. Track A finishes; queue auto-advances to track B
        val nextDecision = engine.advance(fromTrackId = trackA.id)
        assertTrue(nextDecision is NextTrackDecision.PlayTrack)
        coord.onAutoTransitionTo(trackB.id)

        // Track B plays for 15 seconds (qualifies)
        controllerStateFlow.value = playingState(trackB)
        scheduler.advanceTimeBy(15_000L)
        scheduler.runCurrent()

        assertEquals(2, repo.inserts.size)
        assertEquals(trackB.id, repo.inserts[1].trackId)
        assertEquals(
            "Auto-advanced track must be logged with HOME origin",
            PlaybackOrigin.HOME,
            repo.inserts[1].origin
        )

        // 3. Evaluate eligibility for Featured Artists
        val historyItems = repo.inserts.map { ins ->
            HistoryItem(
                id = ins.rowId,
                track = track(ins.trackId, if (ins.trackId == trackA.id) trackA.artist else trackB.artist),
                listenedAt = now - 1000L,
                completed = ins.completed,
                sourceSection = ins.origin
            )
        }

        assertTrue(ArtistAffinityCalculator.isEligibleForPersonalization(historyItems[0]))
        assertFalse(ArtistAffinityCalculator.isEligibleForPersonalization(historyItems[1]))

        coord.stop()
    }

    @Test
    fun `end-to-end integration Featured Artists explicit tap qualifies while auto-advance remains ineligible`() {
        val coord = coordinator()
        coord.start()

        val track1 = track("fa-track-1", "Featured Artist Lead")
        val track2 = track("fa-track-2", "Featured Artist Continuation")

        // User taps track 1 in Featured Artists
        engine.setContext(
            track = track1,
            contextQueue = listOf(track1, track2),
            origin = PlaybackOrigin.FEATURED_ARTISTS
        )

        controllerStateFlow.value = playingState(track1)
        scheduler.advanceTimeBy(15_000L)
        scheduler.runCurrent()

        assertEquals(1, repo.inserts.size)
        assertEquals(PlaybackOrigin.FEATURED_ARTISTS, repo.inserts[0].origin)

        // Track 1 completes, auto-transitions to track 2
        engine.advance(fromTrackId = track1.id)
        coord.onAutoTransitionTo(track2.id)

        controllerStateFlow.value = playingState(track2)
        scheduler.advanceTimeBy(15_000L)
        scheduler.runCurrent()

        assertEquals(2, repo.inserts.size)
        assertEquals(
            "Continuation track from Featured Artists context must be logged as HOME",
            PlaybackOrigin.HOME,
            repo.inserts[1].origin
        )

        coord.stop()
    }

    // =========================================================================
    // Test Recording Repository
    // =========================================================================

    private class RecordingHistoryRepository : HistoryRepository {
        data class Inserted(
            val rowId: Long,
            val trackId: String,
            var completed: Boolean,
            val origin: PlaybackOrigin,
            val sessionId: String
        )

        val inserts = mutableListOf<Inserted>()
        private var nextRowId = 1L
        private val flow = MutableStateFlow<List<HistoryItem>>(emptyList())

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = flow

        override suspend fun recordHistory(track: Track, completed: Boolean): Long =
            recordHistoryWithProvenance(track, completed, PlaybackOrigin.LEGACY_UNKNOWN, "")

        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long {
            val id = nextRowId++
            inserts += Inserted(id, track.id, completed, sourceSection, playbackSessionId)
            return id
        }

        override suspend fun markHistoryCompleted(historyId: Long) {
            inserts.firstOrNull { it.rowId == historyId }?.completed = true
        }

        override suspend fun clearHistory() {
            inserts.clear()
        }
    }
}
