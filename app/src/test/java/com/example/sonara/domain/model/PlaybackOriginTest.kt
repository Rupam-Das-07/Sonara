package com.example.sonara.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOriginTest {
    @Test
    fun unknownOrLegacyStorageFailsClosedToLegacyUnknown() {
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, PlaybackOrigin.fromStorage(null))
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, PlaybackOrigin.fromStorage(""))
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN, PlaybackOrigin.fromStorage("future_value"))
    }

    @Test
    fun supportedStorageValuesRoundTrip() {
        PlaybackOrigin.entries.forEach { origin ->
            assertEquals(origin, PlaybackOrigin.fromStorage(origin.name))
        }
    }
}
