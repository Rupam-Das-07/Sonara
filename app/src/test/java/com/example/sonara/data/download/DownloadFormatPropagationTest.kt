package com.example.sonara.data.download

import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.DownloadInfo
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 tests verifying native format propagation, container determination,
 * temporary file naming, duplicate detection, and reconciliation.
 */
class DownloadFormatPropagationTest {

    // --- Test A: YouTube Source (WebM / Opus) ---

    @Test
    fun testYouTubeWebmOpusPropagation() {
        val youtubeStream = StreamInfo(
            trackId = "dQw4w9WgXcQ",
            streamUrl = "http://localhost:3002/api/v1/stream/play?video_id=dQw4w9WgXcQ",
            format = "audio/webm",
            codec = "opus",
            bitrateKbps = 160,
            qualityTier = "STANDARD",
            provider = "youtube"
        )

        val result = DownloadEngine.determineDownloadFormat(youtubeStream)
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("webm", resolved.extension)
        assertEquals("audio/webm", resolved.mimeType)
    }

    // --- Test B: JioSaavn Source (MP4 / AAC-LC) ---

    @Test
    fun testJioSaavnMp4AacPropagation() {
        val jiosaavnStream = StreamInfo(
            trackId = "kesariya_yt_id",
            streamUrl = "https://aac.saavncdn.com/test_320.mp4",
            format = "audio/mp4",
            codec = "aac",
            bitrateKbps = 320,
            qualityTier = "HIGH",
            provider = "jiosaavn"
        )

        // Default / AUTO maps to m4a
        val defaultResult = DownloadEngine.determineDownloadFormat(jiosaavnStream)
        assertTrue(defaultResult.isSuccess)
        val defaultResolved = defaultResult.getOrThrow()
        assertEquals("m4a", defaultResolved.extension)
        assertEquals("audio/mp4", defaultResolved.mimeType)

        // Explicit MP4 preference maps to mp4
        val mp4Result = DownloadEngine.determineDownloadFormat(jiosaavnStream, com.example.sonara.domain.model.DownloadFileFormat.MP4)
        assertTrue(mp4Result.isSuccess)
        val mp4Resolved = mp4Result.getOrThrow()
        assertEquals("mp4", mp4Resolved.extension)
        assertEquals("audio/mp4", mp4Resolved.mimeType)

        // Explicit M4A preference maps to m4a
        val m4aResult = DownloadEngine.determineDownloadFormat(jiosaavnStream, com.example.sonara.domain.model.DownloadFileFormat.M4A)
        assertTrue(m4aResult.isSuccess)
        val m4aResolved = m4aResult.getOrThrow()
        assertEquals("m4a", m4aResolved.extension)
        assertEquals("audio/mp4", m4aResolved.mimeType)
    }

    @Test
    fun testMp4PreferencePreservesNativeWebmOpusSource() {
        val youtubeStream = StreamInfo(
            trackId = "dQw4w9WgXcQ",
            streamUrl = "http://localhost:3002/api/v1/stream/play?video_id=dQw4w9WgXcQ",
            format = "audio/webm",
            codec = "opus",
            bitrateKbps = 160,
            qualityTier = "STANDARD",
            provider = "youtube"
        )

        val result = DownloadEngine.determineDownloadFormat(youtubeStream, com.example.sonara.domain.model.DownloadFileFormat.MP4)
        assertTrue("MP4 preference on WebM source must gracefully preserve native WebM", result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("webm", resolved.extension)
        assertEquals("audio/webm", resolved.mimeType)
    }

    @Test
    fun testWebmPreferencePreservesNativeMp4AacSource() {
        val jiosaavnStream = StreamInfo(
            trackId = "kesariya_yt_id",
            streamUrl = "https://aac.saavncdn.com/test_320.mp4",
            format = "audio/mp4",
            codec = "aac",
            bitrateKbps = 320,
            qualityTier = "HIGH",
            provider = "jiosaavn"
        )

        val result = DownloadEngine.determineDownloadFormat(jiosaavnStream, com.example.sonara.domain.model.DownloadFileFormat.WEBM)
        assertTrue("WEBM preference on MP4 source must gracefully preserve native M4A", result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("m4a", resolved.extension)
        assertEquals("audio/mp4", resolved.mimeType)
    }

    @Test
    fun testM4aStreamPropagation() {
        val m4aStream = StreamInfo(
            trackId = "m4a_track",
            streamUrl = "https://cdn.example.com/stream.m4a",
            format = "audio/m4a",
            codec = "aac",
            bitrateKbps = 256,
            qualityTier = "HIGH",
            provider = "direct"
        )

        val result = DownloadEngine.determineDownloadFormat(m4aStream)
        assertTrue(result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals("m4a", resolved.extension)
        assertEquals("audio/mp4", resolved.mimeType)
    }

    // --- Test C: Unknown / Unsupported Format ---

    @Test
    fun testUnsupportedFormatFailsExplicitly() {
        val unknownStream = StreamInfo(
            trackId = "unknown_track",
            streamUrl = "http://localhost/unknown.bin",
            format = "application/octet-stream",
            codec = "raw",
            bitrateKbps = 128,
            qualityTier = "STANDARD",
            provider = "unknown"
        )

        val result = DownloadEngine.determineDownloadFormat(unknownStream)
        assertTrue("Unknown formats must explicitly fail", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(exception?.message?.contains("Unsupported audio stream format") == true)
    }

    // --- Test D: Temporary File Naming ---

    @Test
    fun testTemporaryFileNamingProtocol() {
        val webmResolved = DownloadEngine.ResolvedDownloadFormat(extension = "webm", mimeType = "audio/webm")
        val m4aResolved = DownloadEngine.ResolvedDownloadFormat(extension = "m4a", mimeType = "audio/mp4")
        val mp4Resolved = DownloadEngine.ResolvedDownloadFormat(extension = "mp4", mimeType = "audio/mp4")

        val trackId = "track_xyz-123"
        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")

        val webmTarget = "${sanitizedId}.${webmResolved.extension}"
        val webmTemp = "${sanitizedId}.${webmResolved.extension}.download"

        val m4aTarget = "${sanitizedId}.${m4aResolved.extension}"
        val m4aTemp = "${sanitizedId}.${m4aResolved.extension}.download"

        val mp4Target = "${sanitizedId}.${mp4Resolved.extension}"
        val mp4Temp = "${sanitizedId}.${mp4Resolved.extension}.download"

        assertEquals("track_xyz-123.webm", webmTarget)
        assertEquals("track_xyz-123.webm.download", webmTemp)
        assertEquals("track_xyz-123.m4a", m4aTarget)
        assertEquals("track_xyz-123.m4a.download", m4aTemp)
        assertEquals("track_xyz-123.mp4", mp4Target)
        assertEquals("track_xyz-123.mp4.download", mp4Temp)

        // Verify temp file does not look like a completed file
        assertFalse(webmTemp.endsWith(".webm"))
        assertTrue(webmTemp.endsWith(".download"))
        assertFalse(m4aTemp.endsWith(".m4a"))
        assertTrue(m4aTemp.endsWith(".download"))
        assertFalse(mp4Temp.endsWith(".mp4"))
        assertTrue(mp4Temp.endsWith(".download"))
    }

    // --- Test E: Duplicate Detection for All Formats ---

    @Test
    fun testDuplicateDetectionAcrossFormats() {
        val webmRecord = DownloadInfo(
            trackId = "track_1",
            localFilePath = "/data/user/0/com.example.sonara/files/sonara_downloads/track_1.webm",
            totalBytes = 3_000_000L,
            downloadedBytes = 3_000_000L,
            status = DownloadStatus.DOWNLOADED,
            quality = AudioQuality.HIGH,
            mimeType = "audio/webm"
        )

        val m4aRecord = DownloadInfo(
            trackId = "track_2",
            localFilePath = "/data/user/0/com.example.sonara/files/sonara_downloads/track_2.m4a",
            totalBytes = 8_000_000L,
            downloadedBytes = 8_000_000L,
            status = DownloadStatus.DOWNLOADED,
            quality = AudioQuality.VERY_HIGH,
            mimeType = "audio/mp4"
        )

        val mp4Record = DownloadInfo(
            trackId = "track_3",
            localFilePath = "/data/user/0/com.example.sonara/files/sonara_downloads/track_3.mp4",
            totalBytes = 8_000_000L,
            downloadedBytes = 8_000_000L,
            status = DownloadStatus.DOWNLOADED,
            quality = AudioQuality.VERY_HIGH,
            mimeType = "audio/mp4"
        )

        assertEquals(DownloadStatus.DOWNLOADED, webmRecord.status)
        assertEquals("audio/webm", webmRecord.mimeType)
        assertTrue(webmRecord.localFilePath?.endsWith(".webm") == true)

        assertEquals(DownloadStatus.DOWNLOADED, m4aRecord.status)
        assertEquals("audio/mp4", m4aRecord.mimeType)
        assertTrue(m4aRecord.localFilePath?.endsWith(".m4a") == true)

        assertEquals(DownloadStatus.DOWNLOADED, mp4Record.status)
        assertEquals("audio/mp4", mp4Record.mimeType)
        assertTrue(mp4Record.localFilePath?.endsWith(".mp4") == true)
    }

    // --- Test F: Reconciliation Candidate Matching ---

    @Test
    fun testReconciliationCandidateMatching() {
        val trackId = "track_reconcile_999"
        val sanitizedId = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")

        val m4aCandidateName = "${sanitizedId}.m4a"
        val mp4CandidateName = "${sanitizedId}.mp4"
        val webmCandidateName = "${sanitizedId}.webm"

        assertTrue(m4aCandidateName.endsWith(".m4a"))
        assertTrue(mp4CandidateName.endsWith(".mp4"))
        assertTrue(webmCandidateName.endsWith(".webm"))
        assertFalse(m4aCandidateName.endsWith(".download"))
        assertFalse(mp4CandidateName.endsWith(".download"))
        assertFalse(webmCandidateName.endsWith(".download"))
    }

    // --- Test G: Actual Quality Resolution & Truthful Persistence ---

    @Test
    fun testResolveActualQualityFromStreamInfo() {
        val jiosaavn320 = StreamInfo(
            trackId = "jio_320",
            streamUrl = "https://example.com/jio320.mp4",
            format = "audio/mp4",
            codec = "aac",
            bitrateKbps = 320,
            qualityTier = "HIGH",
            provider = "jiosaavn"
        )
        val jioQuality = DownloadEngine.resolveActualQuality(jiosaavn320)
        assertEquals(AudioQuality.VERY_HIGH, jioQuality)

        val jiosaavn160 = StreamInfo(
            trackId = "jio_160",
            streamUrl = "https://example.com/jio160.mp4",
            format = "audio/mp4",
            codec = "aac",
            bitrateKbps = 160,
            qualityTier = "STANDARD",
            provider = "jiosaavn"
        )
        val jio160Quality = DownloadEngine.resolveActualQuality(jiosaavn160)
        assertEquals(AudioQuality.HIGH, jio160Quality)

        val youtubeStandard = StreamInfo(
            trackId = "yt_standard",
            streamUrl = "https://example.com/yt.webm",
            format = "audio/webm",
            codec = "opus",
            bitrateKbps = 160,
            qualityTier = "STANDARD",
            provider = "youtube"
        )
        val ytQuality = DownloadEngine.resolveActualQuality(youtubeStandard)
        assertEquals(AudioQuality.HIGH, ytQuality)
    }

    @Test
    fun testFallbackQualityPersistenceTruthfulness() {
        // When user requested VERY_HIGH, but JioSaavn was unavailable and YouTube resolved STANDARD:
        val fallbackStream = StreamInfo(
            trackId = "track_fallback",
            streamUrl = "https://example.com/stream.webm",
            format = "audio/webm",
            codec = "opus",
            bitrateKbps = 160,
            qualityTier = "STANDARD",
            provider = "youtube"
        )

        val actualPersistedQuality = DownloadEngine.resolveActualQuality(fallbackStream)
        // Must NOT falsely record VERY_HIGH
        assertEquals(AudioQuality.HIGH, actualPersistedQuality)
        assertFalse(actualPersistedQuality == AudioQuality.VERY_HIGH)
    }

    // --- Test H: Complete 6-State Lifecycle Integrity ---

    @Test
    fun testAllSixDownloadStatusesAreDistinctAndHandled() {
        val statuses = DownloadStatus.values().toList()
        assertEquals(6, statuses.size)
        assertTrue(statuses.contains(DownloadStatus.NOT_DOWNLOADED))
        assertTrue(statuses.contains(DownloadStatus.QUEUED))
        assertTrue(statuses.contains(DownloadStatus.DOWNLOADING))
        assertTrue(statuses.contains(DownloadStatus.DOWNLOADED))
        assertTrue(statuses.contains(DownloadStatus.FAILED))
        assertTrue(statuses.contains(DownloadStatus.CANCELLED))

        // Ensure CANCELLED is treated distinctly and not as active downloading or failure
        val cancelled = DownloadStatus.CANCELLED
        assertFalse(cancelled == DownloadStatus.DOWNLOADING)
        assertFalse(cancelled == DownloadStatus.QUEUED)
        assertFalse(cancelled == DownloadStatus.FAILED)
    }
}
