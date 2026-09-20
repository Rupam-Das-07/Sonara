package com.example.sonara.playback

import com.example.sonara.domain.model.Track
import com.example.sonara.playback.controller.NextTrackDecision
import com.example.sonara.playback.controller.PlaybackQueueEngine
import com.example.sonara.playback.controller.PreviousTrackDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueEngineTest {

    private val track1 = Track(id = "v1", title = "Tum Hi Ho", artist = "Arijit Singh")
    private val track2 = Track(id = "v2", title = "Kesariya", artist = "Arijit Singh")
    private val track3 = Track(id = "v3", title = "Channa Mereya", artist = "Pritam, Arijit Singh")
    private val rec1 = Track(id = "rec1", title = "Raataan Lambiyan", artist = "Jubin Nautiyal")
    private val rec2 = Track(id = "rec2", title = "Ranjha", artist = "B Praak, Jasleen Royal")

    @Test
    fun `setContext sets current track and upcoming queue correctly`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2, track3))

        assertEquals(track1, engine.currentTrack)
        assertEquals(listOf(track2, track3), engine.upcomingQueue)
        assertTrue(engine.sessionBackStack.isEmpty())
    }

    @Test
    fun `advance steps through upcoming queue and populates session backstack`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2, track3))

        // First advance -> goes to track2, track1 goes to backstack
        val decision1 = engine.advance()
        assertTrue(decision1 is NextTrackDecision.PlayTrack)
        assertEquals(track2, (decision1 as NextTrackDecision.PlayTrack).track)
        assertEquals(listOf(track1), engine.sessionBackStack)
        assertEquals(listOf(track3), engine.upcomingQueue)

        // Second advance -> goes to track3, track2 goes to backstack
        val decision2 = engine.advance()
        assertTrue(decision2 is NextTrackDecision.PlayTrack)
        assertEquals(track3, (decision2 as NextTrackDecision.PlayTrack).track)
        assertEquals(listOf(track1, track2), engine.sessionBackStack)
        assertTrue(engine.upcomingQueue.isEmpty())
    }

    @Test
    fun `advance when queue is empty returns NeedRecommendations with seed videoId`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1))

        val decision = engine.advance()
        assertTrue(decision is NextTrackDecision.NeedRecommendations)
        assertEquals("v1", (decision as NextTrackDecision.NeedRecommendations).seedTrackId)
        assertEquals(listOf(track1), engine.sessionBackStack)
    }

    @Test
    fun `ingestRecommendations deduplicates and supplies candidates when queue is exhausted`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2))

        // Ingest candidate pool (contains rec1, rec2, and a duplicate of track1 and track2)
        val duplicateTrack1 = Track(id = "v1", title = "Tum Hi Ho", artist = "Arijit Singh")
        engine.ingestRecommendations(listOf(duplicateTrack1, rec1, rec2))

        // Advance past track2
        engine.advance() // Now on track2, backstack has [track1]

        // Advance past end of upcoming queue -> should pull rec1 from recommendationCache!
        val decision = engine.advance()
        assertTrue(decision is NextTrackDecision.PlayTrack)
        assertEquals(rec1, (decision as NextTrackDecision.PlayTrack).track)
        assertEquals(listOf(track1, track2), engine.sessionBackStack)

        // Advance again -> should pull rec2
        val decision2 = engine.advance()
        assertTrue(decision2 is NextTrackDecision.PlayTrack)
        assertEquals(rec2, (decision2 as NextTrackDecision.PlayTrack).track)
        assertEquals(listOf(track1, track2, rec1), engine.sessionBackStack)
    }

    @Test
    fun `previous with position greater than 3000ms restarts current track`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2))
        engine.advance() // On track2, backstack = [track1]

        val decision = engine.previous(currentPositionMs = 3500L)
        assertEquals(PreviousTrackDecision.SeekToStart, decision)
        assertEquals(track2, engine.currentTrack)
        assertEquals(listOf(track1), engine.sessionBackStack)
    }

    @Test
    fun `previous with position less than or equal to 3000ms pops backstack and prepends current to queue`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2))
        engine.advance() // On track2, backstack = [track1], upcoming = []

        val decision = engine.previous(currentPositionMs = 1500L)
        assertTrue(decision is PreviousTrackDecision.PlayTrack)
        assertEquals(track1, (decision as PreviousTrackDecision.PlayTrack).track)
        assertEquals(track1, engine.currentTrack)
        assertTrue(engine.sessionBackStack.isEmpty())
        assertEquals(listOf(track2), engine.upcomingQueue)
    }

    @Test
    fun `previous on first track with empty backstack returns SeekToStart`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2))

        val decision = engine.previous(currentPositionMs = 500L)
        assertEquals(PreviousTrackDecision.SeekToStart, decision)
        assertEquals(track1, engine.currentTrack)
    }

    @Test
    fun `Repeat One returns ReplayCurrent on advance`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2))
        engine.toggleRepeatMode() // 1 = Repeat All
        engine.toggleRepeatMode() // 2 = Repeat One

        assertEquals(2, engine.repeatMode)
        val decision = engine.advance()
        assertEquals(NextTrackDecision.ReplayCurrent, decision)
        assertEquals(track1, engine.currentTrack)
    }

    @Test
    fun `Repeat All loops queue from session snapshot when queue is exhausted`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2))
        engine.toggleRepeatMode() // 1 = Repeat All

        assertEquals(1, engine.repeatMode)
        engine.advance() // Now on track2

        // Queue exhausted -> Repeat All reloads snapshot [track1, track2] and plays track1
        val decision = engine.advance()
        assertTrue(decision is NextTrackDecision.PlayTrack)
        assertEquals(track1, (decision as NextTrackDecision.PlayTrack).track)
    }

    @Test
    fun `multi-set deduplication rejects tracks in current, queue, backstack, cache, or matching title-artist signature`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2))
        engine.advance() // current = track2, backstack = [track1]

        val rawCandidates = listOf(
            Track(id = "v1", title = "Different Title", artist = "Different Artist"), // ID matches track1
            Track(id = "v2", title = "Different Title 2", artist = "Different Artist 2"), // ID matches track2
            Track(id = "v99", title = "tum hi ho ", artist = " Arijit Singh "), // Title+Artist matches track1
            Track(id = "v100", title = "Unique Song", artist = "Unique Artist")
        )

        val filtered = engine.deduplicateCandidates(rawCandidates)
        assertEquals(1, filtered.size)
        assertEquals("v100", filtered.first().id)
        assertEquals("Unique Song", filtered.first().title)
    }

    @Test
    fun `advance with matching fromTrackId advances queue normally`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2, track3))

        val decision = engine.advance(fromTrackId = "v1")
        assertTrue(decision is NextTrackDecision.PlayTrack)
        assertEquals(track2, (decision as NextTrackDecision.PlayTrack).track)
        assertEquals(listOf(track1), engine.sessionBackStack)
        assertEquals(listOf(track3), engine.upcomingQueue)
    }

    @Test
    fun `advance with stale fromTrackId returns current track without advancing twice`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2, track3))

        // First advance moves to track2
        val decision1 = engine.advance(fromTrackId = "v1")
        assertEquals(track2, (decision1 as NextTrackDecision.PlayTrack).track)

        // Competing call with stale fromTrackId="v1" (e.g. autoplay racing manual skip)
        val decision2 = engine.advance(fromTrackId = "v1")
        assertTrue(decision2 is NextTrackDecision.PlayTrack)
        // Must return track2 without skipping to track3!
        assertEquals(track2, (decision2 as NextTrackDecision.PlayTrack).track)
        assertEquals(track2, engine.currentTrack)
        assertEquals(listOf(track3), engine.upcomingQueue)
        assertEquals(listOf(track1), engine.sessionBackStack)
    }

    @Test
    fun `sequential advance with updated fromTrackId advances sequentially`() {
        val engine = PlaybackQueueEngine()
        engine.setContext(track1, listOf(track1, track2, track3))

        // Intentional skip 1
        val decision1 = engine.advance(fromTrackId = "v1")
        assertEquals(track2, (decision1 as NextTrackDecision.PlayTrack).track)

        // Intentional skip 2 (using updated current track ID)
        val decision2 = engine.advance(fromTrackId = "v2")
        assertEquals(track3, (decision2 as NextTrackDecision.PlayTrack).track)
        assertEquals(track3, engine.currentTrack)
        assertTrue(engine.upcomingQueue.isEmpty())
        assertEquals(listOf(track1, track2), engine.sessionBackStack)
    }
}
