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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MediaControllerClientMimeTypeTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class CustomTestUri(private val uriString: String) : android.net.TestUri() {
        override fun toString(): String = uriString
    }

    private class TestableMediaControllerClient(scope: CoroutineScope) : MediaControllerClient(
        context = null,
        scope = scope,
        uriParser = { CustomTestUri(it) }
    ) {
        override fun canConnect(): Boolean = true
        override fun connect() {}
    }

    private fun createDummyPlayer(
        playedItems: MutableList<MediaItem> = mutableListOf()
    ): Player {
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "setMediaItem" -> {
                    val item = args[0] as MediaItem
                    playedItems += item
                    null
                }
                "prepare", "play", "pause", "clearMediaItems", "release", "addListener", "removeListener" -> null
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

    @Test
    fun `playTrack with explicit HLS MIME type sets APPLICATION_M3U8 on MediaItem and requestExtras`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)
        client.handleConnectionSuccess(dummyPlayer)

        val track = Track(id = "sc:12345", title = "SoundCloud Track", artist = "Artist")
        client.playTrack(
            track = track,
            streamUrl = "https://cf-media.sndcdn.com/stream.m3u8",
            generation = 1L,
            mimeType = MimeTypes.APPLICATION_M3U8
        )

        assertEquals(1, playedItems.size)
        val item = playedItems.first()
        assertEquals("sc:12345", item.mediaId)
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
    }

    @Test
    fun `playTrack without MIME type does not set HLS MIME type`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)
        client.handleConnectionSuccess(dummyPlayer)

        val track = Track(id = "sc:progressive", title = "MP3 Track", artist = "Artist")
        client.playTrack(
            track = track,
            streamUrl = "https://cf-media.sndcdn.com/stream.mp3",
            generation = 1L,
            mimeType = null
        )

        assertEquals(1, playedItems.size)
        val item = playedItems.first()
        assertNull("Progressive MP3 must not carry an explicit HLS MIME type", item.localConfiguration?.mimeType)
    }

    @Test
    fun `existing YouTube playTrack invocation preserves null MIME type behavior`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)
        client.handleConnectionSuccess(dummyPlayer)

        val track = Track(id = "youtube-track-1", title = "YouTube Song", artist = "Artist")
        // Invoking existing 3-arg overload
        client.playTrack(track, "https://backend.proxy/stream/youtube-track-1", generation = 2L)

        assertEquals(1, playedItems.size)
        val item = playedItems.first()
        assertNull("Existing YouTube track must not have explicit MIME type", item.localConfiguration?.mimeType)
    }

    @Test
    fun `playExtractedVideo preserves video MIME type resolution for HLS and DASH`() {
        val client = TestableMediaControllerClient(CoroutineScope(testDispatcher))
        val playedItems = mutableListOf<MediaItem>()
        val dummyPlayer = createDummyPlayer(playedItems)
        client.handleConnectionSuccess(dummyPlayer)

        val track = Track(id = "video-hls-1", title = "HLS Video", artist = "Uploader")
        val hlsSource = VideoPlaybackSource(
            videoId = "video-hls-1",
            streamUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant.m3u8",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.HLS
        )
        client.playExtractedVideo(track, hlsSource)

        assertEquals(1, playedItems.size)
        val item = playedItems.first()
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)

        playedItems.clear()
        val dashSource = VideoPlaybackSource(
            videoId = "video-hls-1",
            streamUrl = "https://manifest.googlevideo.com/api/manifest/dash.mpd",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.DASH
        )
        client.playExtractedVideo(track, dashSource)

        assertEquals(1, playedItems.size)
        assertEquals(MimeTypes.APPLICATION_MPD, playedItems.first().localConfiguration?.mimeType)
    }

    @Test
    fun `resolveDeliveryMimeType returns expected Media3 constants`() {
        assertEquals(MimeTypes.APPLICATION_M3U8, MediaControllerClient.resolveDeliveryMimeType(VideoDeliveryMethod.HLS))
        assertEquals(MimeTypes.APPLICATION_MPD, MediaControllerClient.resolveDeliveryMimeType(VideoDeliveryMethod.DASH))
        assertNull(MediaControllerClient.resolveDeliveryMimeType(VideoDeliveryMethod.PROGRESSIVE_HTTP))
        assertNull(MediaControllerClient.resolveDeliveryMimeType(null))
    }
}
