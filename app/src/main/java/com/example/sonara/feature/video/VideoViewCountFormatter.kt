package com.example.sonara.feature.video

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Compact, human-readable YouTube view count formatter.
 *
 * Rules:
 * - Null or negative sentinel values (such as NewPipe's -1) return null (or "LIVE" if [isLive] is true).
 * - Single view uses singular "view"; 0 and values > 1 use plural "views".
 * - When [isLive] is true, uses "watching" suffix instead of "views" (e.g. 20K watching, 1.2K watching, 125K watching).
 * - Scales using compact suffixes: K (thousands), M (millions), B (billions).
 * - Values that round up across a magnitude boundary promote to the next suffix.
 * - Retains at most one decimal place with half-up rounding (e.g. 1.2K views, 1.2M views, 1.2B views).
 */
object VideoViewCountFormatter {
    fun format(viewCount: Long?, isLive: Boolean = false): String? {
        if (isLive) {
            if (viewCount == null || viewCount < 0L) {
                return "LIVE"
            }
            val formattedNumber = formatNumber(viewCount)
            return "$formattedNumber watching"
        }

        if (viewCount == null || viewCount < 0L) return null
        val formattedNumber = formatNumber(viewCount)
        val unit = if (viewCount == 1L) "view" else "views"
        return "$formattedNumber $unit"
    }

    private fun formatNumber(count: Long): String {
        val (divisor, suffix) = when {
            count >= 999_950_000L -> 1_000_000_000.0 to "B"
            count >= 999_950L -> 1_000_000.0 to "M"
            count >= 1_000L -> 1_000.0 to "K"
            else -> 1.0 to ""
        }
        return if (suffix.isEmpty()) {
            count.toString()
        } else {
            val scaled = count / divisor
            val df = DecimalFormat("0.#", DecimalFormatSymbols(Locale.US)).apply {
                roundingMode = RoundingMode.HALF_UP
            }
            val formatted = df.format(scaled)
            if (formatted == "1000" && suffix == "K") {
                "1M"
            } else if (formatted == "1000" && suffix == "M") {
                "1B"
            } else {
                "$formatted$suffix"
            }
        }
    }
}
