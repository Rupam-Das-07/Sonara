package com.example.sonara.feature.library

import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.DownloadWithTrack
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DownloadRepository
import com.example.sonara.domain.repository.HistoryRepository
import com.example.sonara.domain.repository.LibraryRepository
import com.example.sonara.feature.player.PlayerViewModel
import com.example.sonara.playback.client.MediaControllerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDownloadsTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeLibraryRepo : LibraryRepository {
        val likedTracks = MutableStateFlow<List<Track>>(emptyList())
        val cachedTracks = mutableMapOf<String, Track>()

        override fun getLikedSongs(): Flow<List<Track>> = likedTracks
        override fun isLiked(trackId: String): Flow<Boolean> =
            MutableStateFlow(likedTracks.value.any { it.id == trackId })

        override suspend fun setLiked(track: Track, isLiked: Boolean) {
            val list = likedTracks.value.toMutableList()
            if (isLiked) {
                if (!list.contains(track)) list.add(track)
            } else {
                list.removeAll { it.id == track.id }
            }
            likedTracks.value = list
        }

        override suspend fun cacheTrack(track: Track) {
            cachedTracks[track.id] = track
        }

        override suspend fun getTrack(trackId: String): Track? =
            cachedTracks[trackId] ?: likedTracks.value.find { it.id == trackId }

        override suspend fun getTracks(trackIds: List<String>): List<Track> =
            trackIds.mapNotNull { cachedTracks[it] ?: likedTracks.value.find { t -> t.id == it } }
    }

    private class FakeHistoryRepo : HistoryRepository {
        override fun getRecentHistory(limit: Int): Flow<List<HistoryItem>> = flowOf(emptyList())
        override suspend fun recordHistory(track: Track, completed: Boolean): Long = 1L
        override suspend fun recordHistoryWithProvenance(
            track: Track,
            completed: Boolean,
            sourceSection: PlaybackOrigin,
            playbackSessionId: String
        ): Long = 1L
        override suspend fun markHistoryCompleted(historyId: Long) {}
        override suspend fun clearHistory() {}
    }

    private class FakeDownloadRepo : DownloadRepository {
        val downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
        val accessibleFiles = mutableMapOf<String, String>()

        override fun observeDownload(trackId: String): Flow<DownloadInfo?> =
            downloads.map { it[trackId] }

        override fun observeAllDownloads(): Flow<List<DownloadInfo>> =
            downloads.map { it.values.toList() }

        override fun observeCompletedDownloads(): Flow<List<DownloadInfo>> =
            downloads.map { it.values.filter { d -> d.status == DownloadStatus.DOWNLOADED } }

        override fun observeDownloadsWithTrack(): Flow<List<DownloadWithTrack>> =
            downloads.map { map ->
                map.values.map { DownloadWithTrack(it, null) }
            }

        override suspend fun getDownload(trackId: String): DownloadInfo? =
            downloads.value[trackId]

        override suspend fun enqueueDownload(track: Track, quality: AudioQuality?): Result<Unit> =
            Result.success(Unit)

        override suspend fun retryDownload(trackId: String): Result<Unit> = Result.success(Unit)
        override suspend fun cancelDownload(trackId: String): Result<Unit> = Result.success(Unit)

        override suspend fun removeDownload(trackId: String): Result<Unit> {
            downloads.value = downloads.value - trackId
            accessibleFiles.remove(trackId)
            return Result.success(Unit)
        }

        override suspend fun removeAllDownloads(): Result<Unit> {
            downloads.value = emptyMap()
            accessibleFiles.clear()
            return Result.success(Unit)
        }

        override suspend fun getDownloadedFileUri(trackId: String): String? =
            accessibleFiles[trackId]

        fun addCompletedDownload(trackId: String, filePath: String) {
            val info = DownloadInfo(
                trackId = trackId,
                localFilePath = filePath,
                status = DownloadStatus.DOWNLOADED,
                totalBytes = 5_000_000L,
                downloadedBytes = 5_000_000L
            )
            accessibleFiles[trackId] = filePath
            downloads.value = downloads.value + (trackId to info)
        }

        fun addPendingDownload(trackId: String) {
            val info = DownloadInfo(
                trackId = trackId,
                status = DownloadStatus.DOWNLOADING,
                totalBytes = 5_000_000L,
                downloadedBytes = 1_000_000L
            )
            downloads.value = downloads.value + (trackId to info)
        }
    }

    private class TrackingClient : MediaControllerClient(null) {
        var lastPlayedTrack: Track? = null
        var lastStreamUrl: String? = null

        override fun playTrack(track: Track, streamUrl: String, generation: Long) {
            lastPlayedTrack = track
            lastStreamUrl = streamUrl
        }
    }

    private class NoOpDiscoveryRepository : com.example.sonara.domain.repository.DiscoveryRepository {
        override suspend fun getFeaturedArtists(): Result<List<com.example.sonara.domain.model.FeaturedArtist>> = Result.success(emptyList())
        override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<com.example.sonara.domain.model.ArtistCatalog> = Result.success(com.example.sonara.domain.model.ArtistCatalog())
        override suspend fun getPlaylists(): Result<List<com.example.sonara.domain.model.PlaylistSummary>> = Result.success(emptyList())
        override suspend fun getPlaylistDetail(playlistId: String): Result<com.example.sonara.domain.model.PlaylistDetail> = Result.failure(Exception("Not implemented"))
        override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = Result.success(emptyList())
    }

    private class NoOpStreamResolver : StreamResolverPort {
        override fun clearCache() {}
        override suspend fun resolveStream(trackId: String, quality: AudioQuality): Result<StreamInfo> =
            Result.success(StreamInfo(trackId = trackId, streamUrl = "https://remote.stream/$trackId"))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun mappingCompletedDownloads_populatesUiStateCorrectly() = runTest {
        val libraryRepo = FakeLibraryRepo()
        val historyRepo = FakeHistoryRepo()
        val downloadRepo = FakeDownloadRepo()

        val track1 = Track(id = "track_1", title = "Song One", artist = "Artist One")
        val track2 = Track(id = "track_2", title = "Song Two", artist = "Artist Two")
        libraryRepo.cacheTrack(track1)
        libraryRepo.cacheTrack(track2)

        downloadRepo.addCompletedDownload("track_1", "/storage/track_1.m4a")
        downloadRepo.addCompletedDownload("track_2", "/storage/track_2.m4a")

        val viewModel = LibraryViewModel(libraryRepo, historyRepo, downloadRepo)
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.downloads.size)
        assertEquals("track_1", state.downloads[0].id)
        assertEquals("track_2", state.downloads[1].id)

        collectJob.cancel()
    }

    @Test
    fun reactiveUpdates_whenDownloadCompletesOrIsRemoved() = runTest {
        val libraryRepo = FakeLibraryRepo()
        val historyRepo = FakeHistoryRepo()
        val downloadRepo = FakeDownloadRepo()

        val track1 = Track(id = "track_1", title = "Song One", artist = "Artist One")
        val track2 = Track(id = "track_2", title = "Song Two", artist = "Artist Two")
        libraryRepo.cacheTrack(track1)
        libraryRepo.cacheTrack(track2)

        val viewModel = LibraryViewModel(libraryRepo, historyRepo, downloadRepo)
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // 1. Initially empty
        assertTrue(viewModel.uiState.value.downloads.isEmpty())

        // 2. Download 1 completes -> state reactively updates
        downloadRepo.addCompletedDownload("track_1", "/storage/track_1.m4a")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.downloads.size)
        assertEquals("track_1", viewModel.uiState.value.downloads.first().id)

        // 3. Download 2 completes -> state reactively updates to 2 tracks
        downloadRepo.addCompletedDownload("track_2", "/storage/track_2.m4a")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.downloads.size)

        // 4. Download 1 is removed -> state reactively updates to 1 track
        viewModel.removeDownload("track_1")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.downloads.size)
        assertEquals("track_2", viewModel.uiState.value.downloads.first().id)

        collectJob.cancel()
    }

    @Test
    fun emptyStateBehavior_whenNoCompletedDownloadsExist() = runTest {
        val libraryRepo = FakeLibraryRepo()
        val historyRepo = FakeHistoryRepo()
        val downloadRepo = FakeDownloadRepo()

        // Add a DOWNLOADING (incomplete) track
        downloadRepo.addPendingDownload("in_progress_track")

        val viewModel = LibraryViewModel(libraryRepo, historyRepo, downloadRepo)
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // Incomplete download must not appear as completed
        assertTrue(viewModel.uiState.value.downloads.isEmpty())

        // Tab selection to DOWNLOADS
        viewModel.selectTab(LibraryTab.DOWNLOADS)
        advanceUntilIdle()
        assertEquals(LibraryTab.DOWNLOADS, viewModel.uiState.value.selectedTab)
        assertTrue(viewModel.uiState.value.downloads.isEmpty())

        collectJob.cancel()
    }

    @Test
    fun missingMetadataOrMissingLocalFiles_handledSafelyWithoutCrashing() = runTest {
        val libraryRepo = FakeLibraryRepo()
        val historyRepo = FakeHistoryRepo()
        val downloadRepo = FakeDownloadRepo()

        val validTrack = Track(id = "valid_track", title = "Valid", artist = "Artist")
        libraryRepo.cacheTrack(validTrack)
        downloadRepo.addCompletedDownload("valid_track", "/storage/valid_track.m4a")

        // 1. Download record without cached track metadata in repository
        downloadRepo.addCompletedDownload("missing_metadata_track", "/storage/no_meta.m4a")

        // 2. Download record where file is missing from disk (null in accessibleFiles)
        val missingFileTrack = Track(id = "missing_file_track", title = "No File", artist = "Artist")
        libraryRepo.cacheTrack(missingFileTrack)
        downloadRepo.downloads.value = downloadRepo.downloads.value + (
            "missing_file_track" to DownloadInfo(
                trackId = "missing_file_track",
                localFilePath = null,
                status = DownloadStatus.DOWNLOADED
            )
        )

        val viewModel = LibraryViewModel(libraryRepo, historyRepo, downloadRepo)
        val collectJob = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        // Only the valid download with existing metadata and file should be emitted
        val downloads = viewModel.uiState.value.downloads
        assertEquals(1, downloads.size)
        assertEquals("valid_track", downloads.first().id)

        collectJob.cancel()
    }

    @Test
    fun selectingDownloadedTrack_routesThroughPlaybackPathwayToLocalFile() = runTest {
        val libraryRepo = FakeLibraryRepo()
        val historyRepo = FakeHistoryRepo()
        val downloadRepo = FakeDownloadRepo()
        val trackingClient = TrackingClient()
        val streamResolver = NoOpStreamResolver()

        val downloadedTrack = Track(
            id = "offline_track_1",
            title = "Offline Masterpiece",
            artist = "Offline Artist"
        )
        libraryRepo.cacheTrack(downloadedTrack)
        downloadRepo.addCompletedDownload("offline_track_1", "/storage/offline_track_1.m4a")

        val libraryViewModel = LibraryViewModel(libraryRepo, historyRepo, downloadRepo)
        val collectJob = backgroundScope.launch { libraryViewModel.uiState.collect {} }
        advanceUntilIdle()

        // Verify the track is present in the Library downloads list
        val selectedTrack = libraryViewModel.uiState.value.downloads.firstOrNull()
        assertNotNull(selectedTrack)
        assertEquals("offline_track_1", selectedTrack?.id)

        // Simulate user selecting the downloaded track in the Library screen:
        // The UI triggers onPlayTrackWithOrigin(track, PlaybackOrigin.OTHER), which passes it to PlayerViewModel
        val playerViewModel = PlayerViewModel(
            client = trackingClient,
            discoveryRepository = NoOpDiscoveryRepository(),
            streamResolverPort = streamResolver,
            downloadRepository = downloadRepo
        )

        playerViewModel.playTrack(selectedTrack!!)
        advanceUntilIdle()

        // Verify PlayerViewModel routed directly to the local file URI (0ms offline playback)
        assertEquals("offline_track_1", trackingClient.lastPlayedTrack?.id)
        assertEquals("file:///storage/offline_track_1.m4a", trackingClient.lastStreamUrl)

        collectJob.cancel()
    }
}
