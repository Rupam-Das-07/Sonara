package com.example.sonara.feature.video

/**
 * Result of computing the fitted layout dimensions for a video inside a bounding container.
 *
 * @property width The resolved width in density-independent or pixel units.
 * @property height The resolved height in density-independent or pixel units.
 * @property aspectRatio The source aspect ratio (width / height) used for calculation.
 * @property isWidthConstrained True if width reached the container limit; false if height reached it.
 */
data class VideoLayoutResult(
    val width: Float,
    val height: Float,
    val aspectRatio: Float,
    val isWidthConstrained: Boolean
)

/**
 * Pure calculator for responsive video container sizing and aspect-ratio preservation.
 * Fits source video dimensions inside available Compose constraints with zero distortion,
 * handling letterboxing, rotation, pixel aspect ratio, and safe fallback sizing.
 */
object VideoLayoutCalculator {

    /** Default fallback aspect ratio (16:9 standard widescreen) used when source dimensions are unknown. */
    const val DEFAULT_ASPECT_RATIO: Float = 16f / 9f

    /**
     * Resolves the effective source aspect ratio from Media3 video dimensions, accounting for
     * unapplied display rotation and pixel width/height ratio.
     */
    fun calculateAspectRatio(
        videoWidth: Int,
        videoHeight: Int,
        unappliedRotationDegrees: Int = 0,
        pixelWidthHeightRatio: Float = 1.0f
    ): Float {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return DEFAULT_ASPECT_RATIO
        }

        val isRotated = unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270
        val rawWidth = if (isRotated) videoHeight.toFloat() else videoWidth.toFloat()
        val rawHeight = if (isRotated) videoWidth.toFloat() else videoHeight.toFloat()
        val par = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1.0f

        val effectiveWidth = rawWidth * par
        val effectiveHeight = rawHeight
        val ratio = if (effectiveHeight > 0f) effectiveWidth / effectiveHeight else DEFAULT_ASPECT_RATIO
        return if (ratio > 0f && !ratio.isNaN() && !ratio.isInfinite()) ratio else DEFAULT_ASPECT_RATIO
    }

    /**
     * Calculates the fitted video dimensions within [containerWidth] and [containerHeight]
     * preserving the source aspect ratio.
     *
     * Ensures:
     * 1. The video never exceeds [containerWidth] or [containerHeight] (no overflow).
     * 2. The source aspect ratio is preserved without stretching or cropping (letterboxing / pillarboxing).
     * 3. The video expands to be as large as possible within the constraints.
     */
    fun calculateFittedDimensions(
        containerWidth: Float,
        containerHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
        unappliedRotationDegrees: Int = 0,
        pixelWidthHeightRatio: Float = 1.0f
    ): VideoLayoutResult {
        if (containerWidth <= 0f || containerHeight <= 0f) {
            return VideoLayoutResult(
                width = 0f,
                height = 0f,
                aspectRatio = DEFAULT_ASPECT_RATIO,
                isWidthConstrained = true
            )
        }

        val aspectRatio = calculateAspectRatio(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            unappliedRotationDegrees = unappliedRotationDegrees,
            pixelWidthHeightRatio = pixelWidthHeightRatio
        )

        val containerAspectRatio = containerWidth / containerHeight
        return if (containerAspectRatio >= aspectRatio) {
            // Container is wider than the video -> Height is the binding constraint
            val height = containerHeight
            val width = height * aspectRatio
            VideoLayoutResult(
                width = minOf(width, containerWidth),
                height = height,
                aspectRatio = aspectRatio,
                isWidthConstrained = false
            )
        } else {
            // Container is taller than the video -> Width is the binding constraint
            val width = containerWidth
            val height = width / aspectRatio
            VideoLayoutResult(
                width = width,
                height = minOf(height, containerHeight),
                aspectRatio = aspectRatio,
                isWidthConstrained = true
            )
        }
    }
}
