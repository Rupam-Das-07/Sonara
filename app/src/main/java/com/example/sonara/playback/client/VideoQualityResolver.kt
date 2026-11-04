package com.example.sonara.playback.client

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.example.sonara.domain.model.video.VideoQuality
import com.example.sonara.domain.model.video.VideoQualityOption

data class SelectableVideoTrack(
    val group: TrackGroup,
    val trackIndex: Int,
    val height: Int,
    val bitrate: Int,
    val width: Int,
    val frameRate: Float,
    val mimeType: String? = null,
    val codecs: String? = null,
    val formatSupport: Int = C.FORMAT_HANDLED
) {
    /** True if Media3 classifies this format as fully supported for adaptive auto selection. */
    val isAutoEligible: Boolean
        get() = formatSupport == C.FORMAT_HANDLED

    /** True if Media3 classifies this format as capable of playback (handled or exceeds conservative limits). */
    val isManualEligible: Boolean
        get() = formatSupport == C.FORMAT_HANDLED || formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES

    /** Backwards-compatible alias for [isManualEligible]. */
    val isPlayable: Boolean
        get() = isManualEligible

    /** Backwards-compatible alias for [isAutoEligible]. */
    val isDecoderSafe: Boolean
        get() = isAutoEligible
}

data class VideoQualitySelection(
    val requested: VideoQuality,
    val selected: SelectableVideoTrack?
) {
    val actualQuality: VideoQuality
        get() = when {
            requested == VideoQuality.AUTO -> VideoQuality.AUTO
            selected == null -> VideoQuality.AUTO
            else -> VideoQuality.fromTrack(selected.width, selected.height, selected.frameRate)
        }
}

data class AutoQualityTrackGroupSelection(
    val targetGroup: TrackGroup,
    val eligibleTrackIndices: List<Int>,
    val maxEligibleWidth: Int,
    val maxEligibleHeight: Int
)

/** Media3-backed quality enumeration and the bounded resolution fallback rules. */
object VideoQualityResolver {
    @Volatile
    var defaultCapabilityChecker: VideoDecoderCapabilityChecker = AndroidVideoDecoderCapabilityChecker()

    fun selectableTracks(
        tracks: Tracks,
        capabilityChecker: VideoDecoderCapabilityChecker? = null
    ): List<SelectableVideoTrack> = buildList {
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                if (format.height <= 0) continue

                val mimeType = format.sampleMimeType ?: format.containerMimeType
                val codecs = format.codecs
                val height = format.height
                val width = if (format.width > 0) format.width else ((height * 16) / 9)
                val frameRate = format.frameRate
                val effectiveFps = if (frameRate > 0f && frameRate.isFinite()) frameRate else 0f

                if (capabilityChecker != null && !capabilityChecker.isSupported(mimeType, codecs, width, height, effectiveFps)) {
                    continue
                }

                add(
                    SelectableVideoTrack(
                        group = group.mediaTrackGroup,
                        trackIndex = index,
                        height = height,
                        bitrate = format.bitrate,
                        width = width,
                        frameRate = frameRate,
                        mimeType = mimeType,
                        codecs = codecs,
                        formatSupport = group.getTrackSupport(index)
                    )
                )
            }
        }
    }

    /**
     * Enumerates actual video representations from Media3's track groups.
     * Unlike [selectableTracks], this performs source-representation discovery rather than
     * decoder-capability filtering. Genuine video representations parsed from the media manifest
     * (including 4K and 8K) are discovered even when they exceed device capabilities or lack
     * a supported decoder on the current renderer (e.g. C.FORMAT_EXCEEDS_CAPABILITIES,
     * C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_UNSUPPORTED_TYPE).
     * Preserves the actual Media3 support classification in [SelectableVideoTrack.formatSupport].
     */
    fun allAvailableTracks(
        tracks: Tracks
    ): List<SelectableVideoTrack> = buildList {
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                if (format.height <= 0) continue

                val support = group.getTrackSupport(index)
                val mimeType = format.sampleMimeType ?: format.containerMimeType
                val codecs = format.codecs
                val height = format.height
                val width = if (format.width > 0) format.width else ((height * 16) / 9)
                val frameRate = format.frameRate

                add(
                    SelectableVideoTrack(
                        group = group.mediaTrackGroup,
                        trackIndex = index,
                        height = height,
                        bitrate = format.bitrate,
                        width = width,
                        frameRate = frameRate,
                        mimeType = mimeType,
                        codecs = codecs,
                        formatSupport = support
                    )
                )
            }
        }
    }

    fun availableHeights(options: List<SelectableVideoTrack>): List<Int> =
        options.map { it.height }.distinct().sortedDescending()

    /**
     * Enumerates distinct eligible VideoQuality choices along with device decoder support
     * for display in the quality dropdown.
     * Evaluates manual selection capability per individual representation.
     */
    fun availableQualityOptions(
        options: List<SelectableVideoTrack>,
        capabilityChecker: VideoDecoderCapabilityChecker? = null
    ): List<VideoQualityOption> = buildList {
        val byHeight = options.groupBy { track ->
            VideoQuality.normalizeHeight(track.width, track.height)
        }
        val sortedHeights = byHeight.keys.filter { it > 0 }.sortedDescending()
        for (height in sortedHeights) {
            val tracks = byHeight[height].orEmpty()
            val has60 = tracks.any { it.frameRate.isFinite() && it.frameRate >= 45f }
            val has30 = tracks.any { it.frameRate.isFinite() && it.frameRate in 15f..<45f }

            fun evaluateSupport(predicate: (SelectableVideoTrack) -> Boolean): Pair<Boolean, String?> {
                val candidateTracks = tracks.filter(predicate)
                if (candidateTracks.isEmpty()) return false to "Unsupported by device"
                val supported = candidateTracks.any { track ->
                    if (!track.isManualEligible) return@any false
                    if (capabilityChecker == null) return@any false
                    val effectiveFps = if (track.frameRate > 0f && track.frameRate.isFinite()) track.frameRate else 0f
                    val cap = capabilityChecker.evaluate(
                        track.mimeType,
                        track.codecs,
                        track.width,
                        track.height,
                        effectiveFps
                    )
                    cap.isManuallySelectable
                }
                return if (supported) true to null else false to "Unsupported by device"
            }

            when {
                has60 && has30 -> {
                    val (sup60, reason60) = evaluateSupport { it.frameRate.isFinite() && it.frameRate >= 45f }
                    add(VideoQualityOption(VideoQuality.of(height, 60), isSupported = sup60, unsupportedReason = reason60))
                    val (sup30, reason30) = evaluateSupport { it.frameRate.isFinite() && it.frameRate in 15f..<45f }
                    add(VideoQualityOption(VideoQuality.of(height, 30), isSupported = sup30, unsupportedReason = reason30))
                }
                has60 -> {
                    val (sup60, reason60) = evaluateSupport { it.frameRate.isFinite() && it.frameRate >= 45f }
                    add(VideoQualityOption(VideoQuality.of(height, 60), isSupported = sup60, unsupportedReason = reason60))
                }
                has30 -> {
                    val (sup30, reason30) = evaluateSupport { it.frameRate.isFinite() && it.frameRate in 15f..<45f }
                    add(VideoQualityOption(VideoQuality.of(height, 30), isSupported = sup30, unsupportedReason = reason30))
                }
                else -> {
                    val (sup, reason) = evaluateSupport { true }
                    add(VideoQualityOption(VideoQuality.of(height, null), isSupported = sup, unsupportedReason = reason))
                }
            }
        }
    }.filter { it.quality != VideoQuality.AUTO }.distinctBy { it.quality }

    /**
     * Enumerates distinct eligible VideoQuality choices for display in the quality dropdown.
     * Shows separate 30 FPS and 60 FPS options only when representations exist.
     * Never fabricates an FPS label if the frame rate metadata is unknown.
     * Maps non-standard and letterboxed representations to standard tiers and excludes AUTO.
     */
    fun availableQualities(options: List<SelectableVideoTrack>): List<VideoQuality> =
        availableQualityOptions(options).map { it.quality }

    fun resolve(
        requested: VideoQuality,
        options: List<SelectableVideoTrack>
    ): VideoQualitySelection {
        if (requested == VideoQuality.AUTO) return VideoQualitySelection(requested, null)
        val requestedHeight = requested.requestedHeight ?: return VideoQualitySelection(requested, null)
        val selectedHeight = options.map { it.height }.distinct().let { heights ->
            heights.filter { h ->
                val trackWidth = options.firstOrNull { it.height == h }?.width ?: 0
                val normalized = VideoQuality.normalizeHeight(trackWidth, h)
                normalized <= requestedHeight
            }.maxByOrNull { h ->
                val trackWidth = options.firstOrNull { it.height == h }?.width ?: 0
                VideoQuality.normalizeHeight(trackWidth, h)
            } ?: heights.minOrNull()
        } ?: return VideoQualitySelection(requested, null)

        val candidates = options.filter { it.height == selectedHeight }
        val targetFps = requested.targetFps

        val matchingCandidates = if (targetFps != null) {
            val fpsFiltered = when (targetFps) {
                60 -> candidates.filter { it.frameRate.isFinite() && it.frameRate >= 45f }
                30 -> candidates.filter { it.frameRate.isFinite() && it.frameRate in 15f..<45f }
                else -> emptyList()
            }
            if (fpsFiltered.isNotEmpty()) fpsFiltered else candidates
        } else {
            candidates
        }

        val selected = matchingCandidates.maxWithOrNull(
            compareBy<SelectableVideoTrack> { it.isAutoEligible }
                .thenBy { it.isManualEligible }
                .thenBy { it.bitrate }
                .thenBy { it.width }
                .thenBy { it.frameRate }
        )
        return VideoQualitySelection(requested, selected)
    }

    /**
     * Identifies the best eligible video TrackGroup and its eligible track indices for adaptive playback in AUTO mode.
     * Excludes tracks that were filtered out by Media3 renderer support or VideoDecoderCapabilityChecker.
     */
    fun resolveAutoGroup(
        options: List<SelectableVideoTrack>
    ): AutoQualityTrackGroupSelection? {
        val eligibleTracks = options.filter { it.isAutoEligible }
        if (eligibleTracks.isEmpty()) return null
        val eligibleByGroup = eligibleTracks.groupBy { it.group }
        val bestGroupEntry = eligibleByGroup.entries.maxWithOrNull(
            compareBy<Map.Entry<TrackGroup, List<SelectableVideoTrack>>> { entry ->
                entry.value.maxOfOrNull { it.height } ?: 0
            }.thenBy { entry ->
                entry.value.maxOfOrNull { it.bitrate } ?: 0
            }.thenBy { entry ->
                entry.value.maxOfOrNull { it.frameRate } ?: 0f
            }.thenBy { entry ->
                entry.value.size
            }
        ) ?: return null

        val targetGroup = bestGroupEntry.key
        val eligibleIndices = bestGroupEntry.value.map { it.trackIndex }.distinct().sorted()
        val maxHeight = bestGroupEntry.value.maxOf { it.height }
        val maxWidth = bestGroupEntry.value.filter { it.height == maxHeight }
            .maxOfOrNull { it.width } ?: Int.MAX_VALUE

        return AutoQualityTrackGroupSelection(
            targetGroup = targetGroup,
            eligibleTrackIndices = eligibleIndices,
            maxEligibleWidth = maxWidth,
            maxEligibleHeight = maxHeight
        )
    }
}
