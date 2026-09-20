package com.example.sonara.data.import.spotify

import com.example.sonara.domain.model.ParseSummary
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Top-level dispatcher for Spotify export file parsing.
 *
 * Uses content sniffing (first non-whitespace bytes) rather than relying exclusively
 * on file extensions. Imforces the 5 MB maximum file read cap.
 */
object SpotifyExportParser {

    private const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB

    /**
     * Parses a Spotify export input stream into a [ParseSummary].
     *
     * @param inputStream Source stream.
     * @param fallbackPlaylistName Fallback name if omitted in the export.
     * @return [ParseSummary]
     */
    fun parse(inputStream: InputStream, fallbackPlaylistName: String = "Imported Playlist"): ParseSummary {
        // Enforce 5 MB read cap by reading up to 5MB + 1 byte
        val buffered = BufferedInputStream(inputStream)
        val buffer = ByteArray(MAX_FILE_SIZE_BYTES + 1)
        var totalRead = 0

        while (totalRead < buffer.size) {
            val read = buffered.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }

        if (totalRead > MAX_FILE_SIZE_BYTES) {
            throw IllegalArgumentException("Export file exceeds the 5 MB size limit")
        }

        if (totalRead == 0) {
            throw IllegalArgumentException("Export file is empty")
        }

        // Sniff content
        val isJson = sniffIsJson(buffer, totalRead)
        val streamForParsing = ByteArrayInputStream(buffer, 0, totalRead)

        return if (isJson) {
            SpotifyJsonParser.parse(streamForParsing, fallbackPlaylistName)
        } else {
            SpotifyCsvParser.parse(streamForParsing, fallbackPlaylistName)
        }
    }

    private fun sniffIsJson(bytes: ByteArray, length: Int): Boolean {
        for (i in 0 until length) {
            val b = bytes[i].toInt().toChar()
            // Skip UTF-8 BOM bytes if at start
            if (i < 3 && (bytes[i].toInt() and 0xFF) in listOf(0xEF, 0xBB, 0xBF)) {
                continue
            }
            if (b.isWhitespace()) {
                continue
            }
            return b == '{' || b == '['
        }
        return false
    }
}
