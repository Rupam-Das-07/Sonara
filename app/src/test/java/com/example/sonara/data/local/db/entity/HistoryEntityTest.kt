package com.example.sonara.data.local.db.entity

import com.example.sonara.domain.model.PlaybackOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryEntityTest {
    @Test
    fun legacyDefaultsAreSafeAndDoNotInventProvenance() {
        val entity = HistoryEntity(trackId = "track")
        assertEquals(PlaybackOrigin.LEGACY_UNKNOWN.name, entity.sourceSection)
        assertNull(entity.playbackSessionId)
    }
}
