package com.example.sonara.data.remote.extractor

import com.example.sonara.core.error.SonaraException
import com.example.sonara.domain.model.soundcloud.SoundcloudAudioStream
import com.example.sonara.domain.model.soundcloud.SoundcloudDeliveryMethod
import com.example.sonara.domain.model.soundcloud.SoundcloudNormalizedInput
import com.example.sonara.domain.model.soundcloud.SoundcloudSourceIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

class SoundcloudTrackExtractorTest {

    @Before
    fun setUp() {
        if (runCatching { NewPipe.getDownloader() }.getOrNull() == null) {
            NewPipe.init(NoOpDownloader())
        }
    }

    private fun fakeAudioStream(
        id: String,
        content: String,
        format: MediaFormat,
        deliveryMethod: DeliveryMethod,
        bitrate: Int
    ): AudioStream = AudioStream.Builder()
        .setId(id)
        .setContent(content, true)
        .setMediaFormat(format)
        .setDeliveryMethod(deliveryMethod)
        .setAverageBitrate(bitrate)
        .build()

    private class FakeSoundcloudExtractor(
        val fakeId: String = "1356023209",
        val fakeName: String = "Open Minds, Ep 21",
        val fakeUploader: String = "Creative Commons",
        val fakeLength: Long = 1500L,
        val fakeUrl: String = "https://soundcloud.com/wearecc/open-minds-ep-21",
        val fakeThumbnails: List<Image> = listOf(
            Image("https://example.com/art-small.jpg", 100, 100, Image.ResolutionLevel.LOW),
            Image("https://example.com/art-large.jpg", 500, 500, Image.ResolutionLevel.MEDIUM)
        ),
        val fakeAudioStreams: List<AudioStream> = listOf(
            AudioStream.Builder()
                .setId("hls_aac_160k")
                .setContent("https://cf-hls-media.sndcdn.com/playlist.m3u8?Expires=12345", true)
                .setMediaFormat(MediaFormat.M4A)
                .setDeliveryMethod(DeliveryMethod.HLS)
                .setAverageBitrate(160)
                .build()
        ),
        val onFetchError: Exception? = null
    ) : StreamExtractor(
        ServiceList.SoundCloud,
        LinkHandler(fakeUrl, fakeUrl, fakeId)
    ) {
        override fun onFetchPage(downloader: Downloader) {
            if (onFetchError != null) throw onFetchError
        }
        override fun getId(): String = fakeId
        override fun getName(): String = fakeName
        override fun getUploaderName(): String = fakeUploader
        override fun getUploaderUrl(): String = "https://soundcloud.com/wearecc"
        override fun getLength(): Long = fakeLength
        override fun getUrl(): String = fakeUrl
        override fun getThumbnails(): List<Image> = fakeThumbnails
        override fun getAudioStreams(): List<AudioStream> = fakeAudioStreams
        override fun getVideoStreams(): List<VideoStream> = emptyList()
        override fun getVideoOnlyStreams(): List<VideoStream> = emptyList()
        override fun getStreamType(): StreamType = StreamType.AUDIO_STREAM
    }

    // ─── 1. Valid Track Metadata Mapping ──────────────────────────────────────

    @Test
    fun `valid track metadata maps cleanly into domain Track model`() = runTest {
        val fake = FakeSoundcloudExtractor()
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isSuccess)
        val extraction = result.getOrThrow()
        assertEquals("Open Minds, Ep 21", extraction.track.title)
        assertEquals("Creative Commons", extraction.track.artist)
        assertEquals(1500_000L, extraction.track.durationMs)
        assertEquals("https://example.com/art-large.jpg", extraction.track.artworkUrl)
        assertEquals("https://soundcloud.com/wearecc/open-minds-ep-21", extraction.canonicalUrl)
        assertEquals("1356023209", extraction.rawTrackId)
    }

    // ─── 2. Stable Source Identity & ID Collision Prevention ──────────────────

    @Test
    fun `source identity enforces sc prefix and prevents collision with other providers`() = runTest {
        val fake = FakeSoundcloudExtractor(fakeId = "987654321")
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("sc:987654321")

        assertTrue(result.isSuccess)
        val extraction = result.getOrThrow()
        assertEquals("sc:987654321", extraction.track.id)
        assertEquals("987654321", extraction.rawTrackId)

        // Verify helper functions
        assertTrue(SoundcloudSourceIdentity.isSoundcloudTrackId("sc:987654321"))
        assertTrue(SoundcloudSourceIdentity.isSoundcloudTrackId("soundcloud:987654321"))
        assertFalse(SoundcloudSourceIdentity.isSoundcloudTrackId("dQw4w9WgXcQ")) // YouTube ID
        assertEquals("sc:123", SoundcloudSourceIdentity.toSoundcloudTrackId("123"))
        assertEquals("sc:123", SoundcloudSourceIdentity.toSoundcloudTrackId("sc:123"))
        assertEquals("123", SoundcloudSourceIdentity.extractRawSoundcloudId("sc:123"))
        assertEquals("123", SoundcloudSourceIdentity.extractRawSoundcloudId("soundcloud:123"))
    }

    // ─── 3. MP3 Progressive Stream Mapping ────────────────────────────────────

    @Test
    fun `mp3 progressive stream maps accurately with correct bitrate and media3 mime`() = runTest {
        val streams = listOf(
            fakeAudioStream(
                id = "http_mp3_128",
                content = "https://cf-media.sndcdn.com/track.128.mp3?Expires=9999",
                format = MediaFormat.MP3,
                deliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
                bitrate = 128
            )
        )
        val fake = FakeSoundcloudExtractor(fakeAudioStreams = streams)
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isSuccess)
        val stream = result.getOrThrow().selectedStream
        assertEquals("http_mp3_128", stream.id)
        assertEquals(SoundcloudDeliveryMethod.PROGRESSIVE_HTTP, stream.deliveryMethod)
        assertEquals("MP3", stream.mediaFormat)
        assertEquals(128, stream.bitrateKbps)
        assertEquals("audio/mpeg", stream.mimeType)
        assertEquals("https://cf-media.sndcdn.com/track.128.mp3?Expires=9999", stream.streamUrl)
    }

    // ─── 4. MP3 HLS Stream Mapping ────────────────────────────────────────────

    @Test
    fun `mp3 hls stream maps accurately with application x-mpegURL mime`() = runTest {
        val streams = listOf(
            fakeAudioStream(
                id = "hls_mp3_128",
                content = "https://cf-hls-media.sndcdn.com/playlist.128.m3u8?Expires=9999",
                format = MediaFormat.MP3,
                deliveryMethod = DeliveryMethod.HLS,
                bitrate = 128
            )
        )
        val fake = FakeSoundcloudExtractor(fakeAudioStreams = streams)
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isSuccess)
        val stream = result.getOrThrow().selectedStream
        assertEquals("hls_mp3_128", stream.id)
        assertEquals(SoundcloudDeliveryMethod.HLS, stream.deliveryMethod)
        assertEquals("MP3", stream.mediaFormat)
        assertEquals(128, stream.bitrateKbps)
        assertEquals("application/x-mpegURL", stream.mimeType)
    }

    // ─── 5. AAC HLS Stream Mapping ────────────────────────────────────────────

    @Test
    fun `aac hls stream maps accurately with 160 kbps bitrate`() = runTest {
        val streams = listOf(
            fakeAudioStream(
                id = "hls_aac_160k",
                content = "https://cf-hls-media.sndcdn.com/playlist.aac.m3u8?Expires=9999",
                format = MediaFormat.M4A,
                deliveryMethod = DeliveryMethod.HLS,
                bitrate = 160
            )
        )
        val fake = FakeSoundcloudExtractor(fakeAudioStreams = streams)
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isSuccess)
        val stream = result.getOrThrow().selectedStream
        assertEquals("hls_aac_160k", stream.id)
        assertEquals(SoundcloudDeliveryMethod.HLS, stream.deliveryMethod)
        assertEquals("M4A", stream.mediaFormat)
        assertEquals(160, stream.bitrateKbps)
        assertEquals("application/x-mpegURL", stream.mimeType)
    }

    // ─── 6. Opus HLS Mapping ──────────────────────────────────────────────────

    @Test
    fun `opus hls stream maps accurately with 64 kbps bitrate`() = runTest {
        val streams = listOf(
            fakeAudioStream(
                id = "hls_opus_64k",
                content = "https://cf-hls-media.sndcdn.com/playlist.opus.m3u8?Expires=9999",
                format = MediaFormat.OPUS,
                deliveryMethod = DeliveryMethod.HLS,
                bitrate = 64
            )
        )
        val fake = FakeSoundcloudExtractor(fakeAudioStreams = streams)
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isSuccess)
        val stream = result.getOrThrow().selectedStream
        assertEquals("hls_opus_64k", stream.id)
        assertEquals(SoundcloudDeliveryMethod.HLS, stream.deliveryMethod)
        assertEquals("OPUS", stream.mediaFormat)
        assertEquals(64, stream.bitrateKbps)
        assertEquals("application/x-mpegURL", stream.mimeType)
    }

    // ─── 7. Missing or Invalid Stream Metadata Discarded ───────────────────────

    @Test
    fun `audio streams with blank urls or non-positive bitrates are discarded`() = runTest {
        val streams = listOf(
            fakeAudioStream("bad_url", "", MediaFormat.MP3, DeliveryMethod.PROGRESSIVE_HTTP, 128),
            fakeAudioStream("bad_bitrate", "https://example.com/audio", MediaFormat.MP3, DeliveryMethod.PROGRESSIVE_HTTP, 0),
            fakeAudioStream("valid_stream", "https://example.com/audio", MediaFormat.MP3, DeliveryMethod.PROGRESSIVE_HTTP, 128)
        )
        val fake = FakeSoundcloudExtractor(fakeAudioStreams = streams)
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isSuccess)
        val all = result.getOrThrow().allStreams
        assertEquals(1, all.size)
        assertEquals("valid_stream", all.first().id)
    }

    // ─── 8. Unsupported Transcoding Formats Rejected ──────────────────────────

    @Test
    fun `unsupported formats and fabricated bitrates are safely rejected`() = runTest {
        val streams = listOf(
            fakeAudioStream("webm_stream", "https://example.com/webm", MediaFormat.WEBM, DeliveryMethod.PROGRESSIVE_HTTP, 128),
            fakeAudioStream("fake_aac_256", "https://example.com/aac256", MediaFormat.M4A, DeliveryMethod.HLS, 256), // Unverified 256k
            fakeAudioStream("fake_aac_96", "https://example.com/aac96", MediaFormat.M4A, DeliveryMethod.HLS, 96),   // Dropped 96k
            fakeAudioStream("legit_mp3", "https://example.com/mp3", MediaFormat.MP3, DeliveryMethod.PROGRESSIVE_HTTP, 128)
        )
        val fake = FakeSoundcloudExtractor(fakeAudioStreams = streams)
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isSuccess)
        val all = result.getOrThrow().allStreams
        assertEquals(1, all.size)
        assertEquals("legit_mp3", all.first().id)
    }

    // ─── 9. Go+ Preview Restriction Propagation ───────────────────────────────

    @Test
    fun `soundcloud go plus preview restriction propagates as ProviderUnavailableException`() = runTest {
        val fake = FakeSoundcloudExtractor(
            onFetchError = SoundCloudGoPlusContentException()
        )
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/martinsolveig/places")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Expected ProviderUnavailableException, got $error", error is SonaraException.ProviderUnavailableException)
        assertTrue(error!!.message!!.contains("SoundCloud Go+"))
    }

    // ─── 10. Geographic Restriction Propagation ───────────────────────────────

    @Test
    fun `geographic restriction propagates as ProviderUnavailableException`() = runTest {
        val fake = FakeSoundcloudExtractor(
            onFetchError = GeographicRestrictionException("This track is not available in user's country")
        )
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/jessglynne/one-touch")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is SonaraException.ProviderUnavailableException)
        assertTrue(error!!.message!!.contains("region"))
    }

    // ─── 11. Invalid URL Handling ─────────────────────────────────────────────

    @Test
    fun `invalid and non-soundcloud urls are rejected without network calls`() = runTest {
        var factoryCalled = false
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = {
                factoryCalled = true
                FakeSoundcloudExtractor()
            }
        )

        val youtubeUrl = extractor.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val emptyInput = extractor.extract("   ")
        val randomText = extractor.extract("not_a_valid_url_or_id")
        val reservedPath = extractor.extract("https://soundcloud.com/user/tracks")

        assertTrue(youtubeUrl.isFailure)
        assertTrue(youtubeUrl.exceptionOrNull() is SonaraException.ParsingException)
        assertTrue(emptyInput.isFailure)
        assertTrue(emptyInput.exceptionOrNull() is SonaraException.ParsingException)
        assertTrue(randomText.isFailure)
        assertTrue(randomText.exceptionOrNull() is SonaraException.ParsingException)
        assertTrue(reservedPath.isFailure)
        assertTrue(reservedPath.exceptionOrNull() is SonaraException.ParsingException)
        assertFalse("Extractor factory should not have been called for invalid input", factoryCalled)
    }

    // ─── 12. Empty or Unsupported Stream Lists ────────────────────────────────

    @Test
    fun `tracks with no supported audio streams return ProviderUnavailableException`() = runTest {
        val fake = FakeSoundcloudExtractor(fakeAudioStreams = emptyList())
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is SonaraException.ProviderUnavailableException)
        assertTrue(error!!.message!!.contains("No supported audio streams"))
    }

    // ─── 13. Transient Stream URLs Kept Separate From Domain Track ────────────

    @Test
    fun `signed stream urls remain transient and are not persisted to Track model`() = runTest {
        val signedUrl = "https://cf-hls-media.sndcdn.com/playlist.m3u8?Expires=1700000000&Signature=abc"
        val streams = listOf(
            fakeAudioStream("hls_aac_160k", signedUrl, MediaFormat.M4A, DeliveryMethod.HLS, 160)
        )
        val fake = FakeSoundcloudExtractor(
            fakeId = "1356023209",
            fakeUrl = "https://soundcloud.com/wearecc/open-minds-ep-21",
            fakeAudioStreams = streams
        )
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isSuccess)
        val extraction = result.getOrThrow()

        // Track model contains permanent source-neutral fields only
        assertEquals("sc:1356023209", extraction.track.id)
        assertEquals("Open Minds, Ep 21", extraction.track.title)
        assertEquals("Creative Commons", extraction.track.artist)
        assertEquals("https://soundcloud.com/wearecc/open-minds-ep-21", extraction.canonicalUrl)

        // Ephemeral signed URL is isolated in selectedStream
        assertEquals(signedUrl, extraction.selectedStream.streamUrl)
    }

    // ─── 14. Deterministic Stream Selection Hierarchy ─────────────────────────

    @Test
    fun `preferred stream selector prioritizes AAC 160k HLS then MP3 128k Prog then MP3 128k HLS then Opus 64k HLS`() {
        val aac160Hls = SoundcloudAudioStream("aac160", "url1", SoundcloudDeliveryMethod.HLS, "M4A", "application/x-mpegURL", 160)
        val mp3128Prog = SoundcloudAudioStream("mp3prog", "url2", SoundcloudDeliveryMethod.PROGRESSIVE_HTTP, "MP3", "audio/mpeg", 128)
        val mp3128Hls = SoundcloudAudioStream("mp3hls", "url3", SoundcloudDeliveryMethod.HLS, "MP3", "application/x-mpegURL", 128)
        val opus64Hls = SoundcloudAudioStream("opus64", "url4", SoundcloudDeliveryMethod.HLS, "OPUS", "application/x-mpegURL", 64)

        // All present -> AAC 160k HLS preferred
        assertEquals(aac160Hls, SoundcloudTrackExtractor.selectPreferredStream(listOf(opus64Hls, mp3128Hls, mp3128Prog, aac160Hls)))

        // AAC absent -> MP3 128k Prog preferred
        assertEquals(mp3128Prog, SoundcloudTrackExtractor.selectPreferredStream(listOf(opus64Hls, mp3128Hls, mp3128Prog)))

        // MP3 Prog absent -> MP3 128k HLS preferred
        assertEquals(mp3128Hls, SoundcloudTrackExtractor.selectPreferredStream(listOf(opus64Hls, mp3128Hls)))

        // Only Opus -> Opus 64k HLS preferred
        assertEquals(opus64Hls, SoundcloudTrackExtractor.selectPreferredStream(listOf(opus64Hls)))

        // Empty list -> null
        assertNull(SoundcloudTrackExtractor.selectPreferredStream(emptyList()))
    }

    // ─── 15. Input Normalization Variants ─────────────────────────────────────

    @Test
    fun `input normalization supports various soundcloud url forms and id prefixes`() {
        // Direct URL
        val urlResult = SoundcloudTrackExtractor.normalizeInput("https://soundcloud.com/artist/title")
        assertTrue(urlResult.isSuccess)
        assertTrue(urlResult.getOrThrow() is SoundcloudNormalizedInput.Url)
        assertEquals("https://soundcloud.com/artist/title", (urlResult.getOrThrow() as SoundcloudNormalizedInput.Url).url)

        // Mobile URL
        val mUrlResult = SoundcloudTrackExtractor.normalizeInput("https://m.soundcloud.com/artist/title?si=123")
        assertTrue(mUrlResult.isSuccess)

        // Without protocol
        val bareUrlResult = SoundcloudTrackExtractor.normalizeInput("soundcloud.com/artist/title")
        assertTrue(bareUrlResult.isSuccess)
        assertEquals("https://soundcloud.com/artist/title", (bareUrlResult.getOrThrow() as SoundcloudNormalizedInput.Url).url)

        // Short link
        val shortResult = SoundcloudTrackExtractor.normalizeInput("https://on.soundcloud.com/XyZ12")
        assertTrue(shortResult.isSuccess)

        // sc: prefix with URL
        val scPrefixedUrl = SoundcloudTrackExtractor.normalizeInput("sc:https://soundcloud.com/artist/title")
        assertTrue(scPrefixedUrl.isSuccess)

        // sc: prefix with numeric ID
        val scPrefixedId = SoundcloudTrackExtractor.normalizeInput("sc:1356023209")
        assertTrue(scPrefixedId.isSuccess)
        assertTrue(scPrefixedId.getOrThrow() is SoundcloudNormalizedInput.TrackId)
        assertEquals("1356023209", (scPrefixedId.getOrThrow() as SoundcloudNormalizedInput.TrackId).id)

        // soundcloud: prefix with numeric ID
        val soundcloudPrefixedId = SoundcloudTrackExtractor.normalizeInput("soundcloud:1356023209")
        assertTrue(soundcloudPrefixedId.isSuccess)
        assertEquals("1356023209", (soundcloudPrefixedId.getOrThrow() as SoundcloudNormalizedInput.TrackId).id)

        // Raw numeric ID
        val rawNumeric = SoundcloudTrackExtractor.normalizeInput("1356023209")
        assertTrue(rawNumeric.isSuccess)
        assertEquals("1356023209", (rawNumeric.getOrThrow() as SoundcloudNormalizedInput.TrackId).id)
    }

    // ─── 16. Missing Metadata & Content Availability Errors ───────────────────

    @Test
    fun `missing title or id in extractor produces ParsingException`() = runTest {
        val fake = FakeSoundcloudExtractor(fakeName = "")
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SonaraException.ParsingException)
    }

    @Test
    fun `content not available exception produces NotFoundException`() = runTest {
        val fake = FakeSoundcloudExtractor(onFetchError = ContentNotAvailableException("Track deleted"))
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SonaraException.NotFoundException)
    }

    @Test
    fun `io exception produces NetworkException`() = runTest {
        val fake = FakeSoundcloudExtractor(onFetchError = IOException("Socket timed out"))
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        val result = extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SonaraException.NetworkException)
    }

    @Test
    fun `coroutine cancellation is preserved and rethrown`() = runTest {
        val fake = FakeSoundcloudExtractor(onFetchError = CancellationException("Job was cancelled"))
        val extractor = SoundcloudTrackExtractor(
            downloader = NoOpDownloader(),
            initialize = { Result.success(Unit) },
            extractorFactory = { fake }
        )

        var cancelled = false
        try {
            extractor.extract("https://soundcloud.com/wearecc/open-minds-ep-21")
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue("CancellationException must be rethrown", cancelled)
    }
}
