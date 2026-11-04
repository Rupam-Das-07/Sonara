package com.example.sonara.feature.video

import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoPlaybackQueueEngine
import com.example.sonara.domain.model.video.VideoStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRecommendationCoordinatorTest {
    private val seed = track("dQw4w9WgXcQ", "Seed Song", "Artist One")

    @Test
    fun `discovery falls back from radio to related and rejects ineligible candidates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeBackendClient().apply {
            radio = Result.success(listOf(track("m0000000001", "Podcast Interview", "Other")))
            related = Result.success(
                listOf(
                    track("m0000000002", "Kids Song Official Audio", "Artist One"),
                    track("m0000000003", "Good Song Official Audio", "Artist One"),
                    track("m0000000004", "Good Song 2 Official Audio", "Artist One"),
                    track("m0000000005", "Good Song 3 Official Audio", "Artist One")
                )
            )
            statuses["m0000000002"] = Result.success(VideoStatus("m0000000002", MadeForKidsStatus.MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE))
            statuses["m0000000003"] = Result.success(VideoStatus("m0000000003", MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE))
            statuses["m0000000004"] = Result.success(VideoStatus("m0000000004", MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE))
            statuses["m0000000005"] = Result.success(VideoStatus("m0000000005", MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE))
            statuses["m0000000001"] = Result.success(VideoStatus("m0000000001", MadeForKidsStatus.UNKNOWN, EmbeddabilityStatus.UNKNOWN))
        }
        val engine = VideoPlaybackQueueEngine()
        val generation = engine.seed(seed)
        val result = VideoRecommendationCoordinator(client, engine, dispatcher).replenish(seed, generation)

        assertEquals(listOf("radio", "related"), result.attemptedSources.map { it.name.lowercase() })
        assertEquals(listOf("m0000000003", "m0000000004", "m0000000005"), result.accepted.map(Track::id))
    }

    @Test
    fun `status failures are denied and do not erase existing queue`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeBackendClient().apply {
            radio = Result.failure(IllegalStateException("radio down"))
            related = Result.success(listOf(track("m0000000006", "Bad Song", "Artist")))
            statuses["m0000000006"] = Result.failure(IllegalStateException("status down"))
        }
        val engine = VideoPlaybackQueueEngine()
        val generation = engine.seed(seed)
        engine.append(listOf(track("m0000000007", "Existing Song", "Artist")))
        val result = VideoRecommendationCoordinator(client, engine, dispatcher).replenish(seed, generation)

        assertTrue(result.accepted.isEmpty())
        assertEquals(listOf("m0000000007"), engine.snapshot().upcoming.map(Track::id))
    }

    @Test
    fun `invalid candidate ids remain denied even with otherwise allowed status`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val invalidId = "invalid-id"
        val client = FakeBackendClient().apply {
            radio = Result.success(listOf(track(invalidId, "Invalid Candidate", "Artist One")))
            statuses[invalidId] = Result.success(
                VideoStatus(invalidId, MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
            )
        }
        val engine = VideoPlaybackQueueEngine()
        val generation = engine.seed(seed)

        val result = VideoRecommendationCoordinator(client, engine, dispatcher).replenish(seed, generation)

        assertTrue(result.accepted.isEmpty())
        assertTrue(engine.snapshot().upcoming.isEmpty())
    }

    @Test
    fun `stale generation cannot append asynchronous results`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeBackendClient().apply {
            radio = Result.success(listOf(track("m0000000008", "New Song", "Artist")))
            statuses["m0000000008"] = Result.success(VideoStatus("m0000000008", MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE))
        }
        val engine = VideoPlaybackQueueEngine()
        val first = engine.seed(seed)
        engine.seed(track("m0000000009", "Other Seed", "Other Artist"))
        val result = VideoRecommendationCoordinator(client, engine, dispatcher).replenish(seed, first)

        assertTrue(result.stale)
        assertTrue(engine.snapshot().upcoming.isEmpty())
    }

    @Test
    fun `cancellation propagates without being converted to a recommendation failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Result<List<Track>>>()
        val client = FakeBackendClient().apply { radioDeferred = gate }
        val engine = VideoPlaybackQueueEngine()
        val generation = engine.seed(seed)
        val coordinator = VideoRecommendationCoordinator(client, engine, dispatcher)

        val job = launch {
            coordinator.replenish(seed, generation)
        }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `denied top ranked batch does not starve later eligible candidates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeBackendClient().apply {
            val denied = (1..12).map { index ->
                track("A%010d".format(index), "Official Audio $index", "Artist One")
            }
            val eligibleId = "b0000000001"
            radio = Result.success(denied + track(eligibleId, "Plain Candidate", "Artist One"))
            denied.forEach { track ->
                statuses[track.id] = Result.success(
                    VideoStatus(track.id, MadeForKidsStatus.MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
                )
            }
            statuses[eligibleId] = Result.success(
                VideoStatus(eligibleId, MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
            )
        }
        val engine = VideoPlaybackQueueEngine()
        val generation = engine.seed(seed)

        val result = VideoRecommendationCoordinator(client, engine, dispatcher).replenish(seed, generation)

        assertTrue(result.accepted.any { it.id == "b0000000001" })
        assertTrue(engine.snapshot().upcoming.any { it.id == "b0000000001" })
    }

    private class FakeBackendClient : SonaraBackendClient("https://example.invalid") {
        var radio: Result<List<Track>> = Result.success(emptyList())
        var radioDeferred: CompletableDeferred<Result<List<Track>>>? = null
        var related: Result<List<Track>> = Result.success(emptyList())
        var search: Result<List<Track>> = Result.success(emptyList())
        val statuses = mutableMapOf<String, Result<VideoStatus>>()

        override suspend fun getRadioTracks(seedVideoId: String): Result<List<Track>> =
            radioDeferred?.await() ?: radio
        override suspend fun getRelatedTracks(seedVideoId: String): Result<List<Track>> = related
        override suspend fun searchVideos(query: String, limit: Int): Result<List<Track>> = search
        override suspend fun getVideoStatus(videoId: String): Result<VideoStatus> =
            statuses[videoId] ?: Result.failure(IllegalStateException("missing status"))
    }

    private fun track(id: String, title: String, artist: String) = Track(
        id = id,
        title = title,
        artist = artist,
        durationMs = 210_000L
    )
}
