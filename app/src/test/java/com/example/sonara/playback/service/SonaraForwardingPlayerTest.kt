package com.example.sonara.playback.service

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.sonara.domain.model.Track
import com.example.sonara.playback.controller.PlaybackQueueEngine
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
}
