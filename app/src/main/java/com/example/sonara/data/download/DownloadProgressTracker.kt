package com.example.sonara.data.download

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Utility for tracking download speeds, Exponential Moving Average (EMA) smoothing, and ETA.
 */
class DownloadProgressTracker(
    private val smoothingFactor: Double = 0.25
) {
    private var lastTimestampMs = 0L
    private var lastBytes = 0L
    private var smoothedBytesPerSecond = 0.0

    @Synchronized
    fun update(downloadedBytes: Long, nowMs: Long = System.currentTimeMillis()): SpeedAndEta {
        if (lastTimestampMs == 0L) {
            lastTimestampMs = nowMs
            lastBytes = downloadedBytes
            return SpeedAndEta(0L, null, null, null)
        }

        val elapsedMs = nowMs - lastTimestampMs
        if (elapsedMs < 100) {
            // Insufficient elapsed time for stable measurement
            val speedLong = smoothedBytesPerSecond.toLong()
            return SpeedAndEta(
                bytesPerSecond = speedLong,
                etaSeconds = null,
                speedFormatted = formatSpeed(speedLong),
                etaFormatted = null
            )
        }

        val bytesDelta = (downloadedBytes - lastBytes).coerceAtLeast(0L)
        val instantBps = (bytesDelta.toDouble() / elapsedMs.toDouble()) * 1000.0

        smoothedBytesPerSecond = if (smoothedBytesPerSecond == 0.0) {
            instantBps
        } else {
            (smoothingFactor * instantBps) + ((1.0 - smoothingFactor) * smoothedBytesPerSecond)
        }

        lastTimestampMs = nowMs
        lastBytes = downloadedBytes

        val currentSpeedLong = smoothedBytesPerSecond.toLong()
        return SpeedAndEta(
            bytesPerSecond = currentSpeedLong,
            etaSeconds = null,
            speedFormatted = formatSpeed(currentSpeedLong),
            etaFormatted = null
        )
    }

    companion object {
        data class SpeedAndEta(
            val bytesPerSecond: Long,
            val etaSeconds: Long?,
            val speedFormatted: String?,
            val etaFormatted: String?
        )

        fun calculateEtaSeconds(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long): Long? {
            if (totalBytes <= 0 || downloadedBytes >= totalBytes || bytesPerSecond <= 0) return null
            val remainingBytes = totalBytes - downloadedBytes
            return (remainingBytes / bytesPerSecond).coerceAtLeast(0L)
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
                else -> "$bytes B"
            }
        }

        fun formatSpeed(bytesPerSecond: Long): String? {
            if (bytesPerSecond <= 0) return null
            val mbps = bytesPerSecond / (1024.0 * 1024.0)
            val kbps = bytesPerSecond / 1024.0
            return when {
                mbps >= 1.0 -> String.format(Locale.US, "%.1f MB/s", mbps)
                kbps >= 1.0 -> String.format(Locale.US, "%.0f KB/s", kbps)
                else -> "$bytesPerSecond B/s"
            }
        }

        fun formatEta(etaSeconds: Long?): String? {
            if (etaSeconds == null || etaSeconds <= 0) return null
            val minutes = etaSeconds / 60
            val seconds = etaSeconds % 60
            return when {
                minutes > 60 -> "${minutes / 60}h ${minutes % 60}m remaining"
                minutes > 0 -> "${minutes}m ${seconds}s remaining"
                else -> "${seconds}s remaining"
            }
        }
    }
}
