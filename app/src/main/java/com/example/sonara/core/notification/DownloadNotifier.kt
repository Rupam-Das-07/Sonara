package com.example.sonara.core.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.sonara.MainActivity
import com.example.sonara.R
import com.example.sonara.domain.model.DownloadWithTrack
import com.example.sonara.domain.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Observes the download state stream and renders Sonara's frozen download notifications.
 *
 * Division of labour:
 *  - [DownloadNotificationMapper] (pure) decides *what* to show for each snapshot.
 *  - This class only *executes* that plan against the platform: it posts/updates/cancels
 *    notifications, formats byte counts, loads artwork, and gates on the runtime permission.
 *
 * It never writes download state — it is a read-only observer of [DownloadRepository]
 * (§12: no second state machine). A single app-scoped collector is used (§18: no leaked
 * collectors / no per-download work).
 *
 * Frozen-design mapping:
 *  - Progress → [NotificationChannels.CHANNEL_DOWNLOADS], silent, ongoing, monochrome download
 *    glyph, Oxide accent, thin standard progress bar (§7/§9). Single shows the track; two or more
 *    collapse to one aggregate (§8).
 *  - Completion → understated, auto-cancel, restrained check glyph, no colourised background (§10).
 *  - Failure → [NotificationChannels.CHANNEL_ALERTS] with a real Retry action + Dismiss, Oxide
 *    error accent (not a fully red surface) (§11).
 */
class DownloadNotifier(
    context: Context,
    private val downloadRepository: DownloadRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val imageLoader = appContext.imageLoader

    /** Carried-over notifier memory fed back into the pure mapper each snapshot. */
    private var state = NotifierState()
    private var collectJob: Job? = null

    // Coalescing / caching guards (§9/§18): avoid redundant posts and repeated artwork decodes.
    private var lastProgressKey: String? = null
    private var artworkUrlCached: String? = null
    private var artworkBitmapCached: Bitmap? = null

    // Stable notification id per failed trackId, so a failure can be updated/cleared precisely.
    private val failureIds = LinkedHashMap<String, Int>()
    private var nextFailureId = FAILURE_ID_BASE

    /** Begins observing downloads. Idempotent: a second call while running is a no-op. */
    fun start() {
        if (collectJob?.isActive == true) return
        NotificationChannels.ensureChannels(appContext)
        collectJob = scope.launch {
            downloadRepository.observeDownloadsWithTrack()
                .distinctUntilChanged()
                .collect { items -> handle(items) }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    private suspend fun handle(items: List<DownloadWithTrack>) {
        val plan = DownloadNotificationMapper.map(items, state)
        // Always advance state, even when we cannot post, so transitions are not replayed later
        // if the permission is granted mid-session (§13).
        state = plan.newState

        // Posting requires notifications to be enabled (covers POST_NOTIFICATIONS denial on API 33+).
        // We never disrupt media playback when denied; downloads simply stay silent until granted.
        if (!notificationManager.areNotificationsEnabled()) return

        for (trackId in plan.clearedFailureIds) {
            val nid = failureIds.remove(trackId) ?: continue
            notificationManager.cancel(nid)
        }

        if (plan.completed.isNotEmpty()) {
            postCompletion(plan.completed.size, plan.completed.first())
        }

        for (failure in plan.failures) {
            postFailure(failure)
        }

        if (plan.clearProgress) {
            notificationManager.cancel(PROGRESS_ID)
            lastProgressKey = null
        } else {
            plan.progress?.let { postProgress(it) }
        }
    }

    private suspend fun postProgress(progress: ActiveProgress) {
        // Coalesce: identical integer-percent content is not re-posted (§9/§18).
        if (progress.coalesceKey == lastProgressKey) return

        val artwork = if (progress.kind == DownloadNotifKind.SINGLE) {
            artworkFor(progress.artworkUrl)
        } else {
            null
        }

        val title: String
        val text: String
        when (progress.kind) {
            DownloadNotifKind.SINGLE -> {
                title = progress.title.ifBlank { appContext.getString(R.string.notification_downloading) }
                text = progress.artist
            }
            DownloadNotifKind.GROUP -> {
                title = appContext.getString(R.string.notification_downloads_in_progress, progress.count)
                text = ""
            }
        }

        val subText = if (progress.indeterminate) {
            appContext.getString(R.string.notification_downloading)
        } else {
            appContext.getString(
                R.string.notification_progress_bytes,
                Formatter.formatShortFileSize(appContext, progress.downloadedBytes),
                Formatter.formatShortFileSize(appContext, progress.totalBytes),
                progress.percent
            )
        }

        val builder = baseBuilder(NotificationChannels.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setOngoing(true)
            .setContentIntent(openAppIntent(PROGRESS_ID))
            .setProgress(
                if (progress.indeterminate) 0 else 100,
                if (progress.indeterminate) 0 else progress.percent,
                progress.indeterminate
            )
        if (artwork != null) builder.setLargeIcon(artwork)

        notificationManager.notify(PROGRESS_ID, builder.build())
        lastProgressKey = progress.coalesceKey
    }

    private fun postCompletion(count: Int, first: CompletedItem) {
        val title: String
        val text: String
        if (count > 1) {
            title = appContext.getString(R.string.notification_downloads_complete_title)
            text = appContext.getString(R.string.notification_downloads_complete_count, count)
        } else {
            title = appContext.getString(R.string.notification_download_complete_title)
            text = identityLine(first.title, first.artist)
        }

        val builder = baseBuilder(NotificationChannels.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(COMPLETION_ID))

        notificationManager.notify(COMPLETION_ID, builder.build())
    }

    private fun postFailure(failure: FailedItem) {
        val nid = failureIds.getOrPut(failure.trackId) { nextFailureId++ }

        val builder = baseBuilder(NotificationChannels.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_download_error)
            .setContentTitle(appContext.getString(R.string.notification_download_failed_title))
            .setContentText(identityLine(failure.title, failure.artist))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(nid))
            .addAction(
                android.R.drawable.ic_popup_sync,
                appContext.getString(R.string.action_retry),
                actionIntent(NotificationActionReceiver.ACTION_RETRY, nid, failure.trackId)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.notification_action_dismiss),
                actionIntent(NotificationActionReceiver.ACTION_DISMISS, nid, failure.trackId)
            )

        notificationManager.notify(nid, builder.build())
    }

    /** Shared restrained styling: silent, low-priority; Oxide accent reserved for actionable alerts (§8/§11). */
    private fun baseBuilder(channelId: String): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(appContext, channelId)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        if (channelId == NotificationChannels.CHANNEL_ALERTS) {
            builder.setColor(appContext.getColor(R.color.sonara_notification_accent))
        }
        return builder
    }

    private fun identityLine(title: String, artist: String): String =
        listOf(title, artist).filter { it.isNotBlank() }.joinToString(" · ")

    private fun openAppIntent(requestBase: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            requestBase * REQUEST_STRIDE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun actionIntent(action: String, notificationId: Int, trackId: String): PendingIntent {
        val intent = Intent(appContext, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_TRACK_ID, trackId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val offset = if (action == NotificationActionReceiver.ACTION_RETRY) 1 else 2
        return PendingIntent.getBroadcast(
            appContext,
            notificationId * REQUEST_STRIDE + offset,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Loads (and caches, per URL) a software bitmap suitable for a notification large icon. */
    private suspend fun artworkFor(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        if (url == artworkUrlCached) return artworkBitmapCached
        artworkUrlCached = url
        artworkBitmapCached = try {
            val request = ImageRequest.Builder(appContext)
                .data(url)
                .allowHardware(false) // notifications cannot render hardware bitmaps
                .size(ARTWORK_PX, ARTWORK_PX)
                .build()
            (imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
        } catch (_: Exception) {
            null
        }
        return artworkBitmapCached
    }

    companion object {
        private const val PROGRESS_ID = 4101
        private const val COMPLETION_ID = 4102
        private const val FAILURE_ID_BASE = 4200

        // Keeps content/retry/dismiss PendingIntent request codes from colliding across ids.
        private const val REQUEST_STRIDE = 10
        private const val ARTWORK_PX = 256
    }
}
