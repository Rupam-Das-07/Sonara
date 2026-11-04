package com.example.sonara.data.stream

import com.example.sonara.core.error.SonaraException
import com.example.sonara.data.remote.backend.SonaraBackendClient
import com.example.sonara.domain.model.AudioQuality
import com.example.sonara.domain.model.StreamInfo
import com.example.sonara.domain.model.Track
import com.example.sonara.domain.model.soundcloud.SoundcloudAudioStream
import com.example.sonara.domain.model.soundcloud.SoundcloudDeliveryMethod
import com.example.sonara.domain.model.soundcloud.SoundcloudTrackExtraction
import com.example.sonara.domain.ports.SoundcloudTrackExtractionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class StreamResolverSoundcloudTest {

    private class FakeSoundcloudExtractor(
        var streamUrl: String = "https://cf-media.sndcdn.com/stream-123.m3u8",
        var deliveryMethod: SoundcloudDeliveryMethod = SoundcloudDeliveryMethod.HLS,
        var mediaFormat: String = SoundcloudAudioStream.FORMAT_AAC,
        var bitrateKbps: Int = 160,
        var failure: Exception? = null,
        var onExtract: (suspend (String) -> Unit)? = null
    ) : SoundcloudTrackExtractionPort {
        val calls = AtomicInteger(0)
        val extractedInputs = mutableListOf<String>()

        override suspend fun extract(input: String): Result<SoundcloudTrackExtraction> {
            calls.incrementAndGet()
            extractedInputs.add(input)
            onExtract?.invoke(input)
            failure?.let { return Result.failure(it) }

            val stream = SoundcloudAudioStream(
                id = "sc_stream_${calls.get()}",
                streamUrl = streamUrl,
                deliveryMethod = deliveryMethod,
                mediaFormat = mediaFormat,
                mimeType = SoundcloudAudioStream.resolveMimeType(deliveryMethod, mediaFormat),
                bitrateKbps = bitrateKbps
            )
            return Result.success(
                SoundcloudTrackExtraction(
                    track = Track(
                        id = if (input.startsWith("sc:") || input.startsWith("soundcloud:")) input else "sc:$input",
                        title = "SoundCloud Song",
                        artist = "SoundCloud Artist"
                    ),
                    rawTrackId = "123456",
                    canonicalUrl = "https://soundcloud.com/artist/song",
                    selectedStream = stream,
                    allStreams = listOf(stream)
                )
            )
        }
    }

    private class RecordingBackendClient : SonaraBackendClient() {
        val calls = AtomicInteger(0)
        val requestedIds = mutableListOf<String>()

        override suspend fun getStreamUrl(
            videoId: String,
            quality: AudioQuality,
            title: String,
            artist: String,
            durationSeconds: Int
        ): Result<StreamInfo> {
            calls.incrementAndGet()
            requestedIds.add(videoId)
            return Result.success(
                StreamInfo(
                    trackId = videoId,
                    streamUrl = "https://backend.proxy/stream/$videoId",
                    expiresAt = System.currentTimeMillis() + 3_600_000L,
                    format = "audio/mpeg",
                    codec = "opus",
                    bitrateKbps = 160,
                    provider = "youtube"
                )
            )
        }
    }

    @Test
    fun `valid sc ID delegates to soundcloud extractor and never touches backend client`() = runBlocking {
        val scExtractor = FakeSoundcloudExtractor()
        val backend = RecordingBackendClient()
        val resolver = StreamResolverImpl(backendClient = backend, soundcloudExtractor = scExtractor)

        val result = resolver.resolveStream("sc:123456")

        assertTrue("Resolution must succeed", result.isSuccess)
        val streamInfo = result.getOrNull()
        assertNotNull(streamInfo)
        assertEquals("sc:123456", streamInfo?.trackId)
        assertEquals("https://cf-media.sndcdn.com/stream-123.m3u8", streamInfo?.streamUrl)
        assertEquals("soundcloud", streamInfo?.provider)
        assertEquals(androidx.media3.common.MimeTypes.APPLICATION_M3U8, streamInfo?.format)

        assertEquals("SoundCloud extractor must be called exactly once", 1, scExtractor.calls.get())
        assertEquals("Backend client must NEVER be called for sc: ID", 0, backend.calls.get())
    }

    @Test
    fun `valid legacy soundcloud prefix delegates to soundcloud extractor and never touches backend client`() = runBlocking {
        val scExtractor = FakeSoundcloudExtractor()
        val backend = RecordingBackendClient()
        val resolver = StreamResolverImpl(backendClient = backend, soundcloudExtractor = scExtractor)

        val result = resolver.resolveStream("soundcloud:987654")

        assertTrue("Resolution must succeed", result.isSuccess)
        val streamInfo = result.getOrNull()
        assertEquals("soundcloud:987654", streamInfo?.trackId)
        assertEquals(1, scExtractor.calls.get())
        assertEquals(0, backend.calls.get())
    }

    @Test
    fun `normal YouTube ID continues through existing resolution path to backend client and never touches soundcloud extractor`() = runBlocking {
        val scExtractor = FakeSoundcloudExtractor()
        val backend = RecordingBackendClient()
        val resolver = StreamResolverImpl(backendClient = backend, soundcloudExtractor = scExtractor)

        val result = resolver.resolveStream("dQw4w9WgXcQ")

        assertTrue("Resolution must succeed", result.isSuccess)
        val streamInfo = result.getOrNull()
        assertNotNull(streamInfo)
        assertEquals("dQw4w9WgXcQ", streamInfo?.trackId)
        assertEquals("youtube", streamInfo?.provider)
        assertEquals("https://backend.proxy/stream/dQw4w9WgXcQ", streamInfo?.streamUrl)

        assertEquals(0, scExtractor.calls.get())
        assertEquals(1, backend.calls.get())
        assertEquals("dQw4w9WgXcQ", backend.requestedIds.first())
    }

    @Test
    fun `SoundCloud ID never gets converted into a YouTube watch URL`() = runBlocking {
        val scExtractor = FakeSoundcloudExtractor(streamUrl = "https://cf-media.sndcdn.com/signed-audio.m3u8")
        val backend = RecordingBackendClient()
        val resolver = StreamResolverImpl(backendClient = backend, soundcloudExtractor = scExtractor)

        val result = resolver.resolveStream("sc:99999")

        assertTrue(result.isSuccess)
        val streamInfo = result.getOrNull()
        assertFalse(
            "Stream URL must never contain youtube watch URL",
            streamInfo?.streamUrl?.contains("youtube.com/watch") == true
        )
        assertEquals(0, backend.calls.get())
    }

    @Test
    fun `SoundCloud extraction failure surfaces as failure without calling backend client`() = runBlocking {
        val scExtractor = FakeSoundcloudExtractor(
            failure = SonaraException.NotFoundException("SoundCloud track not found")
        )
        val backend = RecordingBackendClient()
        val resolver = StreamResolverImpl(backendClient = backend, soundcloudExtractor = scExtractor)

        val result = resolver.resolveStream("sc:missing123")

        assertTrue("Result must be failure", result.isFailure)
        assertTrue(result.exceptionOrNull() is SonaraException.NotFoundException)
        assertEquals(1, scExtractor.calls.get())
        assertEquals("Backend must never be called on SoundCloud failure", 0, backend.calls.get())
    }

    @Test
    fun `cancellation during SoundCloud extraction rethrows CancellationException`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val scExtractor = FakeSoundcloudExtractor(
            onExtract = {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        )
        val resolver = StreamResolverImpl(backendClient = RecordingBackendClient(), soundcloudExtractor = scExtractor)

        val job = async(Dispatchers.Default) {
            resolver.resolveStream("sc:cancelTest")
        }
        entered.await()
        job.cancelAndJoin()

        assertTrue("Job must be cancelled", job.isCancelled)
    }

    @Test
    fun `SoundCloud stream caching works - repeated calls for sc ID hit in-memory cache without re-extracting`() = runBlocking {
        val scExtractor = FakeSoundcloudExtractor()
        val resolver = StreamResolverImpl(backendClient = RecordingBackendClient(), soundcloudExtractor = scExtractor)

        val res1 = resolver.resolveStream("sc:cached123")
        assertTrue(res1.isSuccess)
        assertEquals(1, scExtractor.calls.get())

        val res2 = resolver.resolveStream("sc:cached123")
        assertTrue(res2.isSuccess)
        assertEquals("Second call must hit cache", 1, scExtractor.calls.get())
        assertEquals(res1.getOrNull()?.streamUrl, res2.getOrNull()?.streamUrl)
    }

    @Test
    fun `refreshStream for sc ID invalidates cache and re-extracts fresh stream URL`() = runBlocking {
        val scExtractor = FakeSoundcloudExtractor(streamUrl = "https://cf-media.sndcdn.com/v1.m3u8")
        val resolver = StreamResolverImpl(backendClient = RecordingBackendClient(), soundcloudExtractor = scExtractor)

        val res1 = resolver.resolveStream("sc:refreshMe")
        assertTrue(res1.isSuccess)
        assertEquals("https://cf-media.sndcdn.com/v1.m3u8", res1.getOrNull()?.streamUrl)
        assertEquals(1, scExtractor.calls.get())

        // Update extractor to return fresh URL on next call
        scExtractor.streamUrl = "https://cf-media.sndcdn.com/v2_fresh.m3u8"

        val res2 = resolver.refreshStream("sc:refreshMe")
        assertTrue(res2.isSuccess)
        assertEquals("https://cf-media.sndcdn.com/v2_fresh.m3u8", res2.getOrNull()?.streamUrl)
        assertEquals("Refresh must trigger a second extraction", 2, scExtractor.calls.get())
    }

    @Test
    fun `concurrent resolutions for same sc ID join in-flight leader`() = runBlocking {
        val releaseLeader = CompletableDeferred<Unit>()
        val scExtractor = FakeSoundcloudExtractor(
            onExtract = {
                releaseLeader.await()
            }
        )
        val resolver = StreamResolverImpl(backendClient = RecordingBackendClient(), soundcloudExtractor = scExtractor)

        val firstDeferred = async(Dispatchers.Default) { resolver.resolveStream("sc:concurrent") }
        val secondDeferred = async(Dispatchers.Default) { resolver.resolveStream("sc:concurrent") }

        releaseLeader.complete(Unit)

        val res1 = firstDeferred.await()
        val res2 = secondDeferred.await()

        assertTrue(res1.isSuccess)
        assertTrue(res2.isSuccess)
        assertEquals("In-flight deduplication must result in only 1 extractor call", 1, scExtractor.calls.get())
        assertEquals(res1.getOrNull()?.streamUrl, res2.getOrNull()?.streamUrl)
    }
}
