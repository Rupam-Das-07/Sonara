package com.example.sonara.playback

import com.example.sonara.domain.model.Track
import com.example.sonara.playback.controller.PreviousAction
import com.example.sonara.playback.controller.QueueManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueManagerTest {

    private val sampleTracks = listOf(
        Track(id = "1", title = "Track 1", artist = "Artist 1"),
        Track(id = "2", title = "Track 2", artist = "Artist 2"),
        Track(id = "3", title = "Track 3", artist = "Artist 3")
    )

    @Test
    fun `initial queue starts at index 0`() {
        val queue = QueueManager(sampleTracks)
        val current = queue.currentTrack()
        assertNotNull(current)
        assertEquals("Track 1", current?.title)
    }

    @Test
    fun `advance steps through queue until end`() {
        val queue = QueueManager(sampleTracks)
        assertEquals("Track 2", queue.advance()?.title)
        assertEquals("Track 3", queue.advance()?.title)
        assertNull("End of queue should return null", queue.advance())
    }

    @Test
    fun `previous with position greater than 3000ms restarts current track`() {
        val queue = QueueManager(sampleTracks)
        queue.advance() // Now on Track 2

        val action = queue.previous(currentPositionMs = 4500L)
        assertEquals(PreviousAction.SeekToStart, action)
        assertEquals("Track 2", queue.currentTrack()?.title)
    }

    @Test
    fun `previous with position less than or equal to 3000ms goes to previous track`() {
        val queue = QueueManager(sampleTracks)
        queue.advance() // Now on Track 2

        val action = queue.previous(currentPositionMs = 1500L)
        assertEquals(PreviousAction.GoToPreviousTrack, action)
        assertEquals("Track 1", queue.currentTrack()?.title)
    }

    @Test
    fun `previous on first track returns SeekToStart`() {
        val queue = QueueManager(sampleTracks)
        val action = queue.previous(currentPositionMs = 500L)
        assertEquals(PreviousAction.SeekToStart, action)
        assertEquals("Track 1", queue.currentTrack()?.title)
    }
}
