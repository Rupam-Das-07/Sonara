package com.example.sonara.data.download

import android.content.ContextWrapper
import com.example.sonara.data.local.db.dao.DownloadDao
import com.example.sonara.data.local.db.dao.DownloadedTrackRelation
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.DownloadEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.AppLanguage
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadFileFormat
import com.example.sonara.domain.model.DownloadLocationMode
import com.example.sonara.domain.model.PlaybackSessionSnapshot
import com.example.sonara.domain.model.SearchMode
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.ThemeMode
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.UserPreferences
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundcloudDownloadRestrictionTest {

    private class FakeContext : ContextWrapper(null)

    private class FakeDownloadDao : DownloadDao {
        val insertedEntities = mutableListOf<DownloadEntity>()

        override suspend fun getDownload(trackId: String): DownloadEntity? = null
        override fun observeDownload(trackId: String): Flow<DownloadEntity?> = flowOf(null)
        override fun observeAllDownloads(): Flow<List<DownloadEntity>> = flowOf(emptyList())
        override fun observeCompletedDownloads(): Flow<List<DownloadEntity>> = flowOf(emptyList())
        override fun observeDownloadsWithTracks(): Flow<List<DownloadedTrackRelation>> = flowOf(emptyList())
        override suspend fun getActiveDownloads(): List<DownloadEntity> = emptyList()
        override suspend fun getDownloadedRecords(): List<DownloadEntity> = emptyList()
        override suspend fun insertOrUpdate(download: DownloadEntity) { insertedEntities.add(download) }
        override suspend fun updateProgress(trackId: String, downloadedBytes: Long, totalBytes: Long, status: String) {}
        override suspend fun updateFileInfo(trackId: String, localFilePath: String, mimeType: String, quality: String) {}
        override suspend fun updateFileInfoLegacy(trackId: String, localFilePath: String, mimeType: String) {}
        override suspend fun markCompleted(trackId: String, completedAt: Long, status: String) {}
        override suspend fun markFailed(trackId: String, reason: String?, status: String) {}
        override suspend fun delete(trackId: String) {}
        override suspend fun deleteAll() {}
    }

    private class FakeTrackDao : TrackDao {
        val insertedTracks = mutableListOf<TrackEntity>()
        override suspend fun insertTrack(track: TrackEntity) { insertedTracks.add(track) }
        override suspend fun insertTracks(tracks: List<TrackEntity>) {}
        override suspend fun getTrackById(id: String): TrackEntity? = null
        override suspend fun getTracksByIds(ids: List<String>): List<TrackEntity> = emptyList()
    }

    private class FakeStreamResolver : StreamResolverPort {
        override suspend fun resolveStream(trackId: String, quality: AudioQuality): Result<StreamInfo> =
            Result.failure(UnsupportedOperationException("Should not be called"))
        override suspend fun resolveStream(
            trackId: String,
            quality: AudioQuality,
            title: String,
            artist: String,
            durationSeconds: Int
        ): Result<StreamInfo> = Result.failure(UnsupportedOperationException("Should not be called"))
        override fun clearCache() {}
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val _prefs = MutableStateFlow(UserPreferences())
        override fun getUserPreferences(): Flow<UserPreferences> = _prefs
        override suspend fun setThemeMode(mode: ThemeMode) { _prefs.value = _prefs.value.copy(themeMode = mode) }
        override suspend fun setShowRomanized(show: Boolean) { _prefs.value = _prefs.value.copy(showRomanized = show) }
        override suspend fun setRestorePlaybackSession(enabled: Boolean) { _prefs.value = _prefs.value.copy(restorePlaybackSession = enabled) }
        override suspend fun setDefaultSearchMode(mode: SearchMode) { _prefs.value = _prefs.value.copy(defaultSearchMode = mode) }
        override suspend fun setReduceMotion(enabled: Boolean) { _prefs.value = _prefs.value.copy(reduceMotion = enabled) }
        override suspend fun setStreamingQuality(quality: AudioQuality) { _prefs.value = _prefs.value.copy(streamingQuality = quality) }
        override suspend fun setDownloadQuality(quality: AudioQuality) { _prefs.value = _prefs.value.copy(downloadQuality = quality) }
        override suspend fun setDownloadOverWifiOnly(wifiOnly: Boolean) { _prefs.value = _prefs.value.copy(downloadOverWifiOnly = wifiOnly) }
        override suspend fun resetSettings() { _prefs.value = UserPreferences() }
        override suspend fun savePlaybackSession(snapshot: PlaybackSessionSnapshot) {}
        override suspend fun getPlaybackSession(): PlaybackSessionSnapshot? = null
        override suspend fun setAppLanguage(language: AppLanguage) { _prefs.value = _prefs.value.copy(appLanguage = language) }
        override suspend fun setEqualizerEnabled(enabled: Boolean) { _prefs.value = _prefs.value.copy(equalizerEnabled = enabled) }
        override suspend fun setEqualizerBandGains(gains: List<Int>) { _prefs.value = _prefs.value.copy(equalizerBandGains = gains) }
        override suspend fun setStopMusicOnTaskClear(enabled: Boolean) { _prefs.value = _prefs.value.copy(stopMusicOnTaskClear = enabled) }
        override suspend fun setDownloadFileFormat(format: DownloadFileFormat) { _prefs.value = _prefs.value.copy(downloadFileFormat = format) }
        override suspend fun setDownloadLocationMode(mode: DownloadLocationMode) { _prefs.value = _prefs.value.copy(downloadLocationMode = mode) }
        override suspend fun setDownloadLocationUri(uri: String?) {}
    }

    @Test
    fun `SoundCloud track download with sc prefix fails with UnsupportedOperationException`() = runTest {
        val downloadDao = FakeDownloadDao()
        val trackDao = FakeTrackDao()

        val engine = DownloadEngine(
            context = FakeContext(),
            downloadDao = downloadDao,
            trackDao = trackDao,
            streamResolver = FakeStreamResolver(),
            settingsRepository = FakeSettingsRepository()
        )

        val soundcloudTrack = Track(
            id = "sc:https://soundcloud.com/artist/lofi-track",
            title = "Lofi Track",
            artist = "SoundCloud Artist"
        )

        val result = engine.enqueueDownload(soundcloudTrack)
        assertTrue("Expected failure for SoundCloud download", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("Expected UnsupportedOperationException but got ${ex?.javaClass?.simpleName}", ex is UnsupportedOperationException)
        assertEquals("Offline downloading is not supported for SoundCloud tracks", ex?.message)
        assertTrue("DownloadDao should not have inserted entities", downloadDao.insertedEntities.isEmpty())
        assertTrue("TrackDao should not have inserted entities", trackDao.insertedTracks.isEmpty())
    }

    @Test
    fun `SoundCloud track download with legacy soundcloud prefix fails with UnsupportedOperationException`() = runTest {
        val downloadDao = FakeDownloadDao()
        val trackDao = FakeTrackDao()

        val engine = DownloadEngine(
            context = FakeContext(),
            downloadDao = downloadDao,
            trackDao = trackDao,
            streamResolver = FakeStreamResolver(),
            settingsRepository = FakeSettingsRepository()
        )

        val soundcloudTrack = Track(
            id = "soundcloud:12345678",
            title = "Legacy Track",
            artist = "SoundCloud Artist"
        )

        val result = engine.enqueueDownload(soundcloudTrack)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(downloadDao.insertedEntities.isEmpty())
    }

    @Test
    fun `SoundCloud HLS stream info fails format determination for direct-file downloading`() {
        val soundcloudHlsStream = StreamInfo(
            trackId = "sc:12345678",
            streamUrl = "https://cf-hls-media.sndcdn.com/playlist.m3u8",
            format = "application/x-mpegURL",
            codec = "aac",
            bitrateKbps = 160,
            qualityTier = "STANDARD",
            provider = "soundcloud"
        )

        val result = DownloadEngine.determineDownloadFormat(soundcloudHlsStream)
        assertTrue("HLS stream must fail direct-file container determination", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
