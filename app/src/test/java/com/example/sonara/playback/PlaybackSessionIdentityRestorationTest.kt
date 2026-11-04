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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Focused JVM contracts for the persisted-vs-genuine-session boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionIdentityRestorationTest {
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

    private class RecordingHistoryRepository : HistoryRepository {
        data class Row(val trackId: String, val sessionId: String, val origin: PlaybackOrigin)
        val rows = mutableListOf<Row>()
        val completed = mutableListOf<Long>()
        private var nextId = 1L
        private val history = MutableStateFlow<List<HistoryItem>>(emptyList())

        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = history
        override suspend fun recordHistory(track: Track, completed: Boolean): Long =
            recordHistoryWithProvenance(track, completed, PlaybackOrigin.LEGACY_UNKNOWN, "")

        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long {
            val id = nextId++
            rows += Row(track.id, playbackSessionId, sourceSection)
            return id
        }

        override suspend fun markHistoryCompleted(historyId: Long) { completed += historyId }
        override suspend fun clearHistory() { rows.clear(); completed.clear() }
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var client: MutableStateFlow<MediaControllerState>
    private lateinit var repository: RecordingHistoryRepository
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        client = MutableStateFlow(MediaControllerState())
        repository = RecordingHistoryRepository()
        scope = TestScope(dispatcher)
    }

    @After
    fun tearDown() { scope.cancel() }

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

    private fun ended(id: String) = playing(id).copy(
        isPlaying = false,
        playbackState = Player.STATE_ENDED
    )

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    @Test
    fun matchingPersistedIdIsAdoptedButRestoredOriginStillFailsClosed() {
        val settings = FakeSettingsRepository(
            PlaybackSessionSnapshot("track-a", queueTrackIds = listOf("track-a"), playbackSessionId = "persisted-a")
        )
        var mintCount = 0
        val coordinator = PlaybackHistoryCoordinator(
            playbackState = client,
            historyRepository = repository,
            settingsRepository = settings,
            scope = scope,
            qualificationThresholdMs = 15_000L,
            newSessionId = { "new-${++mintCount}" }
        )

        coordinator.start()
        client.value = playing("track-a")
        scheduler.runCurrent()
        advance(15_000L)

        assertEquals(1, repository.rows.size)
        assertEquals("persisted-a", repository.rows.single().sessionId)
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, repository.rows.single().origin)
        assertEquals("restoration must not mint a replacement id", 0, mintCount)
    }

    @Test
    fun stopPreservesCandidateButTerminalCloseInvalidatesItBeforeReplay() {
        val settings = FakeSettingsRepository(
            PlaybackSessionSnapshot("track-a", playbackSessionId = "persisted-a")
        )
        val closed = mutableListOf<Pair<String, String>>()
        var mintCount = 0
        val coordinatorBeforeRecreation = PlaybackHistoryCoordinator(
            playbackState = client,
            historyRepository = repository,
            settingsRepository = settings,
            scope = scope,
            qualificationThresholdMs = 15_000L,
            newSessionId = { "unused-${++mintCount}" }
        )

        coordinatorBeforeRecreation.start()
        client.value = playing("track-a")
        scheduler.runCurrent()
        coordinatorBeforeRecreation.stop()

        assertTrue("service stop is not a genuine close", closed.isEmpty())
        assertEquals("persisted-a", settings.snapshot?.playbackSessionId)

        val coordinator = PlaybackHistoryCoordinator(
            playbackState = client,
            historyRepository = repository,
            settingsRepository = settings,
            scope = scope,
            qualificationThresholdMs = 15_000L,
            newSessionId = { "new-${++mintCount}" },
            onSessionClosed = { trackId, sessionId -> closed += trackId to sessionId }
        )
        coordinator.start()
        scheduler.runCurrent()
        advance(15_000L)
        client.value = ended("track-a")
        scheduler.runCurrent()

        assertEquals(listOf("track-a" to "persisted-a"), closed)
        client.value = playing("track-a")
        scheduler.runCurrent()
        advance(15_000L)

        assertEquals(2, repository.rows.size)
        assertEquals("new-1", repository.rows.last().sessionId)
    }
}
