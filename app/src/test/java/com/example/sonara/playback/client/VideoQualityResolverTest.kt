package com.example.sonara.playback.client

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.example.sonara.domain.model.video.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityResolverTest {
    @Test
    fun `available heights are deduplicated and sorted descending across full spectrum`() {
        val options = listOf(
            option(144),
            option(4320, bitrate = 25_000_000),
            option(1080, bitrate = 4_000_000),
            option(2160, bitrate = 15_000_000),
            option(4320, bitrate = 50_000_000),
            option(720, bitrate = 2_000_000),
            option(1440, bitrate = 8_000_000),
            option(360, bitrate = 800_000),
            option(480, bitrate = 1_200_000),
            option(240, bitrate = 400_000),
            option(1080, bitrate = 6_000_000)
        )

        val expected = listOf(4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)
        assertEquals(expected, VideoQualityResolver.availableHeights(options))
    }

    @Test
    fun `exact resolution match resolves all supported quality tiers from 144p to 4320p`() {
        val allHeights = listOf(4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)
        val options = allHeights.map { option(it) }

        assertEquals(4320, VideoQualityResolver.resolve(VideoQuality.P4320, options).selected?.height)
        assertEquals(2160, VideoQualityResolver.resolve(VideoQuality.P2160, options).selected?.height)
        assertEquals(1440, VideoQualityResolver.resolve(VideoQuality.P1440, options).selected?.height)
        assertEquals(1080, VideoQualityResolver.resolve(VideoQuality.P1080, options).selected?.height)
        assertEquals(720, VideoQualityResolver.resolve(VideoQuality.P720, options).selected?.height)
        assertEquals(480, VideoQualityResolver.resolve(VideoQuality.P480, options).selected?.height)
        assertEquals(360, VideoQualityResolver.resolve(VideoQuality.P360, options).selected?.height)
        assertEquals(240, VideoQualityResolver.resolve(VideoQuality.P240, options).selected?.height)
        assertEquals(144, VideoQualityResolver.resolve(VideoQuality.P144, options).selected?.height)
    }

    @Test
    fun `requested quality falls back to highest supported height at or below target`() {
        val options = listOf(option(720), option(480), option(360))
        val lowerOptions = listOf(option(480), option(360))

        assertEquals(720, VideoQualityResolver.resolve(VideoQuality.P1080, options).selected?.height)
        assertEquals(480, VideoQualityResolver.resolve(VideoQuality.P720, lowerOptions).selected?.height)

        // 8K request falls back to 4K when 8K is not present
        val highOptions = listOf(option(2160), option(1440), option(1080))
        assertEquals(2160, VideoQualityResolver.resolve(VideoQuality.P4320, highOptions).selected?.height)

        // 4K request falls back to 2K (1440p) when 4K is not present
        val midHighOptions = listOf(option(1440), option(1080), option(720))
        assertEquals(1440, VideoQualityResolver.resolve(VideoQuality.P2160, midHighOptions).selected?.height)

        // 2K request falls back to 1080p
        val standardHdOptions = listOf(option(1080), option(720))
        assertEquals(1080, VideoQualityResolver.resolve(VideoQuality.P1440, standardHdOptions).selected?.height)

        // Low-tier fallback: 360p falls back to 240p
        val lowTierOptions = listOf(option(240), option(144))
        assertEquals(240, VideoQualityResolver.resolve(VideoQuality.P360, lowTierOptions).selected?.height)

        // 240p falls back to 144p
        val bottomOptions = listOf(option(144))
        assertEquals(144, VideoQualityResolver.resolve(VideoQuality.P240, bottomOptions).selected?.height)
    }

    @Test
    fun `requested quality below all tracks falls back to the lowest track`() {
        val options = listOf(option(720), option(360))
        assertEquals(360, VideoQualityResolver.resolve(VideoQuality.P360, options).selected?.height)

        // 144p requested when lowest available is 720p or 1080p
        val hdOnlyOptions = listOf(option(2160), option(1080), option(720))
        assertEquals(720, VideoQualityResolver.resolve(VideoQuality.P144, hdOnlyOptions).selected?.height)
        assertEquals(720, VideoQualityResolver.resolve(VideoQuality.P240, hdOnlyOptions).selected?.height)
    }

    @Test
    fun `auto does not produce a manual override even with ultra-high definition tracks`() {
        val selection = VideoQualityResolver.resolve(
            VideoQuality.AUTO,
            listOf(option(4320), option(2160), option(1080), option(720))
        )

        assertEquals(VideoQuality.AUTO, selection.requested)
        assertNull(selection.selected)
    }

    @Test
    fun `empty options returns null selected track gracefully`() {
        for (quality in VideoQuality.entries) {
            val selection = VideoQualityResolver.resolve(quality, emptyList())
            assertEquals(quality, selection.requested)
            assertNull(selection.selected)
        }
        assertEquals(emptyList<Int>(), VideoQualityResolver.availableHeights(emptyList()))
    }

    @Test
    fun `duplicate heights tie-break by highest bitrate then width then frame rate`() {
        val lowBitrate4k = option(2160, bitrate = 15_000_000, width = 3840, frameRate = 30f)
        val highBitrate4k = option(2160, bitrate = 30_000_000, width = 3840, frameRate = 60f)
        val midBitrate4k = option(2160, bitrate = 22_000_000, width = 3840, frameRate = 60f)

        val options = listOf(lowBitrate4k, highBitrate4k, midBitrate4k)
        val selection = VideoQualityResolver.resolve(VideoQuality.P2160, options)

        assertNotNull(selection.selected)
        assertEquals(2160, selection.selected?.height)
        assertEquals(30_000_000, selection.selected?.bitrate)
        assertEquals(60f, selection.selected?.frameRate)
    }

    @Test
    fun `enumeration keeps the Media3 group and track index`() {
        val group = TrackGroup(
            Format.Builder()
                .setId("low")
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .build(),
            Format.Builder()
                .setId("high")
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1280)
                .setHeight(720)
                .build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    group,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, true)
                )
            )
        )

        val selected = VideoQualityResolver.selectableTracks(tracks)
            .single { it.height == 720 }
        assertEquals(group, selected.group)
        assertEquals(1, selected.trackIndex)
    }

    @Test
    fun `selectableTracks ignores non-video groups, unsupported tracks, and invalid heights`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_valid_8k").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(7680).setHeight(4320).build(),
            Format.Builder().setId("v_zero_height").setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(0).build(),
            Format.Builder().setId("v_neg_height").setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(-1).build(),
            Format.Builder().setId("v_unsupported").setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(3840).setHeight(2160).build()
        )
        val audioGroup = TrackGroup(
            Format.Builder().setId("a1").setSampleMimeType(MimeTypes.AUDIO_AAC).build()
        )

        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE),
                    booleanArrayOf(true, false, false, false)
                ),
                Tracks.Group(
                    audioGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(true)
                )
            )
        )

        val selectable = VideoQualityResolver.selectableTracks(tracks)
        assertEquals(1, selectable.size)
        assertEquals(4320, selectable.single().height)
    }

    @Test
    fun `video quality enum ordering and backward compatible fromName deserialization`() {
        val expectedOrder = listOf(
            VideoQuality.AUTO,
            VideoQuality.P4320,
            VideoQuality.P2160,
            VideoQuality.P1440,
            VideoQuality.P1080,
            VideoQuality.P720,
            VideoQuality.P480,
            VideoQuality.P360,
            VideoQuality.P240,
            VideoQuality.P144
        )
        assertEquals(expectedOrder, VideoQuality.defaultQualityTiers)

        // Existing persisted preferences deserialization
        assertEquals(VideoQuality.AUTO, VideoQuality.fromName("AUTO"))
        assertEquals(VideoQuality.P1080, VideoQuality.fromName("P1080"))
        assertEquals(VideoQuality.P720, VideoQuality.fromName("P720"))
        assertEquals(VideoQuality.P480, VideoQuality.fromName("P480"))
        assertEquals(VideoQuality.P360, VideoQuality.fromName("P360"))

        // New persisted preferences deserialization
        assertEquals(VideoQuality.P4320, VideoQuality.fromName("P4320"))
        assertEquals(VideoQuality.P2160, VideoQuality.fromName("P2160"))
        assertEquals(VideoQuality.P1440, VideoQuality.fromName("P1440"))
        assertEquals(VideoQuality.P240, VideoQuality.fromName("P240"))
        assertEquals(VideoQuality.P144, VideoQuality.fromName("P144"))

        // Frame-rate aware quality deserialization
        assertEquals(VideoQuality.P4320_60, VideoQuality.fromName("P4320_60"))
        assertEquals(VideoQuality.P4320_30, VideoQuality.fromName("P4320_30"))
        assertEquals(VideoQuality.P2160_60, VideoQuality.fromName("P2160_60"))
        assertEquals(VideoQuality.P2160_30, VideoQuality.fromName("P2160_30"))
        assertEquals(VideoQuality.P1440_60, VideoQuality.fromName("P1440_60"))
        assertEquals(VideoQuality.P1440_30, VideoQuality.fromName("P1440_30"))
        assertEquals(VideoQuality.P1080_60, VideoQuality.fromName("P1080_60"))
        assertEquals(VideoQuality.P1080_30, VideoQuality.fromName("P1080_30"))
        assertEquals(VideoQuality.P720_60, VideoQuality.fromName("P720_60"))
        assertEquals(VideoQuality.P720_30, VideoQuality.fromName("P720_30"))

        // Unknown or corrupted values safely default to AUTO
        assertEquals(VideoQuality.AUTO, VideoQuality.fromName(null))
        assertEquals(VideoQuality.AUTO, VideoQuality.fromName(""))
        assertEquals(VideoQuality.AUTO, VideoQuality.fromName("UNKNOWN_VALUE"))
        assertEquals(VideoQuality.AUTO, VideoQuality.fromName("4K"))
        assertEquals(VideoQuality.AUTO, VideoQuality.fromName("8K"))
    }

    @Test
    fun `selectableTracks filters out representations exceeding capabilityChecker limits`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_8k").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(7680).setHeight(4320).build(),
            Format.Builder().setId("v_4k").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(3840).setHeight(2160).build(),
            Format.Builder().setId("v_1080p").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build(),
            Format.Builder().setId("v_720p").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1280).setHeight(720).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false, false, false)
                )
            )
        )

        // Capability checker that only supports up to 1080p
        val fake1080pChecker = VideoDecoderCapabilityChecker { _, _, _, height, _ ->
            height <= 1080
        }

        val selectable = VideoQualityResolver.selectableTracks(tracks, fake1080pChecker)
        assertEquals(listOf(1080, 720), selectable.map { it.height })
        assertEquals(listOf(1080, 720), VideoQualityResolver.availableHeights(selectable))

        // When 8K (P4320) or 4K (P2160) is requested on this device, it falls back to 1080p
        val selection8k = VideoQualityResolver.resolve(VideoQuality.P4320, selectable)
        assertEquals(VideoQuality.P4320, selection8k.requested)
        assertEquals(1080, selection8k.selected?.height)

        val selection4k = VideoQualityResolver.resolve(VideoQuality.P2160, selectable)
        assertEquals(VideoQuality.P2160, selection4k.requested)
        assertEquals(1080, selection4k.selected?.height)
    }

    @Test
    fun `selectableTracks omits representations with codecs rejected by capability checker`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_av1_1080").setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(1920).setHeight(1080).build(),
            Format.Builder().setId("v_vp9_1080").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                    booleanArrayOf(false, false)
                )
            )
        )

        // Checker that supports VP9 but not AV1
        val checkerNoAv1 = VideoDecoderCapabilityChecker { mimeType, _, _, _, _ ->
            mimeType == MimeTypes.VIDEO_VP9
        }

        val selectable = VideoQualityResolver.selectableTracks(tracks, checkerNoAv1)
        assertEquals(1, selectable.size)
        assertEquals(videoGroup, selectable.single().group)
        assertEquals(1, selectable.single().trackIndex) // VP9 is at index 1
    }

    @Test
    fun `allAvailableTracks discovers 8K and 4K tracks across all support levels while preserving formatSupport classification`() {
        val videoGroup = TrackGroup(
            Format.Builder().setId("v_8k_av1").setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(7680).setHeight(4320).build(),
            Format.Builder().setId("v_8k_unmapped").setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(7680).setHeight(4320).build(),
            Format.Builder().setId("v_4k_vp9").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(3840).setHeight(2160).build(),
            Format.Builder().setId("v_1080").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(1080).build(),
            Format.Builder().setId("v_invalid_height").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(1920).setHeight(0).build()
        )
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    videoGroup,
                    true,
                    intArrayOf(
                        C.FORMAT_UNSUPPORTED_SUBTYPE, // 8K AV1 on phone without AV1 decoder
                        C.FORMAT_UNSUPPORTED_TYPE,    // 8K in unmapped tracks
                        C.FORMAT_EXCEEDS_CAPABILITIES,// 4K VP9 exceeding decoder limits
                        C.FORMAT_HANDLED,             // 1080p fully handled
                        C.FORMAT_HANDLED              // Invalid height format
                    ),
                    booleanArrayOf(false, false, false, false, false)
                )
            )
        )

        val discovered = VideoQualityResolver.allAvailableTracks(tracks)
        // Invalid height is excluded -> 4 valid tracks remain
        assertEquals(4, discovered.size)
        assertEquals(listOf(4320, 4320, 2160, 1080), discovered.map { it.height })
        assertEquals(listOf(4320, 2160, 1080), VideoQualityResolver.availableHeights(discovered))

        // Check formatSupport classifications are accurately preserved
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, discovered[0].formatSupport)
        assertFalse(discovered[0].isPlayable)
        assertFalse(discovered[0].isDecoderSafe)

        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, discovered[1].formatSupport)
        assertFalse(discovered[1].isPlayable)
        assertFalse(discovered[1].isDecoderSafe)

        assertEquals(C.FORMAT_EXCEEDS_CAPABILITIES, discovered[2].formatSupport)
        assertTrue(discovered[2].isPlayable)
        assertFalse(discovered[2].isDecoderSafe)

        assertEquals(C.FORMAT_HANDLED, discovered[3].formatSupport)
        assertTrue(discovered[3].isPlayable)
        assertTrue(discovered[3].isDecoderSafe)

        // Verify qualities contain 8K, 4K, 1080p
        val qualities = VideoQualityResolver.availableQualities(discovered)
        assertTrue(qualities.contains(VideoQuality.P4320))
        assertTrue(qualities.contains(VideoQuality.P2160))
        assertTrue(qualities.contains(VideoQuality.P1080))

        // Auto selection continues excluding unsupported representations
        val autoSelectable = VideoQualityResolver.selectableTracks(tracks, null)
        assertEquals(1, autoSelectable.size)
        assertEquals(1080, autoSelectable.single().height)
        assertEquals(C.FORMAT_HANDLED, autoSelectable.single().formatSupport)

        // Manual selection for 8K preserves TrackGroup and track index
        val manual8k = VideoQualityResolver.resolve(VideoQuality.P4320, discovered)
        assertNotNull(manual8k.selected)
        assertEquals(videoGroup, manual8k.selected!!.group)
        assertEquals(4320, manual8k.selected!!.height)
        assertFalse(manual8k.selected!!.isPlayable) // Accurately reports not playable
    }

    @Test
    fun `resolve prioritizes playable track over unplayable track at same resolution`() {
        val group = TrackGroup(
            Format.Builder().setId("unsupported_av1_4k").setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(3840).setHeight(2160).setAverageBitrate(20_000_000).build(),
            Format.Builder().setId("playable_vp9_4k").setSampleMimeType(MimeTypes.VIDEO_VP9).setWidth(3840).setHeight(2160).setAverageBitrate(15_000_000).build()
        )
        val unplayableTrack = SelectableVideoTrack(
            group = group,
            trackIndex = 0,
            height = 2160,
            bitrate = 20_000_000,
            width = 3840,
            frameRate = 30f,
            mimeType = MimeTypes.VIDEO_AV1,
            formatSupport = C.FORMAT_UNSUPPORTED_TYPE
        )
        val playableTrack = SelectableVideoTrack(
            group = group,
            trackIndex = 1,
            height = 2160,
            bitrate = 15_000_000,
            width = 3840,
            frameRate = 30f,
            mimeType = MimeTypes.VIDEO_VP9,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES
        )

        val selection = VideoQualityResolver.resolve(VideoQuality.P2160, listOf(unplayableTrack, playableTrack))
        assertEquals(playableTrack, selection.selected)
        assertTrue(selection.selected!!.isPlayable)
    }

    @Test
    fun `resolveAutoGroup returns null on empty options`() {
        assertNull(VideoQualityResolver.resolveAutoGroup(emptyList()))
    }

    @Test
    fun `resolveAutoGroup gathers all eligible indices in chosen group for adaptive playback`() {
        val group = TrackGroup(
            Format.Builder().setId("1080").setWidth(1920).setHeight(1080).setAverageBitrate(5_000_000).build(),
            Format.Builder().setId("720").setWidth(1280).setHeight(720).setAverageBitrate(2_500_000).build(),
            Format.Builder().setId("360").setWidth(640).setHeight(360).setAverageBitrate(800_000).build()
        )
        val options = listOf(
            SelectableVideoTrack(group, trackIndex = 0, height = 1080, bitrate = 5_000_000, width = 1920, frameRate = 30f),
            SelectableVideoTrack(group, trackIndex = 1, height = 720, bitrate = 2_500_000, width = 1280, frameRate = 30f),
            SelectableVideoTrack(group, trackIndex = 2, height = 360, bitrate = 800_000, width = 640, frameRate = 30f)
        )

        val autoGroup = VideoQualityResolver.resolveAutoGroup(options)
        assertNotNull(autoGroup)
        assertEquals(group, autoGroup!!.targetGroup)
        assertEquals(listOf(0, 1, 2), autoGroup.eligibleTrackIndices)
        assertEquals(1080, autoGroup.maxEligibleHeight)
        assertEquals(1920, autoGroup.maxEligibleWidth)
    }

    @Test
    fun `resolveAutoGroup selects the highest-capability group and preserves non-contiguous eligible indices`() {
        val lowerGroup = TrackGroup(
            Format.Builder().setId("low_720").setWidth(1280).setHeight(720).setAverageBitrate(2_000_000).build()
        )
        val higherGroup = TrackGroup(
            Format.Builder().setId("high_4k").setWidth(3840).setHeight(2160).setAverageBitrate(15_000_000).build(), // uneligible (index 0)
            Format.Builder().setId("high_1080").setWidth(1920).setHeight(1080).setAverageBitrate(6_000_000).build(), // eligible (index 1)
            Format.Builder().setId("high_720_bad").setWidth(1280).setHeight(720).setAverageBitrate(3_000_000).build(), // uneligible (index 2)
            Format.Builder().setId("high_360").setWidth(640).setHeight(360).setAverageBitrate(1_000_000).build() // eligible (index 3)
        )

        val options = listOf(
            SelectableVideoTrack(lowerGroup, trackIndex = 0, height = 720, bitrate = 2_000_000, width = 1280, frameRate = 30f),
            SelectableVideoTrack(higherGroup, trackIndex = 1, height = 1080, bitrate = 6_000_000, width = 1920, frameRate = 30f),
            SelectableVideoTrack(higherGroup, trackIndex = 3, height = 360, bitrate = 1_000_000, width = 640, frameRate = 30f)
        )

        val autoGroup = VideoQualityResolver.resolveAutoGroup(options)
        assertNotNull(autoGroup)
        assertEquals(higherGroup, autoGroup!!.targetGroup)
        assertEquals(listOf(1, 3), autoGroup.eligibleTrackIndices)
        assertEquals(1080, autoGroup.maxEligibleHeight)
        assertEquals(1920, autoGroup.maxEligibleWidth)
    }

    @Test
    fun `resolveAutoGroup tie-breaks equal max heights by bitrate`() {
        val groupLowBitrate = TrackGroup(
            Format.Builder().setId("1080_low").setWidth(1920).setHeight(1080).setAverageBitrate(3_000_000).build()
        )
        val groupHighBitrate = TrackGroup(
            Format.Builder().setId("1080_high").setWidth(1920).setHeight(1080).setAverageBitrate(6_000_000).build()
        )

        val options = listOf(
            SelectableVideoTrack(groupLowBitrate, trackIndex = 0, height = 1080, bitrate = 3_000_000, width = 1920, frameRate = 30f),
            SelectableVideoTrack(groupHighBitrate, trackIndex = 0, height = 1080, bitrate = 6_000_000, width = 1920, frameRate = 30f)
        )

        val autoGroup = VideoQualityResolver.resolveAutoGroup(options)
        assertNotNull(autoGroup)
        assertEquals(groupHighBitrate, autoGroup!!.targetGroup)
        assertEquals(6_000_000, options.first { it.group == autoGroup.targetGroup }.bitrate)
    }

    @Test
    fun `VideoQuality fromTrack categorizes frame rates into 60fps, 30fps, or unknown`() {
        // >= 45 fps -> 60 FPS
        assertEquals(VideoQuality.P1080_60, VideoQuality.fromTrack(1080, 60.0f))
        assertEquals(VideoQuality.P1080_60, VideoQuality.fromTrack(1080, 59.94f))
        assertEquals(VideoQuality.P1080_60, VideoQuality.fromTrack(1080, 50.0f))
        assertEquals(VideoQuality.P1080_60, VideoQuality.fromTrack(1080, 48.0f))
        assertEquals(VideoQuality.P1080_60, VideoQuality.fromTrack(1080, 45.0f))

        // 15 fps until < 45 fps -> 30 FPS
        assertEquals(VideoQuality.P1080_30, VideoQuality.fromTrack(1080, 44.9f))
        assertEquals(VideoQuality.P1080_30, VideoQuality.fromTrack(1080, 30.0f))
        assertEquals(VideoQuality.P1080_30, VideoQuality.fromTrack(1080, 29.97f))
        assertEquals(VideoQuality.P1080_30, VideoQuality.fromTrack(1080, 25.0f))
        assertEquals(VideoQuality.P1080_30, VideoQuality.fromTrack(1080, 24.0f))
        assertEquals(VideoQuality.P1080_30, VideoQuality.fromTrack(1080, 23.976f))
        assertEquals(VideoQuality.P1080_30, VideoQuality.fromTrack(1080, 15.0f))

        // <= 0 or < 15 fps -> resolution only, no FPS suffix
        assertEquals(VideoQuality.P1080, VideoQuality.fromTrack(1080, 0f))
        assertEquals(VideoQuality.P1080, VideoQuality.fromTrack(1080, -1f))
        assertEquals(VideoQuality.P1080, VideoQuality.fromTrack(1080, 10f))

        // Non-finite frame rates -> resolution only, no FPS suffix
        assertEquals(VideoQuality.P1080, VideoQuality.fromTrack(1080, Float.POSITIVE_INFINITY))
        assertEquals(VideoQuality.P1080, VideoQuality.fromTrack(1080, Float.NEGATIVE_INFINITY))
        assertEquals(VideoQuality.P1080, VideoQuality.fromTrack(1080, Float.NaN))
        assertEquals("1080p", VideoQuality.fromTrack(1080, Float.POSITIVE_INFINITY).label)
        assertEquals("1080p", VideoQuality.fromTrack(1080, Float.NEGATIVE_INFINITY).label)
        assertEquals("1080p", VideoQuality.fromTrack(1080, Float.NaN).label)

        assertEquals("1080p", VideoQuality.P1080.label)
        assertEquals("1080p · 60 FPS", VideoQuality.P1080_60.label)
        assertEquals("1080p · 30 FPS", VideoQuality.P1080_30.label)
    }

    @Test
    fun `non-finite frame rates do not produce 60fps or 30fps options in availableQualities or resolve`() {
        val infTrack = option(1080, frameRate = Float.POSITIVE_INFINITY)
        val negInfTrack = option(1080, frameRate = Float.NEGATIVE_INFINITY)
        val nanTrack = option(720, frameRate = Float.NaN)
        val options = listOf(infTrack, negInfTrack, nanTrack)

        val qualities = VideoQualityResolver.availableQualities(options)
        assertEquals(listOf(VideoQuality.P1080, VideoQuality.P720), qualities)
        assertEquals("1080p", qualities[0].label)
        assertEquals("720p", qualities[1].label)

        // Resolving 60fps or 30fps on non-finite track falls back safely without claiming 60fps or 30fps
        val selection60 = VideoQualityResolver.resolve(VideoQuality.P1080_60, listOf(infTrack))
        assertEquals(VideoQuality.P1080, selection60.actualQuality)
        assertEquals("1080p", selection60.actualQuality.label)

        val selection30 = VideoQualityResolver.resolve(VideoQuality.P1080_30, listOf(negInfTrack))
        assertEquals(VideoQuality.P1080, selection30.actualQuality)
        assertEquals("1080p", selection30.actualQuality.label)
    }

    @Test
    fun `availableQualities distinguishes 1080p60 and 1080p30 representations`() {
        val options = listOf(
            option(1080, frameRate = 60f),
            option(1080, frameRate = 30f),
            option(720, frameRate = 30f)
        )
        val qualities = VideoQualityResolver.availableQualities(options)
        assertEquals(
            listOf(VideoQuality.P1080_60, VideoQuality.P1080_30, VideoQuality.P720_30),
            qualities
        )
    }

    @Test
    fun `availableQualities does not show 60fps when only 30fps tracks exist`() {
        val options = listOf(
            option(1080, frameRate = 30f),
            option(720, frameRate = 29.97f),
            option(480, frameRate = 24f)
        )
        val qualities = VideoQualityResolver.availableQualities(options)
        assertEquals(
            listOf(VideoQuality.P1080_30, VideoQuality.P720_30, VideoQuality.P480_30),
            qualities
        )
    }

    @Test
    fun `availableQualities does not show 30fps when only 60fps tracks exist`() {
        val options = listOf(
            option(1080, frameRate = 60f),
            option(720, frameRate = 59.94f)
        )
        val qualities = VideoQualityResolver.availableQualities(options)
        assertEquals(
            listOf(VideoQuality.P1080_60, VideoQuality.P720_60),
            qualities
        )
    }

    @Test
    fun `availableQualities with unknown frame rate produces resolution-only option without fabricating FPS`() {
        val options = listOf(
            option(1080, frameRate = -1f),
            option(720, frameRate = 0f)
        )
        val qualities = VideoQualityResolver.availableQualities(options)
        assertEquals(
            listOf(VideoQuality.P1080, VideoQuality.P720),
            qualities
        )
        assertEquals("1080p", qualities[0].label)
        assertEquals("720p", qualities[1].label)
    }

    @Test
    fun `availableQualities deduplicates multiple codecs and bitrates for the same height and frame-rate tier`() {
        val options = listOf(
            option(1080, bitrate = 6_000_000, frameRate = 60f),
            option(1080, bitrate = 4_000_000, frameRate = 59.94f),
            option(1080, bitrate = 3_000_000, frameRate = 30f),
            option(1080, bitrate = 2_500_000, frameRate = 29.97f)
        )
        val qualities = VideoQualityResolver.availableQualities(options)
        assertEquals(
            listOf(VideoQuality.P1080_60, VideoQuality.P1080_30),
            qualities
        )
    }

    @Test
    fun `resolve with targetFps picks matching frame-rate track`() {
        val track60 = option(1080, bitrate = 6_000_000, frameRate = 60f)
        val track30 = option(1080, bitrate = 4_000_000, frameRate = 30f)
        val options = listOf(track60, track30)

        val selection60 = VideoQualityResolver.resolve(VideoQuality.P1080_60, options)
        assertEquals(track60, selection60.selected)
        assertEquals(VideoQuality.P1080_60, selection60.actualQuality)

        val selection30 = VideoQualityResolver.resolve(VideoQuality.P1080_30, options)
        assertEquals(track30, selection30.selected)
        assertEquals(VideoQuality.P1080_30, selection30.actualQuality)
    }

    @Test
    fun `resolve falling back to 30fps when 60fps unavailable sets actualQuality to 30fps and never claims 60fps`() {
        val track30 = option(1080, bitrate = 4_000_000, frameRate = 30f)
        val options = listOf(track30)

        val selection = VideoQualityResolver.resolve(VideoQuality.P1080_60, options)
        assertEquals(track30, selection.selected)
        assertEquals(VideoQuality.P1080_30, selection.actualQuality)
        assertEquals("1080p · 30 FPS", selection.actualQuality.label)
    }

    @Test
    fun `resolve on resolution-only request selects best candidate and accurately reflects actualQuality`() {
        val track60 = option(1080, bitrate = 6_000_000, frameRate = 60f)
        val track30 = option(1080, bitrate = 4_000_000, frameRate = 30f)
        val options = listOf(track30, track60)

        val selection = VideoQualityResolver.resolve(VideoQuality.P1080, options)
        assertEquals(track60, selection.selected)
        assertEquals(VideoQuality.P1080_60, selection.actualQuality)
    }

    @Test
    fun `resolve cross-resolution fallback preserves actual frame rate`() {
        val track720_60 = option(720, bitrate = 3_000_000, frameRate = 60f)
        val track720_30 = option(720, bitrate = 2_000_000, frameRate = 30f)

        val selection60 = VideoQualityResolver.resolve(VideoQuality.P1080_60, listOf(track720_60, track720_30))
        assertEquals(track720_60, selection60.selected)
        assertEquals(VideoQuality.P720_60, selection60.actualQuality)

        val selection30 = VideoQualityResolver.resolve(VideoQuality.P1080_30, listOf(track720_30))
        assertEquals(track720_30, selection30.selected)
        assertEquals(VideoQuality.P720_30, selection30.actualQuality)
    }

    @Test
    fun `availableQualities never contains VideoQuality AUTO even with non-standard tracks`() {
        val oddTracks = listOf(
            option(1152, width = 2048, frameRate = 24f),
            option(804, width = 1920, frameRate = 24f),
            option(536, width = 1280, frameRate = 24f),
            option(360, width = 854, frameRate = 24f),
            option(270, width = 640, frameRate = 24f),
            option(180, width = 426, frameRate = 24f),
            option(108, width = 256, frameRate = 24f)
        )

        val qualities = VideoQualityResolver.availableQualities(oddTracks)
        assertFalse("availableQualities must never contain AUTO", qualities.contains(VideoQuality.AUTO))
        assertEquals("All items in availableQualities must be distinct", qualities.distinct(), qualities)
        assertTrue("All qualities must have valid requestedHeight", qualities.all { it.requestedHeight != null })
    }

    @Test
    fun `anamorphic widescreen 21-9 resolutions map cleanly to standard quality tiers`() {
        val widescreenTracks = listOf(
            option(804, width = 1920, frameRate = 30f),
            option(536, width = 1280, frameRate = 30f),
            option(360, width = 854, frameRate = 30f)
        )

        val qualities = VideoQualityResolver.availableQualities(widescreenTracks)
        assertEquals(
            listOf(VideoQuality.P1080_30, VideoQuality.P720_30, VideoQuality.P480_30),
            qualities
        )
        assertEquals("1080p · 30 FPS", qualities[0].label)
        assertEquals("720p · 30 FPS", qualities[1].label)
        assertEquals("480p · 30 FPS", qualities[2].label)
    }

    @Test
    fun `vertical video resolutions map cleanly to standard quality tiers`() {
        val verticalTracks = listOf(
            option(1920, width = 1080, frameRate = 60f),
            option(1280, width = 720, frameRate = 30f),
            option(854, width = 480, frameRate = 30f)
        )

        val qualities = VideoQualityResolver.availableQualities(verticalTracks)
        assertEquals(
            listOf(VideoQuality.P1080_60, VideoQuality.P720_30, VideoQuality.P480_30),
            qualities
        )
    }

    @Test
    fun `VideoQuality normalizeHeight and closestStandardHeight behave deterministically`() {
        assertEquals(1080, VideoQuality.normalizeHeight(1920, 1080))
        assertEquals(1080, VideoQuality.normalizeHeight(1920, 804))
        assertEquals(1080, VideoQuality.normalizeHeight(1080, 1920)) // Vertical 9:16
        assertEquals(720, VideoQuality.normalizeHeight(1280, 536))
        assertEquals(720, VideoQuality.normalizeHeight(720, 1280))
        assertEquals(1080, VideoQuality.closestStandardHeight(1152))
        assertEquals(720, VideoQuality.closestStandardHeight(718))
        assertEquals(360, VideoQuality.closestStandardHeight(356))
    }

    @Test
    fun `availableQualityOptions marks 8K and 4K as unsupported when capabilityChecker rejects them`() {
        val tracks = listOf(
            option(4320, frameRate = 60f, codecs = "av01.0.17M.08"),
            option(2160, frameRate = 60f, codecs = "av01.0.12M.08"),
            option(1080, frameRate = 60f, codecs = "avc1.64002a"),
            option(720, frameRate = 30f, codecs = "avc1.4d401f")
        )

        val cappedChecker = object : VideoDecoderCapabilityChecker {
            override fun isSupported(
                mimeType: String?,
                codecs: String?,
                width: Int,
                height: Int,
                frameRate: Float
            ): Boolean {
                // Device max decoding capability is 1080p
                return height <= 1080
            }
        }

        val options = VideoQualityResolver.availableQualityOptions(tracks, cappedChecker)

        val opt8k = options.firstOrNull { it.quality == VideoQuality.P4320_60 }
        val opt4k = options.firstOrNull { it.quality == VideoQuality.P2160_60 }
        val opt1080 = options.firstOrNull { it.quality == VideoQuality.P1080_60 }
        val opt720 = options.firstOrNull { it.quality == VideoQuality.P720_30 }

        assertTrue("8K option must be present in options list", opt8k != null)
        assertFalse("8K must be marked unsupported on capped device", opt8k!!.isSupported)
        assertEquals("Unsupported by device", opt8k.unsupportedReason)

        assertTrue("4K option must be present in options list", opt4k != null)
        assertFalse("4K must be marked unsupported on capped device", opt4k!!.isSupported)
        assertEquals("Unsupported by device", opt4k.unsupportedReason)

        assertTrue("1080p option must be present in options list", opt1080 != null)
        assertTrue("1080p must be marked supported on capped device", opt1080!!.isSupported)
        assertNull(opt1080.unsupportedReason)

        assertTrue("720p option must be present in options list", opt720 != null)
        assertTrue("720p must be marked supported on capped device", opt720!!.isSupported)
    }

    @Test
    fun `availableQualityOptions marks FORMAT_EXCEEDS_CAPABILITIES as unsupported`() {
        val exceedingTrack = option(
            height = 2160,
            frameRate = 60f,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES
        )
        val options = VideoQualityResolver.availableQualityOptions(listOf(exceedingTrack))

        assertEquals(1, options.size)
        assertEquals(VideoQuality.P2160_60, options[0].quality)
        assertFalse("Track with FORMAT_EXCEEDS_CAPABILITIES must not be marked supported", options[0].isSupported)
        assertEquals("Unsupported by device", options[0].unsupportedReason)
    }

    @Test
    fun `availableQualityOptions enforces strict fail-closed capability validation`() {
        val safeTrack = option(
            height = 1080,
            frameRate = 30f,
            formatSupport = C.FORMAT_HANDLED
        )
        val unsafeTrack = option(
            height = 720,
            frameRate = 30f,
            formatSupport = C.FORMAT_UNSUPPORTED_SUBTYPE
        )

        val acceptingChecker = VideoDecoderCapabilityChecker { _, _, _, _, _ -> true }
        val rejectingChecker = VideoDecoderCapabilityChecker { _, _, _, _, _ -> false }

        // 1. A null capability checker produces unsupported options
        val nullCheckerOptions = VideoQualityResolver.availableQualityOptions(
            listOf(safeTrack),
            capabilityChecker = null
        )
        assertEquals(1, nullCheckerOptions.size)
        assertFalse("Null capability checker must produce unsupported options", nullCheckerOptions[0].isSupported)
        assertEquals("Unsupported by device", nullCheckerOptions[0].unsupportedReason)

        // 2. A checker returning true permits an otherwise decoder-safe track
        val permittedOptions = VideoQualityResolver.availableQualityOptions(
            listOf(safeTrack),
            capabilityChecker = acceptingChecker
        )
        assertEquals(1, permittedOptions.size)
        assertTrue("Checker returning true permits decoder-safe track", permittedOptions[0].isSupported)
        assertNull(permittedOptions[0].unsupportedReason)

        // 3. A checker returning false rejects that track
        val rejectedOptions = VideoQualityResolver.availableQualityOptions(
            listOf(safeTrack),
            capabilityChecker = rejectingChecker
        )
        assertEquals(1, rejectedOptions.size)
        assertFalse("Checker returning false rejects track", rejectedOptions[0].isSupported)
        assertEquals("Unsupported by device", rejectedOptions[0].unsupportedReason)

        // 4. A track with isManualEligible == false remains unsupported even when the checker returns true
        val unsafeWithAcceptingChecker = VideoQualityResolver.availableQualityOptions(
            listOf(unsafeTrack),
            capabilityChecker = acceptingChecker
        )
        assertEquals(1, unsafeWithAcceptingChecker.size)
        assertFalse("Track with isManualEligible == false remains unsupported even with accepting checker", unsafeWithAcceptingChecker[0].isSupported)
        assertEquals("Unsupported by device", unsafeWithAcceptingChecker[0].unsupportedReason)

        // 5. Unsupported options remain visible in the returned list (not filtered out)
        val multiTierTracks = listOf(
            option(height = 1080, frameRate = 60f, formatSupport = C.FORMAT_HANDLED),
            option(height = 1080, frameRate = 30f, formatSupport = C.FORMAT_HANDLED),
            option(height = 720, frameRate = 30f, formatSupport = C.FORMAT_HANDLED)
        )
        val nullCheckerMulti = VideoQualityResolver.availableQualityOptions(multiTierTracks, capabilityChecker = null)
        assertEquals("All quality options must remain visible in the options list even when unsupported", 3, nullCheckerMulti.size)
        assertTrue(nullCheckerMulti.all { !it.isSupported })
        assertTrue(nullCheckerMulti.all { it.unsupportedReason == "Unsupported by device" })
    }

    @Test
    fun `resolve strictly prefers decoder-safe tracks over exceeding capability tracks`() {
        val exceedingTrack = option(
            height = 1080,
            bitrate = 5_000_000,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES
        )
        val decoderSafeTrack = option(
            height = 1080,
            bitrate = 2_000_000,
            formatSupport = C.FORMAT_HANDLED
        )

        val selection = VideoQualityResolver.resolve(VideoQuality.P1080, listOf(exceedingTrack, decoderSafeTrack))

        assertEquals("Must select decoder-safe track even if exceeding track has higher bitrate",
            decoderSafeTrack.bitrate, selection.selected?.bitrate)
        assertTrue(selection.selected?.isDecoderSafe == true)
    }

    @Test
    fun `4K60 track exceeding decoder capabilities is reported as unsupported and disabled without fabricating 4K30`() {
        val track4k60Exceeds = option(
            height = 2160,
            width = 3840,
            frameRate = 60f,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES,
            mimeType = "video/webm",
            codecs = "vp9"
        )
        val track1440p60Handled = option(
            height = 1440,
            width = 2560,
            frameRate = 60f,
            formatSupport = C.FORMAT_HANDLED,
            mimeType = "video/webm",
            codecs = "vp9"
        )

        val capabilityChecker = VideoDecoderCapabilityChecker { mime, codecs, width, height, frameRate ->
            // Simulates hardware decoder supporting up to 1440p60 or 4K30
            val is4k = width > 2560 || height > 1440
            if (is4k) frameRate <= 30.5f else frameRate <= 60.5f
        }

        val options = VideoQualityResolver.availableQualityOptions(
            listOf(track4k60Exceeds, track1440p60Handled),
            capabilityChecker = capabilityChecker
        )

        // 4K60 is present but unsupported
        val opt4k60 = options.find { it.quality == VideoQuality.P2160_60 }
        assertNotNull(opt4k60)
        assertFalse(opt4k60!!.isSupported)
        assertEquals("Unsupported by device", opt4k60.unsupportedReason)

        // 4K30 must NOT be fabricated when not provided by source
        val opt4k30 = options.find { it.quality == VideoQuality.P2160_30 }
        assertNull("Must not invent a 4K30 option if YouTube did not provide one", opt4k30)

        // 1440p60 is supported
        val opt1440p60 = options.find { it.quality == VideoQuality.P1440_60 }
        assertNotNull(opt1440p60)
        assertTrue(opt1440p60!!.isSupported)
        assertNull(opt1440p60.unsupportedReason)
    }

    @Test
    fun `when source provides both 4K60 and 4K30, constrained decoder marks 4K30 supported and 4K60 unsupported`() {
        val track4k60 = option(
            height = 2160,
            width = 3840,
            frameRate = 60f,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES,
            mimeType = "video/webm",
            codecs = "vp9"
        )
        val track4k30 = option(
            height = 2160,
            width = 3840,
            frameRate = 30f,
            formatSupport = C.FORMAT_HANDLED,
            mimeType = "video/webm",
            codecs = "vp9"
        )

        val capabilityChecker = VideoDecoderCapabilityChecker { _, _, width, height, frameRate ->
            val is4k = width > 2560 || height > 1440
            if (is4k) frameRate <= 30.5f else frameRate <= 60.5f
        }

        val options = VideoQualityResolver.availableQualityOptions(
            listOf(track4k60, track4k30),
            capabilityChecker = capabilityChecker
        )

        val opt4k60 = options.find { it.quality == VideoQuality.P2160_60 }
        assertNotNull(opt4k60)
        assertFalse(opt4k60!!.isSupported)

        val opt4k30 = options.find { it.quality == VideoQuality.P2160_30 }
        assertNotNull(opt4k30)
        assertTrue(opt4k30!!.isSupported)
    }

    @Test
    fun `8K source representation produces visible 4320p quality option in availableQualityOptions`() {
        val track8k = option(
            height = 4320,
            width = 7680,
            frameRate = 60f,
            formatSupport = C.FORMAT_EXCEEDS_CAPABILITIES,
            mimeType = "video/mp4",
            codecs = "av01.0.17M.08"
        )

        val capabilityChecker = VideoDecoderCapabilityChecker { _, _, _, _, _ -> false }

        val options = VideoQualityResolver.availableQualityOptions(
            listOf(track8k),
            capabilityChecker = capabilityChecker
        )

        val opt8k = options.find { it.quality == VideoQuality.P4320_60 }
        assertNotNull("8K representation must appear in quality options", opt8k)
        assertFalse("8K on unsupportive device must be disabled", opt8k!!.isSupported)
        assertEquals("Unsupported by device", opt8k.unsupportedReason)
    }

    private fun option(
        height: Int,
        bitrate: Int = 1_000_000,
        width: Int = height * 16 / 9,
        frameRate: Float = 30f,
        formatSupport: Int = C.FORMAT_HANDLED,
        mimeType: String? = MimeTypes.VIDEO_H264,
        codecs: String? = null
    ): SelectableVideoTrack {
        val format = Format.Builder()
            .setId("$height-$bitrate")
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
}
