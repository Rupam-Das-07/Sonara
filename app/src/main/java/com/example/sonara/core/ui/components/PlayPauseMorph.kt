package com.example.sonara.core.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.sonara.core.ui.motion.LocalReduceMotion

/**
 * Sonara Play/Pause True Geometric Morph Component.
 *
 * Implements a continuous, 1-to-1 geometric transformation between the Play triangle and Pause bars,
 * mirroring the exact vector topology from Sonara Web (`AnimatedPlayPauseIcon.jsx`).
 *
 * Geometry & Topology:
 * Both Play and Pause states are represented as two 4-vertex quadrilaterals:
 *  - Left Polygon:  4 points [top-left, bottom-left, bottom-right, top-right]
 *  - Right Polygon: 4 points [top-left, bottom-left, bottom-right, top-right]
 *
 * Play Configuration (t = 0.0):
 *  - Left: Trapezoid meeting right polygon at x = 12.0
 *  - Right: Triangle apex wedge meeting left polygon at x = 12.0 and apex at (19.5, 12.0)
 *  - Both polygons share the exact interface at x = 12.0, forming one solid, seamless Play triangle.
 *
 * Pause Configuration (t = 1.0):
 *  - Left: Vertical bar from x = 7.0 to 9.5, height = 15.0 (y = 4.5 to 19.5)
 *  - Right: Vertical bar from x = 14.5 to 17.0, height = 15.0 (y = 4.5 to 19.5)
 *  - Gap: Symmetric 5.0 unit gap centered at x = 12.0.
 *
 * Continuous Transformation:
 *  - Play -> Pause: The triangle's apex at (19.5, 12.0) symmetrically splits vertically into the top/bottom
 *    corners of the right bar, while the seam at x = 12.0 opens outward into the 5-unit gap.
 *  - Pause -> Play: The two bars move inward, the apex converges, and the shapes seal into the triangle.
 *  - No opacity fades, no crossfades, no scale pop — pure single-form continuous physical deformation.
 *
 * Physics & Rapid Toggles:
 *  - Uses a critically damped spring (stiffness = 380f, dampingRatio = 1.0f) matching Sonara Web's
 *    critically damped spring (stiffness: 400, damping: 28), settling in ~200ms without overshoot.
 *  - Continuous state interruption: Toggling mid-flight smoothly reverses from the current progress
 *    value and velocity without snapping or restarting.
 *
 * Accessibility:
 *  - When [LocalReduceMotion] is active, the spring collapses to an instantaneous snap without deformation.
 *
 * @param isPlaying True to animate/settle to Pause bars; False to animate/settle to Play triangle.
 * @param modifier Modifier applied to the outer layout container.
 * @param size Icon rendering size (default 30.dp).
 * @param color Icon color/tint (default Color.White).
 * @param contentDescription Optional accessibility description.
 */
@Composable
fun PlayPauseMorph(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    color: Color = Color.White,
    contentDescription: String? = null
) {
    val reduceMotion = LocalReduceMotion.current
    val targetValue = if (isPlaying) 1f else 0f

    val progress by animateFloatAsState(
        targetValue = targetValue,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = 380f
            )
        },
        label = "PlayPauseGeometricMorph"
    )

    val solidColor = remember(color) { color.copy(alpha = 1f) }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                alpha = color.alpha
            }
            .then(
                if (contentDescription != null) {
                    Modifier.semantics {
                        this.contentDescription = contentDescription
                        this.role = Role.Button
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawPlayPauseMorph(
                progress = progress,
                color = solidColor
            )
        }
    }
}

/**
 * Draws the interpolated geometric morph at the given progress [0.0 = Play, 1.0 = Pause].
 * All coordinates are normalized in a 24x24 grid and scaled to the canvas bounds.
 */
private fun DrawScope.drawPlayPauseMorph(
    progress: Float,
    color: Color
) {
    val t = progress.coerceIn(0f, 1f)
    val dim = minOf(size.width, size.height)
    if (dim <= 0f) return

    val scale = dim / 24f
    val offsetX = (size.width - 24f * scale) / 2f
    val offsetY = (size.height - 24f * scale) / 2f
    val strokeWidth = 1.5f * scale

    // ── Left Polygon (Trapezoid -> Left Bar) ──────────────────────────
    // At t=0: (7, 4.5) -> (7, 19.5) -> (12, 16.5) -> (12, 7.5)
    // At t=1: (7, 4.5) -> (7, 19.5) -> (9.5, 19.5) -> (9.5, 4.5)
    val l0x = offsetX + 7.0f * scale
    val l0y = offsetY + 4.5f * scale
    val l1x = offsetX + 7.0f * scale
    val l1y = offsetY + 19.5f * scale
    val l2x = offsetX + (12.0f - 2.5f * t) * scale
    val l2y = offsetY + (16.5f + 3.0f * t) * scale
    val l3x = offsetX + (12.0f - 2.5f * t) * scale
    val l3y = offsetY + (7.5f - 3.0f * t) * scale

    // ── Right Polygon (Triangle Apex Wedge -> Right Bar) ──────────────
    // At t=0: (12, 7.5) -> (12, 16.5) -> (19.5, 12) -> (19.5, 12)
    // At t=1: (14.5, 4.5) -> (14.5, 19.5) -> (17, 19.5) -> (17, 4.5)
    val r0x = offsetX + (12.0f + 2.5f * t) * scale
    val r0y = offsetY + (7.5f - 3.0f * t) * scale
    val r1x = offsetX + (12.0f + 2.5f * t) * scale
    val r1y = offsetY + (16.5f + 3.0f * t) * scale
    val r2x = offsetX + (19.5f - 2.5f * t) * scale
    val r2y = offsetY + (12.0f + 7.5f * t) * scale
    val r3x = offsetX + (19.5f - 2.5f * t) * scale
    val r3y = offsetY + (12.0f - 7.5f * t) * scale

    val leftPath = Path().apply {
        moveTo(l0x, l0y)
        lineTo(l1x, l1y)
        lineTo(l2x, l2y)
        lineTo(l3x, l3y)
        close()
    }

    val rightPath = Path().apply {
        moveTo(r0x, r0y)
        lineTo(r1x, r1y)
        lineTo(r2x, r2y)
        lineTo(r3x, r3y)
        close()
    }

    val strokeStyle = Stroke(
        width = strokeWidth,
        join = StrokeJoin.Round,
        cap = StrokeCap.Round
    )

    // Solid fill
    drawPath(path = leftPath, color = color, style = Fill)
    drawPath(path = rightPath, color = color, style = Fill)

    // Rounded outer stroke join
    drawPath(path = leftPath, color = color, style = strokeStyle)
    drawPath(path = rightPath, color = color, style = strokeStyle)
}
