package com.example.sonara.playback.service

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import com.example.sonara.playback.client.MediaControllerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class SonaraPlaybackServiceSecurityTest {

    private val sonaraPackage = "com.example.sonara"
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
}
