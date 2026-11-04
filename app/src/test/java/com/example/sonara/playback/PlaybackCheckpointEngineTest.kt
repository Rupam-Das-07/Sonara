package com.example.sonara.playback

import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.UserPreferences
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.playback.checkpoint.PlaybackCheckpointEngine
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.client.MediaControllerState
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaybackCheckpointEngineTest {

    private class FakeClient(scope: CoroutineScope) : MediaControllerClient(null, scope) {
        private val states = MutableStateFlow(MediaControllerState())
        override val controllerState = states

        fun emit(trackId: String, positionMs: Long) {
            states.value = MediaControllerState(
                isConnected = true,
                isPlaying = false,
                currentMediaItem = MediaItem.Builder().setMediaId(trackId).build(),
                currentPositionMs = positionMs,
                playbackState = Player.STATE_READY
            )
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        var savedSnapshot: PlaybackSessionSnapshot? = null
        private val prefsFlow = MutableStateFlow(UserPreferences())

        override fun getUserPreferences(): Flow<UserPreferences> = prefsFlow

        override suspend fun setThemeMode(mode: ThemeMode) {
            prefsFlow.value = prefsFlow.value.copy(themeMode = mode)
        }
        override suspend fun setShowRomanized(show: Boolean) {
            prefsFlow.value = prefsFlow.value.copy(showRomanized = show)
        }
        override suspend fun setRestorePlaybackSession(enabled: Boolean) {
            prefsFlow.value = prefsFlow.value.copy(restorePlaybackSession = enabled)
        }
        override suspend fun setDefaultSearchMode(mode: com.example.sonara.domain.model.SearchMode) {
            prefsFlow.value = prefsFlow.value.copy(defaultSearchMode = mode)
        }
        override suspend fun setReduceMotion(enabled: Boolean) {
            prefsFlow.value = prefsFlow.value.copy(reduceMotion = enabled)
        }
        override suspend fun setStreamingQuality(quality: com.example.sonara.domain.model.AudioQuality) {
            prefsFlow.value = prefsFlow.value.copy(streamingQuality = quality)
        }
        override suspend fun setDownloadQuality(quality: com.example.sonara.domain.model.AudioQuality) {
            prefsFlow.value = prefsFlow.value.copy(downloadQuality = quality)
        }
        override suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) {
            prefsFlow.value = prefsFlow.value.copy(downloadOverWifiOnly = wifiOnly)
        }
        override suspend fun resetSettings() {
            prefsFlow.value = UserPreferences()
        }

        override suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) {
            savedSnapshot = snapshot
        }

        override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? {
            return savedSnapshot
        }

        // Phase 1 stubs
        override suspend fun setAppLanguage(language: com.example.sonara.domain.model.AppLanguage) {}
        override suspend fun setEqualizerEnabled(enabled: Boolean) {}
        override suspend fun setEqualizerBandGains(gains: List<Int>) {}
        override suspend fun setStopMusicOnTaskClear(enabled: Boolean) {}
        override suspend fun setDownloadFileFormat(format: com.example.sonara.domain.model.DownloadFileFormat) {}
        override suspend fun setDownloadLocationMode(mode: com.example.sonara.domain.model.DownloadLocationMode) {}
        override suspend fun setDownloadLocationUri(uri: String?) {}
    }

    @Test
    fun settingsRepository_savesAndRestoresPlaybackSessionSnapshot() = runBlocking {
        val repo = FakeSettingsRepository()
        val snapshot = PlaybackSessionSnapshot(
            lastTrackId = "track_prelude",
            lastPositionMs = 45000L,
            queueTrackIds = listOf("track_prelude", "track_jazz"),
            playbackSessionId = "session-restored"
        )

        repo.savePlaybackSession(snapshot)

        val restored = repo.getPlaybackSession()
        assertNotNull(restored)
        assertEquals("track_prelude", restored?.lastTrackId)
        assertEquals(45000L, restored?.lastPositionMs)
        assertEquals(listOf("track_prelude", "track_jazz"), restored?.queueTrackIds)
        assertEquals("session-restored", restored?.playbackSessionId)
    }

    @Test
    fun checkpointUsesCoordinatorIdentityAndRetainsItWhenServiceProviderDetaches() {
        val scheduler = TestCoroutineScheduler()
        val scope = TestScope(StandardTestDispatcher(scheduler))
        val repo = FakeSettingsRepository()
        val client = FakeClient(scope)
        val engine = PlaybackCheckpointEngine(
            client = client,
            settingsRepository = repo,
            scope = scope,
            checkpointIntervalMs = 60_000L
        )
        engine.setSessionIdProvider { "session-a" }
        engine.start()

        client.emit("track-a", 1_000L)
        scheduler.runCurrent()
        assertEquals("session-a", repo.savedSnapshot?.playbackSessionId)

        // Service destruction detaches the provider; the active persisted identity remains
        // usable by the next service instance instead of being replaced or cleared.
        engine.setSessionIdProvider(null)
        client.emit("track-a", 2_000L)
        scheduler.runCurrent()
        assertEquals("session-a", repo.savedSnapshot?.playbackSessionId)

        engine.stop()
        engine.start()
        client.emit("track-a", 3_000L)
        scheduler.runCurrent()
        assertEquals(3_000L, repo.savedSnapshot?.lastPositionMs)
    }
}
