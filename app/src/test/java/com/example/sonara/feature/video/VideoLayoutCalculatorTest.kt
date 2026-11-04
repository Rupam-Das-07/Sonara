package com.example.sonara.feature.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VideoLayoutCalculatorTest {

    private val tolerance = 0.001f

    @Test
    fun `16-9 landscape video in a portrait phone viewing region is width-constrained`() {
        // Typical portrait phone media region: 360dp wide x 400dp tall
        val containerWidth = 360f
        val containerHeight = 400f
        val videoWidth = 1920
        val videoHeight = 1080

        val result = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )

        assertTrue("16:9 video in portrait region must be width-constrained", result.isWidthConstrained)
        assertEquals(containerWidth, result.width, tolerance)
        val expectedHeight = containerWidth / (16f / 9f) // 202.5f
        assertEquals(expectedHeight, result.height, tolerance)
        assertTrue("Fitted height must not exceed container height", result.height <= containerHeight)
        assertEquals(16f / 9f, result.aspectRatio, tolerance)
        assertEquals(result.aspectRatio, result.width / result.height, tolerance)
    }

    @Test
    fun `4-3 video fits within available region without stretching`() {
        val containerWidth = 360f
        val containerHeight = 400f
        val videoWidth = 640
        val videoHeight = 480

        val result = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )

        assertTrue("4:3 video in tall container is width-constrained", result.isWidthConstrained)
        assertEquals(containerWidth, result.width, tolerance)
        val expectedHeight = containerWidth / (4f / 3f) // 270f
        assertEquals(expectedHeight, result.height, tolerance)
        assertTrue(result.height <= containerHeight)
        assertEquals(4f / 3f, result.aspectRatio, tolerance)
        assertEquals(result.aspectRatio, result.width / result.height, tolerance)
    }

    @Test
    fun `square video preserves 1-to-1 aspect ratio`() {
        val containerWidth = 360f
        val containerHeight = 400f
        val videoWidth = 1080
        val videoHeight = 1080

        val result = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )

        assertTrue(result.isWidthConstrained)
        assertEquals(containerWidth, result.width, tolerance)
        assertEquals(containerWidth, result.height, tolerance) // 1:1 -> 360f x 360f
        assertEquals(1.0f, result.aspectRatio, tolerance)
        assertEquals(result.width, result.height, tolerance)
    }

    @Test
    fun `portrait 9-16 video is height-constrained and pillarboxed`() {
        val containerWidth = 360f
        val containerHeight = 400f
        val videoWidth = 1080
        val videoHeight = 1920

        val result = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )

        assertFalse("Portrait video in container is height-constrained", result.isWidthConstrained)
        assertEquals(containerHeight, result.height, tolerance)
        val expectedWidth = containerHeight * (9f / 16f) // 225f
        assertEquals(expectedWidth, result.width, tolerance)
        assertTrue("Width must be within container width", result.width <= containerWidth)
        assertEquals(9f / 16f, result.aspectRatio, tolerance)
        assertEquals(result.aspectRatio, result.width / result.height, tolerance)
    }

    @Test
    fun `missing or unknown video dimensions fall back to safe 16-9 temporary policy`() {
        val containerWidth = 360f
        val containerHeight = 400f

        val resultZero = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = 0,
            videoHeight = 0
        )
        val resultNegative = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = -1,
            videoHeight = -1
        )

        assertEquals(VideoLayoutCalculator.DEFAULT_ASPECT_RATIO, resultZero.aspectRatio, tolerance)
        assertEquals(VideoLayoutCalculator.DEFAULT_ASPECT_RATIO, resultNegative.aspectRatio, tolerance)
        assertEquals(containerWidth, resultZero.width, tolerance)
        assertEquals(containerWidth / (16f / 9f), resultZero.height, tolerance)
    }

    @Test
    fun `height-constrained layout applies when container is wider than video aspect ratio`() {
        // Landscape orientation or short container: 600dp wide x 200dp tall
        val containerWidth = 600f
        val containerHeight = 200f
        val videoWidth = 1920
        val videoHeight = 1080 // 16:9 = 1.778. Container aspect ratio = 600 / 200 = 3.0 > 1.778

        val result = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )

        assertFalse("Wide container makes 16:9 height-constrained", result.isWidthConstrained)
        assertEquals(containerHeight, result.height, tolerance)
        val expectedWidth = containerHeight * (16f / 9f) // 355.556f
        assertEquals(expectedWidth, result.width, tolerance)
        assertTrue(result.width <= containerWidth)
        assertEquals(result.aspectRatio, result.width / result.height, tolerance)
    }

    @Test
    fun `fitted dimensions never exceed container bounds`() {
        val testAspectRatios = listOf(
            Pair(1920, 1080), // 16:9
            Pair(2560, 1080), // 21:9
            Pair(640, 480),   // 4:3
            Pair(1000, 1000), // 1:1
            Pair(1080, 1920), // 9:16
            Pair(720, 1280)   // 9:16
        )
        val testContainers = listOf(
            Pair(360f, 400f),
            Pair(400f, 250f),
            Pair(200f, 200f),
            Pair(800f, 300f),
            Pair(320f, 180f)
        )

        for ((w, h) in testContainers) {
            for ((vw, vh) in testAspectRatios) {
                val result = VideoLayoutCalculator.calculateFittedDimensions(
                    containerWidth = w,
                    containerHeight = h,
                    videoWidth = vw,
                    videoHeight = vh
                )
                assertTrue("Width ${result.width} must be <= container $w", result.width <= w + tolerance)
                assertTrue("Height ${result.height} must be <= container $h", result.height <= h + tolerance)
                assertTrue("Width must be strictly positive", result.width > 0f)
                assertTrue("Height must be strictly positive", result.height > 0f)
                val expectedRatio = VideoLayoutCalculator.calculateAspectRatio(vw, vh)
                assertEquals(
                    "Aspect ratio must be preserved for $vw x $vh in $w x $h",
                    expectedRatio,
                    result.width / result.height,
                    0.01f
                )
            }
        }
    }

    @Test
    fun `rotation of 90 and 270 degrees inverts effective aspect ratio`() {
        val normal = VideoLayoutCalculator.calculateAspectRatio(1920, 1080, unappliedRotationDegrees = 0)
        val rotated90 = VideoLayoutCalculator.calculateAspectRatio(1920, 1080, unappliedRotationDegrees = 90)
        val rotated180 = VideoLayoutCalculator.calculateAspectRatio(1920, 1080, unappliedRotationDegrees = 180)
        val rotated270 = VideoLayoutCalculator.calculateAspectRatio(1920, 1080, unappliedRotationDegrees = 270)

        assertEquals(16f / 9f, normal, tolerance)
        assertEquals(9f / 16f, rotated90, tolerance)
        assertEquals(16f / 9f, rotated180, tolerance)
        assertEquals(9f / 16f, rotated270, tolerance)
    }

    @Test
    fun `pixel aspect ratio scales effective width`() {
        // Anamorphic PAL: 720x576 with PAR = 1.4222222 -> effective 16:9
        val anamorphicRatio = VideoLayoutCalculator.calculateAspectRatio(
            videoWidth = 720,
            videoHeight = 576,
            pixelWidthHeightRatio = 1.4222222f
        )
        assertEquals(16f / 9f, anamorphicRatio, 0.01f)
    }

    @Test
    fun `zero or negative container dimensions return safe zero dimensions`() {
        val resultZeroW = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = 0f,
            containerHeight = 400f,
            videoWidth = 1920,
            videoHeight = 1080
        )
        val resultZeroH = VideoLayoutCalculator.calculateFittedDimensions(
            containerWidth = 360f,
            containerHeight = 0f,
            videoWidth = 1920,
            videoHeight = 1080
        )

        assertEquals(0f, resultZeroW.width, tolerance)
        assertEquals(0f, resultZeroW.height, tolerance)
        assertEquals(0f, resultZeroH.width, tolerance)
        assertEquals(0f, resultZeroH.height, tolerance)
    }
}
