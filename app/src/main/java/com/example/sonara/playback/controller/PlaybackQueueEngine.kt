package com.example.sonara.playback.controller

import com.example.sonara.domain.model.Track

/**
 * Decisions produced by [PlaybackQueueEngine.advance].
 */
sealed interface NextTrackDecision {
    data class PlayTrack(val track: Track) : NextTrackDecision
    data class NeedRecommendations(val seedTrackId: String) : NextTrackDecision
    data object ReplayCurrent : NextTrackDecision
    data object StopPlayback : NextTrackDecision
}

/**
 * Decisions produced by [PlaybackQueueEngine.previous].
 */
sealed interface PreviousTrackDecision {
    data object SeekToStart : PreviousTrackDecision
    data class PlayTrack(val track: Track) : PreviousTrackDecision
    data object None : PreviousTrackDecision
}

/**
 * PlaybackQueueEngine — Single authority for queue state, session history,
 * Previous/Next sequencing, candidate deduplication, shuffle, and repeat modes.
 *
 * Implements exact parity with the Sonara Web continuous playback engine:
 * 1. Explicit upcoming queue (from playlists, albums, search, etc.)
 * 2. Session BackStack (LIFO history, max 100 items) — all played tracks enter here
 * 3. Recommendation staging cache (MusicBrainz / ListenBrainz / YTMusic radio)
 * 4. Multi-set candidate deduplication against current track, queue, backstack, and cache
 * 5. 3000ms Previous threshold rule (seek to start vs pop previous track)
 * 6. Repeat One (2), Repeat All (1), and Shuffle modes
 */
class PlaybackQueueEngine(
    initialTracks: List<Track> = emptyList()
) {
    private var _currentTrack: Track? = null
    val currentTrack: Track? get() = _currentTrack

    private val _upcomingQueue = mutableListOf<Track>()
    val upcomingQueue: List<Track> get() = _upcomingQueue.toList()

    private val _sessionBackStack = mutableListOf<Track>()
    val sessionBackStack: List<Track> get() = _sessionBackStack.toList()

    private val _recommendationCache = mutableListOf<Track>()
    val recommendationCache: List<Track> get() = _recommendationCache.toList()

    private val _sessionSnapshot = mutableListOf<Track>()

    private var _isShuffled: Boolean = false
    val isShuffled: Boolean get() = _isShuffled

    private var _repeatMode: Int = 0 // 0 = Off, 1 = Repeat All, 2 = Repeat One
    val repeatMode: Int get() = _repeatMode

    @Volatile
    var onQueueChanged: (() -> Unit)? = null

    /**
     * Determines whether the playback queue can advance forward.
     * Returns true if upcoming queued tracks exist, repeat mode is active,
     * staged recommendations exist, or an active track exists for continuous radio recommendations.
     */
    @Synchronized
    fun canAdvance(): Boolean {
        return _upcomingQueue.isNotEmpty() ||
                _repeatMode != 0 ||
                _recommendationCache.isNotEmpty() ||
                (_currentTrack != null && _currentTrack!!.id.isNotBlank())
    }

    init {
        if (initialTracks.isNotEmpty()) {
            _currentTrack = initialTracks.first()
            _upcomingQueue.addAll(initialTracks.drop(1))
            _sessionSnapshot.addAll(initialTracks)
        }
    }

    /**
     * Initializes or switches playback context with an explicit track and optional upcoming queue.
     */
    @Synchronized
    fun setContext(
        track: Track,
        contextQueue: List<Track> = emptyList(),
        pushCurrentToBackStack: Boolean = true
    ) {
        val prev = _currentTrack
        if (pushCurrentToBackStack && prev != null && prev.id != track.id) {
            pushToBackStack(prev)
        }
        _currentTrack = track

        if (contextQueue.isNotEmpty()) {
            val trackIndex = contextQueue.indexOfFirst { it.id == track.id }
            _upcomingQueue.clear()
            if (trackIndex >= 0) {
                _upcomingQueue.addAll(contextQueue.drop(trackIndex + 1))
            } else {
                _upcomingQueue.addAll(contextQueue)
            }
            _sessionSnapshot.clear()
            _sessionSnapshot.addAll(contextQueue)
        } else {
            _upcomingQueue.clear()
            _sessionSnapshot.clear()
            _sessionSnapshot.add(track)
        }

        // On manual context switch, apply shuffle if currently enabled
        if (_isShuffled && _upcomingQueue.size > 1) {
            _upcomingQueue.shuffle()
        }

        // Clear recommendation cache on explicit context switch so recommendations match new seed
        _recommendationCache.clear()
        onQueueChanged?.invoke()
    }

    /**
     * Advances to the next track in the queue, repeat cycle, or recommendation pool.
     * Guaranteed to push the current track to [sessionBackStack] when moving forward.
     *
     * @param fromTrackId Optional ID of the track initiating the advance. If provided and
     *                    the queue has already advanced past this track, the already-advanced
     *                    current track is returned without advancing twice.
     */
    @Synchronized
    fun advance(fromTrackId: String? = null): NextTrackDecision {
        if (fromTrackId != null && _currentTrack != null && _currentTrack?.id != fromTrackId) {
            return _currentTrack?.let { NextTrackDecision.PlayTrack(it) } ?: NextTrackDecision.StopPlayback
        }
        val curr = _currentTrack

        // 1. Repeat One (mode 2)
        if (_repeatMode == 2 && curr != null) {
            onQueueChanged?.invoke()
            return NextTrackDecision.ReplayCurrent
        }

        // 2. Push current track to session backstack
        if (curr != null) {
            pushToBackStack(curr)
        }

        // 3. Upcoming Queue has items
        if (_upcomingQueue.isNotEmpty()) {
            val next = _upcomingQueue.removeAt(0)
            _currentTrack = next
            onQueueChanged?.invoke()
            return NextTrackDecision.PlayTrack(next)
        }

        // 4. Repeat All (mode 1) with session snapshot
        if (_repeatMode == 1 && _sessionSnapshot.isNotEmpty()) {
            _upcomingQueue.addAll(_sessionSnapshot)
            if (_isShuffled && _upcomingQueue.size > 1) {
                _upcomingQueue.shuffle()
            }
            if (_upcomingQueue.isNotEmpty()) {
                val next = _upcomingQueue.removeAt(0)
                _currentTrack = next
                onQueueChanged?.invoke()
                return NextTrackDecision.PlayTrack(next)
            }
        }

        // 5. Staged Recommendation Cache has pre-fetched candidates
        if (_recommendationCache.isNotEmpty()) {
            val next = _recommendationCache.removeAt(0)
            _currentTrack = next
            onQueueChanged?.invoke()
            return NextTrackDecision.PlayTrack(next)
        }

        // 6. Need dynamic recommendations from backend
        if (curr != null && curr.id.isNotBlank()) {
            onQueueChanged?.invoke()
            return NextTrackDecision.NeedRecommendations(curr.id)
        }

        onQueueChanged?.invoke()
        return NextTrackDecision.StopPlayback
    }

    /**
     * Determines previous track behavior based on current playback position in milliseconds.
     * - If position > 3000ms: restarts current track.
     * - If position <= 3000ms: pops last track from [sessionBackStack], prepends current track
     *   back to [_upcomingQueue], and plays the previous track.
     */
    @Synchronized
    fun previous(currentPositionMs: Long): PreviousTrackDecision {
        if (currentPositionMs > 3000L) {
            return PreviousTrackDecision.SeekToStart
        }

        if (_sessionBackStack.isEmpty()) {
            return PreviousTrackDecision.SeekToStart
        }

        val prev = _sessionBackStack.removeAt(_sessionBackStack.size - 1)
        val curr = _currentTrack
        if (curr != null) {
            _upcomingQueue.add(0, curr)
        }
        _currentTrack = prev
        onQueueChanged?.invoke()
        return PreviousTrackDecision.PlayTrack(prev)
    }

    /**
     * Ingests recommendation candidates after multi-set deduplication.
     */
    @Synchronized
    fun ingestRecommendations(candidates: List<Track>) {
        val filtered = deduplicateCandidates(candidates)
        _recommendationCache.addAll(filtered)
        onQueueChanged?.invoke()
    }

    /**
     * Deduplicates candidates against current track, upcoming queue, session backstack,
     * existing recommendation cache, and normalized title+artist signatures.
     */
    @Synchronized
    fun deduplicateCandidates(candidates: List<Track>): List<Track> {
        val existingIds = mutableSetOf<String>()
        _currentTrack?.let { existingIds.add(it.id) }
        _upcomingQueue.forEach { existingIds.add(it.id) }
        _sessionBackStack.forEach { existingIds.add(it.id) }
        _recommendationCache.forEach { existingIds.add(it.id) }

        val existingNormalizedSignatures = mutableSetOf<String>()
        fun sig(t: Track): String = (t.title.trim().lowercase() + "|||" + t.artist.trim().lowercase())

        _currentTrack?.let { existingNormalizedSignatures.add(sig(it)) }
        _upcomingQueue.forEach { existingNormalizedSignatures.add(sig(it)) }
        _sessionBackStack.forEach { existingNormalizedSignatures.add(sig(it)) }
        _recommendationCache.forEach { existingNormalizedSignatures.add(sig(it)) }

        val result = mutableListOf<Track>()
        for (candidate in candidates) {
            if (candidate.id.isBlank()) continue
            if (candidate.id in existingIds) continue
            val s = sig(candidate)
            if (s in existingNormalizedSignatures) continue

            existingIds.add(candidate.id)
            existingNormalizedSignatures.add(s)
            result.add(candidate)
        }
        return result
    }

    @Synchronized
    fun toggleShuffle(): Boolean {
        _isShuffled = !_isShuffled
        if (_isShuffled && _upcomingQueue.size > 1) {
            _upcomingQueue.shuffle()
        }
        onQueueChanged?.invoke()
        return _isShuffled
    }

    @Synchronized
    fun toggleRepeatMode(): Int {
        _repeatMode = (_repeatMode + 1) % 3
        onQueueChanged?.invoke()
        return _repeatMode
    }

    private fun pushToBackStack(track: Track) {
        val last = _sessionBackStack.lastOrNull()
        if (last?.id != track.id) {
            _sessionBackStack.add(track)
            if (_sessionBackStack.size > 100) {
                _sessionBackStack.removeAt(0)
            }
        }
    }

    @Synchronized
    fun clear() {
        _currentTrack = null
        _upcomingQueue.clear()
        _sessionBackStack.clear()
        _recommendationCache.clear()
        _sessionSnapshot.clear()
        onQueueChanged?.invoke()
    }
}
