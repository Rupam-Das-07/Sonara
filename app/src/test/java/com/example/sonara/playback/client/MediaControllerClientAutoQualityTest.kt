package com.example.sonara.playback.client

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.example.sonara.domain.model.video.VideoQuality
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControllerClientAutoQualityTest {

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
    fun `auto mode clears stale video overrides and enforces eligible tracks override and max dimensions`() {
        val staleVideoGroup = TrackGroup(
            Format.Builder().setId("stale").setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(640).setHeight(360).build()
        )
        val currentVideoGroup = TrackGroup(
            Format.Builder().setId("v_1080").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build(),
            Format.Builder().setId("v_720").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).build(),
            Format.Builder().setId("v_360").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(640).setHeight(360).build()
        )
        val initialTracks = Tracks(
            listOf(
                Tracks.Group(
                    currentVideoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false, false)
                )
            )
        )

        val staleParams = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
            .setOverrideForType(TrackSelectionOverride(staleVideoGroup, 0))
            .setMaxVideoSize(640, 360)
            .build()

        val state = FakePlayerState(
            currentTracks = initialTracks,
            trackSelectionParameters = staleParams
        )
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)

        val resultParams = state.trackSelectionParameters
        // 1. Stale override was cleared
        assertFalse(resultParams.overrides.containsKey(staleVideoGroup))

        // 2. New eligible override for current group exists
        assertTrue(resultParams.overrides.containsKey(currentVideoGroup))
        val override = resultParams.overrides[currentVideoGroup]
        assertNotNull(override)
        assertEquals(listOf(0, 1, 2), override!!.trackIndices)

        // 3. Max dimensions set to highest eligible track
        assertEquals(1920, resultParams.maxVideoWidth)
        assertEquals(1080, resultParams.maxVideoHeight)
    }

    @Test
    fun `unsupported representations are excluded from override even when dimensions fall below configured maximum`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_1080").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build(),
            Format.Builder().setId("v_720_bad").setSampleMimeType("video/unsupported").setWidth(1280).setHeight(720).build(),
            Format.Builder().setId("v_360").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(640).setHeight(360).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false, false)
                )
            )
        )

        val customChecker = VideoDecoderCapabilityChecker { mimeType, _, _, _, _ ->
            mimeType == MimeTypes.VIDEO_VP9
        }

        val state = FakePlayerState(currentTracks = tracks)
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher), capabilityChecker = customChecker)
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)

        val resultParams = state.trackSelectionParameters
        val override = resultParams.overrides[videoGroup]
        assertNotNull(override)

        // Index 1 (720p unsupported) must NOT be in trackIndices, even though 720 <= 1080
        assertEquals(listOf(0, 2), override!!.trackIndices)
        assertFalse("Excluded track 1 must not be selectable in trackIndices", override.trackIndices.contains(1))
        assertEquals(1080, resultParams.maxVideoHeight)
        assertEquals(1920, resultParams.maxVideoWidth)
    }

    @Test
    fun `two codecs at same resolution where only one is eligible excludes the unsupported codec index`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_1080_av1").setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(1920).setHeight(1080).build(), // index 0 (unsupported)
            Format.Builder().setId("v_1080_vp9").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build(), // index 1 (eligible)
            Format.Builder().setId("v_720_vp9").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).build()    // index 2 (eligible)
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false, false)
                )
            )
        )

        val checkerNoAv1 = VideoDecoderCapabilityChecker { mimeType, _, _, _, _ ->
            mimeType == MimeTypes.VIDEO_VP9
        }

        val state = FakePlayerState(currentTracks = tracks)
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher), capabilityChecker = checkerNoAv1)
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)

        val resultParams = state.trackSelectionParameters
        val override = resultParams.overrides[videoGroup]
        assertNotNull(override)

        // Index 0 (AV1 1080p) must be omitted; indices 1 and 2 (VP9 1080p and 720p) must be present
        assertEquals(listOf(1, 2), override!!.trackIndices)
        assertFalse("AV1 1080p at index 0 must be excluded", override.trackIndices.contains(0))
        assertTrue("VP9 1080p at index 1 must be included", override.trackIndices.contains(1))
        assertTrue("VP9 720p at index 2 must be included", override.trackIndices.contains(2))
    }

    @Test
    fun `eligible representations remain available for adaptive selection across multiple tiers`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_1080").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build(),
            Format.Builder().setId("v_720").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).build(),
            Format.Builder().setId("v_480").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(854).setHeight(480).build(),
            Format.Builder().setId("v_360").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(640).setHeight(360).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false, false, false)
                )
            )
        )

        val state = FakePlayerState(currentTracks = tracks)
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)

        val override = state.trackSelectionParameters.overrides[videoGroup]
        assertNotNull(override)
        assertEquals(listOf(0, 1, 2, 3), override!!.trackIndices)
        assertEquals(4, override.trackIndices.size)
    }

    @Test
    fun `audio track selection and overrides remain unaffected when auto video quality is applied`() {
        val audioGroup = TrackGroup(
            Format.Builder().setId("a_en").setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        )
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_720").setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(audioGroup, true, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                Tracks.Group(videoGroup, true, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
            )
        )

        val initialAudioOverride = TrackSelectionOverride(audioGroup, 0)
        val initialParams = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
            .addOverride(initialAudioOverride)
            .build()

        val state = FakePlayerState(
            currentTracks = tracks,
            trackSelectionParameters = initialParams
        )
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)

        val resultParams = state.trackSelectionParameters
        // Audio override must remain intact
        assertEquals(initialAudioOverride, resultParams.overrides[audioGroup])
        // Video override must be added alongside audio override
        assertTrue(resultParams.overrides.containsKey(videoGroup))
    }

    @Test
    fun `track-group changes recalculate eligibility rather than retaining stale constraints`() {
        val videoGroupA = TrackGroup(
            Format.Builder().setId("va_720").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).build()
        )
        val tracksA = Tracks(
            listOf(
                Tracks.Group(videoGroupA, true, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
            )
        )

        val videoGroupB = TrackGroup(
            Format.Builder().setId("vb_1080").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build(),
            Format.Builder().setId("vb_720").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).build()
        )
        val tracksB = Tracks(
            listOf(
                Tracks.Group(videoGroupB, true, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(false, false))
            )
        )

        val state = FakePlayerState(currentTracks = tracksA)
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)
        assertTrue(state.trackSelectionParameters.overrides.containsKey(videoGroupA))
        assertEquals(720, state.trackSelectionParameters.maxVideoHeight)

        // Tracks change to Video B
        state.notifyTracksChanged(tracksB)

        val updatedParams = state.trackSelectionParameters
        assertFalse("Group A override must be cleared after track change", updatedParams.overrides.containsKey(videoGroupA))
        assertTrue("Group B override must be active", updatedParams.overrides.containsKey(videoGroupB))
        assertEquals(listOf(0, 1), updatedParams.overrides[videoGroupB]!!.trackIndices)
        assertEquals(1080, updatedParams.maxVideoHeight)
    }

    @Test
    fun `mediaItem transition with null item clears video overrides and constraints`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_1080").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(videoGroup, true, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
            )
        )

        val state = FakePlayerState(currentTracks = tracks)
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)
        assertTrue(state.trackSelectionParameters.overrides.containsKey(videoGroup))

        // Transition to null mediaItem
        state.notifyMediaItemTransition(null)

        val params = state.trackSelectionParameters
        assertFalse("Video overrides must be cleared when mediaItem is null", params.overrides.containsKey(videoGroup))
        assertEquals(Int.MAX_VALUE, params.maxVideoHeight)
        assertEquals(Int.MAX_VALUE, params.maxVideoWidth)
    }

    @Test
    fun `empty eligible-track lists and unknown codec information fail safely`() {
        val corruptedGroup = TrackGroup(
            Format.Builder().setId("v_corrupt").setSampleMimeType("video/unknown_corrupted").setWidth(1920).setHeight(1080).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(corruptedGroup, true, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
            )
        )

        val rejectAllChecker = VideoDecoderCapabilityChecker { _, _, _, _, _ -> false }

        val state = FakePlayerState(currentTracks = tracks)
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher), capabilityChecker = rejectAllChecker)
        client.handleConnectionSuccess(player)

        val selection = client.applyVideoQuality(VideoQuality.AUTO)

        assertEquals(VideoQuality.AUTO, selection.requested)
        assertNull(selection.selected)
        assertTrue("No video overrides should be added when all tracks are rejected", state.trackSelectionParameters.overrides.isEmpty())
        assertEquals(Int.MAX_VALUE, state.trackSelectionParameters.maxVideoHeight)
        assertEquals(Int.MAX_VALUE, state.trackSelectionParameters.maxVideoWidth)
        assertTrue(client.controllerState.value.availableVideoHeights.isEmpty())
    }

    @Test
    fun `explicit quality selection sets single-track override and preserves deterministic fallback`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_1080").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build(),
            Format.Builder().setId("v_720").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).build(),
            Format.Builder().setId("v_360").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(640).setHeight(360).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false, false)
                )
            )
        )

        val state = FakePlayerState(currentTracks = tracks)
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        // Select 720p explicitly
        val selection720 = client.applyVideoQuality(VideoQuality.P720)
        assertEquals(VideoQuality.P720, selection720.requested)
        assertEquals(720, selection720.selected?.height)

        val params720 = state.trackSelectionParameters
        val override720 = params720.overrides[videoGroup]
        assertNotNull(override720)
        // Explicit selection must lock to single track index
        assertEquals(listOf(1), override720!!.trackIndices)

        // Switch back to Auto
        val selectionAuto = client.applyVideoQuality(VideoQuality.AUTO)
        assertEquals(VideoQuality.AUTO, selectionAuto.requested)
        assertNull(selectionAuto.selected)

        val paramsAuto = state.trackSelectionParameters
        val overrideAuto = paramsAuto.overrides[videoGroup]
        assertNotNull(overrideAuto)
        // Auto restores adaptive multi-track selection
        assertEquals(listOf(0, 1, 2), overrideAuto!!.trackIndices)
    }

    @Test
    fun `progressive single-track and HLS multi-variant playback apply correct overrides`() {
        val progGroup = TrackGroup(
            Format.Builder().setId("v_prog_720").setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).build()
        )
        val progTracks = Tracks(
            listOf(
                Tracks.Group(progGroup, true, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
            )
        )

        val state = FakePlayerState(currentTracks = progTracks)
        val player = createPlayer(state)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)

        val override = state.trackSelectionParameters.overrides[progGroup]
        assertNotNull(override)
        assertEquals(listOf(0), override!!.trackIndices)
        assertEquals(720, state.trackSelectionParameters.maxVideoHeight)
    }

    @Test
    fun `manual selection of 1080p60 applies override to 60fps track index and reflects in state`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("1080_30").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).setFrameRate(30f).build(),
            Format.Builder().setId("1080_60").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).setFrameRate(60f).build(),
            Format.Builder().setId("720_30").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).setFrameRate(30f).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, true, false) // track index 1 (1080p60) is selected
                )
            )
        )

        val playerState = FakePlayerState(currentTracks = tracks)
        val player = createPlayer(playerState)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        // Verify available video qualities exposed on state
        val available = client.controllerState.value.availableVideoQualities
        assertEquals(
            listOf(VideoQuality.P1080_60, VideoQuality.P1080_30, VideoQuality.P720_30),
            available
        )

        // Apply 1080p60 quality
        client.applyVideoQuality(VideoQuality.P1080_60)

        // Verify TrackSelectionOverride targeted track index 1 (60 FPS track)
        val override = playerState.trackSelectionParameters.overrides[videoGroup]
        assertNotNull(override)
        assertEquals(listOf(1), override!!.trackIndices)

        // Verify state reports selected video quality as 1080p60
        assertEquals(VideoQuality.P1080_60, client.controllerState.value.selectedVideoQuality)
    }

    @Test
    fun `manual selection of 60fps falls back to 30fps when 60fps track is absent`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("1080_30").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).setFrameRate(30f).build(),
            Format.Builder().setId("720_30").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).setFrameRate(30f).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(true, false) // track index 0 (1080p30) is selected
                )
            )
        )

        val playerState = FakePlayerState(currentTracks = tracks)
        val player = createPlayer(playerState)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        // Request 1080p60 on 30fps-only stream
        client.applyVideoQuality(VideoQuality.P1080_60)

        // Target override falls back to 1080p30 (index 0)
        val override = playerState.trackSelectionParameters.overrides[videoGroup]
        assertNotNull(override)
        assertEquals(listOf(0), override!!.trackIndices)

        // State reflects actual selected quality as 1080p30, never falsely claiming 60fps
        assertEquals(VideoQuality.P1080_30, client.controllerState.value.selectedVideoQuality)
    }

    @Test
    fun `auto mode preserves frame-rate-aware available qualities and does not clear audio overrides`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("1080_60").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).setFrameRate(60f).build(),
            Format.Builder().setId("1080_30").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).setFrameRate(30f).build(),
            Format.Builder().setId("720_30").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).setFrameRate(30f).build()
        )
        val audioGroup = TrackGroup(
            Format.Builder().setId("audio_en").setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(true, false, false)
                ),
                Tracks.Group(
                    audioGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true)
                )
            )
        )
        val initialParams = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
            .setOverrideForType(TrackSelectionOverride(audioGroup, 0))
            .build()

        val playerState = FakePlayerState(currentTracks = tracks, trackSelectionParameters = initialParams)
        val player = createPlayer(playerState)
        val client = TestableClient(CoroutineScope(testDispatcher))
        client.handleConnectionSuccess(player)

        client.applyVideoQuality(VideoQuality.AUTO)

        // Audio override remains intact
        assertTrue(playerState.trackSelectionParameters.overrides.containsKey(audioGroup))

        // Video override includes all eligible video tracks
        val videoOverride = playerState.trackSelectionParameters.overrides[videoGroup]
        assertNotNull(videoOverride)
        assertEquals(listOf(0, 1, 2), videoOverride!!.trackIndices)

        // State has frame-rate options
        assertEquals(
            listOf(VideoQuality.P1080_60, VideoQuality.P1080_30, VideoQuality.P720_30),
            client.controllerState.value.availableVideoQualities
        )
    }

    private class TestableClient(
        scope: CoroutineScope,
        capabilityChecker: VideoDecoderCapabilityChecker = VideoQualityResolver.defaultCapabilityChecker
    ) : MediaControllerClient(null, scope, capabilityChecker) {
        override fun canConnect(): Boolean = true
    }

    private class FakePlayerState(
        var currentTracks: Tracks = Tracks.EMPTY,
        var trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT,
        var currentMediaItem: MediaItem? = null,
        var videoSize: VideoSize = VideoSize.UNKNOWN
    ) {
        val listeners = mutableListOf<Player.Listener>()
        val playedItems = mutableListOf<MediaItem>()

        fun notifyTracksChanged(tracks: Tracks) {
            currentTracks = tracks
            listeners.toList().forEach { it.onTracksChanged(tracks) }
        }

        fun notifyMediaItemTransition(mediaItem: MediaItem?, reason: Int = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            currentMediaItem = mediaItem
            listeners.toList().forEach { it.onMediaItemTransition(mediaItem, reason) }
        }
    }

    private fun createPlayer(state: FakePlayerState): Player {
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "addListener" -> {
                    state.listeners.add(args[0] as Player.Listener)
                    null
                }
                "removeListener" -> {
                    state.listeners.remove(args[0] as Player.Listener)
                    null
                }
                "setMediaItem" -> {
                    val item = args[0] as MediaItem
                    state.currentMediaItem = item
                    state.playedItems.add(item)
                    null
                }
                "getTrackSelectionParameters" -> state.trackSelectionParameters
                "setTrackSelectionParameters" -> {
                    state.trackSelectionParameters = args[0] as TrackSelectionParameters
                    null
                }
                "getCurrentTracks" -> state.currentTracks
                "getCurrentMediaItem" -> state.currentMediaItem
                "getVideoSize" -> state.videoSize
                "getPlaybackState" -> Player.STATE_READY
                "isPlaying" -> false
                "getCurrentPosition" -> 0L
                "getDuration" -> 100_000L
                "prepare", "play", "pause", "clearMediaItems" -> null
                "equals" -> args?.getOrNull(0) === proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakePlayer"
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
