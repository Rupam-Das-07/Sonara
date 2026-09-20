package com.example.sonara.domain.model

/**
 * Deterministic origin classification for Sonara playlists:
 * - [USER]: Created directly by the user inside Sonara.
 * - [SPOTIFY]: Created via Sonara's Spotify playlist import flow.
 */
enum class PlaylistOrigin {
    USER,
    SPOTIFY;

    companion object {
        /**
         * Safely parses a persisted database string into [PlaylistOrigin].
         * Defaults to [USER] for unknown, blank, or null inputs.
         */
        fun fromString(value: String?): PlaylistOrigin = when (value?.trim()?.uppercase()) {
            "SPOTIFY" -> SPOTIFY
            "USER" -> USER
            else -> USER
        }
    }
}
