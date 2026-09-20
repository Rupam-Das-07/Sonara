package com.example.sonara.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.sonara.SonaraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the download notification action buttons (Retry / Dismiss).
 *
 * This class is deliberately thin: it contains **no download business logic** (§15). It only routes
 * the two user actions to the real download seam:
 *  - [ACTION_RETRY]   → [DownloadRepository.retryDownload], the *same* re-enqueue path the in-app UI
 *                       uses. This is a genuine retry, never a cosmetic one (§11).
 *  - [ACTION_DISMISS] → cancels the notification only; it never mutates download state, so dismissing
 *                       a failure does not corrupt the record (§15).
 *
 * The retry runs on a detached scope via [goAsync] so the re-enqueue (a quick DB read + job launch,
 * verified non-blocking in DownloadEngine) completes even though the broadcast returns immediately.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, INVALID_ID)

        when (intent.action) {
            ACTION_RETRY -> {
                val trackId = intent.getStringExtra(EXTRA_TRACK_ID)
                if (trackId.isNullOrBlank()) return

                // Dismiss the failure notification right away for responsiveness; the notifier will
                // render the re-enqueued progress state on the next download snapshot.
                if (notificationId != INVALID_ID) {
                    NotificationManagerCompat.from(appContext).cancel(notificationId)
                }

                val app = appContext as? SonaraApp ?: return
                val repository = app.container.downloadRepository
                val pending = goAsync()
                receiverScope.launch {
                    try {
                        repository.retryDownload(trackId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Retry failed to re-enqueue $trackId: ${e.message}")
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_DISMISS -> {
                if (notificationId != INVALID_ID) {
                    NotificationManagerCompat.from(appContext).cancel(notificationId)
                }
            }
        }
    }

    companion object {
        private const val TAG = "NotifActionReceiver"

        const val ACTION_RETRY = "com.example.sonara.notification.action.RETRY"
        const val ACTION_DISMISS = "com.example.sonara.notification.action.DISMISS"
        const val EXTRA_TRACK_ID = "com.example.sonara.notification.extra.TRACK_ID"
        const val EXTRA_NOTIFICATION_ID = "com.example.sonara.notification.extra.NOTIFICATION_ID"

        private const val INVALID_ID = -1

        /** Detached scope so a retry survives the synchronous return of [onReceive]. */
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
