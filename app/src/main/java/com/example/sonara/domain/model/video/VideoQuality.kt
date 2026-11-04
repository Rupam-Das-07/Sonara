package com.example.sonara.domain.model.video

/**
 * Requested video height and optional target frame rate tier.
 * Auto leaves Media3's adaptive selection in control.
 */
enum class VideoQuality(
    val requestedHeight: Int?,
    val targetFps: Int?,
    val label: String
) {
    AUTO(null, null, "Auto"),
    P4320_60(4320, 60, "8K (4320p) · 60 FPS"),
    P4320_30(4320, 30, "8K (4320p) · 30 FPS"),
    P4320(4320, null, "8K (4320p)"),
    P2160_60(2160, 60, "4K (2160p) · 60 FPS"),
    P2160_30(2160, 30, "4K (2160p) · 30 FPS"),
    P2160(2160, null, "4K (2160p)"),
    P1440_60(1440, 60, "1440p · 60 FPS"),
    P1440_30(1440, 30, "1440p · 30 FPS"),
    P1440(1440, null, "1440p"),
    P1080_60(1080, 60, "1080p · 60 FPS"),
    P1080_30(1080, 30, "1080p · 30 FPS"),
    P1080(1080, null, "1080p"),
    P720_60(720, 60, "720p · 60 FPS"),
    P720_30(720, 30, "720p · 30 FPS"),
    P720(720, null, "720p"),
    P480_60(480, 60, "480p · 60 FPS"),
    P480_30(480, 30, "480p · 30 FPS"),
    P480(480, null, "480p"),
    P360_60(360, 60, "360p · 60 FPS"),
    P360_30(360, 30, "360p · 30 FPS"),
    P360(360, null, "360p"),
    P240(240, null, "240p"),
    P144(144, null, "144p");

    companion object {
        val standardHeights = listOf(
            4320,
            2160,
            1440,
            1080,
            720,
            480,
            360,
            240,
            144
        )

        val defaultQualityTiers = listOf(
            AUTO,
            P4320,
            P2160,
            P1440,
            P1080,
            P720,
            P480,
            P360,
            P240,
            P144
        )

        fun closestStandardHeight(height: Int): Int {
            if (height <= 0) return 0
            return standardHeights.minByOrNull { kotlin.math.abs(it - height) } ?: height
        }

        fun normalizeHeight(width: Int, height: Int): Int {
            if (height <= 0) return 0
            val effectiveHeight = if (width > 0) {
                // Handles 16:9, vertical (e.g. 1080x1920 -> 1080), and 21:9 letterboxed (1920x804 -> 1080)
                maxOf(minOf(width, height), (maxOf(width, height) * 9) / 16)
            } else {
                height
            }
            return closestStandardHeight(effectiveHeight)
        }

        fun fromName(value: String?): VideoQuality =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO

        fun fromTrack(height: Int, frameRate: Float): VideoQuality =
            fromTrack(width = 0, height = height, frameRate = frameRate)

        fun fromTrack(width: Int, height: Int, frameRate: Float): VideoQuality {
            val fpsCategory = when {
                !frameRate.isFinite() -> null
                frameRate >= 45f -> 60
                frameRate in 15f..<45f -> 30
                else -> null
            }
            val normalizedHeight = if (width > 0) normalizeHeight(width, height) else height
            return of(normalizedHeight, fpsCategory)
        }

        fun of(height: Int, targetFps: Int?): VideoQuality {
            if (height <= 0) return AUTO
            val normalizedHeight = if (height in standardHeights) height else closestStandardHeight(height)
            if (targetFps != null) {
                entries.firstOrNull { it.requestedHeight == normalizedHeight && it.targetFps == targetFps }?.let {
                    return it
                }
            }
            return entries.firstOrNull { it.requestedHeight == normalizedHeight && it.targetFps == null }
                ?: entries.firstOrNull { it.requestedHeight == normalizedHeight }
                ?: AUTO
        }
    }
}

/**
 * Presentation model for video quality options in the UI, separating source availability
 * from device decoder capability.
 */
data class VideoQualityOption(
    val quality: VideoQuality,
    val isSupported: Boolean = true,
    val unsupportedReason: String? = null
)
