package com.example.sonara.playback

import com.example.sonara.playback.controller.TransitionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionManagerTest {

    @Test
    fun `nextGeneration generates monotonically increasing IDs`() {
        val manager = TransitionManager()
        val gen1 = manager.nextGeneration()
        val gen2 = manager.nextGeneration()
        val gen3 = manager.nextGeneration()

        assertEquals(1L, gen1)
        assertEquals(2L, gen2)
        assertEquals(3L, gen3)
    }

    @Test
    fun `isAuthoritative returns true only for current generation`() {
        val manager = TransitionManager()
        val gen1 = manager.nextGeneration()
        assertTrue(manager.isAuthoritative(gen1))

        val gen2 = manager.nextGeneration()
        assertFalse("Stale generation 1 must not be authoritative", manager.isAuthoritative(gen1))
        assertTrue("Generation 2 must be authoritative", manager.isAuthoritative(gen2))
    }

    @Test
    fun `rapid skip invalidates earlier transition requests`() {
        val manager = TransitionManager()
        val requestA = manager.nextGeneration() // Rapid Next 1
        val requestB = manager.nextGeneration() // Rapid Next 2
        val requestC = manager.nextGeneration() // Rapid Previous

        assertFalse(manager.isAuthoritative(requestA))
        assertFalse(manager.isAuthoritative(requestB))
        assertTrue(manager.isAuthoritative(requestC))
    }
}
