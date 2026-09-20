package com.example.sonara.domain.model

/**
 * Represents the search discovery mode in Sonara.
 *
 * - [SONGS]: Mode 1 — Official music catalog search via YouTube Music API with deterministic quality ranking.
 * - [VIDEOS]: Mode 2 — Arbitrary YouTube video-to-audio search via yt-dlp.
 */
enum class SearchMode {
    SONGS,
    VIDEOS
}
