package com.example.sonara.data

import com.example.sonara.data.local.db.dao.HistoryItemWithTrack
import com.example.sonara.data.local.db.entity.HistoryEntity
import com.example.sonara.data.local.db.entity.LikedSongEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMappingTest {

    @Test
    fun trackEntity_mapsToAndFromDomainCorrectly() {
        val domainTrack = Track(
            id = "track_123",
            title = "Acoustic Sun",
            artist = "Sonara Artist",
            album = "Ambient EP",
            durationMs = 210000L,
            artworkUrl = "https://example.com/art.jpg"
        )

        val entity = TrackEntity.fromDomain(domainTrack)
        assertEquals("track_123", entity.id)
        assertEquals("Acoustic Sun", entity.title)
        assertEquals("Sonara Artist", entity.artist)
        assertEquals("Ambient EP", entity.album)
        assertEquals(210000L, entity.durationMs)
        assertEquals("https://example.com/art.jpg", entity.artworkUrl)

        val mappedBack = entity.toDomain()
        assertEquals(domainTrack, mappedBack)
    }

    @Test
    fun historyItemWithTrack_mapsToDomainCorrectly() {
        val trackEntity = TrackEntity(
            id = "t1",
            title = "Title 1",
            artist = "Artist 1",
            album = "Album 1",
            durationMs = 180000L
        )
        val historyWithTrack = HistoryItemWithTrack(
            historyId = 42L,
            listenedAt = 1700000000000L,
            completed = true,
            track = trackEntity
        )

        val domainItem = historyWithTrack.toDomain()
        assertEquals(42L, domainItem.id)
        assertEquals("t1", domainItem.track.id)
        assertEquals("Title 1", domainItem.track.title)
        assertEquals(1700000000000L, domainItem.listenedAt)
        assertTrue(domainItem.completed)
    }
}
