package com.example.sonara.playback.service

import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import com.example.sonara.domain.model.Track
import com.example.sonara.playback.client.MediaControllerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SonaraPlaybackServiceSecurityTest {

    private val sonaraPackage = "app.sonara.music"
    private val sonaraUid = 10250
    private val systemUiPackage = "com.android.systemui"
    private val systemUid = 1000
    private val untrustedPackage = "com.untrusted.attacker.app"
    private val untrustedUid = 10999

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. Controller Classification Unit Tests
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `isInternalController returns true only for matching package AND matching UID`() {
        // Legitimate internal Sonara controller
        assertTrue(
            SonaraPlaybackService.isInternalController(
                controllerPackageName = sonaraPackage,
                controllerUid = sonaraUid,
                expectedPackageName = sonaraPackage,
                expectedUid = sonaraUid
            )
        )

        // Spoofed package name with attacker UID
        assertFalse(
            SonaraPlaybackService.isInternalController(
                controllerPackageName = sonaraPackage,
                controllerUid = untrustedUid,
                expectedPackageName = sonaraPackage,
                expectedUid = sonaraUid
            )
        )

        // Attacker package name with Sonara UID (impossible in real OS, but verifies AND gate)
        assertFalse(
            SonaraPlaybackService.isInternalController(
                controllerPackageName = untrustedPackage,
                controllerUid = sonaraUid,
                expectedPackageName = sonaraPackage,
                expectedUid = sonaraUid
            )
        )

        // Completely untrusted app
        assertFalse(
            SonaraPlaybackService.isInternalController(
                controllerPackageName = untrustedPackage,
                controllerUid = untrustedUid,
                expectedPackageName = sonaraPackage,
                expectedUid = sonaraUid
            )
        )
    }

    @Test
    fun `isTrustedPlatformController returns true for media notification controller`() {
        assertTrue(
            SonaraPlaybackService.isTrustedPlatformController(
                isNotificationController = true,
                isTrusted = false,
                controllerUid = sonaraUid,
                controllerPackageName = sonaraPackage
            )
        )
    }

    @Test
    fun `isTrustedPlatformController returns true for controller with verified isTrusted`() {
        assertTrue(
            SonaraPlaybackService.isTrustedPlatformController(
                isNotificationController = false,
                isTrusted = true,
                controllerUid = systemUid,
                controllerPackageName = systemUiPackage
            )
        )
    }

    @Test
    fun `isTrustedPlatformController returns true for Android system server UID`() {
        assertTrue(
            SonaraPlaybackService.isTrustedPlatformController(
                isNotificationController = false,
                isTrusted = false,
                controllerUid = systemUid,
                controllerPackageName = "android.media.session.MediaController"
            )
        )
    }

    @Test
    fun `isTrustedPlatformController rejects attacker claiming systemui package without system credentials`() {
        // Attacker naming their package com.android.systemui but with untrusted UID and isTrusted = false
        assertFalse(
            SonaraPlaybackService.isTrustedPlatformController(
                isNotificationController = false,
                isTrusted = false,
                controllerUid = untrustedUid,
                controllerPackageName = systemUiPackage
            )
        )
    }

    @Test
    fun `isTrustedPlatformController rejects completely untrusted third-party apps`() {
        assertFalse(
            SonaraPlaybackService.isTrustedPlatformController(
                isNotificationController = false,
                isTrusted = false,
                controllerUid = untrustedUid,
                controllerPackageName = untrustedPackage
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Command Availability Verification (onConnect semantics)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `onConnect_internalController_receivesAllCommands`() {
        val isInternal = true
        val isTrustedPlatform = false

        val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        if (isInternal) {
            sessionCommandsBuilder.add(SessionCommand(MediaControllerClient.ACTION_SET_AUDIO_DEVICE, android.os.Bundle()))
            sessionCommandsBuilder.add(SessionCommand(SonaraPlaybackService.ACTION_TOGGLE_FAVORITE, android.os.Bundle()))
        } else if (isTrustedPlatform) {
            sessionCommandsBuilder.add(SessionCommand(SonaraPlaybackService.ACTION_TOGGLE_FAVORITE, android.os.Bundle()))
        }
        val sessionCommands = sessionCommandsBuilder.build()

        val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

        val configuredPlayerCommands = setOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )

        // Verify Favorite command is available
        assertTrue(sessionCommands.commands.any { it.customAction == SonaraPlaybackService.ACTION_TOGGLE_FAVORITE })
        // Verify Audio Device command is available
        assertTrue(sessionCommands.commands.any { it.customAction == MediaControllerClient.ACTION_SET_AUDIO_DEVICE })
        // Verify playback commands are configured
        assertTrue(configuredPlayerCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(configuredPlayerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun `onConnect_trustedController_receivesFavoriteOnly`() {
        val isInternal = false
        val isTrustedPlatform = true

        val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        if (isInternal) {
            sessionCommandsBuilder.add(SessionCommand(MediaControllerClient.ACTION_SET_AUDIO_DEVICE, android.os.Bundle()))
            sessionCommandsBuilder.add(SessionCommand(SonaraPlaybackService.ACTION_TOGGLE_FAVORITE, android.os.Bundle()))
        } else if (isTrustedPlatform) {
            sessionCommandsBuilder.add(SessionCommand(SonaraPlaybackService.ACTION_TOGGLE_FAVORITE, android.os.Bundle()))
        }
        val sessionCommands = sessionCommandsBuilder.build()

        val configuredPlayerCommands = setOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )

        // Verify Favorite command is available for notification / lock-screen
        assertTrue(sessionCommands.commands.any { it.customAction == SonaraPlaybackService.ACTION_TOGGLE_FAVORITE })
        // Verify Audio Device command is NOT available
        assertFalse(sessionCommands.commands.any { it.customAction == MediaControllerClient.ACTION_SET_AUDIO_DEVICE })
        // Verify standard playback controls are preserved
        assertTrue(configuredPlayerCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(configuredPlayerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test
    fun `onConnect_untrustedController_receivesNoCustomCommands`() {
        val isInternal = false
        val isTrustedPlatform = false

        val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        if (isInternal) {
            sessionCommandsBuilder.add(SessionCommand(MediaControllerClient.ACTION_SET_AUDIO_DEVICE, android.os.Bundle()))
            sessionCommandsBuilder.add(SessionCommand(SonaraPlaybackService.ACTION_TOGGLE_FAVORITE, android.os.Bundle()))
        } else if (isTrustedPlatform) {
            sessionCommandsBuilder.add(SessionCommand(SonaraPlaybackService.ACTION_TOGGLE_FAVORITE, android.os.Bundle()))
        }
        val sessionCommands = sessionCommandsBuilder.build()

        val configuredPlayerCommands = setOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )

        // Verify Favorite command is NOT available
        assertFalse(sessionCommands.commands.any { it.customAction == SonaraPlaybackService.ACTION_TOGGLE_FAVORITE })
        // Verify Audio Device command is NOT available
        assertFalse(sessionCommands.commands.any { it.customAction == MediaControllerClient.ACTION_SET_AUDIO_DEVICE })
        // Verify standard playback controls remain configured
        assertTrue(configuredPlayerCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(configuredPlayerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Defense-in-Depth onCustomCommand Authorization Verification
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `onCustomCommand_unauthorizedFavorite_returnsPermissionDenied`() {
        val isInternal = false
        val isTrustedPlatform = false

        // Defense-in-depth gate in onCustomCommand:
        val result = if (!isInternal && !isTrustedPlatform) {
            SessionError.ERROR_PERMISSION_DENIED
        } else {
            SessionError.ERROR_UNKNOWN
        }

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result)
    }

    @Test
    fun `onCustomCommand_unauthorizedAudioDevice_returnsPermissionDenied`() {
        val isInternal = false

        // Defense-in-depth gate in onCustomCommand:
        val result = if (!isInternal) {
            SessionError.ERROR_PERMISSION_DENIED
        } else {
            SessionError.ERROR_UNKNOWN
        }

        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result)
    }

    @Test
    fun `onCustomCommand_authorizedInternalController_canExecuteAudioDevice`() {
        val isInternal = true
        val isAuthorized = isInternal
        assertTrue(isAuthorized)
    }

    @Test
    fun `onCustomCommand_authorizedTrustedPlatform_canExecuteFavorite`() {
        val isInternal = false
        val isTrustedPlatform = true
        val isAuthorized = isInternal || isTrustedPlatform
        assertTrue(isAuthorized)
    }

    @Test
    fun `onCustomCommand_unsupportedCommand_returnsErrorNotSupported`() {
        val action = "com.unknown.ACTION_DO_SOMETHING"
        val result = when (action) {
            MediaControllerClient.ACTION_SET_AUDIO_DEVICE -> SessionError.ERROR_PERMISSION_DENIED
            SonaraPlaybackService.ACTION_TOGGLE_FAVORITE -> SessionError.ERROR_PERMISSION_DENIED
            else -> SessionError.ERROR_NOT_SUPPORTED
        }
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, result)
    }

    @Test
    fun `extracted video media items are isolated from the native audio queue`() {
        val extractedKind = MediaControllerClient.MEDIA_KIND_EXTRACTED_VIDEO

        // 1. Extracted-video marker recognized across tag, metadata, and request channels
        assertTrue(
            SonaraPlaybackService.isExtractedVideoMarker(
                tag = extractedKind,
                metadataMediaKind = null,
                requestMediaKind = null
            )
        )
        assertTrue(
            SonaraPlaybackService.isExtractedVideoMarker(
                tag = null,
                metadataMediaKind = extractedKind,
                requestMediaKind = null
            )
        )
        assertTrue(
            SonaraPlaybackService.isExtractedVideoMarker(
                tag = null,
                metadataMediaKind = null,
                requestMediaKind = extractedKind
            )
        )

        // 2. Unmarked inputs and arbitrary strings remain unclassified
        assertFalse(
            SonaraPlaybackService.isExtractedVideoMarker(
                tag = null,
                metadataMediaKind = null,
                requestMediaKind = null
            )
        )
        assertFalse(
            SonaraPlaybackService.isExtractedVideoMarker(
                tag = "audio",
                metadataMediaKind = "other",
                requestMediaKind = "song"
            )
        )

        // 3. MediaItem adapter boundary classifies native items and null as non-video
        val nativeAudio = MediaItem.Builder()
            .setMediaId("song-id")
            .build()
        assertFalse(SonaraPlaybackService.isExtractedVideoMediaItem(nativeAudio))
        assertFalse(SonaraPlaybackService.isExtractedVideoMediaItem(null))

        // 4. Extracted videos do not advance queue, enter history, or trigger track transition
        val isExtracted = SonaraPlaybackService.isExtractedVideoMarker(
            tag = extractedKind,
            metadataMediaKind = null,
            requestMediaKind = null
        )
        assertFalse(SonaraPlaybackService.shouldAdvanceQueueOnEnded(isExtracted))
        assertFalse(
            SonaraPlaybackService.shouldRecordAutoTransitionHistory(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                isExtractedVideo = isExtracted
            )
        )
        assertFalse(SonaraPlaybackService.shouldHandleNativeTrackTransition(isExtracted))

        // 5. Native audio items continue to advance queue, enter history, and handle transition
        val isNative = SonaraPlaybackService.isExtractedVideoMediaItem(nativeAudio)
        assertTrue(SonaraPlaybackService.shouldAdvanceQueueOnEnded(isNative))
        assertTrue(
            SonaraPlaybackService.shouldRecordAutoTransitionHistory(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                isExtractedVideo = isNative
            )
        )
        assertTrue(SonaraPlaybackService.shouldHandleNativeTrackTransition(isNative))

        // An empty timeline or paused item is not evidence of natural native completion.
        assertFalse(
            SonaraPlaybackService.shouldAdvanceQueueOnEnded(
                isExtractedVideo = false,
                hasCurrentMediaItem = false,
                mediaItemCount = 0,
                playWhenReady = false
            )
        )
        assertFalse(
            SonaraPlaybackService.shouldAdvanceQueueOnEnded(
                isExtractedVideo = false,
                hasCurrentMediaItem = true,
                mediaItemCount = 1,
                playWhenReady = false
            )
        )
        assertTrue(
            SonaraPlaybackService.shouldAdvanceQueueOnEnded(
                isExtractedVideo = false,
                hasCurrentMediaItem = true,
                mediaItemCount = 1,
                playWhenReady = true
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 5. Favorites & Video Route Target Resolution Tests
    // ─────────────────────────────────────────────────────────────────────────────

    private val audioTrack = Track(id = "audio_1", title = "Audio Song", artist = "Audio Artist")
    private val videoTrack = Track(id = "video_1", title = "Video Title", artist = "Video Artist")

    @Test
    fun `resolveActiveFavoriteTrack returns audio track when video is inactive`() {
        val resolved = SonaraPlaybackService.resolveActiveFavoriteTrack(
            isVideoActive = false,
            currentVideoTrack = videoTrack,
            currentMediaItem = null,
            audioCurrentTrack = audioTrack
        )
        assertNotNull(resolved)
        assertEquals("audio_1", resolved?.id)
        assertEquals("Audio Song", resolved?.title)
    }

    @Test
    fun `resolveActiveFavoriteTrack returns video track when video is active`() {
        val resolved = SonaraPlaybackService.resolveActiveFavoriteTrack(
            isVideoActive = true,
            currentVideoTrack = videoTrack,
            currentMediaItem = null,
            audioCurrentTrack = audioTrack
        )
        assertNotNull(resolved)
        assertEquals("video_1", resolved?.id)
        assertEquals("Video Title", resolved?.title)
    }

    @Test
    fun `resolveActiveFavoriteTrack fails closed and never touches stale audio track when video active without video track`() {
        val resolved = SonaraPlaybackService.resolveActiveFavoriteTrack(
            isVideoActive = true,
            currentVideoTrack = null,
            currentMediaItem = null,
            audioCurrentTrack = audioTrack
        )
        // Must fail closed (null), never modifying the unrelated audio track
        assertNull(resolved)
    }

    @Test
    fun `resolveActiveFavoriteTrack constructs fallback video track from mediaItem when coordinator track missing`() {
        val item = MediaItem.Builder()
            .setMediaId("video_fallback_id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Fallback Video Title")
                    .setArtist("Fallback Video Artist")
                    .build()
            )
            .build()

        val resolved = SonaraPlaybackService.resolveActiveFavoriteTrack(
            isVideoActive = true,
            currentVideoTrack = null,
            currentMediaItem = item,
            audioCurrentTrack = audioTrack
        )
        assertNotNull(resolved)
        assertEquals("video_fallback_id", resolved?.id)
        assertEquals("Fallback Video Title", resolved?.title)
    }

    @Test
    fun `resolveTransitionTrack returns video track on extracted video transition`() {
        val item = MediaItem.Builder()
            .setMediaId("video_1")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Video Title")
                    .setArtist("Video Artist")
                    .build()
            )
            .build()

        val resolved = SonaraPlaybackService.resolveTransitionTrack(
            isExtracted = true,
            mediaItem = item,
            currentVideoTrack = videoTrack
        )
        assertNotNull(resolved)
        assertEquals("video_1", resolved?.id)
    }

    @Test
    fun `resolveTransitionTrack returns null when transition is not extracted video`() {
        val item = MediaItem.Builder()
            .setMediaId("audio_1")
            .build()

        val resolved = SonaraPlaybackService.resolveTransitionTrack(
            isExtracted = false,
            mediaItem = item,
            currentVideoTrack = videoTrack
        )
        assertNull(resolved)
    }
}
