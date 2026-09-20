package com.example.sonara.domain.model

/**
 * Genuine audio quality levels supported across playback and downloads.
 *
 * - AUTO: Best available quality baseline
 * - HIGH: Standard high quality (~160 kbps Opus in WebM / ~128 kbps AAC in M4A)
 * - VERY_HIGH: High bitrate stream — JioSaavn ~320 kbps AAC when confidently
 *              matched, YouTube baseline (~160 kbps Opus) on all other tracks.
 */
enum class AudioQuality(
    val label: String,
    val description: String
) {
    AUTO("Auto", "Best available quality"),
    HIGH("High", "~160 kbps Opus / ~128 kbps AAC"),
    VERY_HIGH("Very High", "Up to ~320 kbps AAC when available")
}
