package com.example.sonara.data.remote.extractor

import com.example.sonara.domain.model.video.ExternalHighResStreamDescriptor
import com.example.sonara.domain.model.video.HighResAudioRepresentation
import com.example.sonara.domain.model.video.HighResByteRange
import com.example.sonara.domain.model.video.HighResVideoRepresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Base64

class ExternalHighResDashIntegrationTest {

    private fun sampleVideoOnlyStream(
        itag: Int = 137,
        url: String = "https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?itag=137",
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 30,
        codec: String = "avc1.640028",
        bitrate: Int = 4_500_000
    ): VideoStream {
        val itagItem = ItagItem(itag, ItagItem.ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "${height}p", fps).apply {
            setWidth(width)
            setHeight(height)
            setCodec(codec)
            setBitrate(bitrate)
            setInitStart(0)
            setInitEnd(840)
            setIndexStart(841)
            setIndexEnd(2150)
        }
        return VideoStream.Builder()
            .setId(itag.toString())
            .setContent(url, true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setDeliveryMethod(DeliveryMethod.DASH)
            .setIsVideoOnly(true)
            .setResolution("${height}p")
            .setItagItem(itagItem)
            .build()
    }

    private fun sampleAudioStream(
        itag: Int = 140,
        url: String = "https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?itag=140",
        codec: String = "mp4a.40.2",
        bitrate: Int = 128_000
    ): AudioStream {
        val itagItem = ItagItem(itag, ItagItem.ItagType.AUDIO, MediaFormat.M4A, bitrate).apply {
            setCodec(codec)
            setBitrate(bitrate)
            setInitStart(0)
            setInitEnd(620)
            setIndexStart(621)
            setIndexEnd(1840)
        }
        return AudioStream.Builder()
            .setId(itag.toString())
            .setContent(url, true)
            .setMediaFormat(MediaFormat.M4A)
            .setDeliveryMethod(DeliveryMethod.DASH)
            .setAverageBitrate(bitrate)
            .setItagItem(itagItem)
            .build()
    }

    private fun sampleValidDescriptor(
        sessionId: String = "sess_secure_xyz789",
        expiresAt: Long = (System.currentTimeMillis() / 1000L) + 7200L
    ): ExternalHighResStreamDescriptor {
        return ExternalHighResStreamDescriptor(
            sessionId = sessionId,
            expiresAt = expiresAt,
            video = HighResVideoRepresentation(
                itag = 571,
                width = 7680,
                height = 4320,
                fps = 60,
                codec = "av01.0.12M.08",
                mimeType = "video/mp4",
                bitrate = 35_000_000L,
                duration = 180.0,
                initRange = HighResByteRange(0, 842),
                indexRange = HighResByteRange(843, 2154),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=$sessionId&track=video"
            ),
            audio = HighResAudioRepresentation(
                itag = 140,
                codec = "mp4a.40.2",
                mimeType = "audio/mp4",
                bitrate = 128_000L,
                duration = 180.0,
                initRange = HighResByteRange(0, 624),
                indexRange = HighResByteRange(625, 1850),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=$sessionId&track=audio"
            )
        )
    }

    private fun decodeMpdDataUri(uri: String): String {
        val prefix = "data:application/dash+xml;charset=utf-8;base64,"
        assertTrue("Expected data URI prefix", uri.startsWith(prefix))
        val base64 = uri.substring(prefix.length)
        return String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
    }

    @Test
    fun `generateDynamicDashMpd injects genuine 8K representation and matching audio into MPD`() {
        val videos = listOf(sampleVideoOnlyStream(itag = 137, height = 1080))
        val audios = listOf(sampleAudioStream(itag = 140))
        val descriptor = sampleValidDescriptor()

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L,
            externalHighRes = descriptor
        )
        assertNotNull(mpdUri)
        val xml = decodeMpdDataUri(mpdUri!!)

        // 1. Verify external 8K video representation is appended
        assertTrue("Must contain representation id 571", xml.contains("id=\"571\""))
        assertTrue("Must contain width 7680", xml.contains("width=\"7680\""))
        assertTrue("Must contain height 4320", xml.contains("height=\"4320\""))
        assertTrue("Must contain frameRate 60", xml.contains("frameRate=\"60\""))
        assertTrue("Must contain AV1 codec", xml.contains("codecs=\"av01.0.12M.08\""))
        assertTrue("Must contain bandwidth 35000000", xml.contains("bandwidth=\"35000000\""))

        // 2. Verify proxy BaseURL is properly XML-escaped
        assertTrue(
            "Video BaseURL must contain escaped query string (&amp;track=video)",
            xml.contains("<BaseURL>http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_secure_xyz789&amp;track=video</BaseURL>")
        )
        assertTrue(
            "Audio BaseURL must contain escaped query string (&amp;track=audio)",
            xml.contains("<BaseURL>http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_secure_xyz789&amp;track=audio</BaseURL>")
        )

        // 3. Verify byte ranges
        assertTrue("Video indexRange must match", xml.contains("indexRange=\"843-2154\""))
        assertTrue("Video initRange must match", xml.contains("range=\"0-842\""))
        assertTrue("Audio indexRange must match", xml.contains("indexRange=\"625-1850\""))
        assertTrue("Audio initRange must match", xml.contains("range=\"0-624\""))

        // 4. Verify existing ordinary NewPipe representations are preserved
        assertTrue("Existing 1080p stream must be preserved", xml.contains("id=\"137\""))
        assertTrue("Existing 1080p height must be preserved", xml.contains("height=\"1080\""))
        assertTrue("Existing NewPipe audio stream must be preserved", xml.contains("id=\"140\""))
    }

    @Test
    fun `generateDynamicDashMpd ignores expired or null descriptor and yields ordinary manifest`() {
        val videos = listOf(sampleVideoOnlyStream(itag = 137, height = 1080))
        val audios = listOf(sampleAudioStream(itag = 140))

        // Null descriptor
        val normalUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L,
            externalHighRes = null
        )
        assertNotNull(normalUri)
        val normalXml = decodeMpdDataUri(normalUri!!)
        assertFalse("Normal manifest must not contain 571", normalXml.contains("id=\"571\""))
        assertFalse("Normal manifest must not contain 4320", normalXml.contains("4320"))

        // Expired descriptor
        val expiredDescriptor = sampleValidDescriptor(
            expiresAt = (System.currentTimeMillis() / 1000L) - 60L // Expired 60s ago
        )
        val expiredMpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L,
            externalHighRes = expiredDescriptor
        )
        assertNotNull(expiredMpdUri)
        val expiredXml = decodeMpdDataUri(expiredMpdUri!!)
        assertFalse("Expired descriptor must be ignored", expiredXml.contains("id=\"571\""))
    }

    @Test
    fun `isExternalDescriptorValid rejects raw googlevideo direct URLs`() {
        val descriptor = sampleValidDescriptor()
        assertTrue(NewPipeExtractionMapper.isExternalDescriptorValid(descriptor))

        val invalidVideoUrl = descriptor.copy(
            video = descriptor.video.copy(
                streamProxyUrl = "https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?expire=123"
            )
        )
        assertFalse(
            "Direct googlevideo URLs must be rejected from external descriptor",
            NewPipeExtractionMapper.isExternalDescriptorValid(invalidVideoUrl)
        )
    }

    @Test
    fun `injectExternalIntoDashMpd seamlessly updates existing data URI manifest`() {
        val videos = listOf(sampleVideoOnlyStream(itag = 137, height = 1080))
        val audios = listOf(sampleAudioStream(itag = 140))

        // Initial ordinary DASH MPD
        val baseUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L,
            externalHighRes = null
        )!!

        val baseXml = decodeMpdDataUri(baseUri)
        assertFalse(baseXml.contains("id=\"571\""))

        // Inject external 8K representation
        val descriptor = sampleValidDescriptor()
        val injectedUri = NewPipeExtractionMapper.injectExternalIntoDashMpd(baseUri, descriptor)
        assertNotNull(injectedUri)

        val injectedXml = decodeMpdDataUri(injectedUri!!)
        assertTrue(injectedXml.contains("id=\"571\""))
        assertTrue(injectedXml.contains("width=\"7680\""))
        assertTrue(injectedXml.contains("height=\"4320\""))
        assertTrue(injectedXml.contains("id=\"137\"")) // Preserves 1080p

        // D1: Verify external audio ID is disambiguated and does not collide with NewPipe 140
        assertTrue("Injected external audio must receive unique disambiguated ID (140_ext)", injectedXml.contains("id=\"140_ext\""))
        assertTrue("Existing NewPipe audio (140) must remain intact", injectedXml.contains("id=\"140\""))

        // Idempotency check: injecting again on the updated URI must not duplicate representations
        val twiceUri = NewPipeExtractionMapper.injectExternalIntoDashMpd(injectedUri, descriptor)
        assertEquals(injectedUri, twiceUri)
    }

    @Test
    fun `injectExternalIntoDashMpd creates dedicated AdaptationSet when only WebM audio exists`() {
        val videos = listOf(sampleVideoOnlyStream(itag = 137, height = 1080))
        val webmItagItem = ItagItem(251, ItagItem.ItagType.AUDIO, MediaFormat.WEBMA, 160_000).apply {
            setCodec("opus")
            setBitrate(160_000)
            setInitStart(0)
            setInitEnd(500)
            setIndexStart(501)
            setIndexEnd(1200)
        }
        val webmAudio = AudioStream.Builder()
            .setId("251")
            .setContent("https://rr1---sn-4g5ednss.googlevideo.com/videoplayback?itag=251", true)
            .setMediaFormat(MediaFormat.WEBMA)
            .setDeliveryMethod(DeliveryMethod.DASH)
            .setAverageBitrate(160_000)
            .setItagItem(webmItagItem)
            .build()

        val baseUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = listOf(webmAudio),
            durationSeconds = 180L,
            externalHighRes = null
        )!!

        val baseXml = decodeMpdDataUri(baseUri)
        assertTrue(baseXml.contains("audio/webm"))
        assertFalse(baseXml.contains("audio/mp4"))

        // Inject external 8K representation (with audio/mp4)
        val descriptor = sampleValidDescriptor()
        val injectedUri = NewPipeExtractionMapper.injectExternalIntoDashMpd(baseUri, descriptor)
        assertNotNull(injectedUri)

        val injectedXml = decodeMpdDataUri(injectedUri!!)
        // D2: Must contain both audio/webm and a separate audio/mp4 AdaptationSet
        assertTrue("Must preserve audio/webm AdaptationSet", injectedXml.contains("mimeType=\"audio/webm\""))
        assertTrue("Must create dedicated audio/mp4 AdaptationSet", injectedXml.contains("mimeType=\"audio/mp4\""))
        assertTrue("Must contain external audio in the audio/mp4 set", injectedXml.contains("id=\"140_ext\""))
    }
}
