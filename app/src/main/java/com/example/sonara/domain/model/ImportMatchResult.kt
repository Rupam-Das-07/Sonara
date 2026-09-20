package com.example.sonara.domain.model

/**
 * Match status classification for an imported track.
 */
enum class ImportMatchStatus {
    MATCHED,
    AMBIGUOUS,
    UNMATCHED,
    SKIPPED;

    companion object {
        fun fromString(value: String): ImportMatchStatus = when (value.lowercase()) {
            "matched" -> MATCHED
            "ambiguous" -> AMBIGUOUS
            "unmatched" -> UNMATCHED
            "skipped" -> SKIPPED
            else -> UNMATCHED
        }
    }
}

/**
 * Confidence tier for an imported track.
 */
enum class ImportMatchTier {
    CONFIDENT,
    REVIEW,
    NONE;

    companion object {
        fun fromString(value: String): ImportMatchTier = when (value.lowercase()) {
            "confident" -> CONFIDENT
            "review" -> REVIEW
            else -> NONE
        }
    }
}

/**
 * Transient match outcome for a single imported track row.
 */
data class ImportMatchItem(
    val sourceOrder: Int,
    val status: ImportMatchStatus,
    val tier: ImportMatchTier,
    val confidence: Double,
    val resolvedTrack: Track? = null,
    val alternatives: List<Track> = emptyList(),
    val reason: String? = null
)

/**
 * Transient summary produced after on-device parsing of an export file.
 */
data class ParseSummary(
    val playlistName: String,
    val totalSourceRows: Int,
    val parsedRows: Int,
    val skippedRows: Int,
    val localFilesCount: Int,
    val episodesCount: Int,
    val invalidRowsCount: Int,
    val tracks: List<ImportedTrack>
)

/**
 * Result of atomic playlist persistence.
 */
data class ImportPlaylistResult(
    val playlistId: String,
    val playlistName: String,
    val totalTracksAdded: Int,
    val collapsedDuplicates: Int
)
