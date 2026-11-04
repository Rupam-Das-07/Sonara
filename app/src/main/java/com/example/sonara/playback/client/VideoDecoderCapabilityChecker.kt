package com.example.sonara.playback.client

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Detailed evidence category supporting a capability determination.
 */
enum class CapabilityEvidence {
    /** Decoder explicitly affirms both dimension and frame rate capability in system metadata. */
    HARDWARE_PERFORMANCE_AFFIRMED,

    /** Hardware decoder supports dimensions and format; viable for direct surface rendering despite conservative declared rate. */
    HARDWARE_SURFACE_VIABLE,

    /** Software decoder explicitly affirms dimension and frame rate capability for standard resolutions. */
    SOFTWARE_AFFIRMED,

    /** Dimensions exceed all available decoders on device (e.g. 8K on a 4K-maximum decoder). */
    UNSUPPORTED_DIMENSIONS,

    /** No decoder on device supports this video MIME / codec. */
    UNSUPPORTED_CODEC,

    /** Frame rate is completely unsupported or cannot be sustained by software decoding. */
    UNSUPPORTED_FRAME_RATE,

    /** No candidate decoder found or query failed. */
    NO_DECODER_FOUND
}

/**
 * Universal capability evaluation result distinguishing automatic adaptive eligibility
 * from user-directed manual selection eligibility.
 */
data class VideoTrackCapability(
    val isAutoEligible: Boolean,
    val isManuallySelectable: Boolean,
    val unsupportedReason: String? = null,
    val capabilityEvidence: CapabilityEvidence = CapabilityEvidence.HARDWARE_PERFORMANCE_AFFIRMED
)

/**
 * Evaluates whether the device's video decoders can reliably decode a given video representation.
 */
fun interface VideoDecoderCapabilityChecker {
    /**
     * Returns true if the device exposes a video decoder supporting the specified MIME type,
     * codecs, dimensions, and frame rate for automatic playback.
     */
    fun isSupported(
        mimeType: String?,
        codecs: String?,
        width: Int,
        height: Int,
        frameRate: Float
    ): Boolean

    /**
     * Evaluates comprehensive decoder capabilities for the given representation,
     * distinguishing automatic eligibility from manual selection eligibility.
     */
    fun evaluate(
        mimeType: String?,
        codecs: String?,
        width: Int,
        height: Int,
        frameRate: Float
    ): VideoTrackCapability {
        val supported = isSupported(mimeType, codecs, width, height, frameRate)
        return VideoTrackCapability(
            isAutoEligible = supported,
            isManuallySelectable = supported,
            unsupportedReason = if (supported) null else "Unsupported by device",
            capabilityEvidence = if (supported) CapabilityEvidence.HARDWARE_PERFORMANCE_AFFIRMED else CapabilityEvidence.NO_DECODER_FOUND
        )
    }

    /**
     * Returns true if the representation can be manually selected by the user.
     */
    fun isManuallySelectable(
        mimeType: String?,
        codecs: String?,
        width: Int,
        height: Int,
        frameRate: Float
    ): Boolean = evaluate(mimeType, codecs, width, height, frameRate).isManuallySelectable
}

/**
 * Android MediaCodecList-backed decoder capability checker.
 *
 * Inspects system regular decoders and caches query results to avoid expensive
 * codec enumeration on track updates or recompositions.
 */
open class AndroidVideoDecoderCapabilityChecker(
    private val decoderEvaluator: ((mimeType: String, width: Int, height: Int, frameRate: Float) -> VideoTrackCapability)? = null,
    private val decoderQuery: ((mimeType: String, width: Int, height: Int, frameRate: Float) -> Boolean)? = null
) : VideoDecoderCapabilityChecker {

    private val effectiveEvaluator: (String, Int, Int, Float) -> VideoTrackCapability = when {
        decoderEvaluator != null -> decoderEvaluator
        decoderQuery != null -> { mime, w, h, fps ->
            val supported = decoderQuery(mime, w, h, fps)
            VideoTrackCapability(
                isAutoEligible = supported,
                isManuallySelectable = supported,
                unsupportedReason = if (supported) null else "Unsupported by device",
                capabilityEvidence = if (supported) CapabilityEvidence.HARDWARE_PERFORMANCE_AFFIRMED else CapabilityEvidence.NO_DECODER_FOUND
            )
        }
        else -> { mime, w, h, fps ->
            querySystemDecodersCapability(mime, w, h, fps)
        }
    }

    private data class CacheKey(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val fpsInt: Int
    )

    private val capabilityCache = ConcurrentHashMap<CacheKey, VideoTrackCapability>()

    override fun evaluate(
        mimeType: String?,
        codecs: String?,
        width: Int,
        height: Int,
        frameRate: Float
    ): VideoTrackCapability {
        if (width <= 0 || height <= 0 || frameRate < 0f || frameRate.isNaN()) {
            return VideoTrackCapability(
                isAutoEligible = false,
                isManuallySelectable = false,
                unsupportedReason = "Unsupported by device",
                capabilityEvidence = CapabilityEvidence.UNSUPPORTED_DIMENSIONS
            )
        }

        val canonicalMime = resolveCanonicalMime(mimeType, codecs) ?: return VideoTrackCapability(
            isAutoEligible = false,
            isManuallySelectable = false,
            unsupportedReason = "Unsupported by device",
            capabilityEvidence = CapabilityEvidence.UNSUPPORTED_CODEC
        )

        val fpsInt = if (frameRate > 0f) frameRate.toInt() else 0
        val key = CacheKey(canonicalMime, width, height, fpsInt)

        return capabilityCache.computeIfAbsent(key) {
            try {
                effectiveEvaluator(canonicalMime, width, height, frameRate)
            } catch (_: Throwable) {
                VideoTrackCapability(
                    isAutoEligible = false,
                    isManuallySelectable = false,
                    unsupportedReason = "Unsupported by device",
                    capabilityEvidence = CapabilityEvidence.NO_DECODER_FOUND
                )
            }
        }
    }

    override fun isSupported(
        mimeType: String?,
        codecs: String?,
        width: Int,
        height: Int,
        frameRate: Float
    ): Boolean = evaluate(mimeType, codecs, width, height, frameRate).isAutoEligible

    override fun isManuallySelectable(
        mimeType: String?,
        codecs: String?,
        width: Int,
        height: Int,
        frameRate: Float
    ): Boolean = evaluate(mimeType, codecs, width, height, frameRate).isManuallySelectable

    companion object {
        /**
         * Queries system decoders to build a comprehensive [VideoTrackCapability] for the given format.
         */
        fun querySystemDecodersCapability(
            mimeType: String,
            width: Int,
            height: Int,
            frameRate: Float
        ): VideoTrackCapability {
            if (Build.VERSION.SDK_INT == 0) {
                return VideoTrackCapability(
                    isAutoEligible = true,
                    isManuallySelectable = true,
                    unsupportedReason = null,
                    capabilityEvidence = CapabilityEvidence.HARDWARE_PERFORMANCE_AFFIRMED
                )
            }

            return try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                var anyCodecSupportsMime = false
                var anyCodecSupportsDimensions = false
                var bestCandidateCapability: VideoTrackCapability? = null

                for (codec in codecList.codecInfos) {
                    if (codec.isEncoder) continue
                    val caps = try {
                        codec.getCapabilitiesForType(mimeType)
                    } catch (_: Throwable) {
                        null
                    } ?: continue

                    anyCodecSupportsMime = true
                    val videoCaps = caps.videoCapabilities ?: continue

                    val isSizeSupported = try {
                        videoCaps.isSizeSupported(width, height)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!isSizeSupported) continue

                    anyCodecSupportsDimensions = true

                    val isHardware = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            codec.isHardwareAccelerated
                        } else {
                            !codec.name.startsWith("OMX.google.") && !codec.name.startsWith("c2.android.")
                        }
                    } catch (_: Throwable) {
                        false
                    }

                    // Direct size and frame rate support check
                    val isSizeAndRateSupported = try {
                        videoCaps.areSizeAndRateSupported(width, height, frameRate.toDouble())
                    } catch (_: Throwable) {
                        false
                    }

                    // API 29+ PerformancePoint coverage check
                    val hasPerformancePointCover = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val perfPoints = videoCaps.supportedPerformancePoints
                            if (!perfPoints.isNullOrEmpty()) {
                                val targetPoint = MediaCodecInfo.VideoCapabilities.PerformancePoint(
                                    width,
                                    height,
                                    frameRate.toInt()
                                )
                                perfPoints.any { it.covers(targetPoint) }
                            } else {
                                false
                            }
                        } catch (_: Throwable) {
                            false
                        }
                    } else {
                        false
                    }

                    // Achievable and supported frame rate ranges
                    val achievableRateSupported = try {
                        val achievable = videoCaps.getAchievableFrameRatesFor(width, height)
                        achievable != null && achievable.upper != null && achievable.upper >= frameRate.toDouble()
                    } catch (_: Throwable) {
                        false
                    }

                    val supportedRateSupported = try {
                        val supported = videoCaps.getSupportedFrameRatesFor(width, height)
                        supported != null && supported.upper != null && supported.upper >= frameRate.toDouble()
                    } catch (_: Throwable) {
                        false
                    }

                    val isRateAffirmed = when {
                        frameRate <= 0f -> true
                        isSizeAndRateSupported -> true
                        hasPerformancePointCover -> true
                        achievableRateSupported -> true
                        supportedRateSupported -> true
                        frameRate <= 30.5f -> true
                        else -> false
                    }

                    val isHighResolution = width >= 2560 || height >= 1440

                    if (isHardware) {
                        if (isRateAffirmed) {
                            // Hardware decoder affirms both size and rate: fully eligible for both auto and manual
                            return VideoTrackCapability(
                                isAutoEligible = true,
                                isManuallySelectable = true,
                                unsupportedReason = null,
                                capabilityEvidence = CapabilityEvidence.HARDWARE_PERFORMANCE_AFFIRMED
                            )
                        } else {
                            // Hardware decoder supports dimensions, but rate exceeds conservative vendor certification (e.g. 4K60 on Dimensity 1080)
                            // Surface direct-rendering is viable for user-selected manual playback, but excluded from auto
                            val candidate = VideoTrackCapability(
                                isAutoEligible = false,
                                isManuallySelectable = true,
                                unsupportedReason = null,
                                capabilityEvidence = CapabilityEvidence.HARDWARE_SURFACE_VIABLE
                            )
                            if (bestCandidateCapability == null || !bestCandidateCapability.isManuallySelectable) {
                                bestCandidateCapability = candidate
                            }
                        }
                    } else {
                        // Software decoder: mobile CPUs cannot sustain high-resolution (> 1080p) real-time decoding
                        if (!isHighResolution && isRateAffirmed) {
                            val candidate = VideoTrackCapability(
                                isAutoEligible = true,
                                isManuallySelectable = true,
                                unsupportedReason = null,
                                capabilityEvidence = CapabilityEvidence.SOFTWARE_AFFIRMED
                            )
                            if (bestCandidateCapability == null) {
                                bestCandidateCapability = candidate
                            }
                        } else if (!isHighResolution) {
                            val candidate = VideoTrackCapability(
                                isAutoEligible = false,
                                isManuallySelectable = true,
                                unsupportedReason = null,
                                capabilityEvidence = CapabilityEvidence.SOFTWARE_AFFIRMED
                            )
                            if (bestCandidateCapability == null) {
                                bestCandidateCapability = candidate
                            }
                        }
                    }
                }

                if (bestCandidateCapability != null) {
                    return bestCandidateCapability
                }

                if (!anyCodecSupportsMime) {
                    return VideoTrackCapability(
                        isAutoEligible = false,
                        isManuallySelectable = false,
                        unsupportedReason = "Unsupported by device",
                        capabilityEvidence = CapabilityEvidence.UNSUPPORTED_CODEC
                    )
                }

                if (!anyCodecSupportsDimensions) {
                    return VideoTrackCapability(
                        isAutoEligible = false,
                        isManuallySelectable = false,
                        unsupportedReason = "Unsupported by device",
                        capabilityEvidence = CapabilityEvidence.UNSUPPORTED_DIMENSIONS
                    )
                }

                return VideoTrackCapability(
                    isAutoEligible = false,
                    isManuallySelectable = false,
                    unsupportedReason = "Unsupported by device",
                    capabilityEvidence = CapabilityEvidence.UNSUPPORTED_FRAME_RATE
                )
            } catch (_: Throwable) {
                VideoTrackCapability(
                    isAutoEligible = false,
                    isManuallySelectable = false,
                    unsupportedReason = "Unsupported by device",
                    capabilityEvidence = CapabilityEvidence.NO_DECODER_FOUND
                )
            }
        }

        /**
         * Resolves the canonical video MIME type from MIME and codec string tokens.
         * Returns null if the codec or format cannot be determined reliably.
         */
        fun resolveCanonicalMime(mimeType: String?, codecs: String?): String? {
            val normalizedCodecs = codecs?.trim()?.lowercase()
            val normalizedMime = mimeType?.trim()?.lowercase()

            return when {
                normalizedCodecs?.startsWith("av01") == true || normalizedCodecs?.startsWith("av1") == true ->
                    "video/av01"
                normalizedCodecs?.startsWith("vp9") == true || normalizedCodecs?.startsWith("vp09") == true ->
                    "video/x-vnd.on2.vp9"
                normalizedCodecs?.startsWith("avc") == true || normalizedCodecs?.startsWith("h264") == true ->
                    "video/avc"
                normalizedCodecs?.startsWith("hev") == true || normalizedCodecs?.startsWith("h265") == true || normalizedCodecs?.startsWith("hvc") == true ->
                    "video/hevc"
                normalizedMime == "video/webm" && normalizedCodecs?.contains("vp8") == true ->
                    "video/x-vnd.on2.vp8"
                normalizedMime == "video/webm" ->
                    "video/x-vnd.on2.vp9"
                normalizedMime == "video/mp4" && normalizedCodecs.isNullOrBlank() ->
                    "video/avc"
                normalizedMime != null && normalizedMime.startsWith("video/") ->
                    normalizedMime
                else -> null
            }
        }
    }
}
