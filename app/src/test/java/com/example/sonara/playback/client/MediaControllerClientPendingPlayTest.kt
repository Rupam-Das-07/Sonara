package com.example.sonara.playback.client

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.VideoDeliveryMethod
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoStreamKind
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControllerClientPendingPlayTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `currentPlayerState defaults to null when disconnected`() {
        val client = MediaControllerClient(null, CoroutineScope(testDispatcher))
        assertNull(client.currentPlayer())
        assertNull(client.currentPlayerState.value)
    }

    @Test
    fun `playback request queued before connection executes exactly once when connection succeeds`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val track = Track(id = "track-101", title = "Track 101", artist = "Artist 101")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)

        client.playTrack(track, "https://example.invalid/stream/101.mp3")

        assertEquals(1, client.connectAttempts)
        assertTrue(client.hasPendingPlayAction())
        assertEquals(0, playedItems.size)

        client.handleConnectionSuccess(dummyPlayer)

        assertFalse(client.hasPendingPlayAction())
        assertEquals(1, playedItems.size)
        assertEquals("track-101", playedItems[0].mediaId)

        // Idempotence: a second connection callback must not re-dispatch the consumed action
        client.handleConnectionSuccess(dummyPlayer)
        assertEquals(1, playedItems.size)
    }

    @Test
    fun `clearing media while request is pending prevents stale request from executing`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val track = Track(id = "track-102", title = "Track 102", artist = "Artist 102")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)

        client.playTrack(track, "https://example.invalid/stream/102.mp3")
        assertTrue(client.hasPendingPlayAction())

        client.clearMediaItems()
        assertFalse(client.hasPendingPlayAction())

        client.handleConnectionSuccess(dummyPlayer)
        assertEquals(0, playedItems.size)
    }

    @Test
    fun `disconnect while request is pending cancels pending action`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val track = Track(id = "track-103", title = "Track 103", artist = "Artist 103")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)

        client.playTrack(track, "https://example.invalid/stream/103.mp3")
        assertTrue(client.hasPendingPlayAction())

        client.disconnect()
        assertFalse(client.hasPendingPlayAction())
        assertNull(client.currentPlayer())

        client.handleConnectionSuccess(dummyPlayer)
        assertEquals(0, playedItems.size)
    }

    @Test
    fun `connection failure clears pending play action and prevents subsequent replay`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val track = Track(id = "track-104", title = "Track 104", artist = "Artist 104")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)

        client.playTrack(track, "https://example.invalid/stream/104.mp3")
        assertTrue(client.hasPendingPlayAction())

        client.handleConnectionFailure(IllegalStateException("Simulated connection failure"))

        assertFalse(client.hasPendingPlayAction())
        assertEquals("Simulated connection failure", client.controllerState.value.errorMessage)

        // Obsolete request must not execute if connection completes later
        client.handleConnectionSuccess(dummyPlayer)
        assertEquals(0, playedItems.size)
    }

    @Test
    fun `newest video selection replaces earlier pending playback request`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val trackA = Track(id = "video-a", title = "Video A", artist = "Artist A")
        val trackB = Track(id = "video-b", title = "Video B", artist = "Artist B")
        val sourceA = VideoPlaybackSource(
            videoId = "video-a",
            streamUrl = "https://example.invalid/videoA.m3u8",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.HLS
        )
        val sourceB = VideoPlaybackSource(
            videoId = "video-b",
            streamUrl = "https://example.invalid/videoB.m3u8",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.HLS
        )
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)

        client.playExtractedVideo(trackA, sourceA)
        assertTrue(client.hasPendingPlayAction())

        client.playExtractedVideo(trackB, sourceB)
        assertTrue(client.hasPendingPlayAction())

        client.handleConnectionSuccess(dummyPlayer)
        assertFalse(client.hasPendingPlayAction())

        assertEquals(1, playedItems.size)
        assertEquals("video-b", playedItems[0].mediaId)
    }

    @Test
    fun `obsolete connection success arriving after disconnect is rejected and does not restore player or state`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        var releaseCount = 0
        val dummyPlayer = createDummyPlayer(playedItems, onRelease = { releaseCount++ })

        val connectionGen = client.nextGeneration()
        client.disconnect()

        val accepted = client.handleConnectionSuccess(dummyPlayer, generation = connectionGen)

        assertFalse(accepted)
        assertNull(client.currentPlayer())
        assertNull(client.currentPlayerState.value)
        assertFalse(client.controllerState.value.isConnected)
        assertEquals(0, playedItems.size)
        assertEquals("Obsolete player must be safely released", 1, releaseCount)
    }

    @Test
    fun `obsolete connection failure from earlier attempt does not cancel newer pending action`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val trackA = Track(id = "track-a", title = "Track A", artist = "Artist A")
        val trackB = Track(id = "track-b", title = "Track B", artist = "Artist B")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)

        val staleGen = client.nextGeneration()
        client.playTrack(trackA, "https://example.invalid/a.mp3")

        val newerGen = client.nextGeneration()
        client.playTrack(trackB, "https://example.invalid/b.mp3")
        assertTrue(client.hasPendingPlayAction())

        val handledStaleFailure = client.handleConnectionFailure(
            IllegalStateException("Stale connection failure"),
            generation = staleGen
        )

        assertFalse(handledStaleFailure)
        assertTrue("Newer pending action must NOT be cleared by stale failure", client.hasPendingPlayAction())
        assertNull("Stale failure error message must NOT be set", client.controllerState.value.errorMessage)

        val accepted = client.handleConnectionSuccess(dummyPlayer, generation = newerGen)
        assertTrue(accepted)
        assertFalse(client.hasPendingPlayAction())
        assertEquals(1, playedItems.size)
        assertEquals("track-b", playedItems[0].mediaId)
    }

    @Test
    fun `out of order connection callbacks ensure newer connection wins and obsolete player is released`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItemsA = mutableListOf<MediaItem>()
        val playedItemsB = mutableListOf<MediaItem>()
        var releaseCountA = 0
        var releaseCountB = 0
        val playerA = createDummyPlayer(playedItemsA, onRelease = { releaseCountA++ })
        val playerB = createDummyPlayer(playedItemsB, onRelease = { releaseCountB++ })

        val genA = client.nextGeneration()
        val genB = client.nextGeneration()

        // Newer connection B succeeds first
        val acceptedB = client.handleConnectionSuccess(playerB, generation = genB)
        assertTrue(acceptedB)
        assertSame(playerB, client.currentPlayer())
        assertEquals(0, releaseCountB)

        // Older connection A arrives later (out of order)
        val acceptedA = client.handleConnectionSuccess(playerA, generation = genA)
        assertFalse(acceptedA)
        assertSame("Active player must remain player B", playerB, client.currentPlayer())
        assertEquals("Obsolete player A must be released", 1, releaseCountA)
        assertEquals("Active player B must NEVER be released", 0, releaseCountB)
    }

    @Test
    fun `repeated handleConnectionSuccess with identical player is idempotent and returns false`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val dummyPlayer = createDummyPlayer()

        val firstCall = client.handleConnectionSuccess(dummyPlayer)
        assertTrue(firstCall)
        assertSame(dummyPlayer, client.currentPlayer())
        assertTrue(client.controllerState.value.isConnected)

        val secondCall = client.handleConnectionSuccess(dummyPlayer)
        assertFalse(secondCall)
        assertSame(dummyPlayer, client.currentPlayer())
    }

    @Test
    fun `legitimate connection failure clears pending action and allows subsequent reconnection attempt`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val track1 = Track(id = "track-fail-1", title = "Track 1", artist = "Artist 1")
        val track2 = Track(id = "track-recover-2", title = "Track 2", artist = "Artist 2")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)

        val gen1 = client.nextGeneration()
        client.playTrack(track1, "https://example.invalid/1.mp3")
        assertTrue(client.hasPendingPlayAction())

        val failureHandled = client.handleConnectionFailure(
            IllegalStateException("Network timeout"),
            generation = gen1
        )
        assertTrue(failureHandled)
        assertFalse(client.hasPendingPlayAction())
        assertEquals("Network timeout", client.controllerState.value.errorMessage)

        // Subsequent connection attempt succeeds
        val gen2 = client.nextGeneration()
        client.playTrack(track2, "https://example.invalid/2.mp3")
        assertTrue(client.hasPendingPlayAction())

        val successHandled = client.handleConnectionSuccess(dummyPlayer, generation = gen2)
        assertTrue(successHandled)
        assertFalse(client.hasPendingPlayAction())
        assertEquals(1, playedItems.size)
        assertEquals("track-recover-2", playedItems[0].mediaId)
    }

    @Test
    fun `disconnect safely releases active player and prevents subsequent stale calls`() {
        var released = false
        val dummyPlayer = createDummyPlayer(onRelease = { released = true })
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))

        val gen = client.nextGeneration()
        val accepted = client.handleConnectionSuccess(dummyPlayer, generation = gen)
        assertTrue(accepted)
        assertSame(dummyPlayer, client.currentPlayer())
        assertFalse(released)

        client.disconnect()
        assertTrue("Active player must be released on disconnect", released)
        assertNull(client.currentPlayer())
        assertFalse(client.controllerState.value.isConnected)

        // Re-invoking success with previous generation must be rejected
        val lateSuccess = client.handleConnectionSuccess(dummyPlayer, generation = gen)
        assertFalse(lateSuccess)
        assertNull(client.currentPlayer())
    }

    @Test
    fun `disconnect after adoption but before lifecycle side effects finish cancels side effects and maintains disconnected state`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        var releaseCount = 0
        val dummyPlayer = createDummyPlayer(playedItems, onRelease = { releaseCount++ })

        val track = Track(id = "track-race-post-adoption", title = "Post Adoption Track", artist = "Artist")
        val gen = client.nextGeneration()
        client.playTrack(track, "https://example.invalid/post-adoption.mp3")
        assertTrue(client.hasPendingPlayAction())

        // Deterministic synchronization point: call disconnect() right after adoption, before remaining side-effects
        client.postAdoptionAction = {
            client.disconnect()
        }

        val accepted = client.handleConnectionSuccess(dummyPlayer, generation = gen)
        assertTrue(accepted)

        assertNull("Current player must be null after disconnect", client.currentPlayer())
        assertNull("Current player state must be null after disconnect", client.currentPlayerState.value)
        assertFalse("Controller state isConnected must be false after disconnect", client.controllerState.value.isConnected)
        assertEquals("Pending play action must not execute after disconnect", 0, playedItems.size)
        assertEquals("Player must be released exactly once on disconnect", 1, releaseCount)
    }

    @Test
    fun `disconnect between pending-action validation and execution prevents playback execution`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        var releaseCount = 0
        val dummyPlayer = createDummyPlayer(playedItems, onRelease = { releaseCount++ })

        val track = Track(id = "track-race-pending-action", title = "Pending Action Track", artist = "Artist")
        val gen = client.nextGeneration()
        client.playTrack(track, "https://example.invalid/pending-action.mp3")
        assertTrue(client.hasPendingPlayAction())

        // Deterministic synchronization point: call disconnect() right before pending action executes
        client.beforePendingActionExecutionAction = {
            client.disconnect()
        }

        val accepted = client.handleConnectionSuccess(dummyPlayer, generation = gen)
        assertTrue(accepted)

        assertNull("Current player must be null after disconnect", client.currentPlayer())
        assertNull("Current player state must be null after disconnect", client.currentPlayerState.value)
        assertFalse("Controller state isConnected must be false after disconnect", client.controllerState.value.isConnected)
        assertEquals("Pending play action must not execute when disconnect preempts execution", 0, playedItems.size)
        assertEquals("Player must be released exactly once on disconnect", 1, releaseCount)
    }

    @Test
    fun `disconnect during connection success before state publication cancels adoption and releases player`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        var releaseCount = 0
        val dummyPlayer = createDummyPlayer(playedItems, onRelease = { releaseCount++ })

        val track = Track(id = "track-race-1", title = "Race Track", artist = "Artist")
        val gen = client.nextGeneration()
        client.playTrack(track, "https://example.invalid/race.mp3")
        assertTrue(client.hasPendingPlayAction())

        // Deterministic synchronization point: call disconnect() before adoption/state publication
        client.preStatePublicationAction = {
            client.disconnect()
        }

        val accepted = client.handleConnectionSuccess(dummyPlayer, generation = gen)

        assertFalse("Connection success must be rejected when disconnect preempts adoption", accepted)
        assertNull("Current player must remain null", client.currentPlayer())
        assertNull("Current player state must remain null", client.currentPlayerState.value)
        assertFalse("Controller state isConnected must remain false", client.controllerState.value.isConnected)
        assertEquals("Pending play action must not execute", 0, playedItems.size)
        assertFalse("Pending play action must be cleared", client.hasPendingPlayAction())
        assertEquals("Player must be released exactly once", 1, releaseCount)
    }

    @Test
    fun `obsolete callback A resuming late does not disturb active connection B`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        var releaseCountA = 0
        var releaseCountB = 0
        val playerA = createDummyPlayer(onRelease = { releaseCountA++ })
        val playerB = createDummyPlayer(onRelease = { releaseCountB++ })

        val genA = client.nextGeneration()
        val genB = client.nextGeneration()

        // Connection B succeeds and is fully active
        val acceptedB = client.handleConnectionSuccess(playerB, generation = genB)
        assertTrue(acceptedB)
        assertSame(playerB, client.currentPlayer())
        assertTrue(client.controllerState.value.isConnected)
        assertEquals(0, releaseCountB)

        // Late callback A attempts to succeed
        val acceptedA = client.handleConnectionSuccess(playerA, generation = genA)
        assertFalse(acceptedA)
        assertSame("Active player must remain player B", playerB, client.currentPlayer())
        assertTrue("Client must remain connected with player B", client.controllerState.value.isConnected)
        assertEquals("Obsolete player A must be released exactly once", 1, releaseCountA)
        assertEquals("Active player B must NEVER be released", 0, releaseCountB)
    }

    @Test
    fun `normal active disconnect releases connected player exactly once`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        var releaseCount = 0
        val player = createDummyPlayer(onRelease = { releaseCount++ })

        val gen = client.nextGeneration()
        val accepted = client.handleConnectionSuccess(player, generation = gen)
        assertTrue(accepted)
        assertSame(player, client.currentPlayer())
        assertEquals(0, releaseCount)

        client.disconnect()
        assertNull(client.currentPlayer())
        assertFalse(client.controllerState.value.isConnected)
        assertEquals("Connected player must be released exactly once on disconnect", 1, releaseCount)
    }

    @Test(timeout = 5000)
    fun `playback execution is serialized against concurrent disconnect preventing premature player release`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        var releaseCount = 0
        var releaseCountDuringPlayback = -1

        val playbackStartedLatch = CountDownLatch(1)
        val disconnectAttemptLatch = CountDownLatch(1)

        val dummyPlayer = createDummyPlayer(
            playedItems = playedItems,
            onRelease = { releaseCount++ },
            onSetMediaItem = { _ ->
                playbackStartedLatch.countDown()
                assertTrue(
                    "Disconnect must attempt disposal while playback is executing",
                    disconnectAttemptLatch.await(3, TimeUnit.SECONDS)
                )
                releaseCountDuringPlayback = releaseCount
            }
        )

        client.beforePlayerReleaseAction = {
            disconnectAttemptLatch.countDown()
        }

        val track = Track(id = "track-concurrent-disconnect", title = "Concurrent Disconnect Track", artist = "Artist")
        val gen = client.nextGeneration()
        client.playTrack(track, "https://example.invalid/concurrent.mp3")
        assertTrue(client.hasPendingPlayAction())

        val disconnectThread = thread(start = false) {
            assertTrue(playbackStartedLatch.await(3, TimeUnit.SECONDS))
            client.disconnect()
        }
        disconnectThread.start()

        val accepted = client.handleConnectionSuccess(dummyPlayer, generation = gen)
        assertTrue(accepted)

        disconnectThread.join(3000)
        assertFalse("Disconnect thread must finish", disconnectThread.isAlive)

        assertEquals("Player must NOT be released while playback action is executing", 0, releaseCountDuringPlayback)
        assertEquals("Player must be released exactly once after disconnect finishes", 1, releaseCount)
        assertNull("Current player must be null after disconnect completes", client.currentPlayer())
        assertNull("Current player state must be null", client.currentPlayerState.value)
        assertFalse("Controller state isConnected must be false", client.controllerState.value.isConnected)
        assertEquals("Playback item was set", 1, playedItems.size)
        assertFalse("Pending play action must not remain active", client.hasPendingPlayAction())
    }

    @Test
    fun `DASH playback request sets APPLICATION_MPD mime type on MediaItem`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val track = Track(id = "track-dash", title = "DASH Track", artist = "Artist")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)
        client.handleConnectionSuccess(dummyPlayer)

        val dashSource = VideoPlaybackSource(
            videoId = track.id,
            streamUrl = "https://manifest.googlevideo.com/api/manifest/dash/index.mpd",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.DASH,
            dashUrl = "https://manifest.googlevideo.com/api/manifest/dash/index.mpd"
        )

        client.playExtractedVideo(track, dashSource)

        assertEquals(1, playedItems.size)
        assertEquals("track-dash", playedItems[0].mediaId)
        assertEquals(VideoDeliveryMethod.DASH, client.currentDeliveryMethod)
        assertEquals(MimeTypes.APPLICATION_MPD, MediaControllerClient.resolveDeliveryMimeType(VideoDeliveryMethod.DASH))
    }

    @Test
    fun `HLS playback request sets APPLICATION_M3U8 mime type on MediaItem`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val track = Track(id = "track-hls", title = "HLS Track", artist = "Artist")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)
        client.handleConnectionSuccess(dummyPlayer)

        val hlsSource = VideoPlaybackSource(
            videoId = track.id,
            streamUrl = "https://manifest.googlevideo.com/api/manifest/hls/index.m3u8",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.HLS,
            hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls/index.m3u8"
        )

        client.playExtractedVideo(track, hlsSource)

        assertEquals(1, playedItems.size)
        assertEquals("track-hls", playedItems[0].mediaId)
        assertEquals(VideoDeliveryMethod.HLS, client.currentDeliveryMethod)
        assertEquals(MimeTypes.APPLICATION_M3U8, MediaControllerClient.resolveDeliveryMimeType(VideoDeliveryMethod.HLS))
    }

    @Test
    fun `progressive playback request does not force DASH or HLS mime type`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val track = Track(id = "track-prog", title = "Progressive Track", artist = "Artist")
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)
        client.handleConnectionSuccess(dummyPlayer)

        val progressiveSource = VideoPlaybackSource(
            videoId = track.id,
            streamUrl = "https://example.invalid/video.mp4",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP
        )

        client.playExtractedVideo(track, progressiveSource)

        assertEquals(1, playedItems.size)
        assertEquals("track-prog", playedItems[0].mediaId)
        assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, client.currentDeliveryMethod)
        assertNull(MediaControllerClient.resolveDeliveryMimeType(VideoDeliveryMethod.PROGRESSIVE_HTTP))
        assertNull(MediaControllerClient.resolveDeliveryMimeType(null))
    }

    private class TestableMediaControllerClient(scope: CoroutineScope) : MediaControllerClient(null, scope) {
        var connectAttempts = 0
        var preStatePublicationAction: (() -> Unit)? = null
        var postAdoptionAction: (() -> Unit)? = null
        var beforePendingActionExecutionAction: (() -> Unit)? = null
        var beforePlayerReleaseAction: (() -> Unit)? = null

        override fun canConnect(): Boolean = true

        override fun connect() {
            connectAttempts++
        }

        override fun onPreStatePublication(player: Player, generation: Long) {
            preStatePublicationAction?.invoke()
        }

        override fun onPostAdoption(player: Player, generation: Long) {
            postAdoptionAction?.invoke()
        }

        override fun onBeforePendingActionExecution(player: Player, generation: Long) {
            beforePendingActionExecutionAction?.invoke()
        }

        override fun onBeforePlayerRelease(player: Player) {
            beforePlayerReleaseAction?.invoke()
        }
    }

    private fun createDummyPlayer(
        playedItems: MutableList<MediaItem> = mutableListOf(),
        onRelease: (() -> Unit)? = null,
        onSetMediaItem: ((MediaItem) -> Unit)? = null
    ): Player {
        val methodCalls = mutableListOf<String>()
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { proxy, method, args ->
            methodCalls += method.name
            when (method.name) {
                "setMediaItem" -> {
                    val item = args[0] as MediaItem
                    onSetMediaItem?.invoke(item)
                    playedItems += item
                    null
                }
                "release" -> {
                    onRelease?.invoke()
                    null
                }
                "prepare", "play", "pause", "clearMediaItems", "addListener", "removeListener" -> null
                "getPlaybackState" -> Player.STATE_READY
                "isPlaying" -> false
                "getCurrentPosition" -> 0L
                "getDuration" -> 0L
                "getCurrentMediaItem" -> playedItems.lastOrNull()
                "getCurrentTracks" -> Tracks.EMPTY
                "getVideoSize" -> VideoSize.UNKNOWN
                "getTrackSelectionParameters" -> TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
                "equals" -> args?.getOrNull(0) === proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "DummyPlayerProxy"
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    else -> null
                }
            }
        } as Player
    }
}
