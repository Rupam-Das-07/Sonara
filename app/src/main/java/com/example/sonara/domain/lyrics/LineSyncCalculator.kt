package com.example.sonara.domain.lyrics

import com.example.sonara.domain.model.LyricLine

/**
 * High-performance line synchronization calculator.
 * Implements Audit 02 §3.3 fast-path O(1) pointer tracking with O(log N) binary search fallback.
 * Supports perceptual lead-time compensation for natural sync across slow, medium, and fast mix songs.
 */
object LineSyncCalculator {

    const val DEFAULT_LEAD_TIME_MS = 150L

    /**
     * Finds the index of the active lyric line for the given playback position.
     * Returns -1 if lines is empty or adjusted position is before the first line.
     */
    fun findActiveLineIndex(
        currentPositionMs: Long,
        lines: List<LyricLine>,
        lastKnownIndex: Int = 0,
        leadTimeMs: Long = 0L
    ): Int {
        if (lines.isEmpty()) return -1
        val pos = (currentPositionMs + leadTimeMs).coerceAtLeast(0L)
        if (pos < lines.first().timestampMs) return -1
        if (pos >= lines.last().timestampMs) return lines.size - 1

        // Fast-path 1: Position is still within the same line
        val currentIndex = lastKnownIndex.coerceIn(0, lines.size - 1)
        val currentLineStart = lines[currentIndex].timestampMs
        val nextLineStart = if (currentIndex + 1 < lines.size) lines[currentIndex + 1].timestampMs else Long.MAX_VALUE

        if (pos in currentLineStart until nextLineStart) {
            return currentIndex
        }

        // Fast-path 2: Position moved to immediate next line
        val nextIndex = currentIndex + 1
        if (nextIndex < lines.size) {
            val nextNextLineStart = if (nextIndex + 1 < lines.size) lines[nextIndex + 1].timestampMs else Long.MAX_VALUE
            if (pos in lines[nextIndex].timestampMs until nextNextLineStart) {
                return nextIndex
            }
        }

        // Fallback: Binary search O(log N) for seeks / skips
        var low = 0
        var high = lines.size - 1
        var result = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestampMs <= pos) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return result
    }
}
