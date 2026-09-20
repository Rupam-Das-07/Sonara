package com.example.sonara.feature.settings

import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.UserPreferences
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSettingsRepository : SettingsRepository {
        val prefsFlow = MutableStateFlow(UserPreferences())
        override fun getUserPreferences(): Flow<UserPreferences> = prefsFlow
        override suspend fun setThemeMode(mode: ThemeMode) { prefsFlow.value = prefsFlow.value.copy(themeMode = mode) }
        override suspend fun setShowRomanized(show: Boolean) { prefsFlow.value = prefsFlow.value.copy(showRomanized = show) }
        override suspend fun setRestorePlaybackSession(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(restorePlaybackSession = enabled) }
        override suspend fun setDefaultSearchMode(mode: SearchMode) { prefsFlow.value = prefsFlow.value.copy(defaultSearchMode = mode) }
        override suspend fun setReduceMotion(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(reduceMotion = enabled) }
        override suspend fun setStreamingQuality(quality: com.example.sonara.domain.model.AudioQuality) { prefsFlow.value = prefsFlow.value.copy(streamingQuality = quality) }
        override suspend fun setDownloadQuality(quality: com.example.sonara.domain.model.AudioQuality) { prefsFlow.value = prefsFlow.value.copy(downloadQuality = quality) }
        override suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) { prefsFlow.value = prefsFlow.value.copy(downloadOverWifiOnly = wifiOnly) }
        override suspend fun resetSettings() { prefsFlow.value = UserPreferences() }
        override suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) {}
        override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? = null
        // Phase 1 stubs
        override suspend fun setAppLanguage(language: com.example.sonara.domain.model.AppLanguage) { prefsFlow.value = prefsFlow.value.copy(appLanguage = language) }
        override suspend fun setEqualizerEnabled(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(equalizerEnabled = enabled) }
        override suspend fun setEqualizerBandGains(gains: List<Int>) { prefsFlow.value = prefsFlow.value.copy(equalizerBandGains = gains) }
        override suspend fun setStopMusicOnTaskClear(enabled: Boolean) { prefsFlow.value = prefsFlow.value.copy(stopMusicOnTaskClear = enabled) }
        override suspend fun setDownloadFileFormat(format: com.example.sonara.domain.model.DownloadFileFormat) { prefsFlow.value = prefsFlow.value.copy(downloadFileFormat = format) }
        override suspend fun setDownloadLocationMode(mode: com.example.sonara.domain.model.DownloadLocationMode) { prefsFlow.value = prefsFlow.value.copy(downloadLocationMode = mode) }
        override suspend fun setDownloadLocationUri(uri: String?) {}
    }

    private class FakeHistoryRepository : HistoryRepository {
        var clearHistoryCalled = false
        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = flowOf(emptyList())
        override suspend fun recordHistory(track: Track, completed: Boolean) {}
        override suspend fun clearHistory() {
            clearHistoryCalled = true
        }
    }

    private class FakeStreamResolver : StreamResolverPort {
        var clearCacheCalled = false
        override suspend fun resolveStream(trackId: String, quality: com.example.sonara.domain.model.AudioQuality): Result<StreamInfo> =
            Result.success(StreamInfo(trackId = "track", streamUrl = "https://stream/track", expiresAt = 3600_000L, format = "audio/mp4"))
        override fun clearCache() {
            clearCacheCalled = true
        }
    }

    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var historyRepo: FakeHistoryRepository
    private lateinit var streamResolver: FakeStreamResolver
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsRepo = FakeSettingsRepository()
        historyRepo = FakeHistoryRepository()
        streamResolver = FakeStreamResolver()

        viewModel = SettingsViewModel(
            settingsRepository = settingsRepo,
            historyRepository = historyRepo,
            streamResolver = streamResolver,
            appContext = null,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateObservesPreferencesFlow() = runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(ThemeMode.SYSTEM, state.preferences.themeMode)
        assertTrue(state.preferences.showRomanized)
        assertEquals(SettingsDialog.None, state.activeDialog)
    }

    @Test
    fun toggleCategoryExpandsAndCollapsesCorrectly() {
        var state = viewModel.uiState.value
        viewModel.toggleCategory(SettingsCategory.LYRICS)
        state = viewModel.uiState.value
        assertTrue(SettingsCategory.LYRICS in state.expandedCategories)

        viewModel.toggleCategory(SettingsCategory.LYRICS)
        state = viewModel.uiState.value
        assertFalse(SettingsCategory.LYRICS in state.expandedCategories)
    }

    @Test
    fun dialogManagementOpensAndDismissesCorrectly() {
        viewModel.openDialog(SettingsDialog.ThemeModeSelector)
        assertEquals(SettingsDialog.ThemeModeSelector, viewModel.uiState.value.activeDialog)

        viewModel.dismissDialog()
        assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)
    }

    @Test
    fun setThemeModeUpdatesRepositoryAndDismissesDialog() = runTest {
        viewModel.openDialog(SettingsDialog.ThemeModeSelector)
        viewModel.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, settingsRepo.prefsFlow.value.themeMode)
        assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)
    }

    @Test
    fun setDefaultSearchModeUpdatesRepositoryAndDismissesDialog() = runTest {
        viewModel.openDialog(SettingsDialog.DefaultSearchModeSelector)
        viewModel.setDefaultSearchMode(SearchMode.VIDEOS)
        advanceUntilIdle()

        assertEquals(SearchMode.VIDEOS, settingsRepo.prefsFlow.value.defaultSearchMode)
        assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)
    }

    @Test
    fun clearStreamCacheInvokesStreamResolverAndSetsFeedback() = runTest {
        viewModel.clearStreamCache()
        advanceUntilIdle()

        assertTrue(streamResolver.clearCacheCalled)
        assertEquals("Stream URL cache cleared", viewModel.uiState.value.feedbackMessage)

        viewModel.clearFeedbackMessage()
        assertEquals(null, viewModel.uiState.value.feedbackMessage)
    }

    @Test
    fun clearPlaybackHistoryInvokesHistoryRepositoryAndDismisses() = runTest {
        viewModel.openDialog(SettingsDialog.ClearHistoryConfirmation)
        viewModel.clearPlaybackHistory()
        advanceUntilIdle()

        assertTrue(historyRepo.clearHistoryCalled)
        assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)
        assertEquals("Playback history cleared", viewModel.uiState.value.feedbackMessage)
    }

    @Test
    fun resetSettingsRestoresDefaultsAndSetsFeedback() = runTest {
        settingsRepo.setThemeMode(ThemeMode.DARK)
        viewModel.openDialog(SettingsDialog.ResetSettingsConfirmation)
        viewModel.resetSettings()
        advanceUntilIdle()

        assertEquals(ThemeMode.SYSTEM, settingsRepo.prefsFlow.value.themeMode)
        assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)
        assertEquals("Settings restored to defaults", viewModel.uiState.value.feedbackMessage)
    }

    // ─── Phase 2 Settings Tests ───────────────────────────────────────────────

    @Test
    fun setAppLanguageUpdatesRepositoryAndDismissesDialog() = runTest {
        viewModel.openDialog(SettingsDialog.AppLanguageSelector)
        viewModel.setAppLanguage(com.example.sonara.domain.model.AppLanguage.HINDI)
        advanceUntilIdle()

        assertEquals(com.example.sonara.domain.model.AppLanguage.HINDI, settingsRepo.prefsFlow.value.appLanguage)
        assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)
    }

    @Test
    fun setEqualizerEnabledUpdatesRepository() = runTest {
        viewModel.setEqualizerEnabled(true)
        advanceUntilIdle()

        assertTrue(settingsRepo.prefsFlow.value.equalizerEnabled)

        viewModel.setEqualizerEnabled(false)
        advanceUntilIdle()

        assertFalse(settingsRepo.prefsFlow.value.equalizerEnabled)
    }

    @Test
    fun setEqualizerBandGainUpdatesSpecificBandAndClampsRange() = runTest {
        viewModel.setEqualizerBandGain(0, 500)
        advanceUntilIdle()

        assertEquals(500, settingsRepo.prefsFlow.value.equalizerBandGains[0])

        // Clamping check (-1500 to +1500)
        viewModel.setEqualizerBandGain(1, 2000)
        advanceUntilIdle()

        assertEquals(1500, settingsRepo.prefsFlow.value.equalizerBandGains[1])

        viewModel.setEqualizerBandGain(2, -2000)
        advanceUntilIdle()

        assertEquals(-1500, settingsRepo.prefsFlow.value.equalizerBandGains[2])
    }

    @Test
    fun resetEqualizerGainsZerosAllBands() = runTest {
        viewModel.setEqualizerBandGains(listOf(300, -200, 500, 100, -400))
        advanceUntilIdle()

        viewModel.resetEqualizerGains()
        advanceUntilIdle()

        assertEquals(listOf(0, 0, 0, 0, 0), settingsRepo.prefsFlow.value.equalizerBandGains)
    }

    @Test
    fun setStopMusicOnTaskClearUpdatesRepository() = runTest {
        viewModel.setStopMusicOnTaskClear(true)
        advanceUntilIdle()

        assertTrue(settingsRepo.prefsFlow.value.stopMusicOnTaskClear)
    }

    @Test
    fun setDownloadFileFormatUpdatesRepositoryAndDismisses() = runTest {
        viewModel.openDialog(SettingsDialog.DownloadFormatSelector)
        viewModel.setDownloadFileFormat(com.example.sonara.domain.model.DownloadFileFormat.WEBM)
        advanceUntilIdle()

        assertEquals(com.example.sonara.domain.model.DownloadFileFormat.WEBM, settingsRepo.prefsFlow.value.downloadFileFormat)
        assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)
    }

    @Test
    fun phase2DialogsOpenAndDismissCorrectly() {
        val dialogs = listOf(
            SettingsDialog.AppLanguageSelector,
            SettingsDialog.EqualizerSettings,
            SettingsDialog.DownloadFormatSelector
        )
        dialogs.forEach { dialog ->
            viewModel.openDialog(dialog)
            assertEquals(dialog, viewModel.uiState.value.activeDialog)
            viewModel.dismissDialog()
            assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)
        }
    }

    @Test
    fun appLanguageModelIntegrityAndAvailability() {
        // Supported languages check
        val supported = AppLanguage.supportedLanguages()
        assertTrue(supported.contains(AppLanguage.SYSTEM_DEFAULT))
        assertTrue(supported.contains(AppLanguage.HINDI))
        assertTrue(supported.contains(AppLanguage.BENGALI))
        assertTrue(supported.contains(AppLanguage.URDU))
        assertTrue(supported.contains(AppLanguage.ENGLISH))

        // Ensure unsupported international languages are flagged as isSupported = false
        assertFalse(AppLanguage.SPANISH.isSupported)
        assertFalse(AppLanguage.FRENCH.isSupported)
        assertFalse(AppLanguage.JAPANESE.isSupported)

        // BCP-47 tags
        assertEquals("", AppLanguage.SYSTEM_DEFAULT.bcp47Tag)
        assertEquals("hi", AppLanguage.HINDI.bcp47Tag)
        assertEquals("bn", AppLanguage.BENGALI.bcp47Tag)
        assertEquals("ur", AppLanguage.URDU.bcp47Tag)
        assertEquals("en", AppLanguage.ENGLISH.bcp47Tag)

        // Endonym Native Display Names
        assertEquals("हिन्दी", AppLanguage.HINDI.nativeDisplayName)
        assertEquals("বাংলা", AppLanguage.BENGALI.nativeDisplayName)
        assertEquals("اردو", AppLanguage.URDU.nativeDisplayName)
    }

    @Test
    fun switchingLanguageUpdatesPreferencesAndDismissesDialog() = runTest {
        viewModel.openDialog(SettingsDialog.AppLanguageSelector)
        viewModel.setAppLanguage(AppLanguage.HINDI)
        advanceUntilIdle()

        assertEquals(AppLanguage.HINDI, settingsRepo.prefsFlow.value.appLanguage)
        assertEquals(SettingsDialog.None, viewModel.uiState.value.activeDialog)

        // Switch to Bengali
        viewModel.setAppLanguage(AppLanguage.BENGALI)
        advanceUntilIdle()
        assertEquals(AppLanguage.BENGALI, settingsRepo.prefsFlow.value.appLanguage)

        // Switch to Urdu
        viewModel.setAppLanguage(AppLanguage.URDU)
        advanceUntilIdle()
        assertEquals(AppLanguage.URDU, settingsRepo.prefsFlow.value.appLanguage)

        // Switch back to System Default
        viewModel.setAppLanguage(AppLanguage.SYSTEM_DEFAULT)
        advanceUntilIdle()
        assertEquals(AppLanguage.SYSTEM_DEFAULT, settingsRepo.prefsFlow.value.appLanguage)
    }
}
