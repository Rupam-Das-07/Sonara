package com.example.sonara.feature.player

import androidx.media3.common.MediaItem
import com.example.sonara.domain.model.ArtistCatalog
import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.PlaylistDetail
import com.example.sonara.domain.model.PlaylistSummary
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.ports.StreamResolverPort
import com.example.sonara.domain.repository.DiscoveryRepository
import com.example.sonara.playback.client.MediaControllerClient
import com.example.sonara.playback.client.MediaControllerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
class PlayerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeClient: FakeMediaControllerClient
    private lateinit var fakeDiscoveryRepo: FakeDiscoveryRepository
    private lateinit var fakeStreamResolver: FakeStreamResolver

    private val track1 = Track(id = "v1", title = "Track 1", artist = "Artist 1")
    private val track2 = Track(id = "v2", title = "Track 2", artist = "Artist 2")
    private val rec1 = Track(id = "rec1", title = "Rec 1", artist = "Rec Artist 1")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeClient = FakeMediaControllerClient()
        fakeDiscoveryRepo = FakeDiscoveryRepository()
        fakeStreamResolver = FakeStreamResolver()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): PlayerViewModel {
        return PlayerViewModel(
            client = fakeClient,
            discoveryRepository = fakeDiscoveryRepo,
            streamResolverPort = fakeStreamResolver,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `playTrack establishes queue context, resolves stream, and plays track`() = testScope.runTest {
        val viewModel = createViewModel()
        viewModel.playTrack(track1, listOf(track1, track2))
        advanceUntilIdle()

        assertEquals(track1, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(track2), viewModel.queueEngine.upcomingQueue)
        assertEquals(track1, fakeClient.lastPlayedTrack)
        assertEquals("https://stream/v1", fakeClient.lastPlayedStreamUrl)
    }

    @Test
    fun `player state keeps raw title and exposes formatted display title`() = testScope.runTest {
        val rawTitle = "Imagine Dragons - Believer (Official Music Video)"
        val track = Track(id = "formatted", title = rawTitle, artist = "Imagine Dragons")
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.playTrack(track)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(rawTitle, state.trackTitle)
        assertEquals("Believer", state.displayTitle)
        assertEquals("Believer", state.effectiveTitle)
    }

    @Test
    fun `shuffle toggle updates authoritative queue and projected player state`() = testScope.runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        assertFalse(viewModel.uiState.value.isShuffled)
        viewModel.toggleShuffle()
        runCurrent()

        assertTrue(viewModel.queueEngine.isShuffled)
        assertTrue(viewModel.uiState.value.isShuffled)
    }

    @Test
    fun `repeat toggle cycles authoritative mode and projected player state`() = testScope.runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.toggleRepeatMode()
        runCurrent()
        assertEquals(1, viewModel.queueEngine.repeatMode)
        assertEquals(1, viewModel.uiState.value.repeatMode)

        viewModel.toggleRepeatMode()
        runCurrent()
        assertEquals(2, viewModel.queueEngine.repeatMode)
        assertEquals(2, viewModel.uiState.value.repeatMode)
    }

    @Test
    fun `new selection pauses the old track before remote resolution completes`() = testScope.runTest {
        val resolver = DeferredStreamResolver()
        val viewModel = PlayerViewModel(
            client = fakeClient,
            discoveryRepository = fakeDiscoveryRepo,
            streamResolverPort = resolver,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.playTrack(track1)
        runCurrent()
        resolver.complete(track1)
        advanceUntilIdle()
        assertEquals(track1, fakeClient.lastPlayedTrack)
        assertTrue(fakeClient.isPlaying)

        viewModel.playTrack(track2)
        runCurrent()

        assertFalse("old track must be paused while B resolves", fakeClient.isPlaying)
        assertTrue("selection must issue a pause before resolution", fakeClient.pauseCount >= 2)
        assertEquals(track1, fakeClient.lastPlayedTrack)

        resolver.complete(track2)
        advanceUntilIdle()
        assertEquals(track2, fakeClient.lastPlayedTrack)
        assertTrue(fakeClient.isPlaying)
    }

    @Test
    fun `selection from the middle of a context preserves only following tracks`() = testScope.runTest {
        val viewModel = createViewModel()
        val context = listOf(track1, track2, rec1)

        viewModel.playTrack(track2, contextQueue = context)
        advanceUntilIdle()

        assertEquals(track2, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(rec1), viewModel.queueEngine.upcomingQueue)
    }

    @Test
    fun `matching controller request failure keeps the target retryable and reports the error`() = testScope.runTest {
        val resolver = DeferredStreamResolver()
        val viewModel = PlayerViewModel(
            client = fakeClient,
            discoveryRepository = fakeDiscoveryRepo,
            streamResolverPort = resolver,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.playTrack(track1)
        runCurrent()
        fakeClient.publishRequestFailure(
            generation = viewModel.transitionManager.currentGeneration(),
            message = "Service rejected playback request"
        )
        runCurrent()

        assertEquals(track1.id, viewModel.uiState.value.trackId)
        assertEquals("Service rejected playback request", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isBuffering)
    }

    @Test
    fun `stale controller failure cannot overwrite a newer selection`() = testScope.runTest {
        val resolver = DeferredStreamResolver()
        val viewModel = PlayerViewModel(
            client = fakeClient,
            discoveryRepository = fakeDiscoveryRepo,
            streamResolverPort = resolver,
            ioDispatcher = testDispatcher
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.playTrack(track1)
        runCurrent()
        viewModel.playTrack(track2)
        runCurrent()

        fakeClient.publishRequestFailure(generation = 1L, message = "stale failure")
        runCurrent()

        assertEquals(track2.id, viewModel.uiState.value.trackId)
        assertTrue(viewModel.uiState.value.isBuffering)
        assertEquals(null, viewModel.uiState.value.errorMessage)

        resolver.complete(track2)
        advanceUntilIdle()
    }

    @Test
    fun `skipToNext advances to queued track and plays it`() = testScope.runTest {
        val viewModel = createViewModel()
        viewModel.playTrack(track1, listOf(track1, track2))
        advanceUntilIdle()

        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(track2, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(track1), viewModel.queueEngine.sessionBackStack)
        assertEquals(track2, fakeClient.lastPlayedTrack)
        assertEquals("https://stream/v2", fakeClient.lastPlayedStreamUrl)
    }

    @Test
    fun `skipToNext with empty queue fetches dynamic recommendations and plays candidate`() = testScope.runTest {
        fakeDiscoveryRepo.relatedResult = Result.success(listOf(rec1))

        val viewModel = createViewModel()
        viewModel.playTrack(track1) // Single track -> empty upcoming queue
        advanceUntilIdle()

        viewModel.skipToNext()
        advanceUntilIdle()

        assertEquals(rec1, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(track1), viewModel.queueEngine.sessionBackStack)
        assertEquals(rec1, fakeClient.lastPlayedTrack)
        assertEquals("https://stream/rec1", fakeClient.lastPlayedStreamUrl)
    }

    @Test
    fun `skipToPrevious with position greater than 3000ms seeks to 0ms`() = testScope.runTest {
        fakeClient.setTestPosition(4500L)

        val viewModel = createViewModel()
        viewModel.playTrack(track1, listOf(track1, track2))
        advanceUntilIdle()
        viewModel.skipToNext() // now on track2
        advanceUntilIdle()

        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(0L, fakeClient.lastSeekPosition)
        assertEquals(track2, viewModel.queueEngine.currentTrack)
    }

    @Test
    fun `skipToPrevious with position less than 3000ms plays previous track from session backstack`() = testScope.runTest {
        fakeClient.setTestPosition(1200L)

        val viewModel = createViewModel()
        viewModel.playTrack(track1, listOf(track1, track2))
        advanceUntilIdle()
        viewModel.skipToNext() // on track2, backstack has [track1]
        advanceUntilIdle()

        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(track1, viewModel.queueEngine.currentTrack)
        assertEquals(track1, fakeClient.lastPlayedTrack)
        assertEquals("https://stream/v1", fakeClient.lastPlayedStreamUrl)
    }

    @Test
    fun `recommendation failure gracefully pauses playback without crashing`() = testScope.runTest {
        fakeDiscoveryRepo.relatedResult = Result.failure(Exception("HTTP 502 Provider Unavailable"))

        val viewModel = createViewModel()
        viewModel.playTrack(track1) // Single track -> empty queue
        advanceUntilIdle()

        viewModel.skipToNext()
        advanceUntilIdle()

        assertTrue(fakeClient.isPaused)
    }

    @Test
    fun `selectAudioOutput delegates to AudioOutputRepository and updates audioOutputState`() = testScope.runTest {
        val fakeAudioOutputRepo = com.example.sonara.domain.model.FakeAudioOutputRepository()
        val viewModel = PlayerViewModel(
            client = fakeClient,
            discoveryRepository = fakeDiscoveryRepo,
            streamResolverPort = fakeStreamResolver,
            audioOutputRepository = fakeAudioOutputRepo,
            ioDispatcher = testDispatcher
        )

        val targetDevice = com.example.sonara.domain.model.AudioOutputDevice(
            id = 42,
            name = "Bose QuietComfort 45",
            type = com.example.sonara.domain.model.AudioDeviceType.BLUETOOTH_HEADPHONES
        )

        viewModel.selectAudioOutput(targetDevice)
        advanceUntilIdle()

        assertEquals(targetDevice, fakeAudioOutputRepo.selectedDevice)
        assertEquals("Bose QuietComfort 45", viewModel.audioOutputState.value.activeDevice.name)
    }

    @Test
    fun `restoreTrack sets queue context and transitional state with position and queue without auto-playing`() = testScope.runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.restoreTrack(track1, initialPositionMs = 45000L, contextQueue = listOf(track1, track2))
        advanceUntilIdle()

        assertEquals(track1, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(track2), viewModel.queueEngine.upcomingQueue)
        assertEquals(null, fakeClient.lastPlayedTrack) // must NOT auto-play
        val state = viewModel.uiState.value
        assertEquals("v1", state.trackId)
        assertEquals(45000L, state.currentPositionMs)
    }

    @Test
    fun `play on restored track plays and seeks to initial position`() = testScope.runTest {
        val viewModel = createViewModel()
        viewModel.restoreTrack(track1, initialPositionMs = 32000L, contextQueue = listOf(track1, track2))
        advanceUntilIdle()

        viewModel.play()
        advanceUntilIdle()

        assertEquals(track1, fakeClient.lastPlayedTrack)
        assertEquals(32000L, fakeClient.lastSeekPosition)
    }

    @Test
    fun `skipToNext with explicit fromTrackId matching current track advances queue`() = testScope.runTest {
        val viewModel = createViewModel()
        viewModel.playTrack(track1, listOf(track1, track2))
        advanceUntilIdle()

        viewModel.skipToNext(fromTrackId = "v1")
        advanceUntilIdle()

        assertEquals(track2, viewModel.queueEngine.currentTrack)
        assertEquals(track2, fakeClient.lastPlayedTrack)
    }

    @Test
    fun `competing duplicate skipToNext with stale fromTrackId does not advance queue twice`() = testScope.runTest {
        val track3 = Track(id = "v3", title = "Track 3", artist = "Artist 3")
        val viewModel = createViewModel()
        viewModel.playTrack(track1, listOf(track1, track2, track3))
        advanceUntilIdle()

        // First skip advances to track2
        viewModel.skipToNext(fromTrackId = "v1")
        advanceUntilIdle()
        assertEquals(track2, viewModel.queueEngine.currentTrack)

        // Competing duplicate skip with same stale fromTrackId="v1" (e.g. bounce or autoplay race)
        viewModel.skipToNext(fromTrackId = "v1")
        advanceUntilIdle()

        // Must still be on track2, NOT skipped ahead to track3!
        assertEquals(track2, viewModel.queueEngine.currentTrack)
        assertEquals(listOf(track3), viewModel.queueEngine.upcomingQueue)
        assertEquals(listOf(track1), viewModel.queueEngine.sessionBackStack)
    }

    @Test
    fun `rapid intentional skipToNext advances sequentially and drops stale stream resolutions`() = testScope.runTest {
        val track3 = Track(id = "v3", title = "Track 3", artist = "Artist 3")
        val viewModel = createViewModel()
        viewModel.playTrack(track1, listOf(track1, track2, track3))
        advanceUntilIdle()

        // Rapid skip 1 (v1 -> v2)
        viewModel.skipToNext()
        // Do not advanceUntilIdle yet; trigger immediate rapid skip 2 (v2 -> v3)
        viewModel.skipToNext()
        advanceUntilIdle()

        // Queue must have moved to track3
        assertEquals(track3, viewModel.queueEngine.currentTrack)
        // Final played track must be track3 (track2 stream resolution was superseded by generation increment)
        assertEquals(track3, fakeClient.lastPlayedTrack)
        assertEquals("https://stream/v3", fakeClient.lastPlayedStreamUrl)
        assertEquals(listOf(track1, track2), viewModel.queueEngine.sessionBackStack)
    }

    @Test
    fun `skipToNext followed immediately by skipToPrevious returns to original track`() = testScope.runTest {
        val viewModel = createViewModel()
        viewModel.playTrack(track1, listOf(track1, track2))
        advanceUntilIdle()

        // Skip to track2
        viewModel.skipToNext()
        advanceUntilIdle()
        assertEquals(track2, viewModel.queueEngine.currentTrack)

        // Previous on track2 with position < 3000ms pops backstack -> track1
        fakeClient.setTestPosition(500L)
        viewModel.skipToPrevious()
        advanceUntilIdle()

        assertEquals(track1, viewModel.queueEngine.currentTrack)
        assertEquals(track1, fakeClient.lastPlayedTrack)
    }
}

private class FakeMediaControllerClient : MediaControllerClient(null) {
    var lastPlayedTrack: Track? = null
    var lastPlayedStreamUrl: String? = null
    var lastSeekPosition: Long? = null
    var isPaused: Boolean = false
    var pauseCount: Int = 0
    val isPlaying: Boolean get() = _controllerState.value.isPlaying

    fun setTestPosition(posMs: Long) {
        _controllerState.value = _controllerState.value.copy(
            isConnected = true,
            currentPositionMs = posMs
        )
    }

    override fun playTrack(track: Track, streamUrl: String, generation: Long) {
        lastPlayedTrack = track
        lastPlayedStreamUrl = streamUrl
        isPaused = false
        _controllerState.value = _controllerState.value.copy(
            isConnected = true,
            isPlaying = true,
            currentMediaItem = MediaItem.Builder()
                .setMediaId(track.id)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .build()
                )
                .build()
        )
    }

    override fun seekTo(positionMs: Long) {
        lastSeekPosition = positionMs
    }

    override fun pause() {
        pauseCount++
        isPaused = true
        _controllerState.value = _controllerState.value.copy(isPlaying = false)
    }

    override fun play() {
        isPaused = false
    }

    fun publishRequestFailure(generation: Long, message: String) {
        _controllerState.value = _controllerState.value.copy(
            errorMessage = message,
            requestFailureGeneration = generation,
            isPlaying = false
        )
    }
}

private class DeferredStreamResolver : StreamResolverPort {
    private val pending = mutableMapOf<String, CompletableDeferred<Result<StreamInfo>>>()

    override fun clearCache() = Unit

    override suspend fun resolveStream(
        trackId: String,
        quality: com.example.sonara.domain.model.AudioQuality
    ): Result<StreamInfo> = resolve(trackId)

    override suspend fun resolveStream(
        trackId: String,
        quality: com.example.sonara.domain.model.AudioQuality,
        title: String,
        artist: String,
        durationSeconds: Int
    ): Result<StreamInfo> = resolve(trackId)

    private suspend fun resolve(trackId: String): Result<StreamInfo> {
        val result = CompletableDeferred<Result<StreamInfo>>()
        pending[trackId] = result
        return result.await()
    }

    fun complete(track: Track) {
        pending.remove(track.id)?.complete(
            Result.success(
                StreamInfo(
                    trackId = track.id,
                    streamUrl = "https://stream/${track.id}",
                    format = "audio/webm"
                )
            )
        )
    }

}

private class FakeStreamResolver : StreamResolverPort {
    override fun clearCache() {}
    override suspend fun resolveStream(trackId: String, quality: com.example.sonara.domain.model.AudioQuality): Result<StreamInfo> {
        return Result.success(
            StreamInfo(
                trackId = trackId,
                streamUrl = "https://stream/$trackId",
                expiresAt = System.currentTimeMillis() + 3600_000L,
                format = "audio/webm"
            )
        )
    }
}

private class FakeDiscoveryRepository : DiscoveryRepository {
    var relatedResult: Result<List<Track>> = Result.success(emptyList())

    override suspend fun getFeaturedArtists(): Result<List<FeaturedArtist>> = Result.success(emptyList())
    override suspend fun getArtistCatalog(artistId: String, artistName: String): Result<ArtistCatalog> = Result.success(ArtistCatalog())
    override suspend fun getPlaylists(): Result<List<PlaylistSummary>> = Result.success(emptyList())
    override suspend fun getPlaylistDetail(playlistId: String): Result<PlaylistDetail> = Result.failure(Exception("Not implemented"))
    override suspend fun getQuickPicks(refresh: Boolean): Result<List<Track>> = Result.success(emptyList())
    override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = relatedResult
}
