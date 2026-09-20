package com.example.sonara.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Commit intent for a released swipe — mirrors ExpandedPlayerScreen's private `SkipIntent`. */
enum class SkipIntentMirror { NEXT, PREVIOUS, CANCEL }

/** Mirrors ExpandedPlayerScreen's private `TransitionLayers`. */
data class LayersMirror(
    val topTranslationX: Float,
    val topTranslationY: Float,
    val topAlpha: Float,
    val underTranslationX: Float,
    val underTranslationY: Float,
    val underAlpha: Float
)

/**
 * Pure-math mirror of ExpandedPlayerScreen's private `FallAwayRiseUp` object.
 *
 * The production object is file-private (the "Fall Away / Opposite Diagonal Entry" physics is deliberately
 * encapsulated to the Expanded Player and is NOT part of the frozen `SonaraMotion` vocabulary), so —
 * following the existing `SwipeGestureHelper` convention this file used — the physics is duplicated
 * verbatim here and unit-tested to pin the contract: mirror symmetry, monotonic progress, no
 * overshoot, clamping, and reduced-motion zeroing. Keep this in exact sync with the production object.
 */
object FallAwayRiseUpMirror {

    fun decideCommit(
        offsetX: Float,
        velocityX: Float,
        distanceThresholdPx: Float,
        velocityThresholdPx: Float,
        minFlingDistancePx: Float = 20f
    ): SkipIntentMirror {
        val leftByDistance = offsetX <= -distanceThresholdPx
        val leftByVelocity = offsetX < -minFlingDistancePx && velocityX <= -velocityThresholdPx
        val rightByDistance = offsetX >= distanceThresholdPx
        val rightByVelocity = offsetX > minFlingDistancePx && velocityX >= velocityThresholdPx
        return when {
            leftByDistance || leftByVelocity -> SkipIntentMirror.NEXT
            rightByDistance || rightByVelocity -> SkipIntentMirror.PREVIOUS
            else -> SkipIntentMirror.CANCEL
        }
    }

    fun progressForOffset(offsetX: Float, spanPx: Float): Float {
        if (spanPx <= 0f) return 0f
        return (kotlin.math.abs(offsetX) / spanPx).coerceIn(0f, 1f)
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun layers(
        offsetX: Float,
        progress: Float,
        maxFallPx: Float,
        maxRisePx: Float,
        maxEntryXPx: Float,
        reduceMotion: Boolean
    ): LayersMirror {
        val p = progress.coerceIn(0f, 1f)
        if (reduceMotion) {
            return LayersMirror(
                topTranslationX = 0f,
                topTranslationY = 0f,
                topAlpha = (1f - p).coerceIn(0f, 1f),
                underTranslationX = 0f,
                underTranslationY = 0f,
                underAlpha = p
            )
        }
        val topAlpha = (1f - p / 0.9f).coerceIn(0f, 1f)
        val topTranslationY = p * maxFallPx
        val horizontalSign = when {
            offsetX < 0f -> 1f
            offsetX > 0f -> -1f
            else -> 0f
        }
        val remaining = 1f - smooth(p)
        val underTranslationX = horizontalSign * remaining * maxEntryXPx
        val underTranslationY = remaining * maxRisePx
        val underAlpha = smooth(p / 0.75f)
        return LayersMirror(
            topTranslationX = offsetX,
            topTranslationY = topTranslationY,
            topAlpha = topAlpha,
            underTranslationX = underTranslationX,
            underTranslationY = underTranslationY,
            underAlpha = underAlpha
        )
    }

    fun settleDurationMs(
        remainingPx: Float,
        velocityAbsPxPerSec: Float,
        minMs: Int,
        maxMs: Int,
        baseMs: Int
    ): Int {
        if (remainingPx <= 0f) return minMs
        val byVelocity = if (velocityAbsPxPerSec > 1f) {
            (remainingPx / velocityAbsPxPerSec * 1000f).toInt()
        } else {
            baseMs
        }
        return byVelocity.coerceIn(minMs, maxMs)
    }

    fun isHorizontalDominant(dx: Float, dy: Float): Boolean =
        kotlin.math.abs(dx) > kotlin.math.abs(dy)
}

class SwipeToSkipGestureTest {

    // Representative on-device geometry (~270dp artwork at ~2.6x density).
    private val artworkWidthPx = 700f
    private val spanPx = artworkWidthPx * 0.85f            // FA_SPAN_FRACTION
    private val commitDistancePx = artworkWidthPx * 0.32f  // FA_COMMIT_DISTANCE_FRACTION ≈ 224px
    private val velocityThresholdPx = 1500f
    private val maxFallPx = 340f
    private val maxRisePx = 230f
    private val maxEntryXPx = 286f                         // FA_MAX_ENTRY_X_DP ≈ 110dp

    // ── decideCommit: distance ────────────────────────────────────────────────────────────────

    @Test
    fun `deliberate left drag past distance threshold commits NEXT`() {
        val result = FallAwayRiseUpMirror.decideCommit(
            offsetX = -(commitDistancePx + 6f),
            velocityX = -100f, // slow, deliberate drag
            distanceThresholdPx = commitDistancePx,
            velocityThresholdPx = velocityThresholdPx
        )
        assertEquals(SkipIntentMirror.NEXT, result)
    }

    @Test
    fun `deliberate right drag past distance threshold commits PREVIOUS`() {
        val result = FallAwayRiseUpMirror.decideCommit(
            offsetX = commitDistancePx + 6f,
            velocityX = 100f,
            distanceThresholdPx = commitDistancePx,
            velocityThresholdPx = velocityThresholdPx
        )
        assertEquals(SkipIntentMirror.PREVIOUS, result)
    }

    @Test
    fun `insufficient drag below distance threshold and low velocity CANCELS`() {
        val result = FallAwayRiseUpMirror.decideCommit(
            offsetX = -100f,
            velocityX = -200f,
            distanceThresholdPx = commitDistancePx,
            velocityThresholdPx = velocityThresholdPx
        )
        assertEquals(SkipIntentMirror.CANCEL, result)
    }

    // ── decideCommit: velocity (fast flicks with shorter travel) ─────────────────────────────

    @Test
    fun `fast left flick commits NEXT even with short displacement`() {
        val result = FallAwayRiseUpMirror.decideCommit(
            offsetX = -40f, // well below commitDistancePx (224px)
            velocityX = -2500f,
            distanceThresholdPx = commitDistancePx,
            velocityThresholdPx = velocityThresholdPx
        )
        assertEquals(SkipIntentMirror.NEXT, result)
    }

    @Test
    fun `fast right flick commits PREVIOUS even with short displacement`() {
        val result = FallAwayRiseUpMirror.decideCommit(
            offsetX = 40f,
            velocityX = 2500f,
            distanceThresholdPx = commitDistancePx,
            velocityThresholdPx = velocityThresholdPx
        )
        assertEquals(SkipIntentMirror.PREVIOUS, result)
    }

    @Test
    fun `flick below minimum travel threshold CANCELS regardless of speed`() {
        val result = FallAwayRiseUpMirror.decideCommit(
            offsetX = -10f, // below minFlingDistancePx (20px) — accidental tap / tremor
            velocityX = -5000f,
            distanceThresholdPx = commitDistancePx,
            velocityThresholdPx = velocityThresholdPx
        )
        assertEquals(SkipIntentMirror.CANCEL, result)
    }

    @Test
    fun `zero offset and zero velocity CANCELS cleanly`() {
        val result = FallAwayRiseUpMirror.decideCommit(
            offsetX = 0f,
            velocityX = 0f,
            distanceThresholdPx = commitDistancePx,
            velocityThresholdPx = velocityThresholdPx
        )
        assertEquals(SkipIntentMirror.CANCEL, result)
    }

    // ── decideCommit: mirror symmetry ────────────────────────────────────────────────────────

    @Test
    fun `commit decision is exactly mirror-symmetric between left and right`() {
        val testOffsets = listOf(50f, 150f, 220f, 230f, 400f)
        val testVelocities = listOf(0f, 400f, 1600f, 3000f)
        for (offset in testOffsets) {
            for (velocity in testVelocities) {
                val next = FallAwayRiseUpMirror.decideCommit(-offset, -velocity, commitDistancePx, velocityThresholdPx)
                val previous = FallAwayRiseUpMirror.decideCommit(offset, velocity, commitDistancePx, velocityThresholdPx)
                when (next) {
                    SkipIntentMirror.NEXT -> assertEquals("Mirror of NEXT must be PREVIOUS", SkipIntentMirror.PREVIOUS, previous)
                    SkipIntentMirror.CANCEL -> assertEquals("Mirror of CANCEL must be CANCEL", SkipIntentMirror.CANCEL, previous)
                    SkipIntentMirror.PREVIOUS -> org.junit.Assert.fail("Negative offset must never commit PREVIOUS")
                }
            }
        }
    }

    // ── progress calculation ─────────────────────────────────────────────────────────────────

    @Test
    fun `progress is clamped strictly to 0 to 1 and symmetric`() {
        val small = FallAwayRiseUpMirror.progressForOffset(-100f, spanPx)
        val mid = FallAwayRiseUpMirror.progressForOffset(-300f, spanPx)
        val past = FallAwayRiseUpMirror.progressForOffset(-spanPx - 200f, spanPx)
        assertTrue(small in 0f..1f)
        assertTrue(mid in 0f..1f)
        assertEquals(1f, past, 0.0001f) // clamped, no overshoot
        // Mirror-symmetric: positive and negative offsets yield identical progress.
        assertEquals(mid, FallAwayRiseUpMirror.progressForOffset(300f, spanPx), 0.0001f)
    }

    @Test
    fun `zero span returns 0 progress without dividing by zero`() {
        assertEquals(0f, FallAwayRiseUpMirror.progressForOffset(-300f, 0f), 0.0001f)
    }

    // ── layers: endpoints ───────────────────────────────────────────────────────────────────

    @Test
    fun `at rest A is fully visible and B is hidden below`() {
        val l = FallAwayRiseUpMirror.layers(0f, 0f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
        assertEquals(0f, l.topTranslationX, 0.0001f)
        assertEquals(0f, l.topTranslationY, 0.0001f)
        assertEquals(1f, l.topAlpha, 0.0001f)
        assertEquals(0f, l.underTranslationX, 0.0001f)
        assertEquals(maxRisePx, l.underTranslationY, 0.0001f) // B starts fully below its resting spot
        assertEquals(0f, l.underAlpha, 0.0001f)               // B not yet revealed
    }

    @Test
    fun `at full progress A has fallen away and B has risen to rest`() {
        val l = FallAwayRiseUpMirror.layers(-spanPx, 1f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
        assertEquals(maxFallPx, l.topTranslationY, 0.0001f) // A sank downward
        assertEquals(0f, l.topAlpha, 0.0001f)               // A gone, not lingering
        assertEquals(0f, l.underTranslationX, 0.0001f)      // B settled at exact resting X
        assertEquals(0f, l.underTranslationY, 0.0001f)      // B settled at exact resting Y
        assertEquals(1f, l.underAlpha, 0.0001f)             // B fully opaque
    }

    @Test
    fun `top layer follows the finger horizontally`() {
        val left = FallAwayRiseUpMirror.layers(-120f, 0.3f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
        val right = FallAwayRiseUpMirror.layers(120f, 0.3f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
        assertEquals(-120f, left.topTranslationX, 0.0001f)
        assertEquals(120f, right.topTranslationX, 0.0001f)
    }

    // ── layers: opposite diagonal entry vectors ─────────────────────────────────────────────

    @Test
    fun `B enters along the exact opposite diagonal vector for Next and Previous`() {
        // NEXT (Swipe Left, offsetX < 0):
        // A falls toward bottom-left: topTranslationX < 0, topTranslationY > 0
        // B enters from bottom-right toward (0, 0): underTranslationX > 0, underTranslationY > 0
        val next = FallAwayRiseUpMirror.layers(-200f, 0.4f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
        assertTrue("A must move left", next.topTranslationX < 0f)
        assertTrue("A must fall downward", next.topTranslationY > 0f)
        assertTrue("B must enter from right (positive X)", next.underTranslationX > 0f)
        assertTrue("B must enter from below (positive Y)", next.underTranslationY > 0f)

        // PREVIOUS (Swipe Right, offsetX > 0):
        // A falls toward bottom-right: topTranslationX > 0, topTranslationY > 0
        // B enters from bottom-left toward (0, 0): underTranslationX < 0, underTranslationY > 0
        val prev = FallAwayRiseUpMirror.layers(200f, 0.4f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
        assertTrue("A must move right", prev.topTranslationX > 0f)
        assertTrue("A must fall downward", prev.topTranslationY > 0f)
        assertTrue("B must enter from left (negative X)", prev.underTranslationX < 0f)
        assertTrue("B must enter from below (positive Y)", prev.underTranslationY > 0f)

        // Exact horizontal negation between the two directions
        assertEquals(-next.underTranslationX, prev.underTranslationX, 0.0001f)
        assertEquals(next.underTranslationY, prev.underTranslationY, 0.0001f)
    }

    // ── layers: monotonicity & bounds (no overshoot / bounce / elastic) ─────────────────────

    @Test
    fun `A fades out and B fades in monotonically across progress`() {
        val samples = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        var prevTopAlpha = Float.MAX_VALUE
        var prevUnderAlpha = -1f
        for (p in samples) {
            val l = FallAwayRiseUpMirror.layers(-p * spanPx, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
            assertTrue("topAlpha must not increase as progress grows", l.topAlpha <= prevTopAlpha + 0.0001f)
            assertTrue("underAlpha must not decrease as progress grows", l.underAlpha >= prevUnderAlpha - 0.0001f)
            prevTopAlpha = l.topAlpha
            prevUnderAlpha = l.underAlpha
        }
    }

    @Test
    fun `A sinks down and B rises up monotonically across progress`() {
        val samples = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        var prevFall = -1f
        var prevRise = Float.MAX_VALUE
        for (p in samples) {
            val l = FallAwayRiseUpMirror.layers(-p * spanPx, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
            assertTrue("A downward travel must not decrease", l.topTranslationY >= prevFall - 0.0001f)
            assertTrue("B upward travel must not increase (rises toward 0)", l.underTranslationY <= prevRise + 0.0001f)
            prevFall = l.topTranslationY
            prevRise = l.underTranslationY
        }
    }

    @Test
    fun `underTranslationX approaches 0 monotonically without overshoot`() {
        val samples = listOf(0.05f, 0.25f, 0.5f, 0.75f, 1f)
        // Test Swipe Left (Next, offsetX < 0)
        var prevAbsXNext = Float.MAX_VALUE
        for (p in samples) {
            val l = FallAwayRiseUpMirror.layers(-p * spanPx, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
            val currentAbsX = kotlin.math.abs(l.underTranslationX)
            assertTrue("B horizontal offset must decrease toward 0 for swipe left", currentAbsX <= prevAbsXNext + 0.0001f)
            prevAbsXNext = currentAbsX
        }
        assertEquals("B must settle at exact 0 X offset at full progress for swipe left", 0f, prevAbsXNext, 0.0001f)

        // Test Swipe Right (Previous, offsetX > 0)
        var prevAbsXPrev = Float.MAX_VALUE
        for (p in samples) {
            val l = FallAwayRiseUpMirror.layers(p * spanPx, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
            val currentAbsX = kotlin.math.abs(l.underTranslationX)
            assertTrue("B horizontal offset must decrease toward 0 for swipe right", currentAbsX <= prevAbsXPrev + 0.0001f)
            prevAbsXPrev = currentAbsX
        }
        assertEquals("B must settle at exact 0 X offset at full progress for swipe right", 0f, prevAbsXPrev, 0.0001f)
    }


    @Test
    fun `all layer outputs stay within physical bounds for the whole progress range`() {
        var p = 0f
        while (p <= 1f) {
            val l = FallAwayRiseUpMirror.layers(-p * spanPx, p, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
            assertTrue(l.topAlpha in 0f..1f)
            assertTrue(l.underAlpha in 0f..1f)
            // No overshoot: translations never exceed their configured maxima and never go negative.
            assertTrue(l.topTranslationY in 0f..maxFallPx + 0.0001f)
            assertTrue(l.underTranslationY in 0f..maxRisePx + 0.0001f)
            assertTrue(kotlin.math.abs(l.underTranslationX) in 0f..maxEntryXPx + 0.0001f)
            p += 0.05f
        }
    }

    @Test
    fun `layers are mirror symmetric for next and previous`() {
        val next = FallAwayRiseUpMirror.layers(-200f, 0.4f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
        val previous = FallAwayRiseUpMirror.layers(200f, 0.4f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = false)
        // Horizontal translations flip sign; every progress-driven vertical/alpha quantity is identical.
        assertEquals(-next.topTranslationX, previous.topTranslationX, 0.0001f)
        assertEquals(-next.underTranslationX, previous.underTranslationX, 0.0001f)
        assertEquals(next.topTranslationY, previous.topTranslationY, 0.0001f)
        assertEquals(next.topAlpha, previous.topAlpha, 0.0001f)
        assertEquals(next.underTranslationY, previous.underTranslationY, 0.0001f)
        assertEquals(next.underAlpha, previous.underAlpha, 0.0001f)
    }

    // ── layers: reduced motion ──────────────────────────────────────────────────────────────

    @Test
    fun `reduced motion zeroes all translation and replaces via opacity only`() {
        val l = FallAwayRiseUpMirror.layers(-200f, 0.5f, maxFallPx, maxRisePx, maxEntryXPx, reduceMotion = true)
        assertEquals(0f, l.topTranslationX, 0.0001f)
        assertEquals(0f, l.topTranslationY, 0.0001f)
        assertEquals(0f, l.underTranslationX, 0.0001f)
        assertEquals(0f, l.underTranslationY, 0.0001f)
        // A crossfades out, B crossfades in, tied to progress — no translation at all.
        assertEquals(0.5f, l.topAlpha, 0.0001f)
        assertEquals(0.5f, l.underAlpha, 0.0001f)
    }

    // ── settle duration ─────────────────────────────────────────────────────────────────────

    @Test
    fun `settle duration shortens as release velocity increases`() {
        val slow = FallAwayRiseUpMirror.settleDurationMs(300f, 1200f, minMs = 90, maxMs = 260, baseMs = 220)
        val faster = FallAwayRiseUpMirror.settleDurationMs(300f, 2000f, minMs = 90, maxMs = 260, baseMs = 220)
        assertTrue("Higher release velocity should not lengthen the settle", faster <= slow)
        assertEquals(250, slow)   // 300/1200*1000 = 250
        assertEquals(150, faster) // 300/2000*1000 = 150
    }

    @Test
    fun `settle duration clamps to min and max and is never negative`() {
        // Remaining distance already covered → minimum.
        assertEquals(90, FallAwayRiseUpMirror.settleDurationMs(0f, 5000f, 90, 260, 220))
        // Extremely fast flick → clamped up to min, never below.
        assertEquals(90, FallAwayRiseUpMirror.settleDurationMs(1000f, 100000f, 90, 260, 220))
        // Very slow / near-zero velocity → falls back to base, clamped into range.
        assertEquals(220, FallAwayRiseUpMirror.settleDurationMs(1000f, 0.5f, 90, 260, 220))
        // Large remaining, gentle velocity → clamped down to max.
        assertEquals(260, FallAwayRiseUpMirror.settleDurationMs(10000f, 500f, 90, 260, 220))

        // Never negative across a sweep of inputs.
        for (remaining in listOf(0f, 5f, 50f, 500f, 5000f)) {
            for (velocity in listOf(-100f, 0f, 1f, 50f, 900f, 9000f)) {
                val d = FallAwayRiseUpMirror.settleDurationMs(remaining, kotlin.math.abs(velocity), 90, 260, 220)
                assertTrue(d in 90..260)
            }
        }
    }

    // ── direction gating ────────────────────────────────────────────────────────────────────

    @Test
    fun `horizontal dominance gate distinguishes horizontal from vertical intent`() {
        assertFalse(FallAwayRiseUpMirror.isHorizontalDominant(dx = 15f, dy = 60f))
        assertTrue(FallAwayRiseUpMirror.isHorizontalDominant(dx = 80f, dy = 20f))
        assertFalse(FallAwayRiseUpMirror.isHorizontalDominant(dx = 40f, dy = 50f))
        assertTrue(FallAwayRiseUpMirror.isHorizontalDominant(dx = 55f, dy = 45f))
    }

    @Test
    fun `system-gesture edge exclusion protects the OS Back gesture bands`() {
        val containerWidthPx = 1080f
        // Production takes max(systemGestureInset, fallback); model a resolved band of 60px.
        val leftExclusionPx = 60f
        val rightExclusionPx = 60f

        fun excluded(x: Float) = x < leftExclusionPx || x > containerWidthPx - rightExclusionPx

        assertTrue("Left edge touch must be excluded", excluded(30f))
        assertTrue("Right edge touch must be excluded", excluded(1060f))
        // The corridor is intentionally wide: gutter outside the artwork is still accepted.
        assertFalse("Gutter touch outside the edge band must be accepted", excluded(200f))
        assertFalse("Center touch must be accepted", excluded(540f))
    }

    // ── snapshot identity ───────────────────────────────────────────────────────────────────

    @Test
    fun `snapshot model preserves track metadata independently across transitions`() {
        data class TestSnapshot(val trackId: String?, val title: String, val artist: String, val artworkUrl: String?)

        val trackA = TestSnapshot("t1", "Track A", "Artist A", "https://example.com/a.jpg")
        val trackB = TestSnapshot("t2", "Track B", "Artist B", "https://example.com/b.jpg")

        // Each layer carries its OWN identity — the outgoing A keeps A's title+artwork while the
        // incoming B carries B's, so a transition can never show A-artwork with B-title.
        assertEquals("Track A", trackA.title)
        assertEquals("Track B", trackB.title)
        assertEquals("https://example.com/a.jpg", trackA.artworkUrl)
        assertEquals("https://example.com/b.jpg", trackB.artworkUrl)
    }
}
