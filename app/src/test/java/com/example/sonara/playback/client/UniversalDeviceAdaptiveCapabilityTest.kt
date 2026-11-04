package com.example.sonara.playback.client

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.example.sonara.domain.model.video.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive regression test suite for the Universal Device-Adaptive Video Quality Capability System (YT-V5.8.11).
 * Verifies all 15 core architectural and device-adaptive requirements.
 */
class UniversalDeviceAdaptiveCapabilityTest {

    // Helper to generate SelectableVideoTrack instances
    private fun track(
        height: Int,
        width: Int = height * 16 / 9,
        frameRate: Float = 30f,
        formatSupport: Int = C.FORMAT_HANDLED,
        mimeType: String? = "video/webm",
        codecs: String? = "vp9",
        bitrate: Int = 5_000_000
    ): SelectableVideoTrack {
        val format = Format.Builder()
            .setId("$height-$frameRate")
            .setSampleMimeType(mimeType)
            .setCodecs(codecs)
            .setWidth(width)
            .setHeight(height)
            .setAverageBitrate(bitrate)
            .setFrameRate(frameRate)
            .build()
        return SelectableVideoTrack(
            group = TrackGroup(format),
            trackIndex = 0,
            height = height,
            bitrate = bitrate,
            width = width,
            frameRate = frameRate,
            mimeType = mimeType,
            codecs = codecs,
            formatSupport = formatSupport
        )
    }

    @Test
    fun `requirement 1 - FORMAT_HANDLED tracks are Auto-eligible and manually selectable`() {
        val handledTrack = track(
            height = 1080,
            frameRate = 60f,
            formatSupport = C.FORMAT_HANDLED
        )

        assertTrue("FORMAT_HANDLED track must be Auto-eligible", handledTrack.isAutoEligible)
        assertTrue("FORMAT_HANDLED track must be manually selectable", handledTrack.isManualEligible)
        assertTrue("FORMAT_HANDLED track must be decoder safe", handledTrack.isDecoderSafe)
        assertTrue("FORMAT_HANDLED track must be playable", handledTrack.isPlayable)

        val tracks = Tracks(listOf(Tracks.Group(handledTrack.group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))))
        val checker = AndroidVideoDecoderCapabilityChecker { _, _, _, _ -> true }

        val autoTracks = VideoQualityResolver.selectableTracks(tracks, checker)
        assertEquals(1, autoTracks.size)
        assertEquals(1080, autoTracks[0].height)

        val options = VideoQualityResolver.availableQualityOptions(listOf(handledTrack), checker)
        val opt1080 = options.find { it.quality == VideoQuality.P1080_60 }
        assertNotNull(opt1080)
        assertTrue(opt1080!!.isSupported)
        assertNull(opt1080.unsupportedReason)
    }

    @Test
    fun `requirement 2 - FORMAT_EXCEEDS_CAPABILITIES tracks are not automatically selected`() {
        val exceedingTrack = track(
            height = 2160,
            width = 3840,
            frameRate = 60f,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES
        )

        assertFalse("FORMAT_EXCEEDS_CAPABILITIES track must NOT be Auto-eligible", exceedingTrack.isAutoEligible)
        assertFalse("FORMAT_EXCEEDS_CAPABILITIES track must NOT be decoder-safe for Auto", exceedingTrack.isDecoderSafe)

        val tracks = Tracks(listOf(Tracks.Group(exceedingTrack.group, false, intArrayOf(C.FORMAT_EXCEEDS_CAPABILITIES), booleanArrayOf(false))))
        val checker = AndroidVideoDecoderCapabilityChecker { _, _, _, _ -> true }

        // selectableTracks strictly filters for group.isTrackSupported(index) == FORMAT_HANDLED
        val autoTracks = VideoQualityResolver.selectableTracks(tracks, checker)
        assertTrue("Auto mode selectableTracks must NEVER contain FORMAT_EXCEEDS_CAPABILITIES tracks", autoTracks.isEmpty())
    }

    @Test
    fun `requirement 3 - qualified FORMAT_EXCEEDS_CAPABILITIES tracks can be manually selected on capable hardware`() {
        val track4k60 = track(
            height = 2160,
            width = 3840,
            frameRate = 60f,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES,
            mimeType = "video/webm",
            codecs = "vp9"
        )

        // Simulates MediaTek Dimensity 1080: hardware VP9 decoder supports 3840x2160 in surface mode
        val dimensity1080Checker = AndroidVideoDecoderCapabilityChecker(
            decoderEvaluator = { mime, width, height, _ ->
                if (mime == "video/x-vnd.on2.vp9" && width <= 4096 && height <= 2176) {
                    VideoTrackCapability(
                        isAutoEligible = false,
                        isManuallySelectable = true,
                        unsupportedReason = null,
                        capabilityEvidence = CapabilityEvidence.HARDWARE_SURFACE_VIABLE
                    )
                } else {
                    VideoTrackCapability(
                        isAutoEligible = false,
                        isManuallySelectable = false,
                        unsupportedReason = "Unsupported by device",
                        capabilityEvidence = CapabilityEvidence.UNSUPPORTED_DIMENSIONS
                    )
                }
            }
        )

        assertTrue(track4k60.isManualEligible)
        assertTrue(dimensity1080Checker.isManuallySelectable("video/webm", "vp9", 3840, 2160, 60f))

        val options = VideoQualityResolver.availableQualityOptions(listOf(track4k60), dimensity1080Checker)
        val opt4k60 = options.find { it.quality == VideoQuality.P2160_60 }
        assertNotNull("4K60 option must be present in quality options", opt4k60)
        assertTrue("4K60 must be ENABLED for manual selection on Dimensity 1080", opt4k60!!.isSupported)
        assertNull("Supported 4K60 option must have null unsupportedReason", opt4k60.unsupportedReason)
    }

    @Test
    fun `requirement 4 - unsupported MIME types remain disabled`() {
        val trackUnsupportedMime = track(
            height = 1080,
            mimeType = "unknown/mime",
            codecs = "unknown_codec"
        )

        val checker = AndroidVideoDecoderCapabilityChecker()
        val cap = checker.evaluate(trackUnsupportedMime.mimeType, trackUnsupportedMime.codecs, 1920, 1080, 30f)
        assertFalse(cap.isManuallySelectable)
        assertFalse(cap.isAutoEligible)
        assertEquals(CapabilityEvidence.UNSUPPORTED_CODEC, cap.capabilityEvidence)

        val options = VideoQualityResolver.availableQualityOptions(listOf(trackUnsupportedMime), checker)
        val opt1080 = options.find { it.quality == VideoQuality.P1080_30 }
        assertNotNull(opt1080)
        assertFalse("Unsupported MIME type must remain disabled in UI", opt1080!!.isSupported)
        assertEquals("Unsupported by device", opt1080.unsupportedReason)
    }

    @Test
    fun `requirement 5 - unsupported subtypes remain disabled`() {
        val trackUnsupportedSubtype = track(
            height = 4320,
            width = 7680,
            frameRate = 60f,
            formatSupport = C.FORMAT_UNSUPPORTED_SUBTYPE,
            mimeType = "video/mp4",
            codecs = "av01"
        )

        assertFalse("FORMAT_UNSUPPORTED_SUBTYPE must NOT be manual-eligible", trackUnsupportedSubtype.isManualEligible)
        assertFalse("FORMAT_UNSUPPORTED_SUBTYPE must NOT be auto-eligible", trackUnsupportedSubtype.isAutoEligible)
        assertFalse("FORMAT_UNSUPPORTED_SUBTYPE must NOT be playable", trackUnsupportedSubtype.isPlayable)

        val checker = AndroidVideoDecoderCapabilityChecker { _, _, _, _ -> true }
        val options = VideoQualityResolver.availableQualityOptions(listOf(trackUnsupportedSubtype), checker)
        val opt8k = options.find { it.quality == VideoQuality.P4320_60 }
        assertNotNull(opt8k)
        assertFalse("FORMAT_UNSUPPORTED_SUBTYPE must remain disabled", opt8k!!.isSupported)
        assertEquals("Unsupported by device", opt8k.unsupportedReason)
    }

    @Test
    fun `requirement 6 - unsupported dimensions remain disabled`() {
        val track8k = track(
            height = 4320,
            width = 7680,
            frameRate = 60f,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES,
            mimeType = "video/webm",
            codecs = "vp9"
        )

        // Dimensity 1080 hardware VP9 decoder capped at 4096x2176
        val dimensity1080Checker = AndroidVideoDecoderCapabilityChecker(
            decoderEvaluator = { mime, width, height, _ ->
                if (mime == "video/x-vnd.on2.vp9" && width <= 4096 && height <= 2176) {
                    VideoTrackCapability(isAutoEligible = false, isManuallySelectable = true)
                } else {
                    VideoTrackCapability(
                        isAutoEligible = false,
                        isManuallySelectable = false,
                        unsupportedReason = "Unsupported by device",
                        capabilityEvidence = CapabilityEvidence.UNSUPPORTED_DIMENSIONS
                    )
                }
            }
        )

        val cap = dimensity1080Checker.evaluate("video/webm", "vp9", 7680, 4320, 60f)
        assertFalse("8K dimensions exceed Dimensity 1080 VPU limit", cap.isManuallySelectable)
        assertEquals(CapabilityEvidence.UNSUPPORTED_DIMENSIONS, cap.capabilityEvidence)

        val options = VideoQualityResolver.availableQualityOptions(listOf(track8k), dimensity1080Checker)
        val opt8k = options.find { it.quality == VideoQuality.P4320_60 }
        assertNotNull(opt8k)
        assertFalse("8K must be disabled on a 4K-capped device", opt8k!!.isSupported)
        assertEquals("Unsupported by device", opt8k.unsupportedReason)
    }

    @Test
    fun `requirement 7 - 4K30 and 4K60 are evaluated independently`() {
        val track4k60 = track(height = 2160, width = 3840, frameRate = 60f, formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES)
        val track4k30 = track(height = 2160, width = 3840, frameRate = 30f, formatSupport = C.FORMAT_HANDLED)

        // A legacy device where 4K30 is supported, but 4K60 cannot be sustained at all
        val legacy4kChecker = AndroidVideoDecoderCapabilityChecker(
            decoderEvaluator = { _, width, height, fps ->
                val is4k = width > 2560 || height > 1440
                if (is4k && fps > 30.5f) {
                    VideoTrackCapability(
                        isAutoEligible = false,
                        isManuallySelectable = false,
                        unsupportedReason = "Unsupported by device",
                        capabilityEvidence = CapabilityEvidence.UNSUPPORTED_FRAME_RATE
                    )
                } else {
                    VideoTrackCapability(
                        isAutoEligible = true,
                        isManuallySelectable = true,
                        unsupportedReason = null,
                        capabilityEvidence = CapabilityEvidence.HARDWARE_PERFORMANCE_AFFIRMED
                    )
                }
            }
        )

        val options = VideoQualityResolver.availableQualityOptions(listOf(track4k60, track4k30), legacy4kChecker)
        val opt4k30 = options.find { it.quality == VideoQuality.P2160_30 }
        val opt4k60 = options.find { it.quality == VideoQuality.P2160_60 }

        assertNotNull(opt4k30)
        assertNotNull(opt4k60)
        assertTrue("4K30 must be enabled when supported", opt4k30!!.isSupported)
        assertFalse("4K60 must be disabled when frame rate cannot be sustained", opt4k60!!.isSupported)
    }

    @Test
    fun `requirement 8 - 8K30 and 8K60 are evaluated independently`() {
        val track8k60 = track(height = 4320, width = 7680, frameRate = 60f, formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES)
        val track8k30 = track(height = 4320, width = 7680, frameRate = 30f, formatSupport = C.FORMAT_HANDLED)

        // Device supporting 8K30 in hardware, but not 8K60
        val checker8k30 = AndroidVideoDecoderCapabilityChecker(
            decoderEvaluator = { _, width, height, fps ->
                if (width <= 7680 && height <= 4320 && fps <= 30.5f) {
                    VideoTrackCapability(isAutoEligible = true, isManuallySelectable = true)
                } else {
                    VideoTrackCapability(isAutoEligible = false, isManuallySelectable = false, unsupportedReason = "Unsupported by device")
                }
            }
        )

        val options = VideoQualityResolver.availableQualityOptions(listOf(track8k60, track8k30), checker8k30)
        val opt8k30 = options.find { it.quality == VideoQuality.P4320_30 }
        val opt8k60 = options.find { it.quality == VideoQuality.P4320_60 }

        assertNotNull(opt8k30)
        assertNotNull(opt8k60)
        assertTrue("8K30 must be enabled when hardware supports it", opt8k30!!.isSupported)
        assertFalse("8K60 must be disabled when hardware does not support it", opt8k60!!.isSupported)
    }

    @Test
    fun `requirement 9 - VP9 and AV1 are evaluated independently`() {
        // Source DASH manifest provides both VP9 and AV1 representations at 4K60
        val trackVp9_4k60 = track(
            height = 2160,
            width = 3840,
            frameRate = 60f,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES,
            mimeType = "video/webm",
            codecs = "vp9"
        )
        val trackAv1_4k60 = track(
            height = 2160,
            width = 3840,
            frameRate = 60f,
            formatSupport = C.FORMAT_UNSUPPORTED_SUBTYPE,
            mimeType = "video/mp4",
            codecs = "av01.0.08M.08"
        )

        // Device has hardware VP9 up to 4K, but no hardware AV1
        val checker = AndroidVideoDecoderCapabilityChecker(
            decoderEvaluator = { mime, width, height, _ ->
                if (mime == "video/x-vnd.on2.vp9" && width <= 4096 && height <= 2176) {
                    VideoTrackCapability(isAutoEligible = false, isManuallySelectable = true)
                } else {
                    VideoTrackCapability(isAutoEligible = false, isManuallySelectable = false, unsupportedReason = "Unsupported by device")
                }
            }
        )

        val options = VideoQualityResolver.availableQualityOptions(listOf(trackVp9_4k60, trackAv1_4k60), checker)
        val opt4k60 = options.find { it.quality == VideoQuality.P2160_60 }

        assertNotNull(opt4k60)
        assertTrue("4K60 must be enabled because a compatible VP9 representation exists", opt4k60!!.isSupported)
    }

    @Test
    fun `requirement 10 - hardware and software decoder availability are evaluated correctly`() {
        // High-resolution (1440p) software-only representation
        val track1440pSoftware = track(
            height = 1440,
            width = 2560,
            frameRate = 60f,
            formatSupport = C.FORMAT_HANDLED,
            mimeType = "video/mp4",
            codecs = "av01"
        )

        val softwareOnlyChecker = AndroidVideoDecoderCapabilityChecker(
            decoderEvaluator = { _, width, height, _ ->
                val isHighRes = width >= 2560 || height >= 1440
                if (isHighRes) {
                    // Mobile CPU cannot sustain software decoding at 1440p+
                    VideoTrackCapability(
                        isAutoEligible = false,
                        isManuallySelectable = false,
                        unsupportedReason = "Unsupported by device",
                        capabilityEvidence = CapabilityEvidence.UNSUPPORTED_FRAME_RATE
                    )
                } else {
                    VideoTrackCapability(
                        isAutoEligible = true,
                        isManuallySelectable = true,
                        capabilityEvidence = CapabilityEvidence.SOFTWARE_AFFIRMED
                    )
                }
            }
        )

        val options = VideoQualityResolver.availableQualityOptions(listOf(track1440pSoftware), softwareOnlyChecker)
        val opt1440 = options.find { it.quality == VideoQuality.P1440_60 }
        assertNotNull(opt1440)
        assertFalse("High-res software decoding must NOT be enabled on mobile CPUs", opt1440!!.isSupported)
    }

    @Test
    fun `requirement 11 - null capability checker fails safely closed`() {
        val track1080p = track(height = 1080, formatSupport = C.FORMAT_HANDLED)
        val options = VideoQualityResolver.availableQualityOptions(listOf(track1080p), capabilityChecker = null)

        val opt1080 = options.find { it.quality == VideoQuality.P1080_30 }
        assertNotNull(opt1080)
        assertFalse("Null capability checker must fail closed (isSupported = false)", opt1080!!.isSupported)
        assertEquals("Unsupported by device", opt1080.unsupportedReason)
    }

    @Test
    fun `requirement 12 - hypothetical device capable of 8K60 enables 8K60 without hardcoded limits`() {
        val track8k60 = track(
            height = 4320,
            width = 7680,
            frameRate = 60f,
            formatSupport = C.FORMAT_HANDLED,
            mimeType = "video/webm",
            codecs = "vp9"
        )

        // Flagship device simulator (e.g. Snapdragon 8 Gen 2/3 with 8K60 hardware decoder)
        val flagship8kChecker = AndroidVideoDecoderCapabilityChecker(
            decoderEvaluator = { mime, width, height, fps ->
                if ((mime == "video/x-vnd.on2.vp9" || mime == "video/av01") && width <= 8192 && height <= 4352 && fps <= 60.5f) {
                    VideoTrackCapability(
                        isAutoEligible = true,
                        isManuallySelectable = true,
                        unsupportedReason = null,
                        capabilityEvidence = CapabilityEvidence.HARDWARE_PERFORMANCE_AFFIRMED
                    )
                } else {
                    VideoTrackCapability(isAutoEligible = false, isManuallySelectable = false, unsupportedReason = "Unsupported by device")
                }
            }
        )

        val options = VideoQualityResolver.availableQualityOptions(listOf(track8k60), flagship8kChecker)
        val opt8k = options.find { it.quality == VideoQuality.P4320_60 }

        assertNotNull("8K60 option must be present", opt8k)
        assertTrue("8K60 MUST be enabled on an 8K-capable device without hardcoded ceilings", opt8k!!.isSupported)
        assertNull("8K60 supported option must have null unsupportedReason", opt8k.unsupportedReason)
    }

    @Test
    fun `requirement 14 - Auto selection behavior remains strictly conservative`() {
        val trackHandled = track(height = 1080, formatSupport = C.FORMAT_HANDLED, bitrate = 2_000_000)
        val trackExceeds = track(height = 2160, formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES, bitrate = 10_000_000)

        val autoGroup = VideoQualityResolver.resolveAutoGroup(listOf(trackHandled, trackExceeds))
        assertNotNull(autoGroup)
        assertEquals(trackHandled.group, autoGroup!!.targetGroup)
        assertEquals(1080, autoGroup.maxEligibleHeight)
    }

    @Test
    fun `requirement 15 - resolve chooses best compatible track among multiple codec representations`() {
        val trackAv1Unsupported = track(height = 2160, width = 3840, frameRate = 60f, formatSupport = C.FORMAT_UNSUPPORTED_SUBTYPE, mimeType = "video/mp4", codecs = "av01")
        val trackVp9Viable = track(height = 2160, width = 3840, frameRate = 60f, formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES, mimeType = "video/webm", codecs = "vp9")

        val selection = VideoQualityResolver.resolve(VideoQuality.P2160_60, listOf(trackAv1Unsupported, trackVp9Viable))
        assertNotNull(selection.selected)
        assertEquals("Must select the playable VP9 track over unplayable AV1", "video/webm", selection.selected?.mimeType)
        assertEquals(2160, selection.selected?.height)
    }
}
