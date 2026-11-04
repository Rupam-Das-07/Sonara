package com.example.sonara.domain.ports

import com.example.sonara.domain.model.Track

/**
 * Domain port for searching song tracks on SoundCloud.
 *
 * Scope: Audio-only song discovery for Sonara's Songs search experience.
 */
interface SoundcloudSearchPort {
    suspend fun searchSongs(query: String, limit: Int = 20): Result<List<Track>>
}
