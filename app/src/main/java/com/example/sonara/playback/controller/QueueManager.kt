package com.example.sonara.playback.controller

import com.example.sonara.domain.model.Track

enum class PreviousAction {
    SeekToStart,
    GoToPreviousTrack,
    None
}

/**
 * Manages playback queue, index position, and previous/next navigation rules.
 * Implements the 3000ms previous track threshold logic from Phase 4D.
 */
class QueueManager(
    initialTracks: List<Track> = emptyList()
) {
    private val tracks = mutableListOf<Track>().apply { addAll(initialTracks) }
    private var currentIndex: Int = 0

    @Synchronized
    fun setQueue(newTracks: List<Track>, startIndex: Int = 0) {
        tracks.clear()
        tracks.addAll(newTracks)
        currentIndex = if (newTracks.isNotEmpty()) {
            startIndex.coerceIn(0, newTracks.size - 1)
        } else {
            0
        }
    }

    @Synchronized
    fun currentTrack(): Track? {
        if (tracks.isEmpty() || currentIndex !in tracks.indices) return null
        return tracks[currentIndex]
    }

    @Synchronized
    fun advance(): Track? {
        if (tracks.isEmpty()) return null
        if (currentIndex < tracks.size - 1) {
            currentIndex++
            return tracks[currentIndex]
        }
        return null
    }

    @Synchronized
    fun previous(currentPositionMs: Long): PreviousAction {
        if (tracks.isEmpty()) return PreviousAction.None

        // Phase 4D §5: If position > 3000ms, restart current track; otherwise go to previous
        return if (currentPositionMs > 3000L) {
            PreviousAction.SeekToStart
        } else if (currentIndex > 0) {
            currentIndex--
            PreviousAction.GoToPreviousTrack
        } else {
            PreviousAction.SeekToStart
        }
    }

    @Synchronized
    fun getQueue(): List<Track> = tracks.toList()

    @Synchronized
    fun hasNext(): Boolean = currentIndex < tracks.size - 1

    @Synchronized
    fun hasPrevious(): Boolean = currentIndex > 0

    @Synchronized
    fun clear() {
        tracks.clear()
        currentIndex = 0
    }
}
