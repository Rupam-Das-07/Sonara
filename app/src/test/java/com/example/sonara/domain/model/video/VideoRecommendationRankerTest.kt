package com.example.sonara.domain.model.video

import com.example.sonara.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRecommendationRankerTest {
    private val seed = track("seed", "Seed Song", "Artist One", 210_000L)

    @Test
    fun `ranker prefers music signals and artist match`() {
        val ranked = VideoRecommendationRanker.rank(
            seed,
            listOf(
                candidate("pod", "Seed Song Podcast Interview", "Artist One", VideoRecommendationSource.RADIO),
                candidate("official", "Seed Song Official Audio", "Artist One", VideoRecommendationSource.RELATED),
                candidate("fallback", "Another Music Video", "Other Artist", VideoRecommendationSource.ARTIST_SEARCH)
            )
        )

        assertEquals("official", ranked.first().candidate.track.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `ranker excludes seed duplicates recent videos and normalized signatures`() {
        val ranked = VideoRecommendationRanker.rank(
            seed,
            listOf(
                candidate("yt-SEED", "Seed Song", "Artist One", VideoRecommendationSource.RADIO),
                candidate("new", "New Song", "Artist Two", VideoRecommendationSource.RADIO),
                candidate("duplicate", " new   song ", "artist two", VideoRecommendationSource.RELATED),
                candidate("recent", "Recent Song", "Artist Three", VideoRecommendationSource.RADIO)
            ),
            recentVideoIds = setOf("recent")
        )

        assertEquals(listOf("new"), ranked.map { it.candidate.track.id })
    }

    @Test
    fun `ranker tie breaking is deterministic`() {
        val ranked = VideoRecommendationRanker.rank(
            seed,
            listOf(
                candidate("b", "Song B", "Artist B", VideoRecommendationSource.RADIO, 1),
                candidate("a", "Song A", "Artist A", VideoRecommendationSource.RADIO, 0)
            )
        )

        assertEquals(listOf("a", "b"), ranked.map { it.candidate.track.id })
    }

    @Test
    fun `missing and unusual durations are not fabricated`() {
        val ranked = VideoRecommendationRanker.rank(
            seed,
            listOf(
                candidate("missing", "Unknown Song", "Unknown Artist", VideoRecommendationSource.RADIO, durationMs = 0L),
                candidate("long", "Very Long Mix", "Unknown Artist", VideoRecommendationSource.RADIO, durationMs = 1_000_000L)
            )
        )

        assertEquals(2, ranked.size)
        assertFalse(ranked.any { it.candidate.track.durationMs < 0L })
    }

    private fun candidate(
        id: String,
        title: String,
        artist: String,
        source: VideoRecommendationSource,
        sourceIndex: Int = 0,
        durationMs: Long = 210_000L
    ) = VideoRecommendationCandidate(track(id, title, artist, durationMs), source, sourceIndex)

    private fun track(id: String, title: String, artist: String, durationMs: Long) = Track(
        id = id,
        title = title,
        artist = artist,
        durationMs = durationMs
    )
}
