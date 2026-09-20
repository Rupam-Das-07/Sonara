package com.example.sonara.domain.repository

import android.net.Uri
import com.example.sonara.domain.model.ImportMatchItem
import com.example.sonara.domain.model.ImportPlaylistResult
import com.example.sonara.domain.model.ImportedTrack
import com.example.sonara.domain.model.ParseSummary
import com.example.sonara.domain.model.Track

/**
 * Domain boundary coordinating file parsing, remote chunk matching, and atomic persistence.
 */
interface ImportRepository {
    /**
     * Parses an export file via its content URI.
     */
    suspend fun parseExportFile(uri: Uri, fallbackName: String = "Imported Playlist"): Result<ParseSummary>

    /**
     * Matches a single chunk of imported tracks against Sonara backend.
     */
    suspend fun matchTracksChunk(importId: String, chunkIndex: Int, tracks: List<ImportedTrack>): Result<List<ImportMatchItem>>

    /**
     * Persists reviewed tracks into an atomic native Sonara playlist.
     */
    suspend fun persistPlaylist(name: String, tracks: List<Track>): Result<ImportPlaylistResult>
}
