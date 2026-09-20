package com.example.sonara.feature.import

import com.example.sonara.domain.model.ImportMatchItem
import com.example.sonara.domain.model.ImportPlaylistResult
import com.example.sonara.domain.model.ParseSummary
import com.example.sonara.domain.model.Track

/**
 * Review state for an individual imported track row.
 */
data class ReviewItemState(
    val sourceOrder: Int,
    val isSelected: Boolean,
    val selectedTrack: Track,
    val confidence: Double,
    val alternatives: List<Track> = emptyList(),
    val isAmbiguous: Boolean = false
)

/**
 * State machine step in the Spotify playlist import flow.
 *
 * All states prior to [Persisting] are strictly in-memory; no Room database writes occur.
 */
sealed interface ImportStep {
    object Idle : ImportStep
    object AwaitingFile : ImportStep
    object Parsing : ImportStep
    data class Parsed(val summary: ParseSummary) : ImportStep
    data class Matching(
        val totalTracks: Int,
        val processedTracks: Int,
        val currentChunk: Int,
        val totalChunks: Int
    ) : ImportStep
    data class Review(
        val playlistName: String,
        val confidentItems: List<ReviewItemState>,
        val reviewItems: List<ReviewItemState>,
        val skippedItems: List<ImportMatchItem>,
        val collapsedDuplicatesCount: Int = 0
    ) : ImportStep
    data class Persisting(val playlistName: String) : ImportStep
    data class Done(val result: ImportPlaylistResult) : ImportStep

    // Failure states
    data class ParseFailed(val error: String) : ImportStep
    data class MatchFailed(
        val error: String,
        val canRetry: Boolean,
        val completedChunks: Int,
        val totalChunks: Int
    ) : ImportStep
    data class PersistFailed(val error: String) : ImportStep
}

/**
 * Top-level UI state for [ImportViewModel].
 */
data class ImportUiState(
    val step: ImportStep = ImportStep.Idle
)
