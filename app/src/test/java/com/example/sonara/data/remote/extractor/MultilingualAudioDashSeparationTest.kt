package com.example.sonara.data.remote.extractor

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
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
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Base64
import java.util.Locale
import java.util.regex.Pattern

class MultilingualAudioDashSeparationTest {

    private fun decodeMpdDataUri(uri: String): String {
        val prefix = "data:application/dash+xml;charset=utf-8;base64,"
        assertTrue("Expected data URI prefix", uri.startsWith(prefix))
        val base64 = uri.substring(prefix.length)
        return String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
    }

    private fun sampleVideoOnlyStream(
        itag: Int = 137,
        url: String = "https://rr1.googlevideo.com/videoplayback?itag=137",
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
        itag: Int = 251,
        url: String = "https://rr1.googlevideo.com/videoplayback?itag=251",
        codec: String = "opus",
        bitrate: Int = 128_000,
        mediaFormat: MediaFormat = MediaFormat.WEBMA,
        trackId: String? = null,
        trackName: String? = null,
        locale: Locale? = null,
        trackType: AudioTrackType? = null
    ): AudioStream {
        val itagItem = ItagItem(itag, ItagItem.ItagType.AUDIO, mediaFormat, bitrate).apply {
            setCodec(codec)
            setBitrate(bitrate)
            setInitStart(0)
            setInitEnd(620)
            setIndexStart(621)
            setIndexEnd(1840)
        }
        val builder = AudioStream.Builder()
            .setId(itag.toString())
            .setContent(url, true)
            .setMediaFormat(mediaFormat)
            .setDeliveryMethod(DeliveryMethod.DASH)
            .setAverageBitrate(bitrate)
            .setItagItem(itagItem)
        trackId?.let { builder.setAudioTrackId(it) }
        trackName?.let { builder.setAudioTrackName(it) }
        locale?.let { builder.setAudioLocale(it) }
        trackType?.let { builder.setAudioTrackType(it) }
        return builder.build()
    }

    private fun extractAdaptationSets(xml: String): List<String> {
        val pattern = Pattern.compile("<AdaptationSet[\\s\\S]*?<\\/AdaptationSet>")
        val matcher = pattern.matcher(xml)
        val list = mutableListOf<String>()
        while (matcher.find()) {
            list.add(matcher.group())
        }
        return list
    }

    private fun extractAudioAdaptationSets(xml: String): List<String> {
        return extractAdaptationSets(xml).filter {
            it.contains("contentType=\"audio\"") || it.contains("mimeType=\"audio/")
        }
    }

    private fun extractRepresentationIds(xml: String): List<String> {
        val pattern = Pattern.compile("<Representation\\s+id=\"([^\"]+)\"")
        val matcher = pattern.matcher(xml)
        val list = mutableListOf<String>()
        while (matcher.find()) {
            matcher.group(1)?.let { list.add(it) }
        }
        return list
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 1: Multilingual Separation
    // Video with Hindi ORIGINAL, Tamil DUBBED, Telugu DUBBED produces 3 separate audio AdaptationSets.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 1 - multilingual audio produces 3 separate audio AdaptationSets`() {
        val videos = listOf(sampleVideoOnlyStream())
        val audios = listOf(
            sampleAudioStream(
                itag = 251,
                trackId = "hi.4",
                trackName = "Hindi",
                locale = Locale.forLanguageTag("hi"),
                trackType = AudioTrackType.ORIGINAL
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "ta.3",
                trackName = "Tamil",
                locale = Locale.forLanguageTag("ta"),
                trackType = AudioTrackType.DUBBED
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "te.3",
                trackName = "Telugu",
                locale = Locale.forLanguageTag("te"),
                trackType = AudioTrackType.DUBBED
            )
        )

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L
        )
        assertNotNull("MPD data URI must not be null", mpdUri)
        val xml = decodeMpdDataUri(mpdUri!!)

        val audioSets = extractAudioAdaptationSets(xml)
        assertEquals("Must produce exactly 3 audio AdaptationSets for 3 languages", 3, audioSets.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 2: Language Attributes
    // Audio AdaptationSets have correct lang="hi", lang="ta", lang="te".
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 2 - audio AdaptationSets contain correct language attributes`() {
        val videos = listOf(sampleVideoOnlyStream())
        val audios = listOf(
            sampleAudioStream(
                itag = 251,
                trackId = "hi.4",
                trackName = "Hindi",
                locale = Locale.forLanguageTag("hi"),
                trackType = AudioTrackType.ORIGINAL
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "ta.3",
                trackName = "Tamil",
                locale = Locale.forLanguageTag("ta"),
                trackType = AudioTrackType.DUBBED
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "te.3",
                trackName = "Telugu",
                locale = Locale.forLanguageTag("te"),
                trackType = AudioTrackType.DUBBED
            )
        )

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L
        )
        val xml = decodeMpdDataUri(mpdUri!!)
        val audioSets = extractAudioAdaptationSets(xml)

        val languages = audioSets.mapNotNull { set ->
            val match = Pattern.compile("lang=\"([^\"]+)\"").matcher(set)
            if (match.find()) match.group(1) else null
        }

        assertTrue("Must include Hindi (hi)", languages.contains("hi"))
        assertTrue("Must include Tamil (ta)", languages.contains("ta"))
        assertTrue("Must include Telugu (te)", languages.contains("te"))
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 3: Role Values & Labels
    // ORIGINAL has Role value="main", DUBBED has Role value="alternate".
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 3 - ORIGINAL has Role main and DUBBED has Role alternate`() {
        val videos = listOf(sampleVideoOnlyStream())
        val audios = listOf(
            sampleAudioStream(
                itag = 251,
                trackId = "hi.4",
                trackName = "Hindi",
                locale = Locale.forLanguageTag("hi"),
                trackType = AudioTrackType.ORIGINAL
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "ta.3",
                trackName = "Tamil",
                locale = Locale.forLanguageTag("ta"),
                trackType = AudioTrackType.DUBBED
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "te.3",
                trackName = "Telugu",
                locale = Locale.forLanguageTag("te"),
                trackType = AudioTrackType.DUBBED
            )
        )

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L
        )
        val xml = decodeMpdDataUri(mpdUri!!)
        val audioSets = extractAudioAdaptationSets(xml)

        val hiSet = audioSets.first { it.contains("lang=\"hi\"") }
        val taSet = audioSets.first { it.contains("lang=\"ta\"") }
        val teSet = audioSets.first { it.contains("lang=\"te\"") }

        assertTrue(
            "Hindi (ORIGINAL) must have Role value='main'",
            hiSet.contains("<Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"main\"/>")
        )
        assertTrue(
            "Hindi label should indicate original",
            hiSet.contains("<Label>Hindi (original)</Label>")
        )

        assertTrue(
            "Tamil (DUBBED) must have Role value='alternate'",
            taSet.contains("<Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"alternate\"/>")
        )
        assertTrue(
            "Tamil label should match track name",
            taSet.contains("<Label>Tamil</Label>")
        )

        assertTrue(
            "Telugu (DUBBED) must have Role value='alternate'",
            teSet.contains("<Role schemeIdUri=\"urn:mpeg:dash:role:2011\" value=\"alternate\"/>")
        )
        assertTrue(
            "Telugu label should match track name",
            teSet.contains("<Label>Telugu</Label>")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 4: Bitrate Grouping Within Language
    // Multiple bitrates for the same language remain grouped in ONE AdaptationSet.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 4 - multiple bitrates for same language are grouped in single AdaptationSet`() {
        val videos = listOf(sampleVideoOnlyStream())
        val audios = listOf(
            // Hindi 160kbps
            sampleAudioStream(
                itag = 251,
                bitrate = 160_000,
                trackId = "hi.4",
                trackName = "Hindi",
                locale = Locale.forLanguageTag("hi"),
                trackType = AudioTrackType.ORIGINAL
            ),
            // Hindi 50kbps
            sampleAudioStream(
                itag = 250,
                bitrate = 50_000,
                trackId = "hi.4",
                trackName = "Hindi",
                locale = Locale.forLanguageTag("hi"),
                trackType = AudioTrackType.ORIGINAL
            ),
            // Tamil 160kbps
            sampleAudioStream(
                itag = 251,
                bitrate = 160_000,
                trackId = "ta.3",
                trackName = "Tamil",
                locale = Locale.forLanguageTag("ta"),
                trackType = AudioTrackType.DUBBED
            )
        )

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L
        )
        val xml = decodeMpdDataUri(mpdUri!!)
        val audioSets = extractAudioAdaptationSets(xml)

        assertEquals("Must produce 2 audio AdaptationSets (Hindi and Tamil)", 2, audioSets.size)

        val hiSet = audioSets.first { it.contains("lang=\"hi\"") }
        val repMatches = Pattern.compile("<Representation").matcher(hiSet)
        var repCount = 0
        while (repMatches.find()) repCount++
        assertEquals("Hindi AdaptationSet must group both 160k and 50k representations", 2, repCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 5: Unique Representation IDs
    // Representation IDs across AdaptationSets must be unique (no collision on itag 251).
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 5 - representation IDs are unique with no collisions`() {
        val videos = listOf(sampleVideoOnlyStream())
        val audios = listOf(
            sampleAudioStream(
                itag = 251,
                trackId = "hi.4",
                locale = Locale.forLanguageTag("hi"),
                trackType = AudioTrackType.ORIGINAL
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "ta.3",
                locale = Locale.forLanguageTag("ta"),
                trackType = AudioTrackType.DUBBED
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "te.3",
                locale = Locale.forLanguageTag("te"),
                trackType = AudioTrackType.DUBBED
            )
        )

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L
        )
        val xml = decodeMpdDataUri(mpdUri!!)
        val repIds = extractRepresentationIds(xml)

        assertEquals("All representation IDs must be unique across MPD", repIds.size, repIds.toSet().size)
        // Verify disambiguated IDs contain language/track indicator
        assertTrue("Expected 251_hi or similar unique ID", repIds.contains("251_hi"))
        assertTrue("Expected 251_ta or similar unique ID", repIds.contains("251_ta"))
        assertTrue("Expected 251_te or similar unique ID", repIds.contains("251_te"))
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 6: Missing Metadata Fallback
    // Audio streams with null audioTrackId, audioLocale, audioTrackType handled gracefully.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 6 - missing audio metadata falls back gracefully to default role main without crashing`() {
        val videos = listOf(sampleVideoOnlyStream())
        val audios = listOf(
            sampleAudioStream(
                itag = 140,
                bitrate = 128_000,
                mediaFormat = MediaFormat.M4A,
                trackId = null,
                trackName = null,
                locale = null,
                trackType = null
            )
        )

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 120L
        )
        assertNotNull("MPD generation must succeed", mpdUri)
        val xml = decodeMpdDataUri(mpdUri!!)

        val audioSets = extractAudioAdaptationSets(xml)
        assertEquals("Must produce 1 audio AdaptationSet", 1, audioSets.size)
        assertTrue("Default role must be 'main'", audioSets[0].contains("value=\"main\""))
        assertTrue("Must contain representation 140", audioSets[0].contains("id=\"140\""))
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 7: Single-Language Video Backward Compatibility
    // Video with single language retains a single audio AdaptationSet with Role main.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 7 - single language video retains single audio AdaptationSet with Role main`() {
        val videos = listOf(sampleVideoOnlyStream(itag = 137, height = 1080))
        val audios = listOf(
            sampleAudioStream(
                itag = 140,
                codec = "mp4a.40.2",
                bitrate = 128_000,
                mediaFormat = MediaFormat.M4A,
                trackId = "en",
                trackName = "English",
                locale = Locale.ENGLISH,
                trackType = AudioTrackType.ORIGINAL
            )
        )

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 200L
        )
        val xml = decodeMpdDataUri(mpdUri!!)

        val audioSets = extractAudioAdaptationSets(xml)
        assertEquals("Single language must produce exactly 1 audio AdaptationSet", 1, audioSets.size)
        assertTrue("Must have Role value='main'", audioSets[0].contains("value=\"main\""))
        assertTrue("Must have lang='en'", audioSets[0].contains("lang=\"en\""))
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 8: Media3 TrackSelectionParameters PreferredAudioRoleFlags
    // Verify that C.ROLE_FLAG_MAIN is preserved and prioritized over ROLE_FLAG_ALTERNATE.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 8 - preferredAudioRoleFlags specifies ROLE_FLAG_MAIN`() {
        val params = TrackSelectionParameters.Builder()
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
            .build()

        assertEquals(
            "TrackSelectionParameters must hold ROLE_FLAG_MAIN",
            C.ROLE_FLAG_MAIN,
            params.preferredAudioRoleFlags
        )

        // Verify Format role flags differentiation
        val mainAudioFormat = Format.Builder()
            .setId("hi_main")
            .setSampleMimeType("audio/webm")
            .setRoleFlags(C.ROLE_FLAG_MAIN)
            .setLanguage("hi")
            .setAverageBitrate(128_000)
            .build()

        val alternateAudioFormat = Format.Builder()
            .setId("ta_alternate")
            .setSampleMimeType("audio/webm")
            .setRoleFlags(C.ROLE_FLAG_ALTERNATE)
            .setLanguage("ta")
            .setAverageBitrate(256_000) // higher bitrate
            .build()

        assertTrue(
            "Main format has ROLE_FLAG_MAIN",
            (mainAudioFormat.roleFlags and C.ROLE_FLAG_MAIN) != 0
        )
        assertTrue(
            "Alternate format has ROLE_FLAG_ALTERNATE",
            (alternateAudioFormat.roleFlags and C.ROLE_FLAG_ALTERNATE) != 0
        )
        // With preferredAudioRoleFlags = ROLE_FLAG_MAIN, matching score for mainFormat roleFlags is strictly higher
        val mainMatches = (mainAudioFormat.roleFlags and params.preferredAudioRoleFlags) != 0
        val altMatches = (alternateAudioFormat.roleFlags and params.preferredAudioRoleFlags) != 0

        assertTrue("Main audio format matches preferred role flags", mainMatches)
        assertFalse("Alternate audio format does not match preferred role flags", altMatches)
    }

    // ---------------------------------------------------------------------------------------------
    // Test Scenario 9: 8K External Audio Pairing
    // Injected 8K audio pairs into the main (ORIGINAL) AdaptationSet without ID collisions.
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `scenario 9 - external 8K audio representation pairs into main audio AdaptationSet without collisions`() {
        val videos = listOf(sampleVideoOnlyStream(itag = 137, height = 1080))
        val audios = listOf(
            sampleAudioStream(
                itag = 251,
                trackId = "hi.4",
                trackName = "Hindi",
                locale = Locale.forLanguageTag("hi"),
                trackType = AudioTrackType.ORIGINAL
            ),
            sampleAudioStream(
                itag = 251,
                trackId = "ta.3",
                trackName = "Tamil",
                locale = Locale.forLanguageTag("ta"),
                trackType = AudioTrackType.DUBBED
            )
        )

        val externalHighRes = ExternalHighResStreamDescriptor(
            sessionId = "sess_multi_8k",
            expiresAt = (System.currentTimeMillis() / 1000L) + 7200L,
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
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_multi_8k&track=video"
            ),
            audio = HighResAudioRepresentation(
                itag = 251,
                codec = "opus",
                mimeType = "audio/webm",
                bitrate = 160_000L,
                duration = 180.0,
                initRange = HighResByteRange(0, 624),
                indexRange = HighResByteRange(625, 1850),
                streamProxyUrl = "http://10.0.2.2:3002/api/v1/stream/video?sessionId=sess_multi_8k&track=audio"
            )
        )

        val mpdUri = NewPipeExtractionMapper.generateDynamicDashMpd(
            videoStreams = videos,
            audioStreams = audios,
            durationSeconds = 180L,
            externalHighRes = externalHighRes
        )
        assertNotNull(mpdUri)
        val xml = decodeMpdDataUri(mpdUri!!)

        val audioSets = extractAudioAdaptationSets(xml)
        assertEquals("Must produce 2 audio AdaptationSets (Hindi and Tamil)", 2, audioSets.size)

        val hiSet = audioSets.first { it.contains("lang=\"hi\"") }
        assertTrue("Main audio set must contain external audio representation", hiSet.contains("sess_multi_8k&amp;track=audio"))

        val repIds = extractRepresentationIds(xml)
        assertEquals("All representation IDs including external must be unique", repIds.size, repIds.toSet().size)
        assertTrue("Contains 8K video representation 571", repIds.contains("571"))
        assertTrue("Contains external audio representation with unique suffix", repIds.any { it.startsWith("251") && it.contains("ext") })
    }
}
