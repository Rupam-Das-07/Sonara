package com.example.sonara.playback.service

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.sonara.playback.controller.PlaybackQueueEngine
import java.util.concurrent.CopyOnWriteArraySet

/**
 * SonaraForwardingPlayer wraps ExoPlayer to guarantee that Sonara's canonical transport
 * controls (PREVIOUS, PLAY/PAUSE, NEXT) remain available in Media3's availableCommands.
 *
 * In standard ExoPlayer with single-item timelines, ExoPlayer automatically clears
 * COMMAND_SEEK_TO_NEXT and COMMAND_SEEK_TO_NEXT_MEDIA_ITEM from available commands,
 * causing DefaultMediaNotificationProvider and Android SystemUI / lock screen to drop
 * the Next transport button.
 *
 * This wrapper:
 * 1. Overrides getAvailableCommands() and isCommandAvailable() to report seek-to-next as available
 *    whenever [PlaybackQueueEngine.canAdvance] returns true.
 * 2. Overrides seekToNext() and seekToNextMediaItem() to delegate to the authoritative advance routine.
 * 3. Overrides seekToPrevious() and seekToPreviousMediaItem() to delegate to the authoritative previous routine.
 * 4. Exposes [invalidateAvailableCommands] so queue mutations immediately notify registered listeners.
 */
@OptIn(UnstableApi::class)
class SonaraForwardingPlayer(
    player: Player,
    private val queueEngineProvider: () -> PlaybackQueueEngine?,
    private val onSeekToNext: () -> Unit,
    private val onSeekToPrevious: () -> Unit
) : ForwardingPlayer(player) {

    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    @Volatile
    private var activeArtworkData: ByteArray? = null

    @Volatile
    private var activeMediaId: String? = null

    fun setArtworkDataForMediaId(mediaId: String, bytes: ByteArray?) {
        activeMediaId = mediaId
        activeArtworkData = bytes
        notifyMediaMetadataChanged()
    }

    override fun getMediaMetadata(): androidx.media3.common.MediaMetadata {
        val base = runCatching { super.getMediaMetadata() }.getOrNull() ?: androidx.media3.common.MediaMetadata.EMPTY
        val bytes = activeArtworkData
        val currentId = runCatching { currentMediaItem?.mediaId }.getOrNull()
        return if (bytes != null && bytes.isNotEmpty() && (activeMediaId == null || currentId == null || activeMediaId == currentId)) {
            base.buildUpon()
                .setArtworkData(bytes, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
        } else {
            base
        }
    }

    fun notifyMediaMetadataChanged() {
        val metadata = mediaMetadata
        for (listener in listeners) {
            try {
                listener.onMediaMetadataChanged(metadata)
            } catch (_: Exception) {}
        }
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners.remove(listener)
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()

        // PREVIOUS is always available (either seeks to start or navigates backstack)
        builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
        builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

        val canAdvance = queueEngineProvider()?.canAdvance() ?: true
        if (canAdvance) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            builder.remove(Player.COMMAND_SEEK_TO_NEXT)
            builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }

        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> queueEngineProvider()?.canAdvance() ?: true
            else -> super.isCommandAvailable(command)
        }
    }

    override fun seekToNext() {
        if (super.hasNextMediaItem()) {
            super.seekToNext()
        } else {
            onSeekToNext()
        }
    }

    override fun seekToNextMediaItem() {
        if (super.hasNextMediaItem()) {
            super.seekToNextMediaItem()
        } else {
            onSeekToNext()
        }
    }

    override fun seekToPrevious() {
        onSeekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        onSeekToPrevious()
    }

    fun invalidateAvailableCommands() {
        val commands = availableCommands
        for (listener in listeners) {
            listener.onAvailableCommandsChanged(commands)
        }
    }
}
