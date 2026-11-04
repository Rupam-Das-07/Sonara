package com.example.sonara.domain.model

/**
 * Provenance recorded for a qualifying playback session.
 *
 * Callers pass this value with the play intent. Legacy or restored playback that has no
 * trustworthy initiating surface uses [LEGACY_UNKNOWN] and remains ineligible.
 */
enum class PlaybackOrigin {
    SEARCH_SONGS,
    LIKED_SONGS,
    HOME,
    RADIO_AUTOPLAY,
    PLAYLIST,
    VIDEO_SEARCH,
    OTHER,
    LEGACY_UNKNOWN;

    companion object {
        fun fromStorage(value: String?): PlaybackOrigin = value
            ?.trim()
            ?.uppercase()
            ?.let { raw -> entries.firstOrNull { it.name == raw } }
            ?: LEGACY_UNKNOWN
    }
}
