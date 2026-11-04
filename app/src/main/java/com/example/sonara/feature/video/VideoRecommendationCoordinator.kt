package com.example.sonara.feature.video

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.VideoDiscoveryResult
import com.example.sonara.domain.model.video.VideoPlaybackQueueEngine
import com.example.sonara.domain.model.video.VideoRecommendationCandidate
import com.example.sonara.domain.model.video.VideoRecommendationRanker
import com.example.sonara.domain.model.video.VideoRecommendationSource
import com.example.sonara.domain.model.video.evaluateVideoEmbeddingEligibility
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Discovers, ranks, and fail-closed verifies candidates for the video-only queue. */
class VideoRecommendationCoordinator(
    private val backendClient: SonaraBackendClient,
    private val queueEngine: VideoPlaybackQueueEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private companion object {
        const val MAX_SOURCE_CANDIDATES = 50
        const val MAX_ACCEPTED_CANDIDATES = 12
        const val MINIMUM_CANDIDATES = 3
    }

    private val statusCache = mutableMapOf<String, Boolean>()

    suspend fun replenish(seed: Track, generation: Long): VideoDiscoveryResult = withContext(ioDispatcher) {
        if (!queueEngine.isCurrentGeneration(generation)) {
            return@withContext VideoDiscoveryResult(generation, emptyList(), emptyList(), stale = true)
        }

        val attemptedSources = mutableListOf<VideoRecommendationSource>()
        val rawCandidates = mutableListOf<VideoRecommendationCandidate>()
        val recentIds = queueEngine.recentIds()

        suspend fun collect(
            source: VideoRecommendationSource,
            load: suspend () -> Result<List<Track>>
        ) {
            attemptedSources += source
            val result = try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.failure(IllegalStateException("Recommendation source failed"))
            }
            result.getOrNull().orEmpty().take(MAX_SOURCE_CANDIDATES).forEachIndexed { index, track ->
                rawCandidates += VideoRecommendationCandidate(track, source, index)
            }
        }

        collect(VideoRecommendationSource.RADIO) {
            backendClient.getRadioTracks(seed.id)
        }

        var ranked = rankAndVerify(seed, rawCandidates, recentIds)
        if (ranked.size < MINIMUM_CANDIDATES) {
            collect(VideoRecommendationSource.RELATED) {
                backendClient.getRelatedTracks(seed.id)
            }
            ranked = rankAndVerify(seed, rawCandidates, recentIds)
        }

        if (ranked.size < MINIMUM_CANDIDATES) {
            val query = seed.artist.trim().ifBlank { seed.title.trim() }
            if (query.isNotBlank()) {
                collect(VideoRecommendationSource.ARTIST_SEARCH) {
                    backendClient.searchVideos(query, limit = 12)
                }
                ranked = rankAndVerify(seed, rawCandidates, recentIds)
            }
        }

        if (!queueEngine.isCurrentGeneration(generation)) {
            return@withContext VideoDiscoveryResult(generation, emptyList(), attemptedSources, stale = true)
        }

        val accepted = queueEngine.append(ranked.map { it.candidate.track })
        VideoDiscoveryResult(
            generation = generation,
            accepted = accepted,
            attemptedSources = attemptedSources,
            noCandidatesAvailable = accepted.isEmpty() && queueEngine.upcomingCount() == 0
        )
    }

    private suspend fun rankAndVerify(
        seed: Track,
        candidates: List<VideoRecommendationCandidate>,
        recentIds: Set<String>
    ): List<com.example.sonara.domain.model.video.RankedVideoRecommendation> {
        val eligible = mutableListOf<com.example.sonara.domain.model.video.RankedVideoRecommendation>()
        // Rank the bounded source pool before eligibility filtering. Limiting to the first 12
        // ranked candidates would allow an ineligible top batch to starve later eligible results.
        for (ranked in VideoRecommendationRanker.rank(seed, candidates, recentIds, limit = candidates.size)) {
            if (isEligible(ranked.candidate.track)) {
                eligible += ranked
                if (eligible.size >= MAX_ACCEPTED_CANDIDATES) break
            }
        }
        return eligible
    }

    private suspend fun isEligible(track: Track): Boolean {
        val id = VideoRecommendationRanker.normalizeId(track.id)
        return statusCache[id] ?: run {
            val status = backendClient.getVideoStatus(id).getOrNull()
            val allowed = status != null && evaluateVideoEmbeddingEligibility(status).isAllowed
            statusCache[id] = allowed
            allowed
        }
    }
}
