package com.example.sonara.core.notification

import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.DownloadWithTrack

/**
 * Pure, Android-free reducer that turns the current download snapshot (plus the previous
 * notifier state) into an explicit [DownloadNotificationPlan]. All decisions about what to
 * show, what to update, and what to clear live here so they can be unit-tested on the JVM
 * without a device (§21). The Android layer ([DownloadNotifier]) only executes the plan.
 *
 * Design constraints this encodes:
 *  - §8  A single active download renders as one notification; two or more collapse into one
 *        grouped/aggregate notification. Never one-per-download.
 *  - §9/§18 Progress churn is coalesced to integer-percent granularity via [ActiveProgress.coalesceKey]
 *        so the Android layer can skip reposting on sub-percent byte updates.
 *  - §10/§11 Completion and failure fire only on a *live* transition (previous status was
 *        QUEUED/DOWNLOADING), never on the first snapshot — this prevents startup spam when the
 *        process restarts over a DB already full of DOWNLOADED/FAILED rows.
 *  - §11 A failure is posted once; if the row later leaves FAILED (retried/removed) its
 *        notification id is scheduled for cancellation.
 *  - §12 This is not a second state machine: it derives everything from the observed snapshot and
 *        the minimal carried-over memory in [NotifierState]; it never writes download state.
 */
object DownloadNotificationMapper {

    private val ACTIVE = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)

    fun map(items: List<DownloadWithTrack>, prev: NotifierState): DownloadNotificationPlan {
        val active = items.filter { it.download.status in ACTIVE }

        val progress: ActiveProgress? = when {
            active.isEmpty() -> null
            active.size == 1 -> {
                val only = active.first()
                ActiveProgress(
                    kind = DownloadNotifKind.SINGLE,
                    count = 1,
                    primaryTrackId = only.download.trackId,
                    title = only.track?.title.orEmpty(),
                    artist = only.track?.artist.orEmpty(),
                    artworkUrl = only.track?.artworkUrl,
                    downloadedBytes = only.download.downloadedBytes,
                    totalBytes = only.download.totalBytes,
                    percent = percentOf(only.download.downloadedBytes, only.download.totalBytes),
                    indeterminate = only.download.totalBytes <= 0L
                )
            }
            else -> {
                val done = active.sumOf { it.download.downloadedBytes }
                val total = active.sumOf { it.download.totalBytes }
                ActiveProgress(
                    kind = DownloadNotifKind.GROUP,
                    count = active.size,
                    primaryTrackId = null,
                    title = "",
                    artist = "",
                    artworkUrl = null,
                    downloadedBytes = done,
                    totalBytes = total,
                    percent = percentOf(done, total),
                    // If no active download has reported a size yet, show an indeterminate bar
                    // rather than a misleading 0%.
                    indeterminate = total <= 0L
                )
            }
        }

        val completed = mutableListOf<CompletedItem>()
        val failures = mutableListOf<FailedItem>()

        for (item in items) {
            val id = item.download.trackId
            val now = item.download.status
            val was = prev.statuses[id]
            val wasActive = was in ACTIVE

            // Fresh completion: a download we were actively tracking has finished. Requires a live
            // transition so a restart over an already-DOWNLOADED DB does not re-announce.
            if (now == DownloadStatus.DOWNLOADED && wasActive) {
                completed += CompletedItem(id, item.track?.title.orEmpty(), item.track?.artist.orEmpty())
            }

            // Fresh failure: a live download failed and we have not already posted it.
            if (now == DownloadStatus.FAILED && wasActive && id !in prev.notifiedFailures) {
                failures += FailedItem(id, item.track?.title.orEmpty(), item.track?.artist.orEmpty())
            }
        }

        // Failure notifications to cancel: anything we previously posted that is no longer FAILED
        // (retried back to QUEUED/DOWNLOADING, completed, or removed entirely).
        val stillFailed = items.asSequence()
            .filter { it.download.status == DownloadStatus.FAILED }
            .map { it.download.trackId }
            .toSet()
        val clearedFailureIds = prev.notifiedFailures.filter { it !in stillFailed }

        // Carry forward only the failures still failing, plus the ones we just posted.
        val newNotifiedFailures =
            prev.notifiedFailures.intersect(stillFailed) + failures.map { it.trackId }

        val currentStatuses = items.associate { it.download.trackId to it.download.status }

        return DownloadNotificationPlan(
            progress = progress,
            clearProgress = active.isEmpty(),
            completed = completed,
            failures = failures,
            clearedFailureIds = clearedFailureIds,
            newState = NotifierState(
                statuses = currentStatuses,
                notifiedFailures = newNotifiedFailures
            )
        )
    }

    /** Integer 0..100 percent; 0 when total is unknown. Deterministic, no timing dependency. */
    fun percentOf(done: Long, total: Long): Int =
        if (total > 0L) ((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100) else 0
}

/** Whether the active-progress notification represents one download or an aggregate of many. */
enum class DownloadNotifKind { SINGLE, GROUP }

/**
 * The single active-download notification to show (either one track, or an aggregate of several).
 * Byte counts are carried raw; the Android layer formats them with the platform Formatter so this
 * type stays testable without a Context.
 */
data class ActiveProgress(
    val kind: DownloadNotifKind,
    val count: Int,
    val primaryTrackId: String?,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val indeterminate: Boolean
) {
    /**
     * Coalescing identity (§9/§18): two plans with the same key are visually identical at
     * integer-percent granularity, so the Android layer can skip reposting. Deliberately excludes
     * raw byte counts (which change constantly) and artworkUrl (loaded once, asynchronously).
     */
    val coalesceKey: String
        get() = "$kind|$count|$percent|$indeterminate|$title|$artist|$primaryTrackId"
}

/** A download that finished this snapshot (used for the understated completion notice, §10). */
data class CompletedItem(
    val trackId: String,
    val title: String,
    val artist: String
)

/** A download that failed this snapshot (drives the actionable failure notification, §11). */
data class FailedItem(
    val trackId: String,
    val title: String,
    val artist: String
)

/**
 * Minimal memory carried between snapshots so transitions can be detected without duplicating the
 * download state machine (§12): the last-seen status per track, and which failures have already
 * been surfaced.
 */
data class NotifierState(
    val statuses: Map<String, DownloadStatus> = emptyMap(),
    val notifiedFailures: Set<String> = emptySet()
)

/**
 * The fully-resolved instruction set for one snapshot. The Android layer executes it verbatim:
 * post/update or clear the progress notification, post any fresh completions/failures, and cancel
 * failure notifications listed in [clearedFailureIds]. [newState] must be fed back as `prev` on the
 * next call.
 */
data class DownloadNotificationPlan(
    val progress: ActiveProgress?,
    val clearProgress: Boolean,
    val completed: List<CompletedItem>,
    val failures: List<FailedItem>,
    val clearedFailureIds: List<String>,
    val newState: NotifierState
)
