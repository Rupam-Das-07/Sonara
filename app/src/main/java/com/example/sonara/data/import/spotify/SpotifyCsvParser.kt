package com.example.sonara.data.import.spotify

import com.example.sonara.domain.model.ImportedTrack
import com.example.sonara.domain.model.ParseSummary
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * RFC-4180 compliant streaming CSV parser tailored for Spotify playlist exports (e.g. Exportify).
 *
 * Requirements:
 * - Header-driven: dynamically resolves column indices; tolerates column reordering.
 * - BOM tolerant: strips UTF-8 BOM (\uFEFF) if present.
 * - CRLF and LF tolerant.
 * - Handles quoted fields with internal commas and escaped double quotes ("").
 * - Extracts title, artist, optional album, optional duration, optional ISRC, optional spotifyUri.
 * - Classifies local files and podcast episodes.
 * - Preserves 0-based source order.
 */
object SpotifyCsvParser {

    private const val MAX_FILE_BYTES = 5 * 1024 * 1024 // 5 MB cap

    /**
     * Parses a CSV input stream into a [ParseSummary].
     *
     * @param inputStream Source input stream (will not be closed by parser).
     * @param fallbackPlaylistName Name to assign if not discovered in metadata.
     * @return [ParseSummary]
     */
    fun parse(inputStream: InputStream, fallbackPlaylistName: String = "Imported Playlist"): ParseSummary {
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        val rows = parseCsvRows(reader)

        if (rows.isEmpty()) {
            throw IllegalArgumentException("CSV file is empty")
        }

        // Header mapping
        val header = rows[0].map { cleanHeader(it) }
        val titleIdx = findHeaderIndex(header, listOf("track name", "track", "title", "name", "song title"))
        val artistIdx = findHeaderIndex(header, listOf("artist name(s)", "artist name", "artist(s)", "artist", "artists"))
        val albumIdx = findHeaderIndex(header, listOf("album name", "album"))
        val durationIdx = findHeaderIndex(header, listOf("duration (ms)", "duration ms", "duration", "length"))
        val uriIdx = findHeaderIndex(header, listOf("spotify id", "track uri", "spotify uri", "uri", "id"))
        val isrcIdx = findHeaderIndex(header, listOf("isrc"))

        if (titleIdx == -1 || artistIdx == -1) {
            throw IllegalArgumentException("CSV missing required Track Name or Artist column headers")
        }

        val totalSourceRows = rows.size - 1
        var parsedRows = 0
        var skippedRows = 0
        var localFilesCount = 0
        var episodesCount = 0
        var invalidRowsCount = 0

        val tracks = mutableListOf<ImportedTrack>()

        for (rowIndex in 1 until rows.size) {
            val row = rows[rowIndex]
            if (row.isEmpty() || (row.size == 1 && row[0].isBlank())) {
                continue // Skip empty blank lines
            }

            val sourceOrder = tracks.size
            val title = row.getOrNull(titleIdx)?.trim().orEmpty()
            val artist = row.getOrNull(artistIdx)?.trim().orEmpty()
            val album = row.getOrNull(albumIdx)?.trim().takeIf { !it.isNullOrBlank() }
            val rawDuration = row.getOrNull(durationIdx)?.trim()
            val rawUri = row.getOrNull(uriIdx)?.trim()
            val isrc = row.getOrNull(isrcIdx)?.trim().takeIf { !it.isNullOrBlank() }

            val durationMs = parseDurationMs(rawDuration)

            // Local file & Episode heuristics
            val isLocal = rawUri?.contains("spotify:local:", ignoreCase = true) == true ||
                rawUri?.startsWith("local", ignoreCase = true) == true

            val isEpisode = rawUri?.contains("spotify:episode:", ignoreCase = true) == true

            if (isLocal) localFilesCount++
            if (isEpisode) episodesCount++

            if (title.isBlank() && artist.isBlank()) {
                invalidRowsCount++
                skippedRows++
                continue
            }

            if (isLocal || isEpisode) {
                skippedRows++
            } else {
                parsedRows++
            }

            tracks.add(
                ImportedTrack(
                    sourceOrder = sourceOrder,
                    title = if (title.isNotBlank()) title else "Unknown Title",
                    artist = if (artist.isNotBlank()) artist else "Unknown Artist",
                    album = album,
                    durationMs = durationMs,
                    isrc = isrc,
                    spotifyUri = rawUri,
                    isLocalFile = isLocal,
                    isEpisode = isEpisode
                )
            )
        }

        val sanitizedPlaylistName = fallbackPlaylistName.trim().take(60).ifBlank { "Imported Playlist" }

        return ParseSummary(
            playlistName = sanitizedPlaylistName,
            totalSourceRows = totalSourceRows,
            parsedRows = parsedRows,
            skippedRows = skippedRows,
            localFilesCount = localFilesCount,
            episodesCount = episodesCount,
            invalidRowsCount = invalidRowsCount,
            tracks = tracks
        )
    }

    /**
     * Parses standard RFC-4180 CSV rows.
     */
    private fun parseCsvRows(reader: BufferedReader): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false
        var isFirstChar = true

        var intChar = reader.read()
        while (intChar != -1) {
            val c = intChar.toChar()

            // Strip UTF-8 BOM if present on the very first character
            if (isFirstChar) {
                isFirstChar = false
                if (c == '\uFEFF') {
                    intChar = reader.read()
                    continue
                }
            }

            if (insideQuotes) {
                if (c == '"') {
                    // Peek ahead for escaped quote ("")
                    reader.mark(1)
                    val nextInt = reader.read()
                    if (nextInt != -1 && nextInt.toChar() == '"') {
                        currentField.append('"')
                    } else {
                        reader.reset()
                        insideQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        insideQuotes = true
                    }
                    ',' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                    }
                    '\r' -> {
                        // Check for \r\n
                        reader.mark(1)
                        val nextInt = reader.read()
                        if (nextInt != -1 && nextInt.toChar() != '\n') {
                            reader.reset()
                        }
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    else -> {
                        currentField.append(c)
                    }
                }
            }

            intChar = reader.read()
        }

        // Add trailing field and row if any
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow)
        }

        return rows
    }

    private fun cleanHeader(header: String): String {
        return header.trim().lowercase().replace(Regex("[^a-z0-9() ]"), "")
    }

    private fun findHeaderIndex(headers: List<String>, candidates: List<String>): Int {
        for (candidate in candidates) {
            val idx = headers.indexOfFirst { it == candidate }
            if (idx != -1) return idx
        }
        return -1
    }

    private fun parseDurationMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim()
        // Integer milliseconds
        clean.toLongOrNull()?.let { return it }

        // mm:ss format
        val parts = clean.split(":")
        if (parts.size == 2) {
            val mins = parts[0].toLongOrNull() ?: return null
            val secs = parts[1].toLongOrNull() ?: return null
            return (mins * 60 + secs) * 1000L
        }
        if (parts.size == 3) {
            val hrs = parts[0].toLongOrNull() ?: return null
            val mins = parts[1].toLongOrNull() ?: return null
            val secs = parts[2].toLongOrNull() ?: return null
            return (hrs * 3600 + mins * 60 + secs) * 1000L
        }
        return null
    }
}
