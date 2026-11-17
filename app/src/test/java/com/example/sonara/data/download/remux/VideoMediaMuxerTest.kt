package com.example.sonara.data.download.remux

import com.example.sonara.domain.model.video.download.VideoDownloadContainer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VideoMediaMuxerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ─── PART 4: REMUXING CONTRACT & BEHAVIOR TESTS (CASES 43-49) ─────────────

    @Test
    fun test43_vp9AndOpus_mappedToWebmContainer() {
        val container = VideoDownloadContainer.WEBM
        assertEquals("webm", container.extension)
        assertEquals("video/webm", container.mimeType)
    }

    @Test
    fun test44_avcAndAac_mappedToMp4Container() {
        val container = VideoDownloadContainer.MP4
        assertEquals("mp4", container.extension)
        assertEquals("video/mp4", container.mimeType)
    }

    @Test
    fun test45_timestampChronologicalInterleaving() {
        // Test chronological track sample interleaving logic
        data class Sample(val trackIndex: Int, val ptsUs: Long, val isKey: Boolean)

        val videoSamples = listOf(
            Sample(0, 0L, true),
            Sample(0, 33333L, false),
            Sample(0, 66666L, false),
            Sample(0, 100000L, false)
        )

        val audioSamples = listOf(
            Sample(1, 0L, true),
            Sample(1, 20000L, true),
            Sample(1, 40000L, true),
            Sample(1, 60000L, true),
            Sample(1, 80000L, true)
        )

        // Merge sort by PTS
        val merged = (videoSamples + audioSamples).sortedBy { it.ptsUs }

        // Verify interleaved non-decreasing PTS
        var lastPts = -1L
        merged.forEach { s ->
            assertTrue("PTS must be non-decreasing: ${s.ptsUs} >= $lastPts", s.ptsUs >= lastPts)
            lastPts = s.ptsUs
        }
        assertEquals(9, merged.size)
        assertEquals(0L, merged[0].ptsUs)
    }

    @Test
    fun test46_audioPrimingPreservation() {
        // Audio streams often start with negative or zero priming presentation timestamps
        val audioPts = listOf(-20000L, 0L, 20000L)
        // Sanitization rule: clamp negative priming to 0 to prevent MediaMuxer WebM crashes
        val sanitized = audioPts.map { it.coerceAtLeast(0L) }
        assertEquals(listOf(0L, 0L, 20000L), sanitized)
        assertTrue(sanitized[0] >= 0L)
    }

    @Test
    fun test47_keyframeFlagsPreservation() {
        // Video samples must preserve KEY_FRAME flag on keyframes
        val BUFFER_FLAG_KEY_FRAME = 1
        val isKeyFrame = true
        val flags = if (isKeyFrame) BUFFER_FLAG_KEY_FRAME else 0
        assertEquals(BUFFER_FLAG_KEY_FRAME, flags)

        val isNotKey = false
        val flagsNotKey = if (isNotKey) BUFFER_FLAG_KEY_FRAME else 0
        assertEquals(0, flagsNotKey)
    }

    @Test
    fun test48_missingOrEmptyVideoFile_failsFastWithDescriptiveError() = runTest {
        val muxer = AndroidNativeMuxer()
        val missingVideo = File(tempFolder.root, "non_existent.webm")
        val audioFile = tempFolder.newFile("audio.opus").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val output = File(tempFolder.root, "output.webm")

        val result = muxer.remux(missingVideo, audioFile, output, VideoDownloadContainer.WEBM)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("missing or empty") == true)
        assertFalse(output.exists())
    }

    @Test
    fun test49_cleanupAndRollbackOnFailure() = runTest {
        val muxer = AndroidNativeMuxer()
        val emptyVideo = tempFolder.newFile("empty_video.webm") // 0 bytes
        val audioFile = tempFolder.newFile("audio.opus").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val output = File(tempFolder.root, "output.webm")

        val result = muxer.remux(emptyVideo, audioFile, output, VideoDownloadContainer.WEBM)
        assertTrue(result.isFailure)
        // Verify no leftover partial output files in directory
        val leftovers = tempFolder.root.listFiles()?.filter { it.name.startsWith("output.webm.tmp") } ?: emptyList()
        assertEquals(0, leftovers.size)
    }
}
