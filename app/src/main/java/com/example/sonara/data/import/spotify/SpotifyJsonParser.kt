package com.example.sonara.data.import.spotify

import com.example.sonara.domain.model.ParseSummary
import java.io.InputStream

/**
 * Official Spotify "Download Your Data" JSON parser placeholder.
 *
 * STATUS: PENDING VERIFIED REAL SAMPLE.
 *
 * Per architecture rules: No real official Spotify JSON sample was found in the workspace,
 * so key structures must NOT be fabricated or assumed. Safe detection is implemented, and
 * an explicit informative exception is raised directing users to Exportify CSV in V1.
 */
object SpotifyJsonParser {

    /**
     * Attempts to parse official Spotify Download Your Data JSON.
     * Throws [UnsupportedOperationException] indicating schema verification status.
     */
    fun parse(inputStream: InputStream, fallbackPlaylistName: String = "Imported Playlist"): ParseSummary {
        throw UnsupportedOperationException(
            "Official Spotify Download Your Data JSON format is pending verified sample schema. " +
            "Please use standard Exportify CSV format for V1 import."
        )
    }
}
