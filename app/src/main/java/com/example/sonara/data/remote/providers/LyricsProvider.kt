package com.example.sonara.data.remote.providers

import com.example.sonara.domain.model.Lyrics

/**
 * Provider-level contract for fetching lyrics for a single track from a single source.
 *
 * Introduced in Lyrics Architecture Revision #1, Stage 1. This interface captures
 * exactly the surface that [com.example.sonara.data.repository.LyricsRepositoryImpl]
 * already consumed from the concrete LRCLIB provider — nothing more.
 *
 * Deliberately NOT implied by this abstraction (out of scope for Stage 1): provider
 * fallback, provider resolution/selection, disk/offline caching, or any change to
 * lyrics matching, romanization, synchronization, or the canonical [Lyrics] model.
 *
 * Implementations return a canonical [Lyrics] wrapped in [Result]:
 *  - `Result.success(...)` carrying [Lyrics.SyncedLyrics], [Lyrics.PlainLyrics],
 *    [Lyrics.Instrumental], or [Lyrics.Unavailable].
 *  - `Result.failure(...)` for network/parsing failures (e.g. SonaraException subtypes).
 */
interface LyricsProvider {
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationMs: Long
    ): Result<Lyrics>
}
