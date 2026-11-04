package com.example.sonara.domain.model.video

import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackQueueEngineTest {
    private val a = track("a")
    private val b = track("b")
    private val c = track("c")
    private val d = track("d")

    @Test
    fun `seed excludes duplicates and low watermark requests replenishment`() {
        val engine = VideoPlaybackQueueEngine()
        val generation = engine.seed(a)
        assertTrue(engine.isCurrentGeneration(generation))
        assertEquals(listOf(b, c), engine.append(listOf(a, b, b, c)))
        assertTrue(engine.shouldReplenish())
        assertEquals(2, engine.snapshot().upcoming.size)
        assertTrue(engine.shouldReplenish(3))
    }

    @Test
    fun `next and previous preserve bounded history with restart threshold`() {
        val engine = VideoPlaybackQueueEngine()
        engine.seed(a)
        engine.append(listOf(b, c))

        assertEquals(VideoNextDecision.Play(b), engine.next())
        assertEquals(VideoPreviousDecision.RestartCurrent, engine.previous(3_001L))
        assertEquals(VideoPreviousDecision.Play(a), engine.previous(2_000L))
        assertEquals(a, engine.snapshot().current)
    }

    @Test
    fun `shuffle preserves current and unshuffle restores insertion order`() {
        val engine = VideoPlaybackQueueEngine()
        engine.seed(a)
        engine.append(listOf(b, c, d))
        assertEquals(a, engine.snapshot().current)
        assertTrue(engine.toggleShuffle())
        assertEquals(a, engine.snapshot().current)
        assertFalse(engine.toggleShuffle())
        assertEquals(listOf(b, c, d), engine.snapshot().upcoming)
    }

    @Test
    fun `repeat one replays and repeat all cycles session`() {
        val engine = VideoPlaybackQueueEngine()
        engine.seed(a)
        engine.append(listOf(b))
        assertEquals(VideoRepeatMode.ALL, engine.toggleRepeat())
        assertEquals(VideoNextDecision.Play(b), engine.next())
        assertEquals(VideoNextDecision.Play(a), engine.next())

        val one = VideoPlaybackQueueEngine()
        one.seed(a)
        assertEquals(VideoRepeatMode.ALL, one.toggleRepeat())
        assertEquals(VideoRepeatMode.ONE, one.toggleRepeat())
        assertEquals(VideoNextDecision.ReplayCurrent, one.next())
    }

    @Test
    fun `empty queue asks for recommendations without stopping current`() {
        val engine = VideoPlaybackQueueEngine()
        engine.seed(a)

        assertEquals(VideoNextDecision.NeedRecommendations(a), engine.next())
        assertTrue(engine.recentIds().isEmpty())
        assertEquals(a, engine.snapshot().current)
    }

    @Test
    fun `recent ids normalize previous track ids`() {
        val engine = VideoPlaybackQueueEngine()
        val seed = track("yt-SeedID")
        val next = track("YT-NextID")
        engine.seed(seed)
        engine.append(listOf(next))
        assertEquals(VideoNextDecision.Play(next), engine.next())

        assertEquals(setOf("seedid"), engine.recentIds())
    }

    @Test
    fun `new seed rejects stale generation`() {
        val engine = VideoPlaybackQueueEngine()
        val first = engine.seed(a)
        val second = engine.seed(b)

        assertFalse(engine.isCurrentGeneration(first))
        assertTrue(engine.isCurrentGeneration(second))
        assertEquals(b, engine.snapshot().current)
    }

    @Test
    fun `video queue is independent from song queue state`() {
        val videoEngine = VideoPlaybackQueueEngine()
        videoEngine.seed(a)
        videoEngine.append(listOf(b))
        videoEngine.next()

        val freshVideoEngine = VideoPlaybackQueueEngine()
        assertEquals(null, freshVideoEngine.snapshot().current)
        assertTrue(videoEngine.snapshot().current == b)
    }

    private fun track(id: String) = Track(id = id, title = "Song $id", artist = "Artist $id", durationMs = 180_000L)
}
