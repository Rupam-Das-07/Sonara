package com.example.sonara.playback

import com.example.sonara.playback.controller.TransitionManager
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleTransitionTest {

    @Test
    fun `stale async transition result is safely rejected`() = runBlocking {
        val transitionManager = TransitionManager()
        var committedTrackId: String? = null

        // User initiates transition 1 (slow network resolution: 200ms)
        val gen1 = transitionManager.nextGeneration()
        val job1 = async {
            delay(200)
            if (transitionManager.isAuthoritative(gen1)) {
                committedTrackId = "track_1"
            }
        }

        // User immediately initiates transition 2 (fast network resolution: 50ms)
        delay(30)
        val gen2 = transitionManager.nextGeneration()
        val job2 = async {
            delay(50)
            if (transitionManager.isAuthoritative(gen2)) {
                committedTrackId = "track_2"
            }
        }

        job1.await()
        job2.await()

        // Verify: Track 2 committed, Track 1 dropped
        assertEquals("track_2", committedTrackId)
    }
}
