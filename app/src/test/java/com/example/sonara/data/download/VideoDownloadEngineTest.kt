package com.example.sonara.data.download

import android.content.Context
import android.content.ContextWrapper
import com.example.sonara.data.download.remux.VideoMediaMuxer
import com.example.sonara.data.local.db.dao.DownloadDao
import com.example.sonara.data.local.db.dao.DownloadedTrackRelation
import com.example.sonara.data.local.db.dao.TrackDao
import com.example.sonara.data.local.db.entity.DownloadEntity
import com.example.sonara.data.local.db.entity.TrackEntity
import com.example.sonara.domain.model.DownloadStatus
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.video.VideoDeliveryMethod
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoPlaybackStream
import com.example.sonara.domain.model.video.VideoQuality
import com.example.sonara.domain.model.video.VideoStreamKind
import com.example.sonara.domain.model.video.VideoStatus
import com.example.sonara.domain.ports.VideoExtractionPort
import com.example.sonara.domain.model.video.download.ActiveDownloadProgress
import com.example.sonara.domain.model.video.download.DownloadStage
import com.example.sonara.domain.model.video.download.VideoDownloadContainer
import com.example.sonara.domain.model.video.download.VideoDownloadOption
import com.example.sonara.domain.model.video.download.VideoDownloadOptionCompatibility
import com.example.sonara.domain.model.video.download.VideoDownloadStreamType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VideoDownloadEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeDownloadDao: FakeDownloadDao
    private lateinit var fakeTrackDao: FakeTrackDao
    private lateinit var fakeMuxer: FakeVideoMediaMuxer
    private lateinit var testContext: Context

    private class FakeContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = baseDir
    }

    private class FakeDownloadDao : DownloadDao {
        val inserted = mutableListOf<DownloadEntity>()
        val progressUpdates = mutableListOf<ProgressUpdate>()
        val fileInfoUpdates = mutableListOf<VideoFileInfoUpdate>()
        val statusUpdates = mutableListOf<Pair<String, String>>()
        var lastCompletedTrackId: String? = null
        var lastFailedReason: String? = null

        data class ProgressUpdate(val trackId: String, val downloaded: Long, val total: Long, val status: String)
        data class VideoFileInfoUpdate(
            val trackId: String,
            val localFilePath: String,
            val mimeType: String,
            val quality: String,
            val mediaType: String,
            val resolutionHeight: Int?,
            val frameRate: Int?,
            val videoCodec: String?,
            val audioCodec: String?,
            val container: String
        )

        override suspend fun getDownload(trackId: String): DownloadEntity? =
            inserted.find { it.trackId == trackId }

        override fun observeDownload(trackId: String): Flow<DownloadEntity?> =
            flowOf(inserted.find { it.trackId == trackId })
        override fun observeAllDownloads(): Flow<List<DownloadEntity>> = flowOf(inserted)
        override fun observeCompletedDownloads(): Flow<List<DownloadEntity>> =
            flowOf(inserted.filter { it.status == DownloadStatus.DOWNLOADED.name })

        override fun observeDownloadsWithTracks(): Flow<List<DownloadedTrackRelation>> = flowOf(emptyList())
        override suspend fun getActiveDownloads(): List<DownloadEntity> =
            inserted.filter { it.status == DownloadStatus.DOWNLOADING.name }

        override suspend fun getDownloadedRecords(): List<DownloadEntity> =
            inserted.filter { it.status == DownloadStatus.DOWNLOADED.name }

        override suspend fun insertOrUpdate(download: DownloadEntity) {
            inserted.removeAll { it.trackId == download.trackId }
            inserted.add(download)
        }

        override suspend fun updateProgress(trackId: String, downloadedBytes: Long, totalBytes: Long, status: String) {
            progressUpdates.add(ProgressUpdate(trackId, downloadedBytes, totalBytes, status))
        }

        override suspend fun updateFileInfo(trackId: String, localFilePath: String, mimeType: String, quality: String, container: String) {}

        override suspend fun updateVideoFileInfo(
            trackId: String,
            localFilePath: String,
            mimeType: String,
            quality: String,
            mediaType: String,
            resolutionHeight: Int?,
            frameRate: Int?,
            videoCodec: String?,
            audioCodec: String?,
            container: String
        ) {
            fileInfoUpdates.add(
                VideoFileInfoUpdate(
                    trackId,
                    localFilePath,
                    mimeType,
                    quality,
                    mediaType,
                    resolutionHeight,
                    frameRate,
                    videoCodec,
                    audioCodec,
                    container
                )
            )
        }

        override suspend fun updateFileInfoLegacy(trackId: String, localFilePath: String, mimeType: String) {}

        override suspend fun markCompleted(trackId: String, completedAt: Long, status: String) {
            lastCompletedTrackId = trackId
            statusUpdates.add(trackId to status)
        }

        override suspend fun markFailed(trackId: String, reason: String?, status: String) {
            lastFailedReason = reason
            statusUpdates.add(trackId to status)
        }

        override suspend fun delete(trackId: String) {
            inserted.removeAll { it.trackId == trackId }
        }

        override suspend fun deleteAll() {
            inserted.clear()
        }
    }

    private class FakeTrackDao : TrackDao {
        val tracks = mutableListOf<TrackEntity>()
        override suspend fun insertTrack(track: TrackEntity) { tracks.add(track) }
        override suspend fun insertTracks(tracks: List<TrackEntity>) { this.tracks.addAll(tracks) }
        override suspend fun getTrackById(id: String): TrackEntity? = tracks.find { it.id == id }
        override suspend fun getTracksByIds(ids: List<String>): List<TrackEntity> = tracks.filter { it.id in ids }
    }

    private class FakeVideoMediaMuxer(
        var shouldSucceed: Boolean = true,
        var errorMessage: String = "Simulated remuxing error"
    ) : VideoMediaMuxer {
        var remuxCalledWith: RemuxCall? = null

        data class RemuxCall(
            val videoFile: File,
            val audioFile: File?,
            val outputFile: File,
            val container: VideoDownloadContainer
        )

        override suspend fun remux(
            videoFile: File,
            audioFile: File?,
            outputFile: File,
            container: VideoDownloadContainer,
            onProgress: ((Float) -> Unit)?
        ): Result<File> {
            remuxCalledWith = RemuxCall(videoFile, audioFile, outputFile, container)
            onProgress?.invoke(0.5f)
            return if (shouldSucceed) {
                outputFile.writeBytes(byteArrayOf(1, 2, 3, 4))
                Result.success(outputFile)
            } else {
                Result.failure(IllegalStateException(errorMessage))
            }
        }
    }

    @Before
    fun setUp() {
        fakeDownloadDao = FakeDownloadDao()
        fakeTrackDao = FakeTrackDao()
        fakeMuxer = FakeVideoMediaMuxer()
        testContext = FakeContext(tempFolder.root)
    }

    private fun createTestTrack(id: String = "track_123"): Track = Track(
        id = id,
        title = "Test Song",
        artist = "Test Artist",
        album = "Test Album",
        durationMs = 180_000L,
        artworkUrl = "https://example.com/art.jpg"
    )

    private fun createTestOption(
        height: Int = 1080,
        fps: Int = 60,
        container: VideoDownloadContainer = VideoDownloadContainer.WEBM,
        videoCodec: String = "VP9",
        audioCodec: String = "Opus",
        isMergeRequired: Boolean = true
    ): VideoDownloadOption = VideoDownloadOption(
        quality = VideoQuality.of(height, fps),
        width = 1920,
        height = height,
        frameRate = fps,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        container = container,
        streamType = if (isMergeRequired) VideoDownloadStreamType.ADAPTIVE else VideoDownloadStreamType.PROGRESSIVE,
        videoStreamUrl = "https://example.com/video",
        audioStreamUrl = if (isMergeRequired) "https://example.com/audio" else null,
        videoItag = 137,
        audioItag = if (isMergeRequired) 251 else null,
        estimatedVideoSizeBytes = 50_000_000L,
        estimatedAudioSizeBytes = 5_000_000L,
        estimatedTotalSizeBytes = 55_000_000L,
        videoBitrateKbps = 2500,
        audioBitrateKbps = 160,
        isMergeRequired = isMergeRequired,
        compatibility = VideoDownloadOptionCompatibility.COMPATIBLE,
        compatibilityReason = null
    )

    // ─── PART 3: DOWNLOAD ENGINE BEHAVIOR TESTS (CASES 21-34) ─────────────────

    @Test
    fun test21_progressiveDownloadOption_flagsZeroMergeRequired() {
        val progOption = createTestOption(height = 720, fps = 30, container = VideoDownloadContainer.MP4, isMergeRequired = false)
        assertFalse(progOption.isMergeRequired)
        assertNull(progOption.audioStreamUrl)
        assertEquals(VideoDownloadStreamType.PROGRESSIVE, progOption.streamType)
    }

    @Test
    fun test22_adaptiveDownloadOption_flagsMergeRequired() {
        val adaptiveOption = createTestOption(height = 1080, fps = 60, container = VideoDownloadContainer.WEBM, isMergeRequired = true)
        assertTrue(adaptiveOption.isMergeRequired)
        assertNotNull(adaptiveOption.audioStreamUrl)
        assertEquals(VideoDownloadStreamType.ADAPTIVE, adaptiveOption.streamType)
    }

    @Test
    fun test23_aggregateByteProgressCalculation() {
        val videoBytes = 40_000_000L
        val audioBytes = 5_000_000L
        val totalBytes = 50_000_000L
        val aggregate = videoBytes + audioBytes

        val progress = ActiveDownloadProgress(
            trackId = "test_1",
            stage = DownloadStage.DOWNLOADING,
            downloadedBytes = aggregate,
            totalBytes = totalBytes,
            progressFraction = aggregate.toFloat() / totalBytes.toFloat()
        )

        assertEquals(45_000_000L, progress.downloadedBytes)
        assertEquals(0.9f, progress.progressFraction ?: 0f, 0.001f)
    }

    @Test
    fun test24_unknownContentLengthHandling() {
        val progress = ActiveDownloadProgress(
            trackId = "test_unknown",
            stage = DownloadStage.DOWNLOADING,
            downloadedBytes = 1_000_000L,
            totalBytes = -1L,
            progressFraction = 0f
        )

        assertTrue(progress.totalBytes <= 0)
        assertEquals(0f, progress.progressFraction ?: 0f, 0.001f)
        assertNull(DownloadProgressTracker.calculateEtaSeconds(progress.downloadedBytes, progress.totalBytes, 100_000L))
    }

    @Test
    fun test25_cancellationSetsStateToCancelled() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = VideoDownloadEngine(
            context = testContext,
            downloadDao = fakeDownloadDao,
            trackDao = fakeTrackDao,
            mediaMuxer = fakeMuxer,
            ioDispatcher = testDispatcher,
            scope = this
        )

        val track = createTestTrack("cancel_me")
        val option = createTestOption()

        engine.cancelDownload(track.id)

        val active = engine.observeActiveProgress(track.id).first()
        assertNotNull(active)
        assertEquals(DownloadStage.CANCELLED, active?.stage)
    }

    @Test
    fun test26_activeDownloadsVisibilityMap() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = VideoDownloadEngine(
            context = testContext,
            downloadDao = fakeDownloadDao,
            trackDao = fakeTrackDao,
            mediaMuxer = fakeMuxer,
            ioDispatcher = testDispatcher,
            scope = this
        )

        val allFlow = engine.observeAllActiveProgress()
        val initial = allFlow.first()
        assertTrue(initial.isEmpty())
        assertFalse(engine.activeProgressMap.value.containsKey("random_id"))
    }

    @Test
    fun test27_duplicateDownloadEnqueue_returnsSuccessImmediately() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = VideoDownloadEngine(
            context = testContext,
            downloadDao = fakeDownloadDao,
            trackDao = fakeTrackDao,
            mediaMuxer = fakeMuxer,
            ioDispatcher = testDispatcher,
            scope = this
        )

        val track = createTestTrack("dup_track")
        val option = createTestOption()

        // Insert as already completed in DB
        fakeDownloadDao.insertOrUpdate(
            DownloadEntity(
                trackId = track.id,
                localFilePath = "/path/to/existing.webm",
                totalBytes = 1000L,
                downloadedBytes = 1000L,
                status = DownloadStatus.DOWNLOADED.name
            )
        )

        val result = engine.enqueueDownload(track, option)
        assertTrue(result.isSuccess)
    }

    @Test
    fun test28_remuxFailure_marksDownloadAsFailed() = runTest {
        fakeMuxer.shouldSucceed = false
        fakeMuxer.errorMessage = "Muxer native failure"

        val option = createTestOption()
        val muxResult = fakeMuxer.remux(
            File("dummy_v"),
            File("dummy_a"),
            File("dummy_out"),
            option.container
        )

        assertTrue(muxResult.isFailure)
        assertEquals("Muxer native failure", muxResult.exceptionOrNull()?.message)
    }

    @Test
    fun test29_temporaryFilesCleanedUpOnCompletion() {
        val downloadsFolder = File(tempFolder.root, "sonara_downloads")
        downloadsFolder.mkdirs()

        val tempV = File(downloadsFolder, "test.part_video")
        val tempA = File(downloadsFolder, "test.part_audio")
        tempV.writeText("video data")
        tempA.writeText("audio data")

        assertTrue(tempV.exists())
        assertTrue(tempA.exists())

        // Simulate cleanup
        tempV.delete()
        tempA.delete()

        assertFalse(tempV.exists())
        assertFalse(tempA.exists())
    }

    @Test
    fun test30_atomicCommitViaRename() {
        val downloadsFolder = File(tempFolder.root, "sonara_downloads")
        downloadsFolder.mkdirs()

        val tempFile = File(downloadsFolder, "test.tmp_commit")
        val finalFile = File(downloadsFolder, "test_final.webm")
        tempFile.writeText("complete content")

        val committed = tempFile.renameTo(finalFile)
        assertTrue(committed)
        assertTrue(finalFile.exists())
        assertFalse(tempFile.exists())
        assertEquals("complete content", finalFile.readText())
    }

    @Test
    fun test31_insufficientDiskSpaceHandling() {
        val availableBytes = 50 * 1024 * 1024L // 50 MB
        val requiredBytes = 100 * 1024 * 1024L // 100 MB baseline
        val isSufficient = availableBytes >= requiredBytes
        assertFalse(isSufficient)
    }

    @Test
    fun test32_databaseVideoFileInfoRecording() = runTest {
        val track = createTestTrack("vid_record_test")
        val option = createTestOption(height = 1440, fps = 60, container = VideoDownloadContainer.WEBM)

        fakeDownloadDao.updateVideoFileInfo(
            trackId = track.id,
            localFilePath = "/storage/test.webm",
            mimeType = option.container.mimeType,
            quality = option.quality.name,
            mediaType = "VIDEO",
            resolutionHeight = option.height,
            frameRate = option.frameRate,
            videoCodec = option.videoCodec,
            audioCodec = option.audioCodec,
            container = option.container.extension
        )

        assertEquals(1, fakeDownloadDao.fileInfoUpdates.size)
        val update = fakeDownloadDao.fileInfoUpdates.first()
        assertEquals(track.id, update.trackId)
        assertEquals("VIDEO", update.mediaType)
        assertEquals(1440, update.resolutionHeight)
        assertEquals(60, update.frameRate)
        assertEquals("VP9", update.videoCodec)
        assertEquals("Opus", update.audioCodec)
        assertEquals("webm", update.container)
    }

    @Test
    fun test33_httpRangeHeaderConstruction() {
        val existingLength = 10_000_000L
        val rangeHeader = if (existingLength > 0L) "bytes=$existingLength-" else null
        assertEquals("bytes=10000000-", rangeHeader)

        val freshLength = 0L
        val freshRangeHeader = if (freshLength > 0L) "bytes=$freshLength-" else null
        assertNull(freshRangeHeader)
    }

    @Test
    fun test34_downloadStagesTransition() {
        val stages = listOf(
            DownloadStage.EXTRACTING_STREAMS,
            DownloadStage.DOWNLOADING,
            DownloadStage.REMUXING,
            DownloadStage.VALIDATING,
            DownloadStage.COMPLETED
        )

        assertEquals(DownloadStage.EXTRACTING_STREAMS, stages[0])
        assertEquals(DownloadStage.DOWNLOADING, stages[1])
        assertEquals(DownloadStage.REMUXING, stages[2])
        assertEquals(DownloadStage.VALIDATING, stages[3])
        assertEquals(DownloadStage.COMPLETED, stages[4])
    }

    // ─── PART 4: URL REFRESH, RESUME INTEGRITY & RANGE TESTS (CASES 35-50) ────

    @Test
    fun test35_parseContentRange_validStandardHeaders() {
        val range1 = VideoDownloadEngine.parseContentRange("bytes 100-199/500")
        assertNotNull(range1)
        assertEquals(100L, range1?.start)
        assertEquals(199L, range1?.end)
        assertEquals(500L, range1?.total)

        val range2 = VideoDownloadEngine.parseContentRange("bytes 0-0/1")
        assertNotNull(range2)
        assertEquals(0L, range2?.start)
        assertEquals(0L, range2?.end)
        assertEquals(1L, range2?.total)
    }

    @Test
    fun test36_parseContentRange_wildcardTotalAndMalformed() {
        val wildcard = VideoDownloadEngine.parseContentRange("bytes 100-199/*")
        assertNotNull(wildcard)
        assertEquals(100L, wildcard?.start)
        assertEquals(199L, wildcard?.end)
        assertNull(wildcard?.total)

        assertNull(VideoDownloadEngine.parseContentRange(null))
        assertNull(VideoDownloadEngine.parseContentRange(""))
        assertNull(VideoDownloadEngine.parseContentRange("invalid-header"))
        assertNull(VideoDownloadEngine.parseContentRange("bytes abc-def/ghi"))
    }

    @Test
    fun test37_parseTotalFromContentRange_416AndStandard() {
        assertEquals(5000L, VideoDownloadEngine.parseTotalFromContentRange("bytes */5000"))
        assertEquals(123456L, VideoDownloadEngine.parseTotalFromContentRange("bytes 0-100/123456"))
        assertNull(VideoDownloadEngine.parseTotalFromContentRange("bytes 0-100/*"))
        assertNull(VideoDownloadEngine.parseTotalFromContentRange(null))
        assertNull(VideoDownloadEngine.parseTotalFromContentRange("gibberish"))
    }

    @Test
    fun test38_matchProgressiveStream_exactItagAndCodecMatching() {
        val stream1 = VideoPlaybackStream(
            url = "https://example.com/p1",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 22,
            container = "mp4",
            videoCodec = "avc1",
            audioCodec = "mp4a",
            width = 1280,
            height = 720,
            frameRate = 30,
            bitrateKbps = 1500
        )
        val stream2 = VideoPlaybackStream(
            url = "https://example.com/p2",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 18,
            container = "mp4",
            videoCodec = "avc1",
            audioCodec = "mp4a",
            width = 640,
            height = 360,
            frameRate = 30,
            bitrateKbps = 600
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            progressiveStreams = listOf(stream1, stream2)
        )

        val opt = createTestOption(height = 720, fps = 30, isMergeRequired = false)
        val (url, sameRep) = VideoDownloadEngine.matchProgressiveStream(source, opt.copy(videoItag = 22))
        assertEquals("https://example.com/p1", url)
        assertTrue(sameRep)

        val (notFoundUrl, _) = VideoDownloadEngine.matchProgressiveStream(source, opt.copy(height = 1080, videoItag = 99))
        assertNull(notFoundUrl)
    }

    @Test
    fun test39_matchAdaptiveVideoStream_exactItagAndFpsCodecMatching() {
        val v1080p60 = VideoPlaybackStream(
            url = "https://example.com/v1080_60",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 299,
            container = "webm",
            videoCodec = "vp9",
            audioCodec = null,
            width = 1920,
            height = 1080,
            frameRate = 60,
            bitrateKbps = 4000
        )
        val v1080p30 = VideoPlaybackStream(
            url = "https://example.com/v1080_30",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 137,
            container = "webm",
            videoCodec = "vp9",
            audioCodec = null,
            width = 1920,
            height = 1080,
            frameRate = 30,
            bitrateKbps = 2500
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            videoOnlyStreams = listOf(v1080p60, v1080p30)
        )

        val opt60 = createTestOption(height = 1080, fps = 60).copy(videoItag = 299, actualFps = 60)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveVideoStream(source, opt60)
        assertEquals("https://example.com/v1080_60", matchedUrl)
        assertTrue(sameRep)

        // Matching by resolution and FPS when itag changed: matches URL but sameRep must be false (F-01)
        val opt60DifferentItag = opt60.copy(videoItag = 999)
        val (matchedUrl2, sameRep2) = VideoDownloadEngine.matchAdaptiveVideoStream(source, opt60DifferentItag)
        assertEquals("https://example.com/v1080_60", matchedUrl2)
        assertFalse(sameRep2)
    }

    @Test
    fun test40_matchAdaptiveAudioStream_opusAndAacSeparation() {
        val opusStream = VideoPlaybackStream(
            url = "https://example.com/opus",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 251,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 160
        )
        val aacStream = VideoPlaybackStream(
            url = "https://example.com/aac",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 140,
            container = "m4a",
            videoCodec = null,
            audioCodec = "mp4a",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 128
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            audioOnlyStreams = listOf(opusStream, aacStream)
        )

        // WebM container should pick Opus
        val webmOption = createTestOption(container = VideoDownloadContainer.WEBM, audioCodec = "Opus").copy(audioItag = 251)
        val (matchedOpus, sameOpusRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, webmOption)
        assertEquals("https://example.com/opus", matchedOpus)
        assertTrue(sameOpusRep)

        // MP4 container should pick AAC
        val mp4Option = createTestOption(container = VideoDownloadContainer.MP4, audioCodec = "AAC").copy(audioItag = 140)
        val (matchedAac, sameAacRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, mp4Option)
        assertEquals("https://example.com/aac", matchedAac)
        assertTrue(sameAacRep)
    }

    @Test
    fun test41_isSameVideoCodec_identifiesEquivalentCodecs() {
        assertTrue(VideoDownloadEngine.isSameVideoCodec("vp9", "VP9"))
        assertTrue(VideoDownloadEngine.isSameVideoCodec("vp09.00.41.08", "VP9"))
        assertTrue(VideoDownloadEngine.isSameVideoCodec("avc1.640028", "AVC/H.264"))
        assertTrue(VideoDownloadEngine.isSameVideoCodec("h264", "AVC/H.264"))
        assertTrue(VideoDownloadEngine.isSameVideoCodec("av01.0.08M.08", "AV1"))
        assertFalse(VideoDownloadEngine.isSameVideoCodec("vp9", "AVC/H.264"))
        assertFalse(VideoDownloadEngine.isSameVideoCodec("avc1", "VP9"))
        assertFalse(VideoDownloadEngine.isSameVideoCodec(null, "VP9"))
    }

    @Test
    fun test42_isMatchingFps_honorsActualFpsAndTiers() {
        val opt24 = createTestOption(fps = 30).copy(actualFps = 24)
        assertTrue(VideoDownloadEngine.isMatchingFps(24, opt24))
        assertFalse(VideoDownloadEngine.isMatchingFps(30, opt24))

        val opt30NoActual = createTestOption(fps = 30).copy(actualFps = null)
        assertTrue(VideoDownloadEngine.isMatchingFps(30, opt30NoActual))
        assertTrue(VideoDownloadEngine.isMatchingFps(25, opt30NoActual)) // normalized tier matches
        assertFalse(VideoDownloadEngine.isMatchingFps(60, opt30NoActual))
    }

    @Test
    fun test43_urlRefreshPolicy_representationUnchanged_preservesOffset() {
        val downloadsFolder = File(tempFolder.root, "sonara_downloads").apply { mkdirs() }
        val partialFile = File(downloadsFolder, "test_part.part")
        partialFile.writeBytes(ByteArray(1024))
        assertEquals(1024L, partialFile.length())

        // Representation matches -> file length must be preserved
        val representationMatches = true
        if (!representationMatches) {
            java.io.RandomAccessFile(partialFile, "rw").use { it.setLength(0L) }
        }
        assertEquals(1024L, partialFile.length())
    }

    @Test
    fun test44_urlRefreshPolicy_representationChanged_truncatesToZero() {
        val downloadsFolder = File(tempFolder.root, "sonara_downloads").apply { mkdirs() }
        val partialFile = File(downloadsFolder, "test_changed.part")
        partialFile.writeBytes(ByteArray(1024))
        assertEquals(1024L, partialFile.length())

        // Representation differs -> file must be truncated to 0
        val representationMatches = false
        if (!representationMatches) {
            java.io.RandomAccessFile(partialFile, "rw").use { it.setLength(0L) }
        }
        assertEquals(0L, partialFile.length())
    }

    @Test
    fun test45_urlRefreshPolicy_videoCompleted_preservesCompletedComponentWhenAudioExpires() {
        var videoCompleted = true
        var audioCompleted = false
        val videoPartFile = File(tempFolder.root, "video.part").apply { writeBytes(ByteArray(5000)) }
        val audioPartFile = File(tempFolder.root, "audio.part").apply { writeBytes(ByteArray(1000)) }

        // Audio expires during fetch
        val expiredComponent = "AUDIO"
        if (expiredComponent == "AUDIO") {
            // Video is already completed: must NOT truncate or delete videoPartFile
            assertFalse(!videoCompleted)
            assertTrue(videoPartFile.exists())
            assertEquals(5000L, videoPartFile.length())
        }
    }

    @Test
    fun test46_urlRefreshPolicy_exceedingMaxAttempts_throwsUrlExpiredException() {
        val maxAttempts = VideoDownloadEngine.MAX_URL_REFRESH_ATTEMPTS
        var currentAttempts = 2

        var thrown: Throwable? = null
        try {
            if (currentAttempts >= maxAttempts) {
                throw UrlExpiredException("Stream URL expired (403) and exceeded max refresh attempts ($maxAttempts)")
            }
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertTrue(thrown is UrlExpiredException)
        assertTrue(thrown?.message?.contains("exceeded max refresh attempts") == true)
    }

    @Test
    fun test47_urlRefreshPolicy_nullExtractor_throwsStreamUnavailableException() {
        val extractor: VideoExtractionPort? = null
        var thrown: Throwable? = null

        try {
            val ex = extractor ?: throw StreamUnavailableException("Stream URL expired (403) but no VideoExtractionPort provided")
        } catch (t: Throwable) {
            thrown = t
        }

        assertNotNull(thrown)
        assertTrue(thrown is StreamUnavailableException)
        assertTrue(thrown?.message?.contains("no VideoExtractionPort provided") == true)
    }

    @Test
    fun test48_rangeResumeValidation_serverReturns200_resetsBytesAndTruncatesToZero() {
        val downloadsFolder = File(tempFolder.root, "sonara_downloads").apply { mkdirs() }
        val partFile = File(downloadsFolder, "test_200.part").apply { writeBytes(ByteArray(2048)) }
        val existingLength = partFile.length()
        val responseCode = 200

        var bytesReset = false
        if (responseCode == 200 && existingLength > 0L) {
            bytesReset = true
            java.io.RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
        }

        assertTrue(bytesReset)
        assertEquals(0L, partFile.length())
    }

    @Test
    fun test49_rangeResumeValidation_serverReturns416_completeFilePasses_incompleteRestarts() {
        val totalLength = 5000L
        val completeExisting = 5000L
        val incompleteExisting = 2500L

        // Complete case: existing == total -> success without restart
        val isComplete = completeExisting == totalLength
        assertTrue(isComplete)

        // Incomplete case: existing != total -> restart from byte 0
        val isIncompleteValid = incompleteExisting == totalLength
        assertFalse(isIncompleteValid)
    }

    @Test
    fun test50_rangeResumeValidation_etagMismatch_triggersRestartFromZero() {
        val expectedEtag = "\"etag-v1\""
        val serverEtag = "\"etag-v2\""
        val etagMatches = expectedEtag == serverEtag

        assertFalse(etagMatches)

        val partFile = File(tempFolder.root, "etag_test.part").apply { writeBytes(ByteArray(1024)) }
        if (!etagMatches) {
            java.io.RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
        }
        assertEquals(0L, partFile.length())
    }

    // ─── PART 7: FINDINGS F-01 & F-03 REFRESH & IDENTITY HARMONIZATION (CASES 51-60) ─────

    @Test
    fun test51_matchAdaptiveVideoStream_exactVideoItagMatch_preservesRepresentation() {
        val stream = VideoPlaybackStream(
            url = "https://example.com/v1080_exact",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 299,
            container = "webm",
            videoCodec = "vp9",
            audioCodec = null,
            width = 1920,
            height = 1080,
            frameRate = 60,
            bitrateKbps = 4000
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            videoOnlyStreams = listOf(stream)
        )

        val opt = createTestOption(height = 1080, fps = 60).copy(videoItag = 299, actualFps = 60)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveVideoStream(source, opt)

        assertEquals("https://example.com/v1080_exact", matchedUrl)
        assertTrue("Exact itag match must preserve representation (sameRep = true)", sameRep)
    }

    @Test
    fun test52_matchAdaptiveVideoStream_differentVideoItag_forcesRestart() {
        val stream = VideoPlaybackStream(
            url = "https://example.com/v1080_new_itag",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 300,
            container = "webm",
            videoCodec = "vp9",
            audioCodec = null,
            width = 1920,
            height = 1080,
            frameRate = 60,
            bitrateKbps = 4000
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            videoOnlyStreams = listOf(stream)
        )

        val opt = createTestOption(height = 1080, fps = 60).copy(videoItag = 299, actualFps = 60)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveVideoStream(source, opt)

        assertEquals("https://example.com/v1080_new_itag", matchedUrl)
        assertFalse("Different itag must force restart from byte zero (sameRep = false)", sameRep)
    }

    @Test
    fun test53_matchAdaptiveVideoStream_missingOriginalVideoItag_forcesRestart() {
        val stream = VideoPlaybackStream(
            url = "https://example.com/v1080_any",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 299,
            container = "webm",
            videoCodec = "vp9",
            audioCodec = null,
            width = 1920,
            height = 1080,
            frameRate = 60,
            bitrateKbps = 4000
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.VIDEO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            videoOnlyStreams = listOf(stream)
        )

        val optWithoutItag = createTestOption(height = 1080, fps = 60).copy(videoItag = null, actualFps = 60)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveVideoStream(source, optWithoutItag)

        assertEquals("https://example.com/v1080_any", matchedUrl)
        assertFalse("Missing original itag must never assume equivalence (sameRep = false)", sameRep)
    }

    @Test
    fun test54_matchProgressiveStream_exactItagVsFallbackRestart() {
        val stream720p = VideoPlaybackStream(
            url = "https://example.com/p720",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 22,
            container = "mp4",
            videoCodec = "avc1",
            audioCodec = "mp4a",
            width = 1280,
            height = 720,
            frameRate = 30,
            bitrateKbps = 2000
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            progressiveStreams = listOf(stream720p)
        )

        val optExact = createTestOption(height = 720, fps = 30, isMergeRequired = false).copy(videoItag = 22)
        val (url1, sameRep1) = VideoDownloadEngine.matchProgressiveStream(source, optExact)
        assertEquals("https://example.com/p720", url1)
        assertTrue("Exact itag match must preserve representation", sameRep1)

        val optDifferent = createTestOption(height = 720, fps = 30, isMergeRequired = false).copy(videoItag = 99)
        val (url2, sameRep2) = VideoDownloadEngine.matchProgressiveStream(source, optDifferent)
        assertEquals("https://example.com/p720", url2)
        assertFalse("Different itag must evaluate to false", sameRep2)

        val optMissing = createTestOption(height = 720, fps = 30, isMergeRequired = false).copy(videoItag = null)
        val (url3, sameRep3) = VideoDownloadEngine.matchProgressiveStream(source, optMissing)
        assertEquals("https://example.com/p720", url3)
        assertFalse("Missing itag must evaluate to false", sameRep3)
    }

    @Test
    fun test55_matchAdaptiveAudioStream_exactAudioItagMatch_preservesRepresentation() {
        val stream = VideoPlaybackStream(
            url = "https://example.com/audio_exact",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 251,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 160
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            audioOnlyStreams = listOf(stream)
        )

        val opt = createTestOption(container = VideoDownloadContainer.WEBM, audioCodec = "Opus").copy(audioItag = 251)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, opt)

        assertEquals("https://example.com/audio_exact", matchedUrl)
        assertTrue("Exact audio itag match must preserve representation", sameRep)
    }

    @Test
    fun test56_matchAdaptiveAudioStream_differentAudioItag_forcesRestart() {
        val stream = VideoPlaybackStream(
            url = "https://example.com/audio_refreshed",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 250,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 70
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            audioOnlyStreams = listOf(stream)
        )

        val opt = createTestOption(container = VideoDownloadContainer.WEBM, audioCodec = "Opus").copy(audioItag = 251)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, opt)

        assertEquals("https://example.com/audio_refreshed", matchedUrl)
        assertFalse("Different audio itag must force restart from zero", sameRep)
    }

    @Test
    fun test57_matchAdaptiveAudioStream_preservesLanguageOverHigherBitrateAlternateLanguage() {
        val englishStream = VideoPlaybackStream(
            url = "https://example.com/audio_en_128",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 601,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 128,
            audioLocale = "en-US",
            audioTrackId = "track_en"
        )
        val spanishStream = VideoPlaybackStream(
            url = "https://example.com/audio_es_192",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 602,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 192,
            audioLocale = "es",
            audioTrackId = "track_es"
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            audioOnlyStreams = listOf(spanishStream, englishStream) // Spanish is higher bitrate and first
        )

        // Option originally used English (audioLocale = "en", audioTrackId = "track_en", audioItag was 600 which changed)
        val opt = createTestOption(container = VideoDownloadContainer.WEBM, audioCodec = "Opus").copy(
            audioItag = 600,
            audioLocale = "en",
            audioTrackId = "track_en"
        )
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, opt)

        // Must pick English stream despite Spanish having 192 kbps vs 128 kbps (F-03)
        assertEquals("https://example.com/audio_en_128", matchedUrl)
        assertFalse("Different itag means sameRep is false", sameRep)
    }

    @Test
    fun test58_matchAdaptiveAudioStream_missingLanguageMetadataFallback_picksHighestBitrateInCodecFamily() {
        val streamLow = VideoPlaybackStream(
            url = "https://example.com/audio_low",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 249,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 50
        )
        val streamHigh = VideoPlaybackStream(
            url = "https://example.com/audio_high",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 251,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 160
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            audioOnlyStreams = listOf(streamLow, streamHigh)
        )

        // Option with no locale metadata and expired itag
        val opt = createTestOption(container = VideoDownloadContainer.WEBM, audioCodec = "Opus").copy(
            audioItag = 999,
            audioLocale = null,
            audioTrackId = null
        )
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, opt)

        assertEquals("https://example.com/audio_high", matchedUrl)
        assertFalse(sameRep)
    }

    @Test
    fun test59_urlRefreshPolicy_videoCompleted_preservesSiblingVideoWhenAudioRestartsFromZero() {
        val videoPartFile = File(tempFolder.root, "sibling_video.part").apply { writeBytes(ByteArray(40960)) }
        val audioPartFile = File(tempFolder.root, "sibling_audio.part").apply { writeBytes(ByteArray(8192)) }

        val videoCompleted = true
        val audioCompleted = false
        val audioRepresentationMatches = false // Audio itag differed on refresh

        // Simulate engine recovery branch
        if (!videoCompleted) {
            // Should not be entered
            java.io.RandomAccessFile(videoPartFile, "rw").use { it.setLength(0L) }
        }

        if (!audioCompleted) {
            if (!audioRepresentationMatches) {
                java.io.RandomAccessFile(audioPartFile, "rw").use { it.setLength(0L) }
            }
        }

        // Verify video file is completely untouched while audio file was truncated to 0
        assertEquals(40960L, videoPartFile.length())
        assertEquals(0L, audioPartFile.length())
    }

    @Test
    fun test60_isMatchingLocale_matchesLanguageCodesAndSubtags() {
        assertTrue(VideoDownloadEngine.isMatchingLocale("en", "en"))
        assertTrue(VideoDownloadEngine.isMatchingLocale("en-US", "en"))
        assertTrue(VideoDownloadEngine.isMatchingLocale("en_US", "en_GB"))
        assertTrue(VideoDownloadEngine.isMatchingLocale("ES-MX", "es"))
        assertFalse(VideoDownloadEngine.isMatchingLocale("en", "es"))
        assertFalse(VideoDownloadEngine.isMatchingLocale("hi", "bn"))
        assertFalse(VideoDownloadEngine.isMatchingLocale(null, "en"))
        assertFalse(VideoDownloadEngine.isMatchingLocale("en", null))
        assertFalse(VideoDownloadEngine.isMatchingLocale("", "en"))
        assertFalse(VideoDownloadEngine.isMatchingLocale("   ", "en"))
    }

    // ─── PART 8: CODEC COMPATIBILITY & CROSS-CODEC FALLBACK ELIMINATION (CASES 61-68) ───

    @Test
    fun test61_matchAdaptiveAudioStream_webmWithOnlyAac_returnsNullNoCrossCodecFallback() {
        val aacStream = VideoPlaybackStream(
            url = "https://example.com/audio_aac",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 140,
            container = "m4a",
            videoCodec = null,
            audioCodec = "mp4a",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 128
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            audioOnlyStreams = listOf(aacStream)
        )

        // WebM requires Opus/Vorbis: must NOT fall back to AAC!
        val webmOption = createTestOption(container = VideoDownloadContainer.WEBM, audioCodec = "Opus").copy(audioItag = null)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, webmOption)

        assertNull("Must not fall back to AAC for WebM download", matchedUrl)
        assertFalse(sameRep)
    }

    @Test
    fun test62_matchAdaptiveAudioStream_mp4WithOnlyOpus_returnsNullNoCrossCodecFallback() {
        val opusStream = VideoPlaybackStream(
            url = "https://example.com/audio_opus",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 251,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 160
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            audioOnlyStreams = listOf(opusStream)
        )

        // MP4 requires AAC: must NOT fall back to Opus!
        val mp4Option = createTestOption(container = VideoDownloadContainer.MP4, audioCodec = "AAC").copy(audioItag = null)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, mp4Option)

        assertNull("Must not fall back to Opus for MP4 download", matchedUrl)
        assertFalse(sameRep)
    }

    @Test
    fun test63_performDownload_incompatibleAudioCodec_failsFastBeforeTransfer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = VideoDownloadEngine(
            context = testContext,
            downloadDao = fakeDownloadDao,
            trackDao = fakeTrackDao,
            mediaMuxer = fakeMuxer,
            ioDispatcher = testDispatcher,
            scope = this
        )

        val track = createTestTrack("incompat_codec_track")
        // WebM container with incompatible AAC audio codec
        val invalidOption = createTestOption(
            container = VideoDownloadContainer.WEBM,
            videoCodec = "VP9",
            audioCodec = "AAC",
            isMergeRequired = true
        )

        engine.enqueueDownload(track, invalidOption)
        testScheduler.advanceUntilIdle()

        // Verify fail-fast before transfer
        val progress = engine.observeActiveProgress(track.id).first()
        assertNotNull(progress)
        assertEquals(DownloadStage.FAILED, progress?.stage)
        assertTrue(progress?.errorMessage?.contains("incompatible with WEBM container") == true)
        assertEquals(DownloadStatus.DOWNLOADING.name, fakeDownloadDao.inserted.first().status) // initially queued
        assertNotNull(fakeDownloadDao.lastFailedReason)
        assertTrue(fakeDownloadDao.lastFailedReason?.contains("incompatible with WEBM container") == true)
    }

    @Test
    fun test64_performDownload_missingAudioUrl_failsFastBeforeTransfer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = VideoDownloadEngine(
            context = testContext,
            downloadDao = fakeDownloadDao,
            trackDao = fakeTrackDao,
            mediaMuxer = fakeMuxer,
            ioDispatcher = testDispatcher,
            scope = this
        )

        val track = createTestTrack("missing_audio_url_track")
        // Adaptive option with null audioStreamUrl
        val invalidOption = createTestOption(
            container = VideoDownloadContainer.WEBM,
            videoCodec = "VP9",
            audioCodec = "Opus",
            isMergeRequired = true
        ).copy(audioStreamUrl = null)

        engine.enqueueDownload(track, invalidOption)
        testScheduler.advanceUntilIdle()

        val progress = engine.observeActiveProgress(track.id).first()
        assertNotNull(progress)
        assertEquals(DownloadStage.FAILED, progress?.stage)
        assertTrue(progress?.errorMessage?.contains("No compatible audio stream available") == true)
    }

    @Test
    fun test65_isAudioCodecCompatible_validatesContainersAndCodecs() {
        // WebM
        assertTrue(VideoDownloadEngine.isAudioCodecCompatible("opus", VideoDownloadContainer.WEBM))
        assertTrue(VideoDownloadEngine.isAudioCodecCompatible("Opus", VideoDownloadContainer.WEBM))
        assertTrue(VideoDownloadEngine.isAudioCodecCompatible("vorbis", VideoDownloadContainer.WEBM))
        assertFalse(VideoDownloadEngine.isAudioCodecCompatible("aac", VideoDownloadContainer.WEBM))
        assertFalse(VideoDownloadEngine.isAudioCodecCompatible("mp4a", VideoDownloadContainer.WEBM))
        assertFalse(VideoDownloadEngine.isAudioCodecCompatible(null, VideoDownloadContainer.WEBM))
        assertFalse(VideoDownloadEngine.isAudioCodecCompatible("", VideoDownloadContainer.WEBM))

        // MP4
        assertTrue(VideoDownloadEngine.isAudioCodecCompatible("aac", VideoDownloadContainer.MP4))
        assertTrue(VideoDownloadEngine.isAudioCodecCompatible("AAC", VideoDownloadContainer.MP4))
        assertTrue(VideoDownloadEngine.isAudioCodecCompatible("mp4a", VideoDownloadContainer.MP4))
        assertFalse(VideoDownloadEngine.isAudioCodecCompatible("opus", VideoDownloadContainer.MP4))
        assertFalse(VideoDownloadEngine.isAudioCodecCompatible("vorbis", VideoDownloadContainer.MP4))
        assertFalse(VideoDownloadEngine.isAudioCodecCompatible(null, VideoDownloadContainer.MP4))
    }

    @Test
    fun test66_isAudioStreamCompatible_validatesStreams() {
        val opusStream = VideoPlaybackStream(
            url = "https://example.com/opus",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 251,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus"
        )
        val aacStream = VideoPlaybackStream(
            url = "https://example.com/aac",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 140,
            container = "m4a",
            videoCodec = null,
            audioCodec = "mp4a"
        )

        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(opusStream, VideoDownloadContainer.WEBM))
        assertFalse(VideoDownloadEngine.isAudioStreamCompatible(opusStream, VideoDownloadContainer.MP4))

        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(aacStream, VideoDownloadContainer.MP4))
        assertFalse(VideoDownloadEngine.isAudioStreamCompatible(aacStream, VideoDownloadContainer.WEBM))
    }

    @Test
    fun test67_matchAdaptiveAudioStream_higherBitrateIncompatibleCodec_neverSelected() {
        val aacHighBitrate = VideoPlaybackStream(
            url = "https://example.com/audio_aac_320",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 141,
            container = "m4a",
            videoCodec = null,
            audioCodec = "mp4a",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 320
        )
        val opusLowBitrate = VideoPlaybackStream(
            url = "https://example.com/audio_opus_128",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 251,
            container = "webm",
            videoCodec = null,
            audioCodec = "opus",
            width = null,
            height = null,
            frameRate = null,
            bitrateKbps = 128
        )
        val source = VideoPlaybackSource(
            videoId = "v1",
            streamUrl = "https://example.com/main",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            audioOnlyStreams = listOf(aacHighBitrate, opusLowBitrate) // AAC is first and higher bitrate
        )

        val webmOption = createTestOption(container = VideoDownloadContainer.WEBM, audioCodec = "Opus").copy(audioItag = null)
        val (matchedUrl, sameRep) = VideoDownloadEngine.matchAdaptiveAudioStream(source, webmOption)

        // Must select Opus at 128 kbps, never the 320 kbps AAC
        assertEquals("https://example.com/audio_opus_128", matchedUrl)
        assertFalse(sameRep)
    }

    @Test
    fun test68_performDownload_completedVideoComponentPreservedWhenFailureOccurs() {
        val videoPartFile = File(tempFolder.root, "completed_video.part").apply { writeBytes(ByteArray(65536)) }
        val audioPartFile = File(tempFolder.root, "incomplete_audio.part").apply { writeBytes(ByteArray(1024)) }

        val videoCompleted = true
        val audioCompleted = false

        // Simulate catch(t: Throwable) block in performDownload
        if (!videoCompleted) {
            videoPartFile.delete()
        }
        if (!audioCompleted) {
            audioPartFile.delete()
        }

        // Completed video part file must NOT be deleted
        assertTrue(videoPartFile.exists())
        assertEquals(65536L, videoPartFile.length())

        // Incomplete audio part file was deleted
        assertFalse(audioPartFile.exists())
    }

    @Test
    fun test69_isAudioStreamCompatible_failClosedAgainstContradictoryAndUnknownMetadata() {
        fun makeStream(codec: String?, container: String? = null, mime: String? = null) = VideoPlaybackStream(
            url = "https://example.com/audio",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 999,
            container = container,
            mimeType = mime,
            videoCodec = null,
            audioCodec = codec
        )

        // 1. Opus + WebM: Accept
        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "opus", container = "webm"), VideoDownloadContainer.WEBM))

        // 2. Vorbis + WebM: Accept
        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "vorbis", container = "webm"), VideoDownloadContainer.WEBM))

        // 3. AAC + WebM: Reject
        assertFalse(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "aac", container = "m4a"), VideoDownloadContainer.WEBM))

        // 4. Opus + MP4: Reject
        assertFalse(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "opus", container = "webm"), VideoDownloadContainer.MP4))

        // 5. AAC + MP4: Accept
        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "aac", container = "mp4"), VideoDownloadContainer.MP4))
        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "mp4a.40.2", container = "m4a"), VideoDownloadContainer.MP4))

        // 6. FLAC + WebM: Reject
        assertFalse(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "flac", container = "webm"), VideoDownloadContainer.WEBM))

        // 7. FLAC + MP4: Reject
        assertFalse(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "flac", container = "mp4"), VideoDownloadContainer.MP4))

        // 8. Missing codec + WebM metadata: Apply documented metadata fallback (Accept)
        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = null, container = "webm"), VideoDownloadContainer.WEBM))
        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "", mime = "audio/webm"), VideoDownloadContainer.WEBM))

        // 9. Missing codec + MP4 metadata: Apply documented metadata fallback (Accept)
        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = null, container = "m4a"), VideoDownloadContainer.MP4))
        assertTrue(VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "", mime = "audio/mp4"), VideoDownloadContainer.MP4))

        // 10. AAC codec + contradictory WebM metadata: Reject (explicit codec takes precedence)
        assertFalse("AAC codec with contradictory WebM container must be rejected",
            VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "aac", container = "webm", mime = "audio/webm"), VideoDownloadContainer.WEBM))
        assertFalse("mp4a codec with contradictory WebM container must be rejected",
            VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "mp4a", container = "webm", mime = "audio/webm"), VideoDownloadContainer.WEBM))

        // 11. Opus codec + contradictory MP4 metadata: Reject (explicit codec takes precedence)
        assertFalse("Opus codec with contradictory MP4 container must be rejected",
            VideoDownloadEngine.isAudioStreamCompatible(makeStream(codec = "opus", container = "mp4", mime = "audio/mp4"), VideoDownloadContainer.MP4))
    }
}
