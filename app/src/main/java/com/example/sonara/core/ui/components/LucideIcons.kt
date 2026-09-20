package com.example.sonara.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Exact Lucide Sun & Moon Vector Icons.
 * 
 * Replicated with 100% mathematical fidelity from lucide-react production SVGs:
 * - ViewBox: 24 x 24
 * - Stroke Width: 2.0 (stroke-only, fill = null)
 * - Stroke LineCap: Round
 * - Stroke LineJoin: Round
 */
object LucideIcons {

    /**
     * Lucide Sun Icon (Active when Theme is Dark).
     * Exact 9 SVG primitives: Center Solar Circle (r=4.0) + 8 discrete radial rays.
     */
    val Sun: ImageVector = ImageVector.Builder(
        name = "LucideSun",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // 1. Center solar circle: center (12, 12), radius 4 (circle cx="12" cy="12" r="4")
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(16f, 12f)
            arcTo(
                horizontalEllipseRadius = 4f,
                verticalEllipseRadius = 4f,
                theta = 0f,
                isMoreThanHalf = false,
                isPositiveArc = true,
                x1 = 8f,
                y1 = 12f
            )
            arcTo(
                horizontalEllipseRadius = 4f,
                verticalEllipseRadius = 4f,
                theta = 0f,
                isMoreThanHalf = false,
                isPositiveArc = true,
                x1 = 16f,
                y1 = 12f
            )
            close()
        }
        // 2. Eight discrete radial rays
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Top vertical ray: M12 2v2
            moveTo(12f, 2f)
            lineTo(12f, 4f)

            // Bottom vertical ray: M12 20v2
            moveTo(12f, 20f)
            lineTo(12f, 22f)

            // Left horizontal ray: M2 12h2
            moveTo(2f, 12f)
            lineTo(4f, 12f)

            // Right horizontal ray: M20 12h2
            moveTo(20f, 12f)
            lineTo(22f, 12f)

            // Top-Left diagonal ray: m4.93 4.93 1.41 1.41
            moveTo(4.93f, 4.93f)
            lineTo(6.34f, 6.34f)

            // Bottom-Right diagonal ray: m17.66 17.66 1.41 1.41
            moveTo(17.66f, 17.66f)
            lineTo(19.07f, 19.07f)

            // Bottom-Left diagonal ray: m6.34 17.66-1.41 1.41
            moveTo(6.34f, 17.66f)
            lineTo(4.93f, 19.07f)

            // Top-Right diagonal ray: m19.07 4.93-1.41 1.41
            moveTo(19.07f, 4.93f)
            lineTo(17.66f, 6.34f)
        }
    }.build()

    /**
     * Lucide Moon Icon (Active when Theme is Light).
     * Exact continuous compound crescent path from lucide-react:
     * "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401"
     */
    val Moon: ImageVector = ImageVector.Builder(
        name = "LucideMoon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // M20.985 12.486
            moveTo(20.985f, 12.486f)
            // a9 9 0 1 1-9.473-9.472
            arcToRelative(
                a = 9f,
                b = 9f,
                theta = 0f,
                isMoreThanHalf = true,
                isPositiveArc = true,
                dx1 = -9.473f,
                dy1 = -9.472f
            )
            // c.405-.022.617.46.402.803
            curveToRelative(
                dx1 = 0.405f,
                dy1 = -0.022f,
                dx2 = 0.617f,
                dy2 = 0.46f,
                dx3 = 0.402f,
                dy3 = 0.803f
            )
            // a6 6 0 0 0 8.268 8.268
            arcToRelative(
                a = 6f,
                b = 6f,
                theta = 0f,
                isMoreThanHalf = false,
                isPositiveArc = false,
                dx1 = 8.268f,
                dy1 = 8.268f
            )
            // c.344-.215.825-.004.803.401
            curveToRelative(
                dx1 = 0.344f,
                dy1 = -0.215f,
                dx2 = 0.825f,
                dy2 = -0.004f,
                dx3 = 0.803f,
                dy3 = 0.401f
            )
        }
    }.build()
}
