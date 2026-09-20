package com.example.sonara.core.notification

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.example.sonara.R

/**
 * Central registry and idempotent initialisation of Sonara's notification channels.
 *
 * Frozen taxonomy (three channels only):
 *  - [CHANNEL_PLAYBACK]  — media transport. IMPORTANCE_LOW (silent). Backs the existing Media3
 *                          MediaStyle notification; Sonara only supplies the id/name/icon, the
 *                          notification itself is still produced by Media3.
 *  - [CHANNEL_DOWNLOADS] — ongoing download progress + restrained completion. IMPORTANCE_LOW
 *                          (silent, no badge) so routine download activity never alerts.
 *  - [CHANNEL_ALERTS]    — genuinely actionable failures. IMPORTANCE_DEFAULT so a failed download
 *                          can surface once; reserved for events that need the user.
 *
 * [NotificationChannelCompat] creation is inherently idempotent: the platform ignores
 * re-registration of an existing channel id, so [ensureChannels] is safe to call on every launch.
 * Creating channels requires no runtime permission (POST_NOTIFICATIONS gates posting, not creation).
 */
object NotificationChannels {

    const val CHANNEL_PLAYBACK = "sonara_playback"
    const val CHANNEL_DOWNLOADS = "sonara_downloads"
    const val CHANNEL_ALERTS = "sonara_alerts"

    fun ensureChannels(context: Context) {
        val playback = NotificationChannelCompat.Builder(
            CHANNEL_PLAYBACK,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.notification_channel_playback_name))
            .setDescription(context.getString(R.string.notification_channel_playback_desc))
            .setShowBadge(false)
            .build()

        val downloads = NotificationChannelCompat.Builder(
            CHANNEL_DOWNLOADS,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.notification_channel_downloads_name))
            .setDescription(context.getString(R.string.notification_channel_downloads_desc))
            .setShowBadge(false)
            .build()

        val alerts = NotificationChannelCompat.Builder(
            CHANNEL_ALERTS,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(context.getString(R.string.notification_channel_alerts_name))
            .setDescription(context.getString(R.string.notification_channel_alerts_desc))
            .setShowBadge(true)
            .build()

        NotificationManagerCompat.from(context)
            .createNotificationChannelsCompat(listOf(playback, downloads, alerts))
    }
}
