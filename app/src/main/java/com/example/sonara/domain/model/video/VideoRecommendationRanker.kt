package com.example.sonara.domain.model.video

import com.example.sonara.domain.model.Track

/** Deterministic, explainable ranking for music-focused YouTube candidates. */
object VideoRecommendationRanker {
    private val positiveMusicSignals = listOf(
        "official video", "official audio", "music video", "lyric video", "lyrics", "audio", "live"
    )
    private val negativeNonMusicSignals = listOf(
        "podcast", "reaction", "interview", "trailer", "shorts", "short film", "karaoke", "compilation"
    )

    fun rank(
        seed: Track,
        candidates: List<VideoRecommendationCandidate>,
        recentVideoIds: Set<String> = emptySet(),
        limit: Int = 50
    ): List<RankedVideoRecommendation> {
        val seedId = normalizeId(seed.id)
        val seenIds = mutableSetOf<String>()
        val seenSignatures = mutableSetOf<String>()
        val seenArtists = mutableSetOf<String>()
        val recent = recentVideoIds.mapTo(mutableSetOf(), ::normalizeId)
        val ranked = mutableListOf<RankedVideoRecommendation>()

        for (candidate in candidates) {
            val track = candidate.track
            val id = normalizeId(track.id)
            val signature = signature(track)
            if (id.isBlank() || id == seedId || !seenIds.add(id) || !seenSignatures.add(signature)) continue
            if (id in recent) continue

            val artist = normalizeText(track.artist)
            val artistMatch = artist.isNotBlank() && normalizeText(seed.artist).contains(artist)
            val sourceScore = when (candidate.source) {
                VideoRecommendationSource.RADIO -> 30
                VideoRecommendationSource.RELATED -> 25
                VideoRecommendationSource.ARTIST_SEARCH -> 15
            }
            val artistScore = if (artistMatch) 28 else if (artist.isBlank()) 0 else 6
            val title = normalizeText(track.title)
            val positiveScore = positiveMusicSignals.count(title::contains) * 5
            val negativeScore = negativeNonMusicSignals.count(title::contains) * 18
            val durationScore = when {
                track.durationMs in 90_000L..600_000L -> 8
                track.durationMs <= 0L -> 0
                else -> -8
            }
            val diversityScore = if (artist.isNotBlank() && seenArtists.add(artist)) 6 else 0
            val score = sourceScore + artistScore + positiveScore + durationScore + diversityScore - negativeScore
            val reason = buildString {
                append("source=$sourceScore")
                append(", artist=$artistScore")
                append(", title=${positiveScore - negativeScore}")
                append(", duration=$durationScore")
                append(", diversity=$diversityScore")
            }
            ranked += RankedVideoRecommendation(candidate, score, reason)
        }

        return ranked
            .sortedWith(
                compareByDescending<RankedVideoRecommendation> { it.score }
                    .thenBy { it.candidate.source.ordinal }
                    .thenBy { it.candidate.sourceIndex }
                    .thenBy { normalizeId(it.candidate.track.id) }
            )
            .take(limit)
    }

    fun normalizeId(id: String): String = id.trim().removePrefix("yt-").lowercase()

    fun signature(track: Track): String =
        "${normalizeText(track.title)}|||${normalizeText(track.artist)}"

    private fun normalizeText(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
