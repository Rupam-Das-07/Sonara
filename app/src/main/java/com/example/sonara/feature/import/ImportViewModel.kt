package com.example.sonara.feature.import

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.domain.model.ImportMatchItem
import com.example.sonara.domain.model.ImportMatchStatus
import com.example.sonara.domain.model.ImportMatchTier
import com.example.sonara.domain.model.ImportPlaylistResult
import com.example.sonara.domain.model.ImportedTrack
import com.example.sonara.domain.model.ParseSummary
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.ImportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Dedicated ViewModel driving the Spotify playlist import flow and state machine.
 *
 * Implements strict separation:
 * - Parsing -> Matching -> Review -> Persisting.
 * - Zero Room database writes occur prior to [confirmImport].
 */
class ImportViewModel(
    private val importRepository: ImportRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    // In-memory working session data
    private var currentSummary: ParseSummary? = null
    private var currentImportId: String = ""
    private var trackChunks: List<List<ImportedTrack>> = emptyList()
    private val accumulatedResults = mutableListOf<ImportMatchItem>()
    private var lastReviewState: ImportStep.Review? = null

    companion object {
        const val CHUNK_SIZE = 25
    }

    fun startImport() {
        accumulatedResults.clear()
        currentSummary = null
        lastReviewState = null
        _uiState.value = ImportUiState(step = ImportStep.AwaitingFile)
    }

    fun onFileSelected(uri: Uri, fallbackName: String = "Imported Playlist") {
        viewModelScope.launch {
            _uiState.value = ImportUiState(step = ImportStep.Parsing)

            val parseResult = importRepository.parseExportFile(uri, fallbackName)
            parseResult.fold(
                onSuccess = { summary ->
                    currentSummary = summary
                    _uiState.value = ImportUiState(step = ImportStep.Parsed(summary))
                    startMatching(summary)
                },
                onFailure = { error ->
                    _uiState.value = ImportUiState(
                        step = ImportStep.ParseFailed(error.message ?: "Failed to parse selected export file")
                    )
                }
            )
        }
    }

    private fun startMatching(summary: ParseSummary) {
        currentImportId = UUID.randomUUID().toString()
        trackChunks = summary.tracks.chunked(CHUNK_SIZE)
        accumulatedResults.clear()

        if (trackChunks.isEmpty()) {
            buildReviewState(summary.playlistName, emptyList())
            return
        }

        matchChunksFrom(startIndex = 0)
    }

    fun retryMatching() {
        val nextIndex = accumulatedResults.size / CHUNK_SIZE
        matchChunksFrom(startIndex = nextIndex)
    }

    private fun matchChunksFrom(startIndex: Int) {
        viewModelScope.launch {
            val totalChunks = trackChunks.size
            val totalTracks = currentSummary?.tracks?.size ?: 0

            for (chunkIdx in startIndex until totalChunks) {
                _uiState.value = ImportUiState(
                    step = ImportStep.Matching(
                        totalTracks = totalTracks,
                        processedTracks = accumulatedResults.size,
                        currentChunk = chunkIdx + 1,
                        totalChunks = totalChunks
                    )
                )

                val chunk = trackChunks[chunkIdx]
                val result = importRepository.matchTracksChunk(currentImportId, chunkIdx, chunk)

                if (result.isFailure) {
                    val errMsg = result.exceptionOrNull()?.message ?: "Matching failed for chunk ${chunkIdx + 1}"
                    _uiState.value = ImportUiState(
                        step = ImportStep.MatchFailed(
                            error = errMsg,
                            canRetry = true,
                            completedChunks = chunkIdx,
                            totalChunks = totalChunks
                        )
                    )
                    return@launch
                }

                accumulatedResults.addAll(result.getOrDefault(emptyList()))
            }

            // All chunks completed
            val playlistName = currentSummary?.playlistName ?: "Imported Playlist"
            buildReviewState(playlistName, accumulatedResults)
        }
    }

    private fun buildReviewState(playlistName: String, results: List<ImportMatchItem>) {
        val confidentItems = mutableListOf<ReviewItemState>()
        val reviewItems = mutableListOf<ReviewItemState>()
        val skippedItems = mutableListOf<ImportMatchItem>()

        // Sort results by source order
        val sortedResults = results.sortedBy { it.sourceOrder }

        for (item in sortedResults) {
            if (item.resolvedTrack == null || item.status == ImportMatchStatus.SKIPPED || item.status == ImportMatchStatus.UNMATCHED) {
                skippedItems.add(item)
            } else if (item.tier == ImportMatchTier.CONFIDENT) {
                confidentItems.add(
                    ReviewItemState(
                        sourceOrder = item.sourceOrder,
                        isSelected = true,
                        selectedTrack = item.resolvedTrack,
                        confidence = item.confidence,
                        alternatives = item.alternatives,
                        isAmbiguous = false
                    )
                )
            } else {
                reviewItems.add(
                    ReviewItemState(
                        sourceOrder = item.sourceOrder,
                        isSelected = true,
                        selectedTrack = item.resolvedTrack,
                        confidence = item.confidence,
                        alternatives = item.alternatives,
                        isAmbiguous = true
                    )
                )
            }
        }

        val reviewStep = ImportStep.Review(
            playlistName = playlistName,
            confidentItems = confidentItems,
            reviewItems = reviewItems,
            skippedItems = skippedItems
        )
        lastReviewState = reviewStep
        _uiState.value = ImportUiState(step = reviewStep)
    }

    fun toggleItemSelection(sourceOrder: Int) {
        val current = _uiState.value.step as? ImportStep.Review ?: return
        val updatedConfident = current.confidentItems.map {
            if (it.sourceOrder == sourceOrder) it.copy(isSelected = !it.isSelected) else it
        }
        val updatedReview = current.reviewItems.map {
            if (it.sourceOrder == sourceOrder) it.copy(isSelected = !it.isSelected) else it
        }
        val updated = current.copy(
            confidentItems = updatedConfident,
            reviewItems = updatedReview
        )
        lastReviewState = updated
        _uiState.value = ImportUiState(step = updated)
    }

    fun selectAlternative(sourceOrder: Int, alternative: Track) {
        val current = _uiState.value.step as? ImportStep.Review ?: return
        val updatedReview = current.reviewItems.map { item ->
            if (item.sourceOrder == sourceOrder) {
                // Swap alternative with selectedTrack
                val oldSelected = item.selectedTrack
                val newAlternatives = item.alternatives.filter { it.id != alternative.id } + oldSelected
                item.copy(
                    selectedTrack = alternative,
                    alternatives = newAlternatives
                )
            } else {
                item
            }
        }
        val updated = current.copy(reviewItems = updatedReview)
        lastReviewState = updated
        _uiState.value = ImportUiState(step = updated)
    }

    fun updatePlaylistName(name: String) {
        val current = _uiState.value.step as? ImportStep.Review ?: return
        val truncated = name.take(60)
        val updated = current.copy(playlistName = truncated)
        lastReviewState = updated
        _uiState.value = ImportUiState(step = updated)
    }

    fun confirmImport() {
        val current = _uiState.value.step as? ImportStep.Review ?: return
        val playlistName = current.playlistName.trim().ifBlank { "Imported Playlist" }

        // Gather all selected tracks preserving sourceOrder
        val selectedTracks = (current.confidentItems + current.reviewItems)
            .filter { it.isSelected }
            .sortedBy { it.sourceOrder }
            .map { it.selectedTrack }

        if (selectedTracks.isEmpty()) {
            _uiState.value = ImportUiState(
                step = ImportStep.PersistFailed("Please select at least one song to import.")
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = ImportUiState(step = ImportStep.Persisting(playlistName))

            val persistResult = importRepository.persistPlaylist(playlistName, selectedTracks)
            persistResult.fold(
                onSuccess = { result ->
                    _uiState.value = ImportUiState(step = ImportStep.Done(result))
                },
                onFailure = { error ->
                    _uiState.value = ImportUiState(
                        step = ImportStep.PersistFailed(
                            error.message ?: "Failed to create playlist in database"
                        )
                    )
                }
            )
        }
    }

    fun retryPersistFromReview() {
        val previous = lastReviewState
        if (previous != null) {
            _uiState.value = ImportUiState(step = previous)
        } else {
            cancelImport()
        }
    }

    fun cancelImport() {
        accumulatedResults.clear()
        currentSummary = null
        lastReviewState = null
        _uiState.value = ImportUiState(step = ImportStep.Idle)
    }
}
