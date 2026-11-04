package com.example.sonara.data.local.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.ColorPalette
import com.example.sonara.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesDataStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createTestDataStore(file: File, scope: CoroutineScope = testScope) = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { file }
    )

    @Test
    fun `1 fresh installation defaults to SONARA_CLASSIC`() = runTest {
        val testFile = tempFolder.newFile("test_fresh.preferences_pb")
        val dataStore = createTestDataStore(testFile)
        val prefDataStore = UserPreferencesDataStore(customDataStore = dataStore)

        val prefs = prefDataStore.userPreferences.first()
        assertEquals(ColorPalette.SONARA_CLASSIC, prefs.selectedPalette)
    }

    @Test
    fun `2 existing installations without the new key remain compatible`() = runTest {
        val testFile = tempFolder.newFile("test_migration.preferences_pb")
        val dataStore = createTestDataStore(testFile)

        // Seed with legacy preferences (themeMode = DARK, streamingQuality = HIGH, but NO selected_palette key)
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("theme_mode")] = "DARK"
            preferences[stringPreferencesKey("streaming_quality")] = "HIGH"
        }

        val prefDataStore = UserPreferencesDataStore(customDataStore = dataStore)
        val prefs = prefDataStore.userPreferences.first()

        assertEquals(ColorPalette.SONARA_CLASSIC, prefs.selectedPalette)
        assertEquals(ThemeMode.DARK, prefs.themeMode)
        assertEquals(AudioQuality.HIGH, prefs.streamingQuality)
    }

    @Test
    fun `3 selecting another palette persists correctly`() = runTest {
        val testFile = tempFolder.newFile("test_selection.preferences_pb")
        val dataStore = createTestDataStore(testFile)
        val prefDataStore = UserPreferencesDataStore(customDataStore = dataStore)

        prefDataStore.setSelectedPalette(ColorPalette.OCEAN_BLUE)
        assertEquals(ColorPalette.OCEAN_BLUE, prefDataStore.userPreferences.first().selectedPalette)

        prefDataStore.setSelectedPalette(ColorPalette.AMOLED_BLACK)
        assertEquals(ColorPalette.AMOLED_BLACK, prefDataStore.userPreferences.first().selectedPalette)

        prefDataStore.setSelectedPalette(ColorPalette.DYNAMIC_WALLPAPER)
        assertEquals(ColorPalette.DYNAMIC_WALLPAPER, prefDataStore.userPreferences.first().selectedPalette)
    }

    @Test
    fun `4 persisted palettes are restored after DataStore recreation`() = runTest {
        val testFile = tempFolder.newFile("test_recreation.preferences_pb")
        val job1 = Job()
        val scope1 = CoroutineScope(job1 + testDispatcher)
        val dataStore1 = createTestDataStore(testFile, scope1)
        val prefDataStore1 = UserPreferencesDataStore(customDataStore = dataStore1)

        prefDataStore1.setSelectedPalette(ColorPalette.FOREST_GREEN)
        assertEquals(ColorPalette.FOREST_GREEN, prefDataStore1.userPreferences.first().selectedPalette)

        // Cancel job1 to cleanly close the active storage connection for this file
        job1.cancel()

        // Recreate a new DataStore instance pointing to the exact same file
        val job2 = Job()
        val scope2 = CoroutineScope(job2 + testDispatcher)
        val dataStore2 = createTestDataStore(testFile, scope2)
        val prefDataStore2 = UserPreferencesDataStore(customDataStore = dataStore2)

        val restoredPrefs = prefDataStore2.userPreferences.first()
        assertEquals(ColorPalette.FOREST_GREEN, restoredPrefs.selectedPalette)
        job2.cancel()
    }

    @Test
    fun `5 invalid stored enum values fall back safely to SONARA_CLASSIC`() = runTest {
        val testFile = tempFolder.newFile("test_invalid.preferences_pb")
        val dataStore = createTestDataStore(testFile)

        // Corrupt the key directly with an invalid enum string
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("selected_palette")] = "NON_EXISTENT_FUTURE_PALETTE"
        }

        val prefDataStore = UserPreferencesDataStore(customDataStore = dataStore)
        val prefs = prefDataStore.userPreferences.first()
        assertEquals(ColorPalette.SONARA_CLASSIC, prefs.selectedPalette)

        // Corrupt with blank string
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("selected_palette")] = ""
        }
        assertEquals(ColorPalette.SONARA_CLASSIC, prefDataStore.userPreferences.first().selectedPalette)
    }

    @Test
    fun `6 all existing preference fields remain unaffected`() = runTest {
        val testFile = tempFolder.newFile("test_unaffected.preferences_pb")
        val dataStore = createTestDataStore(testFile)
        val prefDataStore = UserPreferencesDataStore(customDataStore = dataStore)

        prefDataStore.setSelectedPalette(ColorPalette.ROSE)
        val prefs = prefDataStore.userPreferences.first()

        assertEquals(ColorPalette.ROSE, prefs.selectedPalette)
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode)
        assertTrue(prefs.showRomanized)
        assertTrue(prefs.restorePlaybackSession)
        assertEquals(AudioQuality.AUTO, prefs.streamingQuality)
        assertEquals(AudioQuality.VERY_HIGH, prefs.downloadQuality)
        assertFalse(prefs.downloadOverWifiOnly)
        assertEquals(AppLanguage.SYSTEM_DEFAULT, prefs.appLanguage)
        assertFalse(prefs.equalizerEnabled)
        assertFalse(prefs.stopMusicOnTaskClear)
    }

    @Test
    fun `7 multiple preference updates do not overwrite one another`() = runTest {
        val testFile = tempFolder.newFile("test_multi.preferences_pb")
        val dataStore = createTestDataStore(testFile)
        val prefDataStore = UserPreferencesDataStore(customDataStore = dataStore)

        prefDataStore.setThemeMode(ThemeMode.DARK)
        prefDataStore.setSelectedPalette(ColorPalette.LAVENDER)
        prefDataStore.setStreamingQuality(AudioQuality.HIGH)
        prefDataStore.setAppLanguage(AppLanguage.HINDI)

        val prefs = prefDataStore.userPreferences.first()
        assertEquals(ThemeMode.DARK, prefs.themeMode)
        assertEquals(ColorPalette.LAVENDER, prefs.selectedPalette)
        assertEquals(AudioQuality.HIGH, prefs.streamingQuality)
        assertEquals(AppLanguage.HINDI, prefs.appLanguage)
    }

    @Test
    fun `8 resetSettings restores SONARA_CLASSIC default`() = runTest {
        val testFile = tempFolder.newFile("test_reset.preferences_pb")
        val dataStore = createTestDataStore(testFile)
        val prefDataStore = UserPreferencesDataStore(customDataStore = dataStore)

        prefDataStore.setSelectedPalette(ColorPalette.MIDNIGHT)
        assertEquals(ColorPalette.MIDNIGHT, prefDataStore.userPreferences.first().selectedPalette)

        prefDataStore.resetSettings()
        assertEquals(ColorPalette.SONARA_CLASSIC, prefDataStore.userPreferences.first().selectedPalette)
    }
}
