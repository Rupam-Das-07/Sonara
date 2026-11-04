package com.example.sonara.domain.model.video

import com.example.sonara.domain.model.Track

enum class VideoRecommendationSource {
    RADIO,
    RELATED,
    ARTIST_SEARCH
}

data class VideoRecommendationCandidate(
    val track: Track,
    val source: VideoRecommendationSource,
    val sourceIndex: Int = 0
)

data class RankedVideoRecommendation(
    val candidate: VideoRecommendationCandidate,
    val score: Int,
    val reason: String
)

enum class VideoRepeatMode {
    OFF,
    ALL,
    ONE
}

data class VideoQueueState(
    val current: Track? = null,
    val upcoming: List<Track> = emptyList(),
    val previous: List<Track> = emptyList(),
    val isShuffled: Boolean = false,
    val repeatMode: VideoRepeatMode = VideoRepeatMode.OFF,
    val generation: Long = 0L,
    val isReplenishing: Boolean = false,
    val noCandidatesAvailable: Boolean = false
)

sealed interface VideoNextDecision {
    data class Play(val track: Track) : VideoNextDecision
    data class NeedRecommendations(val seed: Track) : VideoNextDecision
    data object ReplayCurrent : VideoNextDecision
    data object Stop : VideoNextDecision
}

sealed interface VideoPreviousDecision {
    data object RestartCurrent : VideoPreviousDecision
    data class Play(val track: Track) : VideoPreviousDecision
    data object None : VideoPreviousDecision
}

data class VideoDiscoveryResult(
    val generation: Long,
    val accepted: List<Track>,
    val attemptedSources: List<VideoRecommendationSource>,
    val stale: Boolean = false,
    val noCandidatesAvailable: Boolean = false
)
