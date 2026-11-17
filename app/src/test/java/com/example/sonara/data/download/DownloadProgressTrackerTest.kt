package com.example.sonara.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTrackerTest {

    @Test
    fun test35_progressPercentageCalculation() {
        val total = 100_000_000L
        val half = 50_000_000L
        val progressPercent = (half.toFloat() / total.toFloat()) * 100f
        assertEquals(50.0f, progressPercent, 0.01f)

        // Indeterminate or zero total bytes
        val zeroTotal = 0L
        val percentZero = if (zeroTotal > 0) (half.toFloat() / zeroTotal.toFloat()) * 100f else 0f
        assertEquals(0f, percentZero, 0.01f)
    }

    @Test
    fun test36_sizeFormatting() {
        assertEquals("0 B", DownloadProgressTracker.formatBytes(0L))
        assertEquals("500 B", DownloadProgressTracker.formatBytes(500L))
        assertEquals("50 KB", DownloadProgressTracker.formatBytes(50 * 1024L))
        assertEquals("15.5 MB", DownloadProgressTracker.formatBytes((15.5 * 1024 * 1024).toLong()))
        assertEquals("1.25 GB", DownloadProgressTracker.formatBytes((1.25 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun test37_speedCalculationWithEmaSmoothing() {
        val tracker = DownloadProgressTracker(smoothingFactor = 0.5)

        // T0: start
        var res = tracker.update(0L, nowMs = 1000L)
        assertEquals(0L, res.bytesPerSecond)

        // T1: 1 second later, 1 MB downloaded (instant = 1 MB/s)
        res = tracker.update(1024 * 1024L, nowMs = 2000L)
        assertEquals(1024 * 1024L, res.bytesPerSecond)
        assertEquals("1.0 MB/s", res.speedFormatted)

        // T2: 1 second later, +2 MB downloaded (instant = 2 MB/s)
        // EMA with factor 0.5: 0.5 * 2MB/s + 0.5 * 1MB/s = 1.5 MB/s
        res = tracker.update(3 * 1024 * 1024L, nowMs = 3000L)
        val expected = (1.5 * 1024 * 1024).toLong()
        assertEquals(expected, res.bytesPerSecond)
        assertEquals("1.5 MB/s", res.speedFormatted)
    }

    @Test
    fun test38_etaCalculation() {
        val downloaded = 50 * 1024 * 1024L
        val total = 100 * 1024 * 1024L
        val speed = 5 * 1024 * 1024L // 5 MB/s

        val etaSeconds = DownloadProgressTracker.calculateEtaSeconds(downloaded, total, speed)
        assertEquals(10L, etaSeconds) // 50MB / 5MB/s = 10 seconds

        val etaFormatted = DownloadProgressTracker.formatEta(etaSeconds)
        assertEquals("10s remaining", etaFormatted)
    }

    @Test
    fun test39_speedSmoothingDampsJitter() {
        val tracker = DownloadProgressTracker(smoothingFactor = 0.2) // heavy smoothing

        tracker.update(0L, nowMs = 1000L)
        // 1 MB in 1s
        tracker.update(1024 * 1024L, nowMs = 2000L) // 1 MB/s

        // Sudden spike: 10 MB in next second
        val resSpike = tracker.update(11 * 1024 * 1024L, nowMs = 3000L)

        // Spike instant is 10 MB/s. But smoothed = 0.2 * 10 + 0.8 * 1 = 2.8 MB/s
        val smoothed = resSpike.bytesPerSecond
        assertTrue("Speed should be smoothed below raw spike of 10 MB/s", smoothed < 4 * 1024 * 1024L)
        assertTrue("Speed should increase above previous 1 MB/s", smoothed > 1024 * 1024L)
    }

    @Test
    fun test40_indeterminateProgressHandling() {
        // ETA when total bytes is unknown or 0
        assertNull(DownloadProgressTracker.calculateEtaSeconds(5000L, 0L, 1000L))
        assertNull(DownloadProgressTracker.calculateEtaSeconds(5000L, -1L, 1000L))
        // ETA when speed is 0
        assertNull(DownloadProgressTracker.calculateEtaSeconds(5000L, 10000L, 0L))
        // ETA when already complete
        assertNull(DownloadProgressTracker.calculateEtaSeconds(10000L, 10000L, 1000L))
    }

    @Test
    fun test41_formatSpeedUnits() {
        assertNull(DownloadProgressTracker.formatSpeed(0L))
        assertEquals("500 B/s", DownloadProgressTracker.formatSpeed(500L))
        assertEquals("250 KB/s", DownloadProgressTracker.formatSpeed(250 * 1024L))
        assertEquals("12.5 MB/s", DownloadProgressTracker.formatSpeed((12.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun test42_formatEtaDurations() {
        assertEquals("45s remaining", DownloadProgressTracker.formatEta(45L))
        assertEquals("2m 15s remaining", DownloadProgressTracker.formatEta(135L))
        assertEquals("1h 15m remaining", DownloadProgressTracker.formatEta(4500L))
    }
}
