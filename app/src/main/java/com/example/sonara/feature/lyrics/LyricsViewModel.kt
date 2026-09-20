package com.example.sonara.feature.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sonara.domain.lyrics.LineSyncCalculator
import com.example.sonara.domain.model.Lyrics
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.repository.LyricsRepository
import com.example.sonara.domain.repository.SettingsRepository
import com.example.sonara.playback.client.MediaControllerClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LyricsViewModel managing high-frequency line synchronization and Romanization toggling.
 * High-frequency position tracking is isolated to line calculations without triggering root UI recomposition.
 */
class LyricsViewModel(
    private val lyricsRepository: LyricsRepository,
    private val mediaControllerClient: MediaControllerClient,
    private val settingsRepository: SettingsRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<LyricsUiState>(LyricsUiState.Hidden)
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    private var currentLyrics: Lyrics? = null
    private var isRomanized: Boolean = false
    private var lastActiveIndex: Int = 0
    private var activeTrackId: String = ""
    private var activeTrackTitle: String = ""
    private var activeArtistName: String = ""
    private var fetchJob: Job? = null

    init {
        // Collect persisted Romanization preference (default true for Indic Romanization)
        if (settingsRepository != null) {
            viewModelScope.launch {
                settingsRepository.getUserPreferences().collect { prefs ->
                    if (isRomanized != prefs.showRomanized) {
                        isRomanized = prefs.showRomanized
                        val currentState = _uiState.value
                        when (currentState) {
                            is LyricsUiState.Synced -> _uiState.value = currentState.copy(isRomanized = isRomanized)
                            is LyricsUiState.Plain -> _uiState.value = currentState.copy(isRomanized = isRomanized)
                            else -> {}
                        }
                    }
                }
            }
        }

        // Observe track changes and playback position from MediaControllerClient
        viewModelScope.launch {
            mediaControllerClient.controllerState.collect { state ->
                val trackId = state.currentMediaItem?.mediaId ?: ""
                val metadata = state.currentMediaItem?.mediaMetadata
                val title = metadata?.title?.toString() ?: ""
                val artist = metadata?.artist?.toString() ?: ""

                if (trackId.isNotBlank() && trackId != activeTrackId) {
                    activeTrackId = trackId
                    activeTrackTitle = title
                    activeArtistName = artist
                    loadLyrics(trackId, title, artist, state.durationMs)
                }

                // Update line synchronization if currently showing Synced lyrics
                updateSyncPosition(state.currentPositionMs)
            }
        }
    }

    fun loadLyrics(trackId: String, title: String, artist: String, durationMs: Long) {
        activeTrackId = trackId
        activeTrackTitle = title
        activeArtistName = artist
        fetchJob?.cancel()
        _uiState.value = LyricsUiState.Loading

        fetchJob = viewModelScope.launch {
            val result = lyricsRepository.getLyrics(trackId, title, artist, durationMs)
            result.fold(
                onSuccess = { lyrics ->
                    currentLyrics = lyrics
                    lastActiveIndex = 0
                    publishState(lyrics, 0L)
                },
                onFailure = { error ->
                    _uiState.value = LyricsUiState.Unavailable(
                        reason = error.message ?: "Lyrics not found",
                        trackTitle = title,
                        artistName = artist
                    )
                }
            )
        }
    }

    private fun updateSyncPosition(positionMs: Long) {
        val lyrics = currentLyrics ?: return
        if (lyrics is Lyrics.SyncedLyrics) {
            val newIndex = LineSyncCalculator.findActiveLineIndex(
                currentPositionMs = positionMs,
                lines = lyrics.lines,
                lastKnownIndex = lastActiveIndex,
                leadTimeMs = LineSyncCalculator.DEFAULT_LEAD_TIME_MS
            )
            if (newIndex != lastActiveIndex || _uiState.value !is LyricsUiState.Synced) {
                lastActiveIndex = newIndex
                _uiState.value = LyricsUiState.Synced(
                    lines = lyrics.lines,
                    activeLineIndex = newIndex,
                    isRomanized = isRomanized,
                    trackTitle = activeTrackTitle,
                    artistName = activeArtistName
                )
            }
        }
    }

    private fun publishState(lyrics: Lyrics, positionMs: Long) {
        when (lyrics) {
            is Lyrics.SyncedLyrics -> {
                val index = LineSyncCalculator.findActiveLineIndex(
                    currentPositionMs = positionMs,
                    lines = lyrics.lines,
                    lastKnownIndex = 0,
                    leadTimeMs = LineSyncCalculator.DEFAULT_LEAD_TIME_MS
                )
                lastActiveIndex = index
                _uiState.value = LyricsUiState.Synced(
                    lines = lyrics.lines,
                    activeLineIndex = index,
                    isRomanized = isRomanized,
                    trackTitle = activeTrackTitle,
                    artistName = activeArtistName
                )
            }
            is Lyrics.PlainLyrics -> {
                _uiState.value = LyricsUiState.Plain(
                    text = lyrics.text,
                    romanizedText = lyrics.romanizedText,
                    isRomanized = isRomanized,
                    trackTitle = activeTrackTitle,
                    artistName = activeArtistName
                )
            }
            is Lyrics.Instrumental -> {
                _uiState.value = LyricsUiState.Unavailable(
                    reason = "♪ Instrumental Track",
                    trackTitle = activeTrackTitle,
                    artistName = activeArtistName
                )
            }
            is Lyrics.Unavailable -> {
                _uiState.value = LyricsUiState.Unavailable(
                    reason = lyrics.reason,
                    trackTitle = activeTrackTitle,
                    artistName = activeArtistName
                )
            }
        }
    }

    fun toggleRomanization() {
        isRomanized = !isRomanized
        val currentState = _uiState.value
        when (currentState) {
            is LyricsUiState.Synced -> {
                _uiState.value = currentState.copy(isRomanized = isRomanized)
            }
            is LyricsUiState.Plain -> {
                _uiState.value = currentState.copy(isRomanized = isRomanized)
            }
            else -> {}
        }
        if (settingsRepository != null) {
            viewModelScope.launch {
                settingsRepository.setShowRomanized(isRomanized)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaControllerClient.seekTo(positionMs)
    }
}
