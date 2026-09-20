package com.example.sonara.data.repository

import android.content.ContentResolver
import android.net.Uri
import com.example.sonara.data.import.spotify.SpotifyExportParser
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.ImportMatchItem
import com.example.sonara.domain.model.ImportPlaylistResult
import com.example.sonara.domain.model.ImportedTrack
import com.example.sonara.domain.model.ParseSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.ImportRepository
import com.example.sonara.domain.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of [ImportRepository] coordinating on-device parsing, network matching, and Room persistence.
 */
class ImportRepositoryImpl(
    private val contentResolver: ContentResolver,
    private val backendClient: SonaraBackendClient,
    private val playlistRepository: PlaylistRepository
) : ImportRepository {

    override suspend fun parseExportFile(uri: Uri, fallbackName: String): Result<ParseSummary> = withContext(Dispatchers.IO) {
        try {
            val stream = contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalArgumentException("Unable to open input stream for selected file"))

            stream.use {
                val summary = SpotifyExportParser.parse(it, fallbackName)
                Result.success(summary)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun matchTracksChunk(
        importId: String,
        chunkIndex: Int,
        tracks: List<ImportedTrack>
    ): Result<List<ImportMatchItem>> = withContext(Dispatchers.IO) {
        backendClient.matchImportChunk(importId, chunkIndex, tracks)
    }

    override suspend fun persistPlaylist(name: String, tracks: List<Track>): Result<ImportPlaylistResult> = withContext(Dispatchers.IO) {
        playlistRepository.importPlaylist(name, tracks)
    }
}
