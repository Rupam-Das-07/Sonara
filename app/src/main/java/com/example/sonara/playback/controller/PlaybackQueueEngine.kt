package com.example.sonara.playback.controller

import com.example.sonara.domain.model.PlaybackOrigin
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
    /** A queue/session entry keeps the provenance that was present when it was enqueued. */
    private data class QueueEntry(
        val track: Track,
        val origin: PlaybackOrigin
    )

    private var _currentTrack: Track? = null
    val currentTrack: Track? get() = _currentTrack

    private val _upcomingQueue = mutableListOf<QueueEntry>()
    val upcomingQueue: List<Track> get() = _upcomingQueue.map { it.track }

    private val _sessionBackStack = mutableListOf<QueueEntry>()
    val sessionBackStack: List<Track> get() = _sessionBackStack.map { it.track }

    private val _recommendationCache = mutableListOf<QueueEntry>()
    val recommendationCache: List<Track> get() = _recommendationCache.map { it.track }

    private val _sessionSnapshot = mutableListOf<QueueEntry>()

    private var _isShuffled: Boolean = false
    val isShuffled: Boolean get() = _isShuffled

    private var _repeatMode: Int = 0 // 0 = Off, 1 = Repeat All, 2 = Repeat One
    val repeatMode: Int get() = _repeatMode

    /**
     * Provenance of the CURRENT playback context (Phase 2B).
     *
     * Origin is modelled on the current queue/session entry, not on a Media3 item. That
     * matches this engine, which is already the single authority for what plays next:
     * [setContext] replaces the queue and its entry origins atomically, while explicit
     * Play Next entries retain the origin supplied by their initiating surface.
     *
     * It is deliberately NOT carried in `MediaMetadata.extras`: the service rebuilds
     * every auto-advanced and gapless-preloaded item from a bare [Track] via
     * `SonaraPlaybackService.buildMediaItem`, which sets no extras, so an extras-only
     * channel would silently lose provenance on exactly the paths that must stay
     * ineligible. Keeping it here also makes every rule below JVM-unit-testable, which
     * `MediaMetadata.extras` is not (`isReturnDefaultValues = true` makes Bundle
     * accessors return defaults in unit tests).
     *
     * Fails closed: the default is [PlaybackOrigin.LEGACY_UNKNOWN], any call site that
     * does not supply an origin resets it to LEGACY_UNKNOWN, and [originFor] only
     * answers for the track that is actually current.
     */
    private var _currentOrigin: PlaybackOrigin = PlaybackOrigin.LEGACY_UNKNOWN
    val currentOrigin: PlaybackOrigin get() = _currentOrigin

    /**
     * Provenance for [trackId], or [PlaybackOrigin.LEGACY_UNKNOWN] when it is not the
     * track this context is currently playing.
     *
     * The identity check is what prevents misattribution: a history write that arrives
     * late — after the queue has already moved on — cannot be stamped with the new
     * context's origin.
     */
    @Synchronized
    fun originFor(trackId: String?): PlaybackOrigin {
        if (trackId.isNullOrBlank()) return PlaybackOrigin.LEGACY_UNKNOWN
        if (_currentTrack?.id != trackId) return PlaybackOrigin.LEGACY_UNKNOWN
        return _currentOrigin
    }

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
            val entries = initialTracks.map { QueueEntry(it, PlaybackOrigin.LEGACY_UNKNOWN) }
            _upcomingQueue.addAll(entries.drop(1))
            _sessionSnapshot.addAll(entries)
        }
    }

    /**
     * Initializes or switches playback context with an explicit track and optional upcoming queue.
     */
    @Synchronized
    fun setContext(
        track: Track,
        contextQueue: List<Track> = emptyList(),
        pushCurrentToBackStack: Boolean = true,
        origin: PlaybackOrigin = PlaybackOrigin.LEGACY_UNKNOWN
    ) {
        val prev = _currentTrack
        if (pushCurrentToBackStack && prev != null && prev.id != track.id) {
            pushToBackStack(prev)
        }
        _currentTrack = track
        // Queue and provenance are replaced together, so a new context can never inherit
        // the previous one's origin. An omitted origin fails closed to LEGACY_UNKNOWN.
        _currentOrigin = origin

        if (contextQueue.isNotEmpty()) {
            val trackIndex = contextQueue.indexOfFirst { it.id == track.id }
            _upcomingQueue.clear()
            if (trackIndex >= 0) {
                _upcomingQueue.addAll(
                    contextQueue.drop(trackIndex + 1).map { QueueEntry(it, origin) }
                )
            } else {
                _upcomingQueue.addAll(contextQueue.map { QueueEntry(it, origin) })
            }
            _sessionSnapshot.clear()
            _sessionSnapshot.addAll(contextQueue.map { QueueEntry(it, origin) })
        } else {
            _upcomingQueue.clear()
            _sessionSnapshot.clear()
            _sessionSnapshot.add(QueueEntry(track, origin))
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
     * Inserts [track] at the top of the upcoming queue (index 0) so that it plays
     * immediately after the currently playing track finishes, without interrupting
     * current playback.
     *
     * Invariants enforced:
     * - If [track] id is blank, no-op.
     * - If [track] is already at index 0 of upcoming queue, no-op.
     * - If [track] is located elsewhere in upcoming queue, it is moved/repositioned to index 0.
     * - If [track] exists in recommendation cache, it is removed from cache and placed at index 0.
     * - If current playing track is selected, it is inserted at index 0 so it plays again next.
     * - Synchronizes session snapshot if active.
     * - Fires [onQueueChanged] callback to notify service and listeners.
     */
    @Synchronized
    fun playNext(
        track: Track,
        origin: PlaybackOrigin = PlaybackOrigin.LEGACY_UNKNOWN
    ) {
        if (track.id.isBlank()) return
        if (_upcomingQueue.firstOrNull()?.track?.id == track.id) return

        _upcomingQueue.removeAll { it.track.id == track.id }
        _recommendationCache.removeAll { it.track.id == track.id }
        val entry = QueueEntry(track, origin)
        _upcomingQueue.add(0, entry)

        if (_sessionSnapshot.isNotEmpty()) {
            if (track.id != _currentTrack?.id) {
                _sessionSnapshot.removeAll { it.track.id == track.id }
                val currentIndex = _sessionSnapshot.indexOfFirst { it.track.id == _currentTrack?.id }
                if (currentIndex >= 0) {
                    _sessionSnapshot.add(currentIndex + 1, entry)
                } else {
                    _sessionSnapshot.add(entry)
                }
            }
        }

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

        // 2. Upcoming Queue has items
        if (_upcomingQueue.isNotEmpty()) {
            if (curr != null) {
                pushToBackStack(curr)
            }
            val next = _upcomingQueue.removeAt(0)
            _currentTrack = next.track
            _currentOrigin = next.origin
            onQueueChanged?.invoke()
            return NextTrackDecision.PlayTrack(next.track)
        }

        // 3. Repeat All (mode 1) with session snapshot
        if (_repeatMode == 1 && _sessionSnapshot.isNotEmpty()) {
            _upcomingQueue.addAll(_sessionSnapshot)
            if (_isShuffled && _upcomingQueue.size > 1) {
                _upcomingQueue.shuffle()
            }
            if (_upcomingQueue.isNotEmpty()) {
                if (curr != null) {
                    pushToBackStack(curr)
                }
                val next = _upcomingQueue.removeAt(0)
                _currentTrack = next.track
                _currentOrigin = next.origin
                onQueueChanged?.invoke()
                return NextTrackDecision.PlayTrack(next.track)
            }
        }

        // 4. Staged Recommendation Cache has pre-fetched candidates
        if (_recommendationCache.isNotEmpty()) {
            if (curr != null) {
                pushToBackStack(curr)
            }
            val next = _recommendationCache.removeAt(0)
            _currentTrack = next.track
            // Recommendation entries are staged with RADIO_AUTOPLAY, so they can never
            // inherit the eligible origin of the track that requested them.
            _currentOrigin = next.origin
            onQueueChanged?.invoke()
            return NextTrackDecision.PlayTrack(next.track)
        }

        // 5. Need dynamic recommendations from backend
        if (curr != null && curr.id.isNotBlank()) {
            // Keep the current session's provenance until a replacement track is actually
            // installed. The recommendation request is only an intention; if it fails or
            // returns no usable candidates, the still-current track remains in its context.
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
            _upcomingQueue.add(0, QueueEntry(curr, _currentOrigin))
        }
        _currentTrack = prev.track
        _currentOrigin = prev.origin
        onQueueChanged?.invoke()
        return PreviousTrackDecision.PlayTrack(prev.track)
    }

    /**
     * Ingests recommendation candidates after multi-set deduplication.
     */
    @Synchronized
    fun ingestRecommendations(candidates: List<Track>) {
        val filtered = deduplicateCandidates(candidates)
        _recommendationCache.addAll(filtered.map { QueueEntry(it, PlaybackOrigin.RADIO_AUTOPLAY) })
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
        _upcomingQueue.forEach { existingIds.add(it.track.id) }
        _sessionBackStack.forEach { existingIds.add(it.track.id) }
        _recommendationCache.forEach { existingIds.add(it.track.id) }

        val existingNormalizedSignatures = mutableSetOf<String>()
        fun sig(t: Track): String = (t.title.trim().lowercase() + "|||" + t.artist.trim().lowercase())

        _currentTrack?.let { existingNormalizedSignatures.add(sig(it)) }
        _upcomingQueue.forEach { existingNormalizedSignatures.add(sig(it.track)) }
        _sessionBackStack.forEach { existingNormalizedSignatures.add(sig(it.track)) }
        _recommendationCache.forEach { existingNormalizedSignatures.add(sig(it.track)) }

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
        val entry = QueueEntry(track, _currentOrigin)
        if (last?.track?.id != track.id || last.origin != entry.origin) {
            _sessionBackStack.add(entry)
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
        _currentOrigin = PlaybackOrigin.LEGACY_UNKNOWN
        onQueueChanged?.invoke()
    }
}
