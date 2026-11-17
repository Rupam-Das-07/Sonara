package com.example.sonara.playback.service

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.sonara.domain.model.Track
import com.example.sonara.playback.controller.PlaybackQueueEngine
import com.example.sonara.playback.controller.PlaybackRouteCoordinator
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SonaraForwardingPlayerTest {

    private fun createFakePlayer(
        underlyingAvailableCommands: Set<Int> = emptySet(),
        hasNextMediaItemValue: Boolean = false,
        currentMediaItemValue: androidx.media3.common.MediaItem? = null,
        onPlayerSeekToNext: (() -> Unit)? = null,
        onPlayerSeekToNextMediaItem: (() -> Unit)? = null
    ): Player {
        val defaultCommands = Player.Commands.Builder().build()
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getAvailableCommands" -> defaultCommands
                "getMediaMetadata" -> androidx.media3.common.MediaMetadata.EMPTY
                "getCurrentMediaItem" -> currentMediaItemValue
                "isCommandAvailable" -> {
                    val cmd = args[0] as Int
                    underlyingAvailableCommands.contains(cmd)
                }
                "hasNextMediaItem" -> hasNextMediaItemValue
                "seekToNext" -> {
                    onPlayerSeekToNext?.invoke()
                    null
                }
                "seekToNextMediaItem" -> {
                    onPlayerSeekToNextMediaItem?.invoke()
                    null
                }
                "addListener", "removeListener" -> null
                "equals" -> false
                "hashCode" -> 0
                "toString" -> "FakePlayer"
                else -> {
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Float::class.javaPrimitiveType -> 0f
                        else -> null
                    }
                }
            }
        } as Player
    }

    private val track1 = Track(id = "v1", title = "Tum Hi Ho", artist = "Arijit Singh")
    private val track2 = Track(id = "v2", title = "Kesariya", artist = "Arijit Singh")

    @Test
    fun `transport controls PREVIOUS is always available even on empty queue`() {
        val fakePlayer = createFakePlayer()
        val queueEngine = PlaybackQueueEngine() // empty

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { queueEngine },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
    }

    @Test
    fun `NEXT command is available when queue can advance`() {
        val fakePlayer = createFakePlayer()
        val queueEngine = PlaybackQueueEngine()
        queueEngine.setContext(track1, listOf(track1, track2))

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { queueEngine },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        assertTrue(queueEngine.canAdvance())
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    }

    @Test
    fun `NEXT command is unavailable when queue cannot advance`() {
        val fakePlayer = createFakePlayer()
        val queueEngine = PlaybackQueueEngine()
        // empty queue, no track, no repeat -> canAdvance() is false

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { queueEngine },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        assertFalse(queueEngine.canAdvance())
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    }

    @Test
    fun `isCommandAvailable delegates to underlying player for unrelated commands`() {
        val fakePlayer = createFakePlayer(underlyingAvailableCommands = setOf(Player.COMMAND_PLAY_PAUSE))
        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_STOP))
    }

    @Test
    fun `seekToNext and seekToNextMediaItem delegate to onSeekToNext callback when hasNextMediaItem is false`() {
        val fakePlayer = createFakePlayer(hasNextMediaItemValue = false)
        val nextCount = AtomicInteger(0)

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = { nextCount.incrementAndGet() },
            onSeekToPrevious = {}
        )

        forwardingPlayer.seekToNext()
        assertEquals(1, nextCount.get())

        forwardingPlayer.seekToNextMediaItem()
        assertEquals(2, nextCount.get())
    }

    @Test
    fun `seekToNext and seekToNextMediaItem delegate to underlying player when hasNextMediaItem is true`() {
        val underlyingNextCount = AtomicInteger(0)
        val underlyingNextMediaItemCount = AtomicInteger(0)
        val fallbackNextCount = AtomicInteger(0)

        val fakePlayer = createFakePlayer(
            hasNextMediaItemValue = true,
            onPlayerSeekToNext = { underlyingNextCount.incrementAndGet() },
            onPlayerSeekToNextMediaItem = { underlyingNextMediaItemCount.incrementAndGet() }
        )

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = { fallbackNextCount.incrementAndGet() },
            onSeekToPrevious = {}
        )

        forwardingPlayer.seekToNext()
        assertEquals(1, underlyingNextCount.get())
        assertEquals(0, fallbackNextCount.get())

        forwardingPlayer.seekToNextMediaItem()
        assertEquals(1, underlyingNextMediaItemCount.get())
        assertEquals(0, fallbackNextCount.get())
    }

    @Test
    fun `seekToPrevious and seekToPreviousMediaItem delegate to onSeekToPrevious callback`() {
        val fakePlayer = createFakePlayer()
        val prevCount = AtomicInteger(0)

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = {},
            onSeekToPrevious = { prevCount.incrementAndGet() }
        )

        forwardingPlayer.seekToPrevious()
        assertEquals(1, prevCount.get())

        forwardingPlayer.seekToPreviousMediaItem()
        assertEquals(2, prevCount.get())
    }

    @Test
    fun `invalidateAvailableCommands notifies registered Player listeners`() {
        val fakePlayer = createFakePlayer()
        val queueEngine = PlaybackQueueEngine()

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { queueEngine },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        val listenerNotified = AtomicBoolean(false)

        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                listenerNotified.set(true)
            }
        }

        forwardingPlayer.addListener(listener)

        // Queue is initially empty -> canAdvance() is false
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

        // Set context -> canAdvance() becomes true
        queueEngine.setContext(track1, listOf(track1, track2))
        assertTrue(queueEngine.canAdvance())

        forwardingPlayer.invalidateAvailableCommands()

        assertTrue(listenerNotified.get())
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun `listener unregistration removes listener from notifications`() {
        val fakePlayer = createFakePlayer()
        val queueEngine = PlaybackQueueEngine()

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { queueEngine },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        val notificationCount = AtomicInteger(0)
        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                notificationCount.incrementAndGet()
            }
        }

        forwardingPlayer.addListener(listener)
        forwardingPlayer.invalidateAvailableCommands()
        assertEquals(1, notificationCount.get())

        forwardingPlayer.removeListener(listener)
        forwardingPlayer.invalidateAvailableCommands()
        assertEquals(1, notificationCount.get()) // No second notification
    }

    @Test
    fun `setArtworkDataForMediaId injects artworkData and notifies listener`() {
        val testBytes = byteArrayOf(1, 2, 3, 4)
        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setMediaId("track_123")
            .build()
        val fakePlayer = createFakePlayer(currentMediaItemValue = mediaItem)

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        var notifiedMetadata: androidx.media3.common.MediaMetadata? = null
        forwardingPlayer.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                notifiedMetadata = mediaMetadata
            }
        })

        forwardingPlayer.setArtworkDataForMediaId("track_123", testBytes)

        val metadata = forwardingPlayer.mediaMetadata
        org.junit.Assert.assertNotNull(metadata.artworkData)
        org.junit.Assert.assertArrayEquals(testBytes, metadata.artworkData)
        org.junit.Assert.assertNotNull(notifiedMetadata)
        org.junit.Assert.assertArrayEquals(testBytes, notifiedMetadata?.artworkData)
    }

    @Test
    fun `artwork is not exposed when activeMediaId does not match currentMediaItem`() {
        val testBytes = byteArrayOf(5, 6, 7, 8)
        val mediaItem = androidx.media3.common.MediaItem.Builder()
            .setMediaId("track_different")
            .build()
        val fakePlayer = createFakePlayer(currentMediaItemValue = mediaItem)

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = {},
            onSeekToPrevious = {}
        )

        forwardingPlayer.setArtworkDataForMediaId("track_123", testBytes)

        val metadata = forwardingPlayer.mediaMetadata
        org.junit.Assert.assertNull(metadata.artworkData)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Video Playback Route Coordination Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `video active and video queue can advance reports NEXT available even if audio queue is empty`() {
        val fakePlayer = createFakePlayer()
        val audioQueue = PlaybackQueueEngine() // empty, canAdvance() is false
        val routeCoordinator = PlaybackRouteCoordinator()
        routeCoordinator.registerVideoDelegate(object : PlaybackRouteCoordinator.VideoPlaybackDelegate {
            override val isVideoActive: Boolean = true
            override fun canAdvance(): Boolean = true
            override fun onSeekToNext() {}
            override fun onSeekToPrevious() {}
            override fun currentTrack(): Track? = track1
        })

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { audioQueue },
            onSeekToNext = {},
            onSeekToPrevious = {},
            routeCoordinator = routeCoordinator
        )

        assertFalse(audioQueue.canAdvance())
        assertTrue(routeCoordinator.isVideoActive)
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    }

    @Test
    fun `video active and video queue cannot advance reports NEXT unavailable`() {
        val fakePlayer = createFakePlayer()
        val audioQueue = PlaybackQueueEngine()
        audioQueue.setContext(track1, listOf(track1, track2)) // audio CAN advance
        val routeCoordinator = PlaybackRouteCoordinator()
        routeCoordinator.registerVideoDelegate(object : PlaybackRouteCoordinator.VideoPlaybackDelegate {
            override val isVideoActive: Boolean = true
            override fun canAdvance(): Boolean = false // video CANNOT advance
            override fun onSeekToNext() {}
            override fun onSeekToPrevious() {}
            override fun currentTrack(): Track? = track1
        })

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { audioQueue },
            onSeekToNext = {},
            onSeekToPrevious = {},
            routeCoordinator = routeCoordinator
        )

        assertTrue(audioQueue.canAdvance())
        assertTrue(routeCoordinator.isVideoActive)
        // Video authority takes precedence:
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
    }

    @Test
    fun `video Next delegates only to video callback and does not touch audio callback`() {
        val fakePlayer = createFakePlayer(hasNextMediaItemValue = false)
        val audioNextCount = AtomicInteger(0)
        val videoNextCount = AtomicInteger(0)
        val routeCoordinator = PlaybackRouteCoordinator()
        routeCoordinator.registerVideoDelegate(object : PlaybackRouteCoordinator.VideoPlaybackDelegate {
            override val isVideoActive: Boolean = true
            override fun canAdvance(): Boolean = true
            override fun onSeekToNext() { videoNextCount.incrementAndGet() }
            override fun onSeekToPrevious() {}
            override fun currentTrack(): Track? = track1
        })

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = { audioNextCount.incrementAndGet() },
            onSeekToPrevious = {},
            routeCoordinator = routeCoordinator
        )

        forwardingPlayer.seekToNext()
        assertEquals(1, videoNextCount.get())
        assertEquals(0, audioNextCount.get())

        forwardingPlayer.seekToNextMediaItem()
        assertEquals(2, videoNextCount.get())
        assertEquals(0, audioNextCount.get())
    }

    @Test
    fun `video Previous delegates only to video callback and does not touch audio callback`() {
        val fakePlayer = createFakePlayer()
        val audioPrevCount = AtomicInteger(0)
        val videoPrevCount = AtomicInteger(0)
        val routeCoordinator = PlaybackRouteCoordinator()
        routeCoordinator.registerVideoDelegate(object : PlaybackRouteCoordinator.VideoPlaybackDelegate {
            override val isVideoActive: Boolean = true
            override fun canAdvance(): Boolean = true
            override fun onSeekToNext() {}
            override fun onSeekToPrevious() { videoPrevCount.incrementAndGet() }
            override fun currentTrack(): Track? = track1
        })

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = {},
            onSeekToPrevious = { audioPrevCount.incrementAndGet() },
            routeCoordinator = routeCoordinator
        )

        forwardingPlayer.seekToPrevious()
        assertEquals(1, videoPrevCount.get())
        assertEquals(0, audioPrevCount.get())

        forwardingPlayer.seekToPreviousMediaItem()
        assertEquals(2, videoPrevCount.get())
        assertEquals(0, audioPrevCount.get())
    }

    @Test
    fun `audio Next and Previous continue using existing audio callbacks when video is inactive`() {
        val fakePlayer = createFakePlayer(hasNextMediaItemValue = false)
        val audioNextCount = AtomicInteger(0)
        val audioPrevCount = AtomicInteger(0)
        val videoNextCount = AtomicInteger(0)
        val videoPrevCount = AtomicInteger(0)
        val routeCoordinator = PlaybackRouteCoordinator()
        routeCoordinator.registerVideoDelegate(object : PlaybackRouteCoordinator.VideoPlaybackDelegate {
            override val isVideoActive: Boolean = false
            override fun canAdvance(): Boolean = true
            override fun onSeekToNext() { videoNextCount.incrementAndGet() }
            override fun onSeekToPrevious() { videoPrevCount.incrementAndGet() }
            override fun currentTrack(): Track? = null
        })

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = { audioNextCount.incrementAndGet() },
            onSeekToPrevious = { audioPrevCount.incrementAndGet() },
            routeCoordinator = routeCoordinator
        )

        forwardingPlayer.seekToNext()
        forwardingPlayer.seekToPrevious()
        assertEquals(1, audioNextCount.get())
        assertEquals(1, audioPrevCount.get())
        assertEquals(0, videoNextCount.get())
        assertEquals(0, videoPrevCount.get())
    }

    @Test
    fun `isCommandAvailable accurately tracks video and audio state transitions`() {
        val fakePlayer = createFakePlayer()
        val audioQueue = PlaybackQueueEngine()
        val routeCoordinator = PlaybackRouteCoordinator()
        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { audioQueue },
            onSeekToNext = {},
            onSeekToPrevious = {},
            routeCoordinator = routeCoordinator
        )

        // State 1: audio empty -> next false, prev true
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

        // State 2: audio can advance -> next true, prev true
        audioQueue.setContext(track1, listOf(track1, track2))
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

        // State 3: video active, cannot advance -> video takes priority, next false
        var videoCanAdv = false
        val delegate = object : PlaybackRouteCoordinator.VideoPlaybackDelegate {
            override val isVideoActive: Boolean = true
            override fun canAdvance(): Boolean = videoCanAdv
            override fun onSeekToNext() {}
            override fun onSeekToPrevious() {}
            override fun currentTrack(): Track? = track1
        }
        routeCoordinator.registerVideoDelegate(delegate)
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))

        // State 4: video active, can advance -> next true, prev true
        videoCanAdv = true
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
    }

    @Test
    fun `route transition invalidates available commands and notifies listeners`() {
        val fakePlayer = createFakePlayer()
        val routeCoordinator = PlaybackRouteCoordinator()
        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = {},
            onSeekToPrevious = {},
            routeCoordinator = routeCoordinator
        )

        var invalidateCalls = 0
        forwardingPlayer.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                invalidateCalls++
            }
        })
        routeCoordinator.onRouteChanged = {
            forwardingPlayer.invalidateAvailableCommands()
        }

        val delegate = object : PlaybackRouteCoordinator.VideoPlaybackDelegate {
            override val isVideoActive: Boolean = true
            override fun canAdvance(): Boolean = true
            override fun onSeekToNext() {}
            override fun onSeekToPrevious() {}
            override fun currentTrack(): Track? = track1
        }

        routeCoordinator.registerVideoDelegate(delegate)
        assertEquals(1, invalidateCalls)

        routeCoordinator.notifyRouteOrCommandsChanged()
        assertEquals(2, invalidateCalls)

        routeCoordinator.unregisterVideoDelegate(delegate)
        assertEquals(3, invalidateCalls)
    }

    @Test
    fun `missing or stale video delegate fails safely`() {
        val fakePlayer = createFakePlayer(hasNextMediaItemValue = false)
        val routeCoordinator = PlaybackRouteCoordinator()
        val audioNextCount = AtomicInteger(0)
        val audioPrevCount = AtomicInteger(0)

        val forwardingPlayer = SonaraForwardingPlayer(
            player = fakePlayer,
            queueEngineProvider = { null },
            onSeekToNext = { audioNextCount.incrementAndGet() },
            onSeekToPrevious = { audioPrevCount.incrementAndGet() },
            routeCoordinator = routeCoordinator
        )

        // No delegate registered
        assertFalse(routeCoordinator.isVideoActive)
        assertFalse(routeCoordinator.canVideoAdvance())
        assertTrue(forwardingPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        forwardingPlayer.seekToNext()
        assertEquals(1, audioNextCount.get())

        forwardingPlayer.seekToPrevious()
        assertEquals(1, audioPrevCount.get())
    }
}
