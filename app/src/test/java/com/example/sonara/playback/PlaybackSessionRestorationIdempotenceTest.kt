package com.example.sonara.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.UserPreferences
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.playback.checkpoint.PlaybackHistoryCoordinator
import com.example.sonara.playback.client.MediaControllerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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
 * Phase 2B closure — what session-identity restoration actually BUYS, measured against the
 * real persistence contract.
 *
 * Why this file exists
 * --------------------
 * [PlaybackSessionIdentityRestorationTest] already proves that a matching persisted id is
 * adopted and that a terminal close invalidates it. But its fake repository allocates a new
 * row id on every call, so it cannot observe the only reason adoption exists:
 * `HistoryDao.insertHistoryIdempotent` is FIRST-WRITE-WINS on a non-blank session id, so a
 * resumed session that carries its pre-death identity lands on the row that already exists
 * instead of creating a second one — and, because the conflicting insert updates nothing,
 * the ORIGINAL `sourceSection` survives even though the restored queue can only report
 * LEGACY_UNKNOWN.
 *
 * The fake here mirrors that DAO contract exactly (dedup on non-blank session id, first
 * write wins, origin never overwritten), which makes the following observable:
 *
 *   R1 [state]      resuming after process recreation yields ONE row, and the eligible
 *                   pre-death origin is preserved rather than downgraded
 *   R2 [ablation]   the SAME state sequence without a persisted id yields TWO rows, the
 *                   second one LEGACY_UNKNOWN — the regression adoption prevents
 *   R3 [state]      completion after recreation marks the pre-death row, not a new one
 *   R4 [regression] the candidate is track-matched AND consumed: a different track neither
 *                   adopts it nor leaves it available to a later session
 *   R5 [property]   repeated emissions, pause/resume flaps and reconnections after
 *                   restoration never add a row and never change the adopted identity
 *
 * Documented residual (deliberate, not a defect this file hides)
 * -------------------------------------------------------------
 * R1's state sequence is identical whether the user RESUMES the restored track or
 * deliberately REPLAYS it from the start as their first action, because the only signals
 * available at [PlaybackHistoryCoordinator.openSession] are the media id and the snapshot.
 * The restored live position cannot disambiguate them: the coordinator opens a session on
 * the first non-null media item regardless of `isPlaying`, which can be observed before the
 * restoring seek lands. The design therefore resolves the ambiguity toward never inventing a
 * phantom row, at the cost of merging that one replay into the previous listen. R2 quantifies
 * the alternative, which is strictly worse: a duplicate row carrying an ineligible origin for
 * every genuine resume. A terminal close (R3 of the sibling file) or an intervening track
 * (R4 here) removes the ambiguity, and both correctly produce a new identity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionRestorationIdempotenceTest {

    private class FakeSettingsRepository(
        var snapshot: PlaybackSessionSnapshot?
    ) : SettingsRepository {
        private val preferences = MutableStateFlow(UserPreferences())

        override fun getUserPreferences(): Flow<UserPreferences> = preferences
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun setShowRomanized(show: Boolean) {}
        override suspend fun setRestorePlaybackSession(enabled: Boolean) {}
        override suspend fun setDefaultSearchMode(mode: SearchMode) {}
        override suspend fun setReduceMotion(enabled: Boolean) {}
        override suspend fun setStreamingQuality(quality: AudioQuality) {}
        override suspend fun setDownloadQuality(quality: AudioQuality) {}
        override suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) {}
        override suspend fun resetSettings() {}
        override suspend fun setAppLanguage(language: com.example.sonara.domain.model.AppLanguage) {}
        override suspend fun setEqualizerEnabled(enabled: Boolean) {}
        override suspend fun setEqualizerBandGains(gains: List<Int>) {}
        override suspend fun setStopMusicOnTaskClear(enabled: Boolean) {}
        override suspend fun setDownloadFileFormat(format: com.example.sonara.domain.model.DownloadFileFormat) {}
        override suspend fun setDownloadLocationMode(mode: com.example.sonara.domain.model.DownloadLocationMode) {}
        override suspend fun setDownloadLocationUri(uri: String?) {}
        override suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) { this.snapshot = snapshot }
        override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? = snapshot
    }

    /**
     * Faithful stand-in for `HistoryDao.insertHistoryIdempotent`:
     *  - a blank session id is normalised to null and is NEVER deduplicated (legacy rows stay
     *    distinct, exactly as SQLite's unique index permits unlimited NULLs);
     *  - a non-blank session id that already exists returns the EXISTING row id;
     *  - the conflicting insert updates nothing, so `origin` and `completed` are untouched.
     */
    private class IdempotentHistoryRepository : HistoryRepository {
        data class Row(
            val id: Long,
            val trackId: String,
            val origin: PlaybackOrigin,
            val sessionId: String?,
            var completed: Boolean
        )

        val rows = mutableListOf<Row>()
        val completedCalls = mutableListOf<Long>()
        private var nextId = 1L
        private val history = MutableStateFlow<List<HistoryItem>>(emptyList())

        /** Seeds the row a pre-death session already wrote. */
        fun seedExistingRow(trackId: String, origin: PlaybackOrigin, sessionId: String): Long {
            val id = nextId++
            rows += Row(id, trackId, origin, sessionId, completed = false)
            return id
        }

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = history

        override suspend fun recordHistory(track: Track, completed: Boolean): Long =
            recordHistoryWithProvenance(track, completed, PlaybackOrigin.LEGACY_UNKNOWN, "")

        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long {
            val key = playbackSessionId.takeUnless { it.isBlank() }
            if (key != null) {
                rows.firstOrNull { it.sessionId == key }?.let { return it.id }
            }
            val id = nextId++
            rows += Row(id, track.id, sourceSection, key, completed)
            return id
        }

        override suspend fun markHistoryCompleted(historyId: Long) {
            completedCalls += historyId
            rows.firstOrNull { it.id == historyId }?.completed = true
        }

        override suspend fun clearHistory() {
            rows.clear()
            completedCalls.clear()
        }
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var client: MutableStateFlow<MediaControllerState>
    private lateinit var repository: IdempotentHistoryRepository
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        client = MutableStateFlow(MediaControllerState())
        repository = IdempotentHistoryRepository()
        scope = TestScope(dispatcher)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun playing(id: String) = MediaControllerState(
        isConnected = true,
        isPlaying = true,
        currentMediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(id).setArtist("artist").build())
            .build(),
        playbackState = Player.STATE_READY,
        durationMs = 60_000L
    )

    private fun paused(id: String) = playing(id).copy(isPlaying = false)

    private fun ended(id: String) = playing(id).copy(
        isPlaying = false,
        playbackState = Player.STATE_ENDED
    )

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** A coordinator standing in for the one the recreated service builds. */
    private fun recreatedCoordinator(
        settings: SettingsRepository,
        mint: () -> String
    ) = PlaybackHistoryCoordinator(
        playbackState = client,
        historyRepository = repository,
        settingsRepository = settings,
        scope = scope,
        qualificationThresholdMs = 15_000L,
        // The restored queue genuinely cannot establish provenance, so the service's
        // provider fails closed here exactly as it does in production after process death.
        originFor = { PlaybackOrigin.LEGACY_UNKNOWN },
        newSessionId = mint
    )

    // ------------------------------------------------------------------ R1 [state]

    @Test
    fun resumingAfterRecreationKeepsOneRowAndPreservesTheEligibleOrigin() {
        // A pre-death session from Songs search already qualified and wrote its row.
        val existingRow = repository.seedExistingRow(
            trackId = "track-a",
            origin = PlaybackOrigin.SEARCH_SONGS,
            sessionId = "persisted-a"
        )
        val settings = FakeSettingsRepository(
            PlaybackSessionSnapshot(
                lastTrackId = "track-a",
                lastPositionMs = 42_000L,
                queueTrackIds = listOf("track-a"),
                playbackSessionId = "persisted-a"
            )
        )
        var mintCount = 0
        val coordinator = recreatedCoordinator(settings) { "minted-${++mintCount}" }

        coordinator.start()
        // Restoration puts the item in the player paused, then the user resumes it.
        client.value = paused("track-a")
        scheduler.runCurrent()
        client.value = playing("track-a")
        scheduler.runCurrent()
        advance(15_000L)

        assertEquals("the resumed listen must not create a second row", 1, repository.rows.size)
        val row = repository.rows.single()
        assertEquals("the resumed session landed on the pre-death row", existingRow, row.id)
        assertEquals(
            "first-write-wins means the eligible origin is NOT downgraded to LEGACY_UNKNOWN",
            PlaybackOrigin.SEARCH_SONGS,
            row.origin
        )
        assertEquals("restoration must not mint a replacement id", 0, mintCount)
    }

    // --------------------------------------------------------------- R2 [ablation]

    @Test
    fun withoutAPersistedIdTheSameResumeDuplicatesTheRowAndLosesTheOrigin() {
        // Identical to R1 except the snapshot carries no identity (pre-Phase-2B snapshot,
        // or a cleared one). This is what adoption prevents, so R1 cannot be vacuous.
        val existingRow = repository.seedExistingRow(
            trackId = "track-a",
            origin = PlaybackOrigin.SEARCH_SONGS,
            sessionId = "persisted-a"
        )
        val settings = FakeSettingsRepository(
            PlaybackSessionSnapshot(
                lastTrackId = "track-a",
                lastPositionMs = 42_000L,
                queueTrackIds = listOf("track-a"),
                playbackSessionId = null
            )
        )
        var mintCount = 0
        val coordinator = recreatedCoordinator(settings) { "minted-${++mintCount}" }

        coordinator.start()
        client.value = paused("track-a")
        scheduler.runCurrent()
        client.value = playing("track-a")
        scheduler.runCurrent()
        advance(15_000L)

        assertEquals("a missing identity forces a mint", 1, mintCount)
        assertEquals("one continuous listen now occupies two rows", 2, repository.rows.size)
        val added = repository.rows.last()
        assertNotEquals("the second row is genuinely new", existingRow, added.id)
        assertEquals("minted-1", added.sessionId)
        assertEquals(
            "and it carries the fail-closed origin, so the listen looks ineligible",
            PlaybackOrigin.LEGACY_UNKNOWN,
            added.origin
        )
        assertEquals(
            "while the original row keeps its eligible origin, double-counting the artist",
            PlaybackOrigin.SEARCH_SONGS,
            repository.rows.first().origin
        )
    }

    // ------------------------------------------------------------------ R3 [state]

    @Test
    fun completionAfterRecreationMarksThePreDeathRow() {
        val existingRow = repository.seedExistingRow(
            trackId = "track-a",
            origin = PlaybackOrigin.SEARCH_SONGS,
            sessionId = "persisted-a"
        )
        val settings = FakeSettingsRepository(
            PlaybackSessionSnapshot("track-a", 42_000L, listOf("track-a"), "persisted-a")
        )
        val coordinator = recreatedCoordinator(settings) { "minted" }

        coordinator.start()
        client.value = playing("track-a")
        scheduler.runCurrent()
        advance(15_000L)
        client.value = ended("track-a")
        scheduler.runCurrent()

        assertEquals(
            "completion is applied to the row the pre-death session created",
            listOf(existingRow),
            repository.completedCalls
        )
        assertTrue("and that row is now a completed listen", repository.rows.single().completed)
        assertEquals(1, repository.rows.size)
    }

    // ------------------------------------------------------------- R4 [regression]

    @Test
    fun aDifferentTrackNeitherAdoptsTheCandidateNorLeavesItAvailable() {
        repository.seedExistingRow("track-a", PlaybackOrigin.SEARCH_SONGS, "persisted-a")
        val settings = FakeSettingsRepository(
            PlaybackSessionSnapshot("track-a", 42_000L, listOf("track-a"), "persisted-a")
        )
        var mintCount = 0
        val coordinator = recreatedCoordinator(settings) { "minted-${++mintCount}" }

        coordinator.start()
        // The user ignores the restored item and plays something else first.
        client.value = playing("track-b")
        scheduler.runCurrent()
        advance(15_000L)

        val bRow = repository.rows.last()
        assertEquals("track-b", bRow.trackId)
        assertEquals("a non-matching track must never inherit the persisted identity", "minted-1", bRow.sessionId)

        // Returning to track-a afterwards is a genuinely new listen, not the restored one.
        client.value = playing("track-a")
        scheduler.runCurrent()
        advance(15_000L)

        assertEquals("three rows: the seeded one plus one per new session", 3, repository.rows.size)
        val aRow = repository.rows.last()
        assertEquals("track-a", aRow.trackId)
        assertEquals(
            "the consumed candidate cannot be adopted by a later session",
            "minted-2",
            aRow.sessionId
        )
        assertNotEquals("persisted-a", aRow.sessionId)
    }

    // --------------------------------------------------------------- R5 [property]

    @Test
    fun noisyStateAfterRestorationNeverAddsARowOrChangesTheAdoptedIdentity() {
        val existingRow = repository.seedExistingRow(
            trackId = "track-a",
            origin = PlaybackOrigin.SEARCH_SONGS,
            sessionId = "persisted-a"
        )
        val settings = FakeSettingsRepository(
            PlaybackSessionSnapshot("track-a", 42_000L, listOf("track-a"), "persisted-a")
        )
        var mintCount = 0
        val coordinator = recreatedCoordinator(settings) { "minted-${++mintCount}" }
        coordinator.start()

        // Every disturbance that is NOT a new session: duplicate emissions, pause/resume,
        // a dropped controller connection, and long playback past qualification.
        val disturbances: List<MediaControllerState> = listOf(
            paused("track-a"),
            playing("track-a"),
            playing("track-a"),
            paused("track-a"),
            playing("track-a").copy(isConnected = false),
            playing("track-a"),
            playing("track-a")
        )

        for (state in disturbances) {
            client.value = state
            scheduler.runCurrent()
            advance(10_000L)
            assertTrue(
                "no disturbance may ever produce a second row (saw ${repository.rows.size})",
                repository.rows.size == 1
            )
            assertEquals(
                "the adopted identity is stable across every disturbance",
                "persisted-a",
                repository.rows.single().sessionId
            )
        }

        assertEquals("nothing was minted for the restored session", 0, mintCount)
        assertEquals(existingRow, repository.rows.single().id)
        assertEquals(PlaybackOrigin.SEARCH_SONGS, repository.rows.single().origin)
        assertEquals(
            "none of these disturbances is a completion, so the row stays open",
            emptyList<Long>(),
            repository.completedCalls
        )
    }
}
