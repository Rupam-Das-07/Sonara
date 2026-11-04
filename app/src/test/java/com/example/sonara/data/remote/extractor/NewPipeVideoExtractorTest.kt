package com.example.sonara.data.remote.extractor

import com.example.sonara.domain.model.video.EmbeddabilityStatus
import com.example.sonara.domain.model.video.MadeForKidsStatus
import com.example.sonara.domain.model.video.VideoStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

class NewPipeVideoExtractorTest {
    @Test
    fun `valid id and metadata produce a Sonara extraction result`() = runTest {
        var requestedUrl: String? = null
        val extractor = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            loadInfo = { url ->
                requestedUrl = url
                extractionInfo()
            }
        )

        val result = extractor.extract("dQw4w9WgXcQ", validVideoStatus())

        assertTrue(result.isSuccess)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", requestedUrl)
        assertEquals("dQw4w9WgXcQ", result.getOrThrow().videoId)
    }

    @Test
    fun `playback source extraction preserves the eligibility gate and exposes only ephemeral source data`() = runTest {
        val result = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            loadInfo = { extractionInfo() }
        ).extractPlaybackSource("dQw4w9WgXcQ", validVideoStatus())

        assertTrue(result.isSuccess)
        assertEquals("dQw4w9WgXcQ", result.getOrThrow().videoId)
        assertEquals("https://example.invalid/video", result.getOrThrow().streamUrl)
    }

    @Test
    fun `youtube urls are canonicalized and invalid input is rejected`() = runTest {
        val extractor = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            loadInfo = { extractionInfo() }
        )

        val valid = extractor.extract(
            "https://youtu.be/dQw4w9WgXcQ?t=30",
            validVideoStatus()
        )
        val invalid = extractor.extract("not-a-youtube-video", validVideoStatus())

        assertTrue(valid.isSuccess)
        assertTrue(invalid.isFailure)
    }

    @Test
    fun `eligibility denial prevents extraction`() = runTest {
        var loaderCalled = false
        val extractor = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            loadInfo = {
                loaderCalled = true
                extractionInfo()
            }
        )
        val denied = VideoStatus(
            "dQw4w9WgXcQ",
            MadeForKidsStatus.UNKNOWN,
            EmbeddabilityStatus.EMBEDDABLE
        )

        val result = extractor.extract("dQw4w9WgXcQ", denied)

        assertTrue(result.isFailure)
        assertFalse(loaderCalled)
    }

    @Test
    fun `mismatched status is rejected before extraction`() = runTest {
        var loaderCalled = false
        val extractor = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            loadInfo = {
                loaderCalled = true
                extractionInfo()
            }
        )

        val result = extractor.extract(
            "dQw4w9WgXcQ",
            validVideoStatus("9bZkp7q19f0")
        )

        assertTrue(result.isFailure)
        assertFalse(loaderCalled)
    }

    @Test
    fun `initializer and extractor failures are returned predictably`() = runTest {
        val initFailure = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.failure(IllegalStateException("init")) },
            loadInfo = { extractionInfo() }
        ).extract("dQw4w9WgXcQ", validVideoStatus())
        val extractorFailure = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            loadInfo = { throw IllegalStateException("unavailable") }
        ).extract("dQw4w9WgXcQ", validVideoStatus())

        assertTrue(initFailure.isFailure)
        assertTrue(extractorFailure.isFailure)
    }

    @Test
    fun `extractor-specific unavailable content exception is contained`() = runTest {
        val result = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            loadInfo = { throw ContentNotAvailableException("removed") }
        ).extract("dQw4w9WgXcQ", validVideoStatus())

        assertTrue(result.isFailure)
    }

    @Test
    fun `cancellation is not converted into a failure result`() = runTest {
        val extractor = NewPipeVideoExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            loadInfo = { throw CancellationException("cancelled") }
        )

        var cancelled = false
        try {
            extractor.extract("dQw4w9WgXcQ", validVideoStatus())
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
    }
}
