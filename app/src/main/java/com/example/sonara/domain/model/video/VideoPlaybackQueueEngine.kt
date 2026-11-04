package com.example.sonara.domain.model.video

import com.example.sonara.domain.model.Track

/** UI-independent queue authority for video playback. It never touches the Media3 song queue. */
class VideoPlaybackQueueEngine(
    private val maxPrevious: Int = 100,
    private val previousRestartThresholdMs: Long = 3_000L
) {
    private var current: Track? = null
    private val upcoming = mutableListOf<QueueEntry>()
    private val previous = mutableListOf<Track>()
    private val sessionOrder = mutableListOf<Track>()
    private var shuffled = false
    private var repeatMode = VideoRepeatMode.OFF
    private var generation = 0L
    private var nextOrder = 0L

    private data class QueueEntry(val track: Track, val order: Long)

    fun snapshot(isReplenishing: Boolean = false, noCandidatesAvailable: Boolean = false): VideoQueueState =
        VideoQueueState(
            current = current,
            upcoming = upcoming.map { it.track },
            previous = previous.toList(),
            isShuffled = shuffled,
            repeatMode = repeatMode,
            generation = generation,
            isReplenishing = isReplenishing,
            noCandidatesAvailable = noCandidatesAvailable
        )

    fun seed(track: Track): Long {
        current = track
        upcoming.clear()
        previous.clear()
        sessionOrder.clear()
        sessionOrder += track
        shuffled = false
        repeatMode = VideoRepeatMode.OFF
        generation++
        nextOrder = 0L
        return generation
    }

    fun isCurrentGeneration(candidateGeneration: Long): Boolean = candidateGeneration == generation

    fun upcomingCount(): Int = upcoming.size

    fun shouldReplenish(lowWatermark: Int = 3): Boolean = current != null && upcoming.size < lowWatermark

    fun append(candidates: List<Track>): List<Track> {
        val existingIds = mutableSetOf<String>()
        val existingSignatures = mutableSetOf<String>()
        current?.let {
            existingIds += VideoRecommendationRanker.normalizeId(it.id)
            existingSignatures += VideoRecommendationRanker.signature(it)
        }
        previous.forEach {
            existingIds += VideoRecommendationRanker.normalizeId(it.id)
            existingSignatures += VideoRecommendationRanker.signature(it)
        }
        upcoming.forEach {
            existingIds += VideoRecommendationRanker.normalizeId(it.track.id)
            existingSignatures += VideoRecommendationRanker.signature(it.track)
        }

        val accepted = mutableListOf<Track>()
        candidates.forEach { track ->
            val id = VideoRecommendationRanker.normalizeId(track.id)
            val signature = VideoRecommendationRanker.signature(track)
            if (id.isBlank() || !existingIds.add(id) || !existingSignatures.add(signature)) return@forEach
            val entry = QueueEntry(track, nextOrder++)
            upcoming += entry
            accepted += track
        }
        if (shuffled && upcoming.size > 1) upcoming.shuffle()
        return accepted
    }

    fun next(): VideoNextDecision {
        val active = current ?: return VideoNextDecision.Stop
        if (repeatMode == VideoRepeatMode.ONE) return VideoNextDecision.ReplayCurrent

        val next = when {
            upcoming.isNotEmpty() -> upcoming.removeAt(0).track
            repeatMode == VideoRepeatMode.ALL -> {
                val replay = sessionOrder.filter { VideoRecommendationRanker.normalizeId(it.id) != VideoRecommendationRanker.normalizeId(active.id) }
                upcoming += replay.map { QueueEntry(it, nextOrder++) }
                if (upcoming.isNotEmpty()) upcoming.removeAt(0).track else null
            }
            else -> null
        }

        if (next != null) {
            previous += active
            if (previous.size > maxPrevious) previous.removeAt(0)
            current = next
            if (sessionOrder.none { VideoRecommendationRanker.normalizeId(it.id) == VideoRecommendationRanker.normalizeId(next.id) }) {
                sessionOrder += next
            }
            return VideoNextDecision.Play(next)
        }
        return if (repeatMode == VideoRepeatMode.OFF) {
            VideoNextDecision.NeedRecommendations(active)
        } else {
            VideoNextDecision.Stop
        }
    }

    fun previous(currentPositionMs: Long): VideoPreviousDecision {
        if (currentPositionMs > previousRestartThresholdMs) return VideoPreviousDecision.RestartCurrent
        val prior = previous.removeLastOrNull() ?: return VideoPreviousDecision.RestartCurrent
        current?.let { upcoming.add(0, QueueEntry(it, nextOrder++)) }
        current = prior
        return VideoPreviousDecision.Play(prior)
    }

    fun toggleShuffle(): Boolean {
        shuffled = !shuffled
        if (shuffled) upcoming.shuffle()
        else upcoming.sortBy { it.order }
        return shuffled
    }

    fun toggleRepeat(): VideoRepeatMode {
        repeatMode = when (repeatMode) {
            VideoRepeatMode.OFF -> VideoRepeatMode.ALL
            VideoRepeatMode.ALL -> VideoRepeatMode.ONE
            VideoRepeatMode.ONE -> VideoRepeatMode.OFF
        }
        return repeatMode
    }

    fun recentIds(): Set<String> = previous.mapTo(mutableSetOf()) {
        VideoRecommendationRanker.normalizeId(it.id)
    }

    fun clear() {
        current = null
        upcoming.clear()
        previous.clear()
        sessionOrder.clear()
        generation++
        shuffled = false
        repeatMode = VideoRepeatMode.OFF
    }
}
