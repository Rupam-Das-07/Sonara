package com.example.sonara.domain.repository

import com.example.sonara.domain.model.Lyrics

/**
 * Domain interface for retrieving static synced or plain lyrics with Romanization support.
 * Implements Phase 4B-3 §16.
 */
interface LyricsRepository {
    suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long
    ): Result<Lyrics>
}
