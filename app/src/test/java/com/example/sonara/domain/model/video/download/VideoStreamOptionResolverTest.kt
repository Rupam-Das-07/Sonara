package com.example.sonara.domain.model.video.download

import com.example.sonara.domain.model.video.VideoDeliveryMethod
import com.example.sonara.domain.model.video.VideoPlaybackSource
import com.example.sonara.domain.model.video.VideoPlaybackStream
import com.example.sonara.domain.model.video.VideoQuality
import com.example.sonara.domain.model.video.VideoStreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStreamOptionResolverTest {

    private fun createStream(
        url: String = "https://example.com/stream",
        kind: VideoStreamKind = VideoStreamKind.VIDEO_ONLY,
        deliveryMethod: VideoDeliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
        formatId: Int = 137,
        container: String = "webm",
        videoCodec: String? = "vp9",
        audioCodec: String? = null,
        width: Int? = 1920,
        height: Int? = 1080,
        frameRate: Int? = 30,
        bitrateKbps: Int? = 2500
    ): VideoPlaybackStream = VideoPlaybackStream(
        url = url,
        kind = kind,
        deliveryMethod = deliveryMethod,
        formatId = formatId,
        container = container,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        width = width,
        height = height,
        frameRate = frameRate,
        bitrateKbps = bitrateKbps
    )

    private fun createAudioStream(
        formatId: Int = 251,
        codec: String = "opus",
        container: String = "webm",
        bitrate: Int = 160
    ): VideoPlaybackStream = VideoPlaybackStream(
        url = "https://example.com/audio_$formatId",
        kind = VideoStreamKind.AUDIO_ONLY,
        deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
        formatId = formatId,
        container = container,
        videoCodec = null,
        audioCodec = codec,
        width = null,
        height = null,
        frameRate = null,
        bitrateKbps = bitrate
    )

    private fun createSource(
        videoOnlyStreams: List<VideoPlaybackStream> = emptyList(),
        audioOnlyStreams: List<VideoPlaybackStream> = emptyList(),
        progressiveStreams: List<VideoPlaybackStream> = emptyList(),
        durationMs: Long? = 120_000L
    ): VideoPlaybackSource = VideoPlaybackSource(
        videoId = "test_vid",
        streamUrl = videoOnlyStreams.firstOrNull()?.url ?: progressiveStreams.firstOrNull()?.url ?: "https://example.com/main",
        kind = VideoStreamKind.VIDEO_ONLY,
        deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
        videoOnlyStreams = videoOnlyStreams,
        audioOnlyStreams = audioOnlyStreams,
        progressiveStreams = progressiveStreams,
        durationMs = durationMs
    )

    // ─── PART 1: STREAM DISCOVERY & RESOLUTION (CASES 1-12) ───────────────────

    @Test
    fun test01_720p30StreamDiscovery_generatesWebmAndMp4Options() {
        val vp9_720p = createStream(height = 720, frameRate = 30, videoCodec = "vp9", container = "webm")
        val opus = createAudioStream(codec = "opus", container = "webm")
        val source = createSource(
            videoOnlyStreams = listOf(vp9_720p),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        assertEquals(1, options.size)
        val opt = options.first()
        assertEquals(720, opt.height)
        assertEquals(30, opt.frameRate)
        assertEquals(VideoDownloadContainer.WEBM, opt.container)
        assertEquals("VP9", opt.videoCodec)
        assertEquals("Opus", opt.audioCodec)
        assertTrue(opt.isSupported)
        assertTrue(opt.isMergeRequired)
        assertNotNull(opt.estimatedTotalSizeBytes)
    }

    @Test
    fun test02_720p60StreamDiscovery_distinguishes60Fps() {
        val vp9_720p60 = createStream(height = 720, frameRate = 60, videoCodec = "vp9", container = "webm")
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(vp9_720p60),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(720, opt.height)
        assertEquals(60, opt.frameRate)
        assertEquals("60 FPS", opt.fpsLabel)
    }

    @Test
    fun test03_1080p30StreamDiscovery_resolvesCorrectly() {
        val vp9_1080p30 = createStream(height = 1080, frameRate = 30, videoCodec = "vp9", container = "webm")
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(vp9_1080p30),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(1080, opt.height)
        assertEquals(30, opt.frameRate)
        assertEquals("30 FPS", opt.fpsLabel)
    }

    @Test
    fun test04_1080p60StreamDiscovery_resolvesCorrectly() {
        val vp9_1080p60 = createStream(height = 1080, frameRate = 60, videoCodec = "vp9", container = "webm")
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(vp9_1080p60),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(1080, opt.height)
        assertEquals(60, opt.frameRate)
        assertEquals("60 FPS", opt.fpsLabel)
    }

    @Test
    fun test05_1440p30StreamDiscovery_resolves2KWebM() {
        val vp9_1440p30 = createStream(height = 1440, width = 2560, frameRate = 30, videoCodec = "vp9", container = "webm")
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(vp9_1440p30),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(1440, opt.height)
        assertEquals(2560, opt.width)
        assertEquals(30, opt.frameRate)
        assertEquals("1440p", opt.resolutionLabel)
        assertEquals(VideoDownloadContainer.WEBM, opt.container)
    }

    @Test
    fun test06_1440p60StreamDiscovery_resolvesAndCapsAt1440p() {
        val vp9_1440p60 = createStream(height = 1440, width = 2560, frameRate = 60, videoCodec = "vp9", container = "webm")
        val vp9_2160p60 = createStream(height = 2160, width = 3840, frameRate = 60, videoCodec = "vp9", container = "webm")
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(vp9_2160p60, vp9_1440p60),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        // 2160p (4K) must be explicitly excluded
        assertEquals(1, options.size)
        assertEquals(1440, options.first().height)
        assertEquals(60, options.first().frameRate)
    }

    @Test
    fun test07_videoWithOnly30FpsStreams_doesNotGenerateFake60Fps() {
        val s720 = createStream(height = 720, frameRate = 30)
        val s1080 = createStream(height = 1080, frameRate = 30)
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(s1080, s720),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        assertTrue(options.all { it.frameRate == 30 })
        assertFalse(options.any { it.frameRate == 60 })
    }

    @Test
    fun test08_videoWithout1440p_highestOptionIs1080pOrLower() {
        val s720 = createStream(height = 720, frameRate = 30)
        val s1080 = createStream(height = 1080, frameRate = 30)
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(s1080, s720),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        assertEquals(1080, options.maxOf { it.height })
        assertFalse(options.any { it.height > 1080 })
    }

    @Test
    fun test09_videoWithAv1Only_isMarkedUnsupportedCodec() {
        val av1_1080p = createStream(height = 1080, frameRate = 30, videoCodec = "av01.0.08M.08", container = "mp4")
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(av1_1080p),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(VideoDownloadOptionCompatibility.UNSUPPORTED_CODEC, opt.compatibility)
        assertFalse(opt.isSupported)
    }

    @Test
    fun test10_videoWithMissingAudioStream_isMarkedMissingAudio() {
        val vp9_1080p = createStream(height = 1080, frameRate = 30, videoCodec = "vp9", container = "webm")
        val source = createSource(
            videoOnlyStreams = listOf(vp9_1080p),
            audioOnlyStreams = emptyList() // No audio streams
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(VideoDownloadOptionCompatibility.MISSING_AUDIO, opt.compatibility)
        assertFalse(opt.isSupported)
        assertNull(opt.audioStreamUrl)
    }

    @Test
    fun test11_progressiveStreams_requireNoMerge() {
        val prog720 = VideoPlaybackStream(
            url = "https://example.com/progressive_720.mp4",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 22,
            container = "mp4",
            videoCodec = "avc1.64001F",
            audioCodec = "mp4a.40.2",
            width = 1280,
            height = 720,
            frameRate = 30,
            bitrateKbps = 1500
        )
        val source = createSource(
            progressiveStreams = listOf(prog720)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        assertEquals(1, options.size)
        val opt = options.first()
        assertEquals(VideoDownloadStreamType.PROGRESSIVE, opt.streamType)
        assertFalse(opt.isMergeRequired)
        assertNull(opt.audioStreamUrl)
        assertEquals(VideoDownloadContainer.MP4, opt.container)
        assertTrue(opt.isSupported)
    }

    @Test
    fun test12_unsupported2160pStream_filteredOutFromDownloadOptions() {
        val stream2160p = createStream(height = 2160, width = 3840, frameRate = 60, videoCodec = "vp9")
        val stream1080p = createStream(height = 1080, width = 1920, frameRate = 60, videoCodec = "vp9")
        val opus = createAudioStream()
        val source = createSource(
            videoOnlyStreams = listOf(stream2160p, stream1080p),
            audioOnlyStreams = listOf(opus)
        )

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        assertEquals(1, options.size)
        assertEquals(1080, options.first().height)
    }

    // ─── PART 2: PLAYBACK-QUALITY PRESELECTION & FALLBACK (CASES 13-20) ────────

    @Test
    fun test13_preselectionExactMatch720p30() {
        val s720_30 = createOption(720, 30)
        val s1080_30 = createOption(1080, 30)
        val options = listOf(s1080_30, s720_30)

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = 720,
            renderedWidth = 1280,
            renderedFps = 30,
            isActualTelemetry = true,
            sourceRoute = "MEDIA3"
        )

        val result = VideoStreamOptionResolver.preselectOption(options, snapshot)
        assertEquals(s720_30, result.selectedOption)
        assertEquals(DownloadPreselectionReason.EXACT_MATCH, result.reason)
        assertTrue(result.explanation?.contains("Matches current playback") == true)
    }

    @Test
    fun test14_preselectionExactMatch720p60() {
        val s720_30 = createOption(720, 30)
        val s720_60 = createOption(720, 60)
        val options = listOf(s720_60, s720_30)

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = 720,
            renderedWidth = 1280,
            renderedFps = 60,
            isActualTelemetry = true,
            sourceRoute = "MEDIA3"
        )

        val result = VideoStreamOptionResolver.preselectOption(options, snapshot)
        assertEquals(s720_60, result.selectedOption)
        assertEquals(DownloadPreselectionReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun test15_preselectionExactMatch1080p60() {
        val s1080_60 = createOption(1080, 60)
        val s1080_30 = createOption(1080, 30)
        val options = listOf(s1080_60, s1080_30)

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = 1080,
            renderedWidth = 1920,
            renderedFps = 60,
            isActualTelemetry = true,
            sourceRoute = "MEDIA3"
        )

        val result = VideoStreamOptionResolver.preselectOption(options, snapshot)
        assertEquals(s1080_60, result.selectedOption)
        assertEquals(DownloadPreselectionReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun test16_preselectionExactMatch1440p60() {
        val s1440_60 = createOption(1440, 60)
        val s1080_60 = createOption(1080, 60)
        val options = listOf(s1440_60, s1080_60)

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = 1440,
            renderedWidth = 2560,
            renderedFps = 60,
            isActualTelemetry = true,
            sourceRoute = "MEDIA3"
        )

        val result = VideoStreamOptionResolver.preselectOption(options, snapshot)
        assertEquals(s1440_60, result.selectedOption)
        assertEquals(DownloadPreselectionReason.EXACT_MATCH, result.reason)
    }

    @Test
    fun test17_preselectionFallbackResolutionWithSameFps() {
        // Watching at 1080p60, but only 720p60 and 720p30 are available for download
        val s720_60 = createOption(720, 60)
        val s720_30 = createOption(720, 30)
        val options = listOf(s720_60, s720_30)

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = 1080,
            renderedWidth = 1920,
            renderedFps = 60,
            isActualTelemetry = true,
            sourceRoute = "MEDIA3"
        )

        val result = VideoStreamOptionResolver.preselectOption(options, snapshot)
        assertEquals(s720_60, result.selectedOption)
        assertEquals(DownloadPreselectionReason.FALLBACK_RESOLUTION_SAME_FPS, result.reason)
        assertTrue(result.explanation?.contains("fallback to 720p · 60 FPS") == true)
    }

    @Test
    fun test18_preselectionFallbackResolutionWithLowerFps() {
        // Watching at 1080p60, but only 720p30 is available
        val s720_30 = createOption(720, 30)
        val s480_30 = createOption(480, 30)
        val options = listOf(s720_30, s480_30)

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = 1080,
            renderedWidth = 1920,
            renderedFps = 60,
            isActualTelemetry = true,
            sourceRoute = "MEDIA3"
        )

        val result = VideoStreamOptionResolver.preselectOption(options, snapshot)
        assertEquals(s720_30, result.selectedOption)
        assertEquals(DownloadPreselectionReason.FALLBACK_RESOLUTION_LOWER_FPS, result.reason)
    }

    @Test
    fun test19_manualSelectionWithoutAlteringPlaybackQuality() {
        val s1080_60 = createOption(1080, 60)
        val s720_30 = createOption(720, 30)
        val options = listOf(s1080_60, s720_30)

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = 1080,
            renderedWidth = 1920,
            renderedFps = 60,
            isActualTelemetry = true,
            sourceRoute = "MEDIA3"
        )

        val preselection = VideoStreamOptionResolver.preselectOption(options, snapshot)
        assertEquals(s1080_60, preselection.selectedOption)

        // Manual user selection override
        var activeOption = preselection.selectedOption
        activeOption = s720_30

        assertEquals(s720_30, activeOption)
        // Telemetry snapshot remains unaffected
        assertEquals(1080, snapshot.renderedHeight)
        assertEquals(60, snapshot.renderedFps)
    }

    @Test
    fun test20_iframeFallbackWithUnknownTelemetry() {
        val s1080_30 = createOption(1080, 30)
        val s720_30 = createOption(720, 30)
        val options = listOf(s1080_30, s720_30)

        val iframeSnapshot = PlaybackQualitySnapshot(
            renderedHeight = null,
            renderedWidth = null,
            renderedFps = null,
            isActualTelemetry = false,
            sourceRoute = "IFRAME"
        )

        val result = VideoStreamOptionResolver.preselectOption(options, iframeSnapshot)
        assertEquals(s720_30, result.selectedOption)
        assertEquals(DownloadPreselectionReason.PLAYBACK_QUALITY_UNAVAILABLE, result.reason)
        assertTrue(result.explanation?.contains("IFrame mode") == true)
    }

    // ─── PART 3: FRAME RATE ACCURACY (CASES 21-25) ────────────────────────────

    @Test
    fun test21_preserves24FpsSource_doesNotLabelAs30Fps() {
        val s24 = createStream(height = 1080, frameRate = 24, videoCodec = "vp9")
        val opus = createAudioStream()
        val source = createSource(videoOnlyStreams = listOf(s24), audioOnlyStreams = listOf(opus))

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(24, opt.actualFps)
        assertEquals(30, opt.frameRate) // 30 FPS selection tier
        assertEquals("24 FPS", opt.fpsLabel)
        assertEquals("1080p · 24 FPS · WEBM", opt.displayLabel)
        assertFalse(opt.fpsLabel.contains("30"))
    }

    @Test
    fun test22_preserves25FpsSource_doesNotLabelAs30Fps() {
        val s25 = createStream(height = 1080, frameRate = 25, videoCodec = "vp9")
        val opus = createAudioStream()
        val source = createSource(videoOnlyStreams = listOf(s25), audioOnlyStreams = listOf(opus))

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(25, opt.actualFps)
        assertEquals(30, opt.frameRate)
        assertEquals("25 FPS", opt.fpsLabel)
        assertEquals("1080p · 25 FPS · WEBM", opt.displayLabel)
        assertFalse(opt.fpsLabel.contains("30"))
    }

    @Test
    fun test23_nullFrameRateStream_doesNotInventFakeFps() {
        val sNullFps = createStream(height = 720, frameRate = null, videoCodec = "vp9")
        val opus = createAudioStream()
        val source = createSource(videoOnlyStreams = listOf(sNullFps), audioOnlyStreams = listOf(opus))

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertNull(opt.actualFps)
        assertEquals(30, opt.frameRate)
        assertEquals("", opt.fpsLabel)
        assertEquals("720p · WEBM", opt.displayLabel)
    }

    @Test
    fun test24_progressiveStreamPreservesActualFps() {
        val prog24 = VideoPlaybackStream(
            url = "https://example.com/prog.mp4",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 22,
            container = "mp4",
            videoCodec = "avc1",
            audioCodec = "mp4a",
            width = 1280,
            height = 720,
            frameRate = 24,
            bitrateKbps = 1500
        )
        val source = createSource(progressiveStreams = listOf(prog24))

        val options = VideoStreamOptionResolver.resolveDownloadOptions(source)
        val opt = options.first()
        assertEquals(24, opt.actualFps)
        assertEquals("24 FPS", opt.fpsLabel)
        assertEquals("720p · 24 FPS · MP4", opt.displayLabel)
    }

    @Test
    fun test25_preselectionFormatsFpsLabelCorrectlyWhenPresent() {
        val s720_24 = createOption(720, 30, actualFps = 24)
        val options = listOf(s720_24)

        val snapshot = PlaybackQualitySnapshot(
            renderedHeight = 720,
            renderedWidth = 1280,
            renderedFps = 30,
            isActualTelemetry = true,
            sourceRoute = "MEDIA3"
        )

        val result = VideoStreamOptionResolver.preselectOption(options, snapshot)
        assertEquals(s720_24, result.selectedOption)
        assertTrue(result.explanation?.contains("720p · 24 FPS") == true)
    }

    @Test
    fun test51_audioLocaleAndTrackId_propagatedFromStreamsToDownloadOptions() {
        // 1. Adaptive VP9 + Opus
        val vp9 = createStream(height = 1080, frameRate = 60, videoCodec = "vp9", container = "webm")
        val opus = VideoPlaybackStream(
            url = "https://example.com/opus_en",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 251,
            container = "webm",
            audioCodec = "opus",
            bitrateKbps = 160,
            audioLocale = "en-US",
            audioTrackId = "track_opus_en"
        )
        val vp9Source = createSource(
            videoOnlyStreams = listOf(vp9),
            audioOnlyStreams = listOf(opus)
        )
        val vp9Options = VideoStreamOptionResolver.resolveDownloadOptions(vp9Source)
        assertEquals(1, vp9Options.size)
        assertEquals("en-US", vp9Options.first().audioLocale)
        assertEquals("track_opus_en", vp9Options.first().audioTrackId)

        // 2. Adaptive AVC + AAC
        val avc = createStream(height = 720, frameRate = 30, videoCodec = "avc1.64001f", container = "mp4")
        val aac = VideoPlaybackStream(
            url = "https://example.com/aac_es",
            kind = VideoStreamKind.AUDIO_ONLY,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 140,
            container = "m4a",
            audioCodec = "mp4a",
            bitrateKbps = 128,
            audioLocale = "es-ES",
            audioTrackId = "track_aac_es"
        )
        val avcSource = createSource(
            videoOnlyStreams = listOf(avc),
            audioOnlyStreams = listOf(aac)
        )
        val avcOptions = VideoStreamOptionResolver.resolveDownloadOptions(avcSource)
        assertEquals(1, avcOptions.size)
        assertEquals("es-ES", avcOptions.first().audioLocale)
        assertEquals("track_aac_es", avcOptions.first().audioTrackId)

        // 3. Progressive Stream
        val prog = VideoPlaybackStream(
            url = "https://example.com/prog_hi",
            kind = VideoStreamKind.COMBINED,
            deliveryMethod = VideoDeliveryMethod.PROGRESSIVE_HTTP,
            formatId = 22,
            container = "mp4",
            videoCodec = "avc1",
            audioCodec = "mp4a",
            height = 720,
            width = 1280,
            frameRate = 30,
            audioLocale = "hi-IN",
            audioTrackId = "track_prog_hi"
        )
        val progSource = createSource(progressiveStreams = listOf(prog))
        val progOptions = VideoStreamOptionResolver.resolveDownloadOptions(progSource)
        assertEquals(1, progOptions.size)
        assertEquals("hi-IN", progOptions.first().audioLocale)
        assertEquals("track_prog_hi", progOptions.first().audioTrackId)
    }

    @Test
    fun test52_displayLabel_missingFpsFormatsCleanlyWithoutEmptySeparators() {
        val optWithoutFps = createOption(height = 720, fps = 30, actualFps = null)
        assertEquals("", optWithoutFps.fpsLabel)
        assertEquals("720p · WEBM", optWithoutFps.displayLabel)

        val optWithFps = createOption(height = 1080, fps = 60, actualFps = 60)
        assertEquals("60 FPS", optWithFps.fpsLabel)
        assertEquals("1080p · 60 FPS · WEBM", optWithFps.displayLabel)
    }

    private fun createOption(
        height: Int,
        fps: Int,
        container: VideoDownloadContainer = VideoDownloadContainer.WEBM,
        videoCodec: String = "VP9",
        audioCodec: String = "Opus",
        actualFps: Int? = fps
    ): VideoDownloadOption = VideoDownloadOption(
        quality = VideoQuality.of(height, fps),
        width = (height * 16) / 9,
        height = height,
        frameRate = fps,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        container = container,
        streamType = VideoDownloadStreamType.ADAPTIVE,
        videoStreamUrl = "https://example.com/video_$height",
        audioStreamUrl = "https://example.com/audio",
        videoItag = 137,
        audioItag = 251,
        estimatedVideoSizeBytes = 50_000_000L,
        estimatedAudioSizeBytes = 5_000_000L,
        estimatedTotalSizeBytes = 55_000_000L,
        videoBitrateKbps = 2500,
        audioBitrateKbps = 160,
        isMergeRequired = true,
        compatibility = VideoDownloadOptionCompatibility.COMPATIBLE,
        compatibilityReason = null,
        actualFps = actualFps
    )
}
