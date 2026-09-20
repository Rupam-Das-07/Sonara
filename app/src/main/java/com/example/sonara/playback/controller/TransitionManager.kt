package com.example.sonara.playback.controller

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe monotonic transition generation coordinator.
 * Implements the transitionGenerationId specification from Phase 4B-1 and Phase 4D.
 * Prevents race conditions and stale async result commits during rapid user intents.
 */
class TransitionManager {
    private val generationId = AtomicLong(0L)

    /**
     * Increments and returns the next authoritative transition generation ID.
     */
    fun nextGeneration(): Long {
        return generationId.incrementAndGet()
    }

    /**
     * Checks if the given generation ID is still the current authoritative transition.
     */
    fun isAuthoritative(id: Long): Boolean {
        return id == generationId.get()
    }

    /**
     * Returns the current generation ID without modifying it.
     */
    fun currentGeneration(): Long {
        return generationId.get()
    }
}
