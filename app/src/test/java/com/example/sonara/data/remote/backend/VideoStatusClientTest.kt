package com.example.sonara.data.remote.backend

import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoEmbeddingDenialReason
import com.example.sonara.domain.model.video.VideoEmbeddingEligibility
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.model.video.evaluateVideoEmbeddingEligibility
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStatusClientTest {

    private val videoId = "dQw4w9WgXcQ"

    @Test
    fun `status parser maps backend response into typed statuses`() {
        val status = parseVideoStatus(
            """
            {
              "videoId": "dQw4w9WgXcQ",
              "madeForKidsStatus": "NOT_MADE_FOR_KIDS",
              "embeddabilityStatus": "EMBEDDABLE"
            }
            """.trimIndent(),
            requestedVideoId = videoId
        )

        assertEquals(videoId, status.videoId)
        assertEquals(MadeForKidsStatus.NOT_MADE_FOR_KIDS, status.madeForKidsStatus)
        assertEquals(EmbeddabilityStatus.EMBEDDABLE, status.embeddabilityStatus)
    }

    @Test
    fun `status parser preserves unknown values and rejects a mismatched returned id`() {
        val status = parseVideoStatus(
            """{"videoId":"$videoId","madeForKidsStatus":"unexpected","embeddabilityStatus":null}""",
            requestedVideoId = videoId
        )
        assertEquals(MadeForKidsStatus.UNKNOWN, status.madeForKidsStatus)
        assertEquals(EmbeddabilityStatus.UNKNOWN, status.embeddabilityStatus)

        val error = runCatching {
            parseVideoStatus(
                """{"videoId":"different","madeForKidsStatus":"MADE_FOR_KIDS","embeddabilityStatus":"EMBEDDABLE"}""",
                requestedVideoId = videoId
            )
        }.exceptionOrNull()
        assertTrue(error is SonaraException.ParsingException)
    }

    @Test
    fun `status parser rejects missing returned id and malformed JSON`() {
        val missingId = runCatching {
            parseVideoStatus("""{"madeForKidsStatus":"MADE_FOR_KIDS"}""", requestedVideoId = videoId)
        }.exceptionOrNull()
        assertTrue(missingId is SonaraException.ParsingException)

        val malformed = runCatching {
            parseVideoStatus("not-json", requestedVideoId = videoId)
        }.exceptionOrNull()
        assertTrue(malformed is SonaraException.ParsingException)
    }

    @Test
    fun `status parser accepts whitespace escapes unicode and empty strings`() {
        val status = parseVideoStatus(
            """
                {
                  "videoId" : "dQw4w9WgXcQ",
                  "madeForKidsStatus" : "NOT_MADE_FOR_KIDS\u0020",
                  "embeddabilityStatus" : "EMBEDDABLE",
                  "ignored" : "quote: \\\" slash: \\\\ unicode: \\u263A",
                  "empty" : ""
                }
            """.trimIndent(),
            requestedVideoId = videoId
        )

        assertEquals(MadeForKidsStatus.NOT_MADE_FOR_KIDS, status.madeForKidsStatus)
        assertEquals(EmbeddabilityStatus.EMBEDDABLE, status.embeddabilityStatus)
    }

    @Test
    fun `status parser rejects duplicate keys trailing commas garbage and truncated values`() {
        val malformedResponses = listOf(
            "{\"videoId\":\"$videoId\",\"videoId\":\"$videoId\"}",
            "{\"videoId\":\"$videoId\",}",
            "{\"videoId\":\"$videoId\"} trailing",
            "{\"videoId\":\"$videoId\"",
            "{\"videoId\":\"$videoId\\\"}",
            "{\"videoId\":\"$videoId\",\"embeddabilityStatus\":truX}"
        )

        malformedResponses.forEach { json ->
            val error = runCatching { parseVideoStatus(json, requestedVideoId = videoId) }.exceptionOrNull()
            assertTrue("expected parser failure for $json", error is SonaraException.ParsingException)
        }
    }

    @Test
    fun `status parser rejects nested values and non-string video ids`() {
        val malformedResponses = listOf(
            "{\"videoId\":{\"value\":\"$videoId\"}}",
            "{\"videoId\":[\"$videoId\"]}",
            "{\"videoId\":12345678901,\"madeForKidsStatus\":\"NOT_MADE_FOR_KIDS\",\"embeddabilityStatus\":\"EMBEDDABLE\"}",
            "{\"videoId\":true,\"madeForKidsStatus\":\"NOT_MADE_FOR_KIDS\",\"embeddabilityStatus\":\"EMBEDDABLE\"}",
            "{\"videoId\":\"$videoId\",\"madeForKidsStatus\":{}}",
            "{\"videoId\":\"$videoId\",\"embeddabilityStatus\":[]}"
        )

        malformedResponses.forEach { json ->
            val error = runCatching { parseVideoStatus(json, requestedVideoId = videoId) }.exceptionOrNull()
            assertTrue("expected parser failure for $json", error is SonaraException.ParsingException)
        }
    }

    @Test
    fun `status parser treats valid unexpected primitive status types as unknown`() {
        val status = parseVideoStatus(
            """{"videoId":"$videoId","madeForKidsStatus":false,"embeddabilityStatus":1}""",
            requestedVideoId = videoId
        )

        assertEquals(MadeForKidsStatus.UNKNOWN, status.madeForKidsStatus)
        assertEquals(EmbeddabilityStatus.UNKNOWN, status.embeddabilityStatus)
    }

    @Test
    fun `blank video status request fails locally without a network call`() = runTest {
        val result = SonaraBackendClient(baseUrl = "http://127.0.0.1:1").getVideoStatus("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SonaraException.NotFoundException)
    }

    @Test
    fun `malformed video ids fail locally without a network call`() = runTest {
        for (invalidId in listOf("short", "1234567890!", "123456789012")) {
            val result = SonaraBackendClient(baseUrl = "http://127.0.0.1:1").getVideoStatus(invalidId)
            assertTrue("expected $invalidId to fail", result.isFailure)
            assertTrue(result.exceptionOrNull() is SonaraException.NotFoundException)
        }
    }

    @Test
    fun `embedding is allowed only for known kids status and explicit embeddability`() {
        val result = evaluateVideoEmbeddingEligibility(
            VideoStatus(videoId, MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
        )
        assertEquals(VideoEmbeddingEligibility.ALLOWED, result.eligibility)
        assertTrue(result.isAllowed)
    }

    @Test
    fun `made for kids videos are conservatively denied with a policy-specific reason`() {
        val result = evaluateVideoEmbeddingEligibility(
            VideoStatus(videoId, MadeForKidsStatus.MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
        )

        assertEquals(VideoEmbeddingEligibility.DENIED, result.eligibility)
        assertFalse(result.isAllowed)
        assertEquals(
            VideoEmbeddingDenialReason.MADE_FOR_KIDS_REQUIRES_POLICY_APPROVAL,
            result.denialReason
        )
    }

    @Test
    fun `unknown statuses and errors fail closed`() {
        val statuses = listOf(
            MadeForKidsStatus.UNKNOWN,
            MadeForKidsStatus.MADE_FOR_KIDS,
            MadeForKidsStatus.NOT_MADE_FOR_KIDS
        )
        for (madeForKidsStatus in statuses) {
            for (embeddabilityStatus in listOf(EmbeddabilityStatus.UNKNOWN, EmbeddabilityStatus.NOT_EMBEDDABLE)) {
                val result = evaluateVideoEmbeddingEligibility(
                    VideoStatus(videoId, madeForKidsStatus, embeddabilityStatus)
                )
                assertEquals(VideoEmbeddingEligibility.DENIED, result.eligibility)
                assertFalse(result.isAllowed)
            }
        }

        assertEquals(
            VideoEmbeddingDenialReason.UNKNOWN_MADE_FOR_KIDS_STATUS,
            evaluateVideoEmbeddingEligibility(
                VideoStatus(videoId, MadeForKidsStatus.UNKNOWN, EmbeddabilityStatus.EMBEDDABLE)
            ).denialReason
        )

        assertEquals(
            VideoEmbeddingDenialReason.NOT_EMBEDDABLE,
            evaluateVideoEmbeddingEligibility(
                VideoStatus(videoId, MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.NOT_EMBEDDABLE)
            ).denialReason
        )

        assertEquals(
            VideoEmbeddingDenialReason.UNKNOWN_EMBEDDABILITY_STATUS,
            evaluateVideoEmbeddingEligibility(
                VideoStatus(videoId, MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.UNKNOWN)
            ).denialReason
        )

        val invalidStatus = evaluateVideoEmbeddingEligibility(
            VideoStatus("", MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
        )
        assertEquals(VideoEmbeddingEligibility.DENIED, invalidStatus.eligibility)
        assertFalse(invalidStatus.isAllowed)
        assertEquals(VideoEmbeddingDenialReason.INVALID_VIDEO_ID, invalidStatus.denialReason)

        val malformedIdStatus = evaluateVideoEmbeddingEligibility(
            VideoStatus("not-a-video-id", MadeForKidsStatus.NOT_MADE_FOR_KIDS, EmbeddabilityStatus.EMBEDDABLE)
        )
        assertEquals(VideoEmbeddingEligibility.DENIED, malformedIdStatus.eligibility)
        assertFalse(malformedIdStatus.isAllowed)

        val failed = evaluateVideoEmbeddingEligibility(
            Result.failure<VideoStatus>(SonaraException.NetworkException("offline"))
        )
        assertEquals(VideoEmbeddingEligibility.DENIED, failed.eligibility)
        assertFalse(failed.isAllowed)
        assertEquals(VideoEmbeddingDenialReason.VERIFICATION_FAILED, failed.denialReason)
    }
}
