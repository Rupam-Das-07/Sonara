package com.example.sonara.playback.controller

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe monotonic transition generation coordinator.
 * Implements the transitionGenerationId specification from Phase 4B-1 and Phase 4D.
 * Prevents race conditions and stale async result commits during rapid user intents.
 */
class TransitionManager(
    initialGeneration: Long = 0L
) {
    private val generationId = AtomicLong(initialGeneration)

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

    /**
     * Updates the generation to [newGen] if and only if [newGen] is strictly greater
     * than the current generation. Thread-safe atomic CAS loop.
     * Returns true if updated, false otherwise.
     */
    fun updateIfGreater(newGen: Long): Boolean {
        while (true) {
            val current = generationId.get()
            if (newGen <= current) return false
            if (generationId.compareAndSet(current, newGen)) return true
        }
    }
}
