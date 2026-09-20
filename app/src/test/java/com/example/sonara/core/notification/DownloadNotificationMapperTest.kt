package com.example.sonara.core.notification

import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.DownloadWithTrack
import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM unit tests for [DownloadNotificationMapper].
 *
 * The mapper is a pure Kotlin reducer with no Android, coroutine, or timing dependencies,
 * so all tests use plain JUnit4 on the host JVM — no Robolectric required.
 *
 * Covered cases:
 *  §8  Single vs. group/aggregate progress layout
 *  §9  Progress coalescing (coalesceKey)
 *  §10 Completion fires only on live QUEUED/DOWNLOADING → DOWNLOADED transition
 *  §11 Failure fires only on live transition; duplicate failures are suppressed;
 *      leaving FAILED emits clearedFailureIds
 *  §12 percentOf() boundary conditions
 */
class DownloadNotificationMapperTest {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun makeTrack(id: String) = Track(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        durationMs = 240_000L,
        artworkUrl = "https://art/$id.jpg"
    )

    private fun makeDownload(
        trackId: String,
        status: DownloadStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 1_000_000L
    ) = DownloadInfo(
        trackId = trackId,
        status = status,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        quality = AudioQuality.VERY_HIGH
    )

    private fun makeItem(
        trackId: String,
        status: DownloadStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 1_000_000L,
        withTrack: Boolean = true
    ) = DownloadWithTrack(
        download = makeDownload(trackId, status, downloadedBytes, totalBytes),
        track = if (withTrack) makeTrack(trackId) else null
    )

    private val emptyState = NotifierState()

    // ─── §8 Single active download ────────────────────────────────────────────

    @Test
    fun singleActiveDownload_producesSingleProgress() {
        val items = listOf(makeItem("t1", DownloadStatus.DOWNLOADING, 500_000L, 1_000_000L))
        val plan = DownloadNotificationMapper.map(items, emptyState)

        val progress = plan.progress
        assertNotNull(progress)
        assertEquals(DownloadNotifKind.SINGLE, progress!!.kind)
        assertEquals(1, progress.count)
        assertEquals("Track t1", progress.title)
        assertEquals("Artist t1", progress.artist)
        assertEquals("https://art/t1.jpg", progress.artworkUrl)
        assertEquals(50, progress.percent)
        assertFalse("should not be indeterminate when totalBytes > 0", progress.indeterminate)
    }

    @Test
    fun singleActiveDownload_indeterminate_whenTotalBytesUnknown() {
        val items = listOf(makeItem("t1", DownloadStatus.QUEUED, 0L, 0L))
        val plan = DownloadNotificationMapper.map(items, emptyState)

        val progress = plan.progress
        assertNotNull(progress)
        assertTrue("should be indeterminate when totalBytes <= 0", progress!!.indeterminate)
        assertEquals(0, progress.percent)
    }

    // ─── §8 Multiple active downloads (group) ─────────────────────────────────

    @Test
    fun multipleActiveDownloads_producesGroupProgress() {
        val items = listOf(
            makeItem("t1", DownloadStatus.DOWNLOADING, 300_000L, 1_000_000L),
            makeItem("t2", DownloadStatus.QUEUED, 0L, 2_000_000L)
        )
        val plan = DownloadNotificationMapper.map(items, emptyState)

        val progress = plan.progress
        assertNotNull(progress)
        assertEquals(DownloadNotifKind.GROUP, progress!!.kind)
        assertEquals(2, progress.count)
        assertEquals(300_000L, progress.downloadedBytes)
        assertEquals(3_000_000L, progress.totalBytes)
        assertEquals(10, progress.percent)
        assertTrue("group title should be blank", progress.title.isBlank())
        assertTrue("group artist should be blank", progress.artist.isBlank())
        assertNull(progress.artworkUrl)
    }

    @Test
    fun multipleActiveDownloads_indeterminate_whenNoSizesKnown() {
        val items = listOf(
            makeItem("t1", DownloadStatus.QUEUED, 0L, 0L),
            makeItem("t2", DownloadStatus.QUEUED, 0L, 0L)
        )
        val plan = DownloadNotificationMapper.map(items, emptyState)
        assertTrue("should be indeterminate when all totalBytes <= 0", plan.progress!!.indeterminate)
    }

    // ─── Progress clearing ────────────────────────────────────────────────────

    @Test
    fun emptyItems_clearsProgress() {
        val plan = DownloadNotificationMapper.map(emptyList(), emptyState)
        assertNull(plan.progress)
        assertTrue(plan.clearProgress)
    }

    @Test
    fun onlyCompletedItems_clearsProgress() {
        val items = listOf(makeItem("t1", DownloadStatus.DOWNLOADED))
        val plan = DownloadNotificationMapper.map(items, emptyState)
        assertNull(plan.progress)
        assertTrue(plan.clearProgress)
    }

    @Test
    fun onlyFailedItems_clearsProgress() {
        val items = listOf(makeItem("t1", DownloadStatus.FAILED))
        val plan = DownloadNotificationMapper.map(items, emptyState)
        assertNull(plan.progress)
        assertTrue(plan.clearProgress)
    }

    // ─── §9 Coalescing identity ────────────────────────────────────────────────

    @Test
    fun coalesceKey_unchangedWhileIntegerPercentUnchanged() {
        val item1 = makeItem("t1", DownloadStatus.DOWNLOADING, 100_000L, 1_000_000L)
        val item2 = makeItem("t1", DownloadStatus.DOWNLOADING, 109_999L, 1_000_000L)

        val key1 = DownloadNotificationMapper.map(listOf(item1), emptyState).progress!!.coalesceKey
        val key2 = DownloadNotificationMapper.map(listOf(item2), emptyState).progress!!.coalesceKey
        assertEquals("coalesceKey must not change within the same integer percent", key1, key2)
    }

    @Test
    fun coalesceKey_changesWhenIntegerPercentAdvances() {
        val item1 = makeItem("t1", DownloadStatus.DOWNLOADING, 100_000L, 1_000_000L)
        val item2 = makeItem("t1", DownloadStatus.DOWNLOADING, 110_000L, 1_000_000L)

        val key1 = DownloadNotificationMapper.map(listOf(item1), emptyState).progress!!.coalesceKey
        val key2 = DownloadNotificationMapper.map(listOf(item2), emptyState).progress!!.coalesceKey
        assertTrue("coalesceKey must change when integer percent advances", key1 != key2)
    }

    // ─── §10 Completion on live transition only ────────────────────────────────

    @Test
    fun completion_notFired_onInitialDownloadedSnapshot() {
        val items = listOf(makeItem("t1", DownloadStatus.DOWNLOADED))
        val plan = DownloadNotificationMapper.map(items, emptyState)
        assertTrue("no completion on first snapshot of DOWNLOADED item", plan.completed.isEmpty())
    }

    @Test
    fun completion_fired_onLiveQueuedToDownloadedTransition() {
        val prev = NotifierState(statuses = mapOf("t1" to DownloadStatus.QUEUED))
        val items = listOf(makeItem("t1", DownloadStatus.DOWNLOADED))
        val plan = DownloadNotificationMapper.map(items, prev)

        assertEquals(1, plan.completed.size)
        assertEquals("t1", plan.completed.first().trackId)
        assertEquals("Track t1", plan.completed.first().title)
    }

    @Test
    fun completion_fired_onLiveDownloadingToDownloadedTransition() {
        val prev = NotifierState(statuses = mapOf("t1" to DownloadStatus.DOWNLOADING))
        val items = listOf(makeItem("t1", DownloadStatus.DOWNLOADED))
        val plan = DownloadNotificationMapper.map(items, prev)

        assertEquals(1, plan.completed.size)
    }

    // ─── §11 Failure on live transition; no duplicates; failure cancellation ───

    @Test
    fun failure_notFired_onInitialFailedSnapshot() {
        val items = listOf(makeItem("t1", DownloadStatus.FAILED))
        val plan = DownloadNotificationMapper.map(items, emptyState)
        assertTrue("no failure on first snapshot of FAILED item", plan.failures.isEmpty())
    }

    @Test
    fun failure_fired_onLiveDownloadingToFailedTransition() {
        val prev = NotifierState(statuses = mapOf("t1" to DownloadStatus.DOWNLOADING))
        val items = listOf(makeItem("t1", DownloadStatus.FAILED))
        val plan = DownloadNotificationMapper.map(items, prev)

        assertEquals(1, plan.failures.size)
        assertEquals("t1", plan.failures.first().trackId)
    }

    @Test
    fun failure_notFiredAgain_whenTrackRemainsFailedInNextSnapshot() {
        val prev = NotifierState(statuses = mapOf("t1" to DownloadStatus.DOWNLOADING))
        val items = listOf(makeItem("t1", DownloadStatus.FAILED))
        val plan1 = DownloadNotificationMapper.map(items, prev)
        assertEquals(1, plan1.failures.size)

        val plan2 = DownloadNotificationMapper.map(items, plan1.newState)
        assertTrue("failure must not fire again for the same already-posted FAILED track",
            plan2.failures.isEmpty())
    }

    @Test
    fun failure_cancelled_whenTrackLeavesFailedState() {
        val stateWithFailure = NotifierState(
            statuses = mapOf("t1" to DownloadStatus.FAILED),
            notifiedFailures = setOf("t1")
        )

        val items = listOf(makeItem("t1", DownloadStatus.QUEUED))
        val plan = DownloadNotificationMapper.map(items, stateWithFailure)

        assertTrue("t1 should appear in clearedFailureIds", plan.clearedFailureIds.contains("t1"))
        assertFalse("t1 should be removed from newState.notifiedFailures",
            plan.newState.notifiedFailures.contains("t1"))
    }

    @Test
    fun failure_cancelled_whenTrackIsRemovedFromList() {
        val stateWithFailure = NotifierState(
            statuses = mapOf("t1" to DownloadStatus.FAILED),
            notifiedFailures = setOf("t1")
        )

        val plan = DownloadNotificationMapper.map(emptyList(), stateWithFailure)

        assertTrue("removed track should appear in clearedFailureIds",
            plan.clearedFailureIds.contains("t1"))
        assertFalse(plan.newState.notifiedFailures.contains("t1"))
    }

    // ─── percentOf() boundary conditions ──────────────────────────────────────

    @Test
    fun percentOf_zeroDoneOfHundred() {
        assertEquals(0, DownloadNotificationMapper.percentOf(0L, 100L))
    }

    @Test
    fun percentOf_fiftyOfHundred() {
        assertEquals(50, DownloadNotificationMapper.percentOf(50L, 100L))
    }

    @Test
    fun percentOf_hundredOfHundred() {
        assertEquals(100, DownloadNotificationMapper.percentOf(100L, 100L))
    }

    @Test
    fun percentOf_clampsAtHundred_whenDoneExceedsTotal() {
        assertEquals(100, DownloadNotificationMapper.percentOf(120L, 100L))
    }

    @Test
    fun percentOf_returnsZero_whenTotalIsZero() {
        assertEquals(0, DownloadNotificationMapper.percentOf(50L, 0L))
    }

    @Test
    fun percentOf_returnsZero_whenTotalIsNegative() {
        assertEquals(0, DownloadNotificationMapper.percentOf(50L, -1L))
    }
}
