package com.example.sonara.data.remote.extractor
import com.example.sonara.domain.model.video.ExternalHighResStreamDescriptor
import com.example.sonara.domain.model.video.HighResAudioRepresentation
import com.example.sonara.domain.model.video.HighResByteRange
import com.example.sonara.domain.model.video.HighResVideoRepresentation
import java.util.Base64

import com.example.sonara.domain.model.video.VideoDeliveryMethod
import com.example.sonara.domain.model.video.VideoStreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

class NewPipeExtractionMapperTest {
    @Test
    fun `valid NewPipe metadata maps without retaining stream urls`() {
        val result = NewPipeExtractionMapper.map(extractionInfo(), "dQw4w9WgXcQ")

        assertTrue(result.isSuccess)
        val mapped = result.getOrThrow()
        assertEquals("Synthetic title", mapped.title)
        assertEquals("Synthetic uploader", mapped.uploader)
        assertEquals(213_000L, mapped.durationMs)
        assertEquals(2, mapped.streams.size)
        assertEquals("mp4", mapped.streams[0].container)
        assertEquals("m4a", mapped.streams[1].container)
        assertFalse(mapped.streams.any { it.toString().contains("example.invalid/video") })
    }

    @Test
    fun `missing or unusable stream metadata fails closed`() {
        val result = NewPipeExtractionMapper.map(
            extractionInfo(includeStreams = false),
            "dQw4w9WgXcQ"
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `playback source selects a usable progressive URL without changing metadata contract`() {
        val result = NewPipeExtractionMapper.mapPlaybackSource(extractionInfo(), "dQw4w9WgXcQ")

        assertTrue(result.isSuccess)
        val source = result.getOrThrow()
        assertEquals("https://example.invalid/video", source.streamUrl)
        assertEquals(VideoStreamKind.COMBINED, source.kind)
        assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, source.deliveryMethod)
    }

    @Test
    fun `playback source prefers HLS and retains progressive fallback`() {
        val info = extractionInfo().apply {
            videoStreams = listOf(
                VideoStream.Builder()
                    .setId("hls")
                    .setContent("https://example.invalid/video.m3u8", true)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setDeliveryMethod(DeliveryMethod.HLS)
                    .setIsVideoOnly(false)
                    .setResolution("720p")
                    .build(),
                VideoStream.Builder()
                    .setId("progressive")
                    .setContent("https://example.invalid/video.mp4", true)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                    .setIsVideoOnly(false)
                    .setResolution("360p")
                    .build()
            )
        }

        val source = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()

        assertEquals(VideoDeliveryMethod.HLS, source.deliveryMethod)
        assertEquals("https://example.invalid/video.m3u8", source.streamUrl)
        assertEquals("https://example.invalid/video.mp4", source.progressiveFallbackUrl)
    }

    @Test
    fun `playback source prefers stream info hlsUrl as primary candidate and retains progressive fallback`() {
        val info = extractionInfo().apply {
            hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/file/index.m3u8"
            // info.videoStreams has default progressive 360p from extractionInfo()
        }

        val source = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()

        assertEquals(VideoDeliveryMethod.HLS, source.deliveryMethod)
        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_variant/file/index.m3u8", source.streamUrl)
        assertEquals("https://example.invalid/video", source.progressiveFallbackUrl)
    }

    @Test
    fun `playback source falls back to progressive when stream info hlsUrl is empty`() {
        val info = extractionInfo().apply {
            hlsUrl = ""
        }

        val source = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()

        assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, source.deliveryMethod)
        assertEquals("https://example.invalid/video", source.streamUrl)
        assertNull(source.progressiveFallbackUrl)
    }

    @Test
    fun `playback source prefers DASH manifest as primary candidate and retains progressive fallback`() {
        val info = extractionInfo().apply {
            dashMpdUrl = "https://manifest.googlevideo.com/api/manifest/dash/index.mpd"
            hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/file/index.m3u8"
            // info.videoStreams has default progressive 360p from extractionInfo()
        }

        val extractionResult = NewPipeExtractionMapper.map(info, "dQw4w9WgXcQ").getOrThrow()
        assertEquals("https://manifest.googlevideo.com/api/manifest/dash/index.mpd", extractionResult.dashUrl)
        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_variant/file/index.m3u8", extractionResult.hlsUrl)

        val playbackSource = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()
        assertEquals(VideoDeliveryMethod.DASH, playbackSource.deliveryMethod)
        assertEquals("https://manifest.googlevideo.com/api/manifest/dash/index.mpd", playbackSource.streamUrl)
        assertEquals("https://manifest.googlevideo.com/api/manifest/dash/index.mpd", playbackSource.dashUrl)
        assertEquals("https://manifest.googlevideo.com/api/manifest/hls_variant/file/index.m3u8", playbackSource.hlsUrl)
        assertEquals("https://example.invalid/video", playbackSource.progressiveFallbackUrl)
    }

    @Test
    fun `video-only adaptive streams preserve 1440p, 2160p, and 4320p metadata and direct URLs`() {
        val info = extractionInfo().apply {
            videoOnlyStreams = listOf(
                videoOnlyStream(272, "https://example.invalid/video_8k.webm", "4320p", 7680, 4320, 60, "vp9", 45_000_000, MediaFormat.WEBM),
                videoOnlyStream(313, "https://example.invalid/video_4k.webm", "2160p", 3840, 2160, 60, "vp9", 20_000_000, MediaFormat.WEBM),
                videoOnlyStream(271, "https://example.invalid/video_2k.webm", "1440p", 2560, 1440, 60, "vp9", 10_000_000, MediaFormat.WEBM),
                videoOnlyStream(137, "https://example.invalid/video_1080p.mp4", "1080p", 1920, 1080, 30, "avc1", 4_000_000, MediaFormat.MPEG_4)
            )
        }

        val metadataResult = NewPipeExtractionMapper.map(info, "dQw4w9WgXcQ").getOrThrow()
        val videoOnlyDescriptors = metadataResult.streams.filter { it.kind == VideoStreamKind.VIDEO_ONLY }
        assertEquals(4, videoOnlyDescriptors.size)
        // Verify URL is NOT retained in metadata descriptors
        assertFalse(videoOnlyDescriptors.any { it.toString().contains("example.invalid") })

        val descriptor8k = videoOnlyDescriptors.first { it.height == 4320 }
        assertEquals("4320p", descriptor8k.resolution)
        assertEquals(7680, descriptor8k.width)
        assertEquals(60, descriptor8k.frameRate)
        assertEquals("vp9", descriptor8k.videoCodec)
        assertEquals("webm", descriptor8k.container)

        val playbackSource = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()
        val playbackVideoStreams = playbackSource.videoOnlyStreams
        assertEquals(4, playbackVideoStreams.size)
        // Verify ordering: descending by height
        assertEquals(listOf(4320, 2160, 1440, 1080), playbackVideoStreams.map { it.height })

        val stream8k = playbackVideoStreams.first { it.height == 4320 }
        assertEquals("https://example.invalid/video_8k.webm", stream8k.url)
        assertEquals(VideoStreamKind.VIDEO_ONLY, stream8k.kind)
        assertEquals(VideoDeliveryMethod.DASH, stream8k.deliveryMethod)
        assertEquals("webm", stream8k.container)
        assertEquals(45_000, stream8k.bitrateKbps)
        assertEquals(60, stream8k.frameRate)

        val stream4k = playbackVideoStreams.first { it.height == 2160 }
        assertEquals("https://example.invalid/video_4k.webm", stream4k.url)
        assertEquals(20_000, stream4k.bitrateKbps)

        val stream2k = playbackVideoStreams.first { it.height == 1440 }
        assertEquals("https://example.invalid/video_2k.webm", stream2k.url)
        assertEquals(10_000, stream2k.bitrateKbps)
    }

    @Test
    fun `audio-only stream metadata and URLs are preserved and sorted by bitrate`() {
        val info = extractionInfo().apply {
            audioStreams = listOf(
                audioStream(140, "https://example.invalid/audio_128k.m4a", "mp4a.40.2", 128_000, MediaFormat.M4A),
                audioStream(251, "https://example.invalid/audio_160k.webm", "opus", 160_000, MediaFormat.WEBM)
            )
        }

        val metadataResult = NewPipeExtractionMapper.map(info, "dQw4w9WgXcQ").getOrThrow()
        val audioDescriptors = metadataResult.streams.filter { it.kind == VideoStreamKind.AUDIO_ONLY }
        assertEquals(2, audioDescriptors.size)
        assertFalse(audioDescriptors.any { it.toString().contains("example.invalid") })

        val playbackSource = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()
        val audioStreams = playbackSource.audioOnlyStreams
        assertEquals(2, audioStreams.size)
        // Ordered descending by bitrate
        assertEquals("https://example.invalid/audio_160k.webm", audioStreams[0].url)
        assertEquals("opus", audioStreams[0].audioCodec)
        assertEquals(160, audioStreams[0].bitrateKbps)
        assertEquals("https://example.invalid/audio_128k.m4a", audioStreams[1].url)
        assertEquals("mp4a.40.2", audioStreams[1].audioCodec)
        assertEquals(128, audioStreams[1].bitrateKbps)
    }

    @Test
    fun `adaptive streams without a combined progressive candidate expose metadata but fail playback candidate mapping`() {
        val info = extractionInfo(includeStreams = false).apply {
            videoOnlyStreams = listOf(
                videoOnlyStream(272, "https://example.invalid/video_8k.webm", "4320p", 7680, 4320, 60, "vp9", 45_000_000, MediaFormat.WEBM)
            )
            audioStreams = listOf(
                audioStream(251, "https://example.invalid/audio_160k.webm", "opus", 160_000, MediaFormat.WEBM)
            )
        }

        // Metadata extraction succeeds and exposes descriptors
        val metadataResult = NewPipeExtractionMapper.map(info, "dQw4w9WgXcQ")
        assertTrue(metadataResult.isSuccess)
        val descriptors = metadataResult.getOrThrow().streams
        assertEquals(2, descriptors.size)
        assertTrue(descriptors.any { it.kind == VideoStreamKind.VIDEO_ONLY && it.height == 4320 })
        assertTrue(descriptors.any { it.kind == VideoStreamKind.AUDIO_ONLY })

        // Playback candidate mapping fails closed because no playable combined single stream is available
        val playbackResult = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ")
        assertTrue(playbackResult.isFailure)
        assertEquals("Extractor returned no playable stream URL", playbackResult.exceptionOrNull()?.message)
    }

    @Test
    fun `invalid or malformed manifest and stream URLs are rejected defensively`() {
        val info = extractionInfo().apply {
            dashMpdUrl = "ftp://malformed.example/dash.mpd"
            hlsUrl = "not-a-valid-url"
        }

        val playbackSource = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()
        // Falls back to progressive because DASH and HLS URLs have invalid schemes
        assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, playbackSource.deliveryMethod)
        assertEquals("https://example.invalid/video", playbackSource.streamUrl)
        assertNull(playbackSource.dashUrl)
        assertNull(playbackSource.hlsUrl)
    }

    @Test
    fun `blank or whitespace-only DASH and HLS URLs are treated as absent`() {
        val info = extractionInfo().apply {
            dashMpdUrl = "   "
            hlsUrl = " \t\n "
        }

        val metadata = NewPipeExtractionMapper.map(info, "dQw4w9WgXcQ").getOrThrow()
        assertNull(metadata.dashUrl)
        assertNull(metadata.hlsUrl)

        val source = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()
        assertEquals(VideoDeliveryMethod.PROGRESSIVE_HTTP, source.deliveryMethod)
        assertNull(source.dashUrl)
        assertNull(source.hlsUrl)
    }

    @Test
    fun `unknown content availability on eligible stream succeeds`() {
        val info = extractionInfo().apply {
            contentAvailability = ContentAvailability.UNKNOWN
            ageLimit = 0
            privacy = org.schabi.newpipe.extractor.stream.StreamExtractor.Privacy.PUBLIC
        }
        val result = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ")

        assertTrue(result.isSuccess)
        assertEquals("https://example.invalid/video", result.getOrThrow().streamUrl)
    }

    @Test
    fun `explicitly restricted content availability fails closed`() {
        for (restricted in listOf(
            ContentAvailability.MEMBERSHIP,
            ContentAvailability.PAID,
            ContentAvailability.UPCOMING
        )) {
            val info = extractionInfo().apply {
                contentAvailability = restricted
            }
            val result = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ")
            assertTrue("Expected failure for $restricted", result.isFailure)
            assertEquals("Extracted content is not publicly available", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `unknown availability with age restriction fails closed`() {
        val info = extractionInfo().apply {
            contentAvailability = ContentAvailability.UNKNOWN
            ageLimit = 18
        }
        val result = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ")

        assertTrue(result.isFailure)
        assertEquals("Extracted content is not publicly available", result.exceptionOrNull()?.message)
    }

    @Test
    fun `unknown availability with private stream privacy fails closed`() {
        val info = extractionInfo().apply {
            contentAvailability = ContentAvailability.UNKNOWN
            privacy = org.schabi.newpipe.extractor.stream.StreamExtractor.Privacy.PRIVATE
        }
        val result = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ")

        assertTrue(result.isFailure)
        assertEquals("Extracted content is not publicly available", result.exceptionOrNull()?.message)
    }

    @Test
    fun `unknown availability with no streams fails closed`() {
        val info = extractionInfo(includeStreams = false).apply {
            contentAvailability = ContentAvailability.UNKNOWN
        }
        val result = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ")

        assertTrue(result.isFailure)
    }

    @Test
    fun `view count from stream info is mapped to extraction result and playback source`() {
        val info = extractionInfo().apply {
            viewCount = 1_200_000L
        }
        val extractionResult = NewPipeExtractionMapper.map(info, "dQw4w9WgXcQ").getOrThrow()
        assertEquals(1_200_000L, extractionResult.viewCount)

        val playbackSource = NewPipeExtractionMapper.mapPlaybackSource(info, "dQw4w9WgXcQ").getOrThrow()
        assertEquals(1_200_000L, playbackSource.viewCount)
    }

    @Test
    fun `negative or sentinel view count is mapped to null`() {
        val infoSentinel = extractionInfo().apply {
            viewCount = -1L
        }
        assertNull(NewPipeExtractionMapper.map(infoSentinel, "dQw4w9WgXcQ").getOrThrow().viewCount)
        assertNull(NewPipeExtractionMapper.mapPlaybackSource(infoSentinel, "dQw4w9WgXcQ").getOrThrow().viewCount)

        val infoNegative = extractionInfo().apply {
            viewCount = -100L
        }
        assertNull(NewPipeExtractionMapper.map(infoNegative, "dQw4w9WgXcQ").getOrThrow().viewCount)
        assertNull(NewPipeExtractionMapper.mapPlaybackSource(infoNegative, "dQw4w9WgXcQ").getOrThrow().viewCount)
    }

    @Test
    fun `cleanMimeType strips codecs parameter and normalizes whitespace`() {
        assertEquals("video/mp4", NewPipeExtractionMapper.cleanMimeType("video/mp4; codecs=\"av01.0.17M.08\""))
        assertEquals("audio/mp4", NewPipeExtractionMapper.cleanMimeType("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("video/webm", NewPipeExtractionMapper.cleanMimeType(" video/webm ; codecs=\"vp9\" "))
        assertEquals("video/mp4", NewPipeExtractionMapper.cleanMimeType("video/mp4"))
    }

    @Test
    fun `injectExternalIntoDashMpd strictly isolates MP4 representation from WebM AdaptationSet`() {
        val webmOnlyMpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" minBufferTime="PT1.5S" type="static">
              <Period id="0" duration="PT100S">
                <AdaptationSet id="0" mimeType="video/webm" subsegmentAlignment="true">
                  <Representation id="248" width="1920" height="1080" codecs="vp9" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val prefix = "data:application/dash+xml;charset=utf-8;base64,"
        val dataUri = prefix + Base64.getEncoder().encodeToString(webmOnlyMpd.toByteArray(Charsets.UTF_8))
        val descriptor = ExternalHighResStreamDescriptor(
            sessionId = "sess_123",
            expiresAt = (System.currentTimeMillis() / 1000L) + 7200L,
            video = HighResVideoRepresentation(
                itag = 571,
                width = 7680,
                height = 4320,
                fps = 60,
                codec = "av01.0.17M.08",
                mimeType = "video/mp4",
                bitrate = 40_000_000L,
                duration = 180.0,
                initRange = HighResByteRange(0, 740),
                indexRange = HighResByteRange(741, 1200),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_123&track=video"
            ),
            audio = HighResAudioRepresentation(
                itag = 140,
                codec = "mp4a.40.2",
                mimeType = "audio/mp4",
                bitrate = 128_000L,
                duration = 180.0,
                initRange = HighResByteRange(0, 600),
                indexRange = HighResByteRange(601, 1000),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_123&track=audio"
            )
        )

        val injectedUri = NewPipeExtractionMapper.injectExternalIntoDashMpd(dataUri, descriptor)
        assertTrue(injectedUri != null)
        val injected = String(Base64.getDecoder().decode(injectedUri!!.substring(prefix.length)), Charsets.UTF_8)

        // Ensure the WebM AdaptationSet (id="0") DOES NOT contain the external 8K MP4 representation
        val webmSetRegex = Regex("""<AdaptationSet[^>]*mimeType="video/webm"[^>]*>([\s\S]*?)</AdaptationSet>""")
        val webmMatch = webmSetRegex.find(injected)
        assertTrue(webmMatch != null)
        assertFalse(webmMatch!!.groupValues[1].contains("7680"))
        assertFalse(webmMatch.groupValues[1].contains("av01"))

        // Ensure a dedicated video/mp4 AdaptationSet was created before </Period>
        val mp4SetRegex = Regex("""<AdaptationSet[^>]*mimeType="video/mp4"[^>]*>([\s\S]*?)</AdaptationSet>""")
        val mp4Match = mp4SetRegex.find(injected)
        assertTrue(mp4Match != null)
        assertTrue(mp4Match!!.groupValues[1].contains("height=\"4320\""))
        assertTrue(mp4Match!!.groupValues[1].contains("width=\"7680\""))
    }

    @Test
    fun `injectExternalIntoDashMpd appends representation ID suffix on collision`() {
        val mpdWithExisting140 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" minBufferTime="PT1.5S" type="static">
              <Period id="0" duration="PT100S">
                <AdaptationSet id="0" contentType="audio" mimeType="audio/mp4" subsegmentAlignment="true">
                  <Representation id="140" codecs="mp4a.40.2" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val prefix = "data:application/dash+xml;charset=utf-8;base64,"
        val dataUri = prefix + Base64.getEncoder().encodeToString(mpdWithExisting140.toByteArray(Charsets.UTF_8))
        val descriptor = ExternalHighResStreamDescriptor(
            sessionId = "sess_123",
            expiresAt = (System.currentTimeMillis() / 1000L) + 7200L,
            video = HighResVideoRepresentation(
                itag = 571,
                width = 7680,
                height = 4320,
                fps = 60,
                codec = "av01.0.17M.08",
                mimeType = "video/mp4",
                bitrate = 40_000_000L,
                duration = 180.0,
                initRange = HighResByteRange(0, 740),
                indexRange = HighResByteRange(741, 1200),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_123&track=video"
            ),
            audio = HighResAudioRepresentation(
                itag = 140,
                codec = "mp4a.40.2",
                mimeType = "audio/mp4",
                bitrate = 128_000L,
                duration = 180.0,
                initRange = HighResByteRange(0, 600),
                indexRange = HighResByteRange(601, 1000),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_123&track=audio"
            )
        )

        val injectedUri = NewPipeExtractionMapper.injectExternalIntoDashMpd(dataUri, descriptor)
        assertTrue(injectedUri != null)
        val injected = String(Base64.getDecoder().decode(injectedUri!!.substring(prefix.length)), Charsets.UTF_8)
        assertTrue(injected.contains("<Representation id=\"140_ext\""))
    }


    private fun videoOnlyStream(
        itag: Int,
        url: String,
        resolution: String,
        width: Int,
        height: Int,
        fps: Int,
        codec: String,
        bitrate: Int,
        mediaFormat: MediaFormat = MediaFormat.WEBM
    ): VideoStream {
        val itagItem = ItagItem(itag, ItagItem.ItagType.VIDEO_ONLY, mediaFormat, resolution, fps).apply {
            setWidth(width)
            setHeight(height)
            setCodec(codec)
            setBitrate(bitrate)
        }
        return VideoStream.Builder()
            .setId(itag.toString())
            .setContent(url, true)
            .setMediaFormat(mediaFormat)
            .setDeliveryMethod(DeliveryMethod.DASH)
            .setIsVideoOnly(true)
            .setResolution(resolution)
            .setItagItem(itagItem)
            .build()
    }

    private fun audioStream(
        itag: Int,
        url: String,
        codec: String,
        bitrate: Int,
        mediaFormat: MediaFormat = MediaFormat.M4A
    ): AudioStream {
        val itagItem = ItagItem(itag, ItagItem.ItagType.AUDIO, mediaFormat, bitrate).apply {
            setCodec(codec)
            setBitrate(bitrate)
        }
        return AudioStream.Builder()
            .setId(itag.toString())
            .setContent(url, true)
            .setMediaFormat(mediaFormat)
            .setDeliveryMethod(DeliveryMethod.DASH)
            .setAverageBitrate(bitrate)
            .setItagItem(itagItem)
            .build()
    }
}
