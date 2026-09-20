package com.example.sonara.feature.lyrics

import com.example.sonara.domain.model.LyricLine

sealed class LyricsUiState {
    data object Hidden : LyricsUiState()
    data object Loading : LyricsUiState()
    data class Synced(
        val lines: List<LyricLine>,
        val activeLineIndex: Int,
        val isRomanized: Boolean = false,
        val trackTitle: String,
        val artistName: String
    ) : LyricsUiState()
    data class Plain(
        val text: String,
        val romanizedText: String? = null,
        val isRomanized: Boolean = false,
        val trackTitle: String,
        val artistName: String
    ) : LyricsUiState()
    data class Unavailable(
        val reason: String = "Lyrics not available",
        val trackTitle: String,
        val artistName: String
    ) : LyricsUiState()
}
