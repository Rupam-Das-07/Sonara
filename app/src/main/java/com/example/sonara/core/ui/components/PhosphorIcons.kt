package com.example.sonara.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Authoritative Phosphor Icons (Regular Weight) for Sonara Android.
 * 
 * Rendered with exact 24x24 viewport and clean vector stroke/geometry:
 * - Viewport: 24 x 24
 * - Stroke: 2.0 (StrokeCap.Round, StrokeJoin.Round)
 */
object PhosphorIcons {

    /** ArrowLeft (Back Navigation) */
    val ArrowLeft: ImageVector = ImageVector.Builder(
        name = "PhosphorArrowLeft",
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
            moveTo(20f, 12f)
            lineTo(4f, 12f)
            moveTo(10f, 18f)
            lineTo(4f, 12f)
            lineTo(10f, 6f)
        }
    }.build()

    /** House (Home) */
    val House: ImageVector = ImageVector.Builder(
        name = "PhosphorHouse",
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
            moveTo(3f, 10.5f)
            lineTo(12f, 3f)
            lineTo(21f, 10.5f)
            lineTo(21f, 20f)
            arcTo(1f, 1f, 0f, false, true, 20f, 21f)
            lineTo(4f, 21f)
            arcTo(1f, 1f, 0f, false, true, 3f, 20f)
            close()
            moveTo(9.5f, 21f)
            lineTo(9.5f, 13.5f)
            arcTo(0.5f, 0.5f, 0f, false, true, 10f, 13f)
            lineTo(14f, 13f)
            arcTo(0.5f, 0.5f, 0f, false, true, 14.5f, 13.5f)
            lineTo(14.5f, 21f)
        }
    }.build()

    /** MagnifyingGlass (Search) */
    val MagnifyingGlass: ImageVector = ImageVector.Builder(
        name = "PhosphorMagnifyingGlass",
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
            moveTo(11f, 18f)
            arcTo(7f, 7f, 0f, true, true, 18f, 11f)
            arcTo(7f, 7f, 0f, false, true, 11f, 18f)
            close()
            moveTo(16f, 16f)
            lineTo(21.5f, 21.5f)
        }
    }.build()

    /** Books (Library) */
    val Books: ImageVector = ImageVector.Builder(
        name = "PhosphorBooks",
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
            // Book 1
            moveTo(4f, 4f)
            lineTo(8f, 4f)
            lineTo(8f, 20f)
            lineTo(4f, 20f)
            close()
            // Book 2
            moveTo(9f, 4f)
            lineTo(13f, 4f)
            lineTo(13f, 20f)
            lineTo(9f, 20f)
            close()
            // Slanted Book 3
            moveTo(14.5f, 4.5f)
            lineTo(18.5f, 6f)
            lineTo(13.5f, 20f)
            lineTo(9.5f, 18.5f)
            close()
        }
    }.build()

    /** Play (Verb) */
    val Play: ImageVector = ImageVector.Builder(
        name = "PhosphorPlay",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7.5f, 5.2f)
            arcTo(1.2f, 1.2f, 0f, false, true, 9.3f, 4.2f)
            lineTo(19.5f, 11f)
            arcTo(1.2f, 1.2f, 0f, false, true, 19.5f, 13f)
            lineTo(9.3f, 19.8f)
            arcTo(1.2f, 1.2f, 0f, false, true, 7.5f, 18.8f)
            close()
        }
    }.build()

    /** Pause (Verb) */
    val Pause: ImageVector = ImageVector.Builder(
        name = "PhosphorPause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7f, 5.5f)
            arcTo(1f, 1f, 0f, false, true, 8f, 4.5f)
            lineTo(9.5f, 4.5f)
            arcTo(1f, 1f, 0f, false, true, 10.5f, 5.5f)
            lineTo(10.5f, 18.5f)
            arcTo(1f, 1f, 0f, false, true, 9.5f, 19.5f)
            lineTo(8f, 19.5f)
            arcTo(1f, 1f, 0f, false, true, 7f, 18.5f)
            close()

            moveTo(13.5f, 5.5f)
            arcTo(1f, 1f, 0f, false, true, 14.5f, 4.5f)
            lineTo(16f, 4.5f)
            arcTo(1f, 1f, 0f, false, true, 17f, 5.5f)
            lineTo(17f, 18.5f)
            arcTo(1f, 1f, 0f, false, true, 16f, 19.5f)
            lineTo(14.5f, 19.5f)
            arcTo(1f, 1f, 0f, false, true, 13.5f, 18.5f)
            close()
        }
    }.build()

    /** ArrowClockwise (Refresh) */
    val ArrowClockwise: ImageVector = ImageVector.Builder(
        name = "PhosphorArrowClockwise",
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
            moveTo(21f, 4f)
            lineTo(21f, 9f)
            lineTo(16f, 9f)
            moveTo(3f, 12f)
            arcTo(9f, 9f, 0f, false, true, 18.4f, 5.6f)
            lineTo(21f, 9f)
            moveTo(3f, 20f)
            lineTo(3f, 15f)
            lineTo(8f, 15f)
            moveTo(21f, 12f)
            arcTo(9f, 9f, 0f, false, true, 5.6f, 18.4f)
            lineTo(3f, 15f)
        }
    }.build()

    /** CaretDown */
    val CaretDown: ImageVector = ImageVector.Builder(
        name = "PhosphorCaretDown",
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
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }.build()

    /** MusicNote */
    val MusicNote: ImageVector = ImageVector.Builder(
        name = "PhosphorMusicNote",
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
            moveTo(10f, 18f)
            arcTo(3f, 3f, 0f, true, true, 7f, 15f)
            arcTo(3f, 3f, 0f, false, true, 10f, 18f)
            close()
            moveTo(10f, 15f)
            lineTo(10f, 4f)
            lineTo(19f, 4f)
            lineTo(19f, 8f)
            lineTo(10f, 8f)
        }
    }.build()

    /** Heart (Outlined) */
    val Heart: ImageVector = ImageVector.Builder(
        name = "PhosphorHeart",
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
            moveTo(12f, 20.5f)
            lineTo(4.8f, 13.7f)
            arcTo(5.2f, 5.2f, 0f, false, true, 12f, 6.3f)
            arcTo(5.2f, 5.2f, 0f, false, true, 19.2f, 13.7f)
            close()
        }
    }.build()

    /** HeartFilled */
    val HeartFilled: ImageVector = ImageVector.Builder(
        name = "PhosphorHeartFilled",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 20.5f)
            lineTo(4.8f, 13.7f)
            arcTo(5.2f, 5.2f, 0f, false, true, 12f, 6.3f)
            arcTo(5.2f, 5.2f, 0f, false, true, 19.2f, 13.7f)
            close()
        }
    }.build()

    /** Sparkle (Journal Stamp) */
    val Sparkle: ImageVector = ImageVector.Builder(
        name = "PhosphorSparkle",
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
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, false, true, 21f, 12f)
            arcTo(9f, 9f, 0f, false, true, 12f, 21f)
            arcTo(9f, 9f, 0f, false, true, 3f, 12f)
            arcTo(9f, 9f, 0f, false, true, 12f, 3f)
            close()
        }
    }.build()

    /** WarningCircle (Offline) */
    val WarningCircle: ImageVector = ImageVector.Builder(
        name = "PhosphorWarningCircle",
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
            moveTo(12f, 22f)
            arcTo(10f, 10f, 0f, true, true, 22f, 12f)
            arcTo(10f, 10f, 0f, false, true, 12f, 22f)
            close()
            moveTo(12f, 8f)
            lineTo(12f, 13f)
            moveTo(12f, 16.5f)
            lineTo(12.01f, 16.5f)
        }
    }.build()

    /** SkipBack */
    val SkipBack: ImageVector = ImageVector.Builder(
        name = "PhosphorSkipBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 5f)
            lineTo(6f, 19f)
            moveTo(19f, 5.5f)
            lineTo(9f, 12f)
            lineTo(19f, 18.5f)
            close()
        }
    }.build()

    /** SkipForward */
    val SkipForward: ImageVector = ImageVector.Builder(
        name = "PhosphorSkipForward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18f, 5f)
            lineTo(18f, 19f)
            moveTo(5f, 5.5f)
            lineTo(15f, 12f)
            lineTo(5f, 18.5f)
            close()
        }
    }.build()

    /** Shuffle */
    val Shuffle: ImageVector = ImageVector.Builder(
        name = "PhosphorShuffle",
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
            moveTo(4f, 18f)
            lineTo(8f, 18f)
            lineTo(15f, 6f)
            lineTo(20f, 6f)
            moveTo(16f, 3f)
            lineTo(20f, 6f)
            lineTo(16f, 9f)
            moveTo(4f, 6f)
            lineTo(8f, 6f)
            lineTo(10.5f, 10.2f)
            moveTo(13.5f, 14f)
            lineTo(15f, 18f)
            lineTo(20f, 18f)
            moveTo(16f, 15f)
            lineTo(20f, 18f)
            lineTo(16f, 21f)
        }
    }.build()

    /** Repeat */
    val Repeat: ImageVector = ImageVector.Builder(
        name = "PhosphorRepeat",
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
            // Top arrow wrapping left to right
            moveTo(4f, 13f)
            lineTo(4f, 8.5f)
            arcTo(3.5f, 3.5f, 0f, false, true, 7.5f, 5f)
            lineTo(20f, 5f)
            moveTo(17f, 2f)
            lineTo(20f, 5f)
            lineTo(17f, 8f)

            // Bottom arrow wrapping right to left
            moveTo(20f, 11f)
            lineTo(20f, 15.5f)
            arcTo(3.5f, 3.5f, 0f, false, true, 16.5f, 19f)
            lineTo(4f, 19f)
            moveTo(7f, 16f)
            lineTo(4f, 19f)
            lineTo(7f, 22f)
        }
    }.build()

    /** RepeatOnce */
    val RepeatOnce: ImageVector = ImageVector.Builder(
        name = "PhosphorRepeatOnce",
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
            // Top arrow wrapping left to right
            moveTo(4f, 13f)
            lineTo(4f, 8.5f)
            arcTo(3.5f, 3.5f, 0f, false, true, 7.5f, 5f)
            lineTo(20f, 5f)
            moveTo(17f, 2f)
            lineTo(20f, 5f)
            lineTo(17f, 8f)

            // Bottom arrow wrapping right to left
            moveTo(20f, 11f)
            lineTo(20f, 15.5f)
            arcTo(3.5f, 3.5f, 0f, false, true, 16.5f, 19f)
            lineTo(4f, 19f)
            moveTo(7f, 16f)
            lineTo(4f, 19f)
            lineTo(7f, 22f)

            // Center numeral 1
            moveTo(11.5f, 10.5f)
            lineTo(12.5f, 9.5f)
            lineTo(12.5f, 14.5f)
        }
    }.build()

    /** ListPlus (Add to playlist) */
    val ListPlus: ImageVector = ImageVector.Builder(
        name = "PhosphorListPlus",
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
            moveTo(4f, 6f)
            lineTo(15f, 6f)
            moveTo(4f, 12f)
            lineTo(15f, 12f)
            moveTo(4f, 18f)
            lineTo(11f, 18f)
            moveTo(18f, 15f)
            lineTo(18f, 21f)
            moveTo(15f, 18f)
            lineTo(21f, 18f)
        }
    }.build()

    /** TextAa (Lyrics toggle) */
    val TextAa: ImageVector = ImageVector.Builder(
        name = "PhosphorTextAa",
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
            moveTo(3f, 18f)
            lineTo(8f, 6f)
            lineTo(13f, 18f)
            moveTo(5f, 14f)
            lineTo(11f, 14f)
            moveTo(17f, 12f)
            arcTo(3f, 3f, 0f, true, true, 20f, 15f)
            lineTo(20f, 18f)
            moveTo(20f, 14.5f)
            arcTo(2.5f, 2.5f, 0f, true, true, 17f, 17f)
            arcTo(2.5f, 2.5f, 0f, false, true, 17f, 12f)
        }
    }.build()

    /** CornersIn (Collapse / Minimize) */
    val CornersIn: ImageVector = ImageVector.Builder(
        name = "PhosphorCornersIn",
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
            moveTo(9f, 4f)
            lineTo(9f, 9f)
            lineTo(4f, 9f)
            moveTo(15f, 4f)
            lineTo(15f, 9f)
            lineTo(20f, 9f)
            moveTo(9f, 20f)
            lineTo(9f, 15f)
            lineTo(4f, 15f)
            moveTo(15f, 20f)
            lineTo(15f, 15f)
            lineTo(20f, 15f)
        }
    }.build()

    /** SpeakerHigh */
    val SpeakerHigh: ImageVector = ImageVector.Builder(
        name = "PhosphorSpeakerHigh",
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
            moveTo(14f, 4f)
            lineTo(8f, 9f)
            lineTo(3f, 9f)
            lineTo(3f, 15f)
            lineTo(8f, 15f)
            lineTo(14f, 20f)
            close()
            moveTo(18f, 8f)
            arcTo(5.5f, 5.5f, 0f, false, true, 18f, 16f)
            moveTo(21f, 5.5f)
            arcTo(9f, 9f, 0f, false, true, 21f, 18.5f)
        }
    }.build()

    /** SpeakerLow */
    val SpeakerLow: ImageVector = ImageVector.Builder(
        name = "PhosphorSpeakerLow",
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
            moveTo(14f, 4f)
            lineTo(8f, 9f)
            lineTo(3f, 9f)
            lineTo(3f, 15f)
            lineTo(8f, 15f)
            lineTo(14f, 20f)
            close()
            moveTo(18f, 9.5f)
            arcTo(3.5f, 3.5f, 0f, false, true, 18f, 14.5f)
        }
    }.build()

    /** SpeakerSlash */
    val SpeakerSlash: ImageVector = ImageVector.Builder(
        name = "PhosphorSpeakerSlash",
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
            moveTo(14f, 4f)
            lineTo(8f, 9f)
            lineTo(3f, 9f)
            lineTo(3f, 15f)
            lineTo(8f, 15f)
            lineTo(14f, 20f)
            close()
            moveTo(18f, 9.5f)
            lineTo(22f, 14.5f)
            moveTo(22f, 9.5f)
            lineTo(18f, 14.5f)
        }
    }.build()

    /** Playlist (Footer Nav & Library Tab) */
    val Playlist: ImageVector = ImageVector.Builder(
        name = "PhosphorPlaylist",
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
            moveTo(4f, 6f)
            lineTo(16f, 6f)
            moveTo(4f, 12f)
            lineTo(13f, 12f)
            moveTo(4f, 18f)
            lineTo(10f, 18f)
            // Note symbol
            moveTo(18f, 9f)
            lineTo(18f, 16f)
            arcTo(2.5f, 2.5f, 0f, true, true, 15.5f, 13.5f)
            lineTo(18f, 13.5f)
        }
    }.build()

    /** Plus (Create / Add) */
    val Plus: ImageVector = ImageVector.Builder(
        name = "PhosphorPlus",
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
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }.build()

    /** Trash (Delete) */
    val Trash: ImageVector = ImageVector.Builder(
        name = "PhosphorTrash",
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
            moveTo(4f, 6f)
            lineTo(20f, 6f)
            moveTo(9f, 6f)
            lineTo(9f, 4f)
            lineTo(15f, 4f)
            lineTo(15f, 6f)
            moveTo(19f, 6f)
            lineTo(18f, 20f)
            lineTo(6f, 20f)
            lineTo(5f, 6f)
            moveTo(10f, 10f)
            lineTo(10f, 16f)
            moveTo(14f, 10f)
            lineTo(14f, 16f)
        }
    }.build()

    /** PencilSimple (Rename / Edit) */
    val PencilSimple: ImageVector = ImageVector.Builder(
        name = "PhosphorPencilSimple",
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
            moveTo(4f, 20f)
            lineTo(8.5f, 19.5f)
            lineTo(19.5f, 8.5f)
            arcTo(2f, 2f, 0f, false, false, 16.5f, 5.5f)
            lineTo(5.5f, 16.5f)
            lineTo(4f, 20f)
            close()
            moveTo(14f, 8f)
            lineTo(17f, 11f)
        }
    }.build()

    /** DotsThreeVertical (Overflow Menu) */
    val DotsThreeVertical: ImageVector = ImageVector.Builder(
        name = "PhosphorDotsThreeVertical",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 6f)
            arcTo(1.5f, 1.5f, 0f, true, true, 12f, 6.01f)
            moveTo(12f, 12f)
            arcTo(1.5f, 1.5f, 0f, true, true, 12f, 12.01f)
            moveTo(12f, 18f)
            arcTo(1.5f, 1.5f, 0f, true, true, 12f, 18.01f)
        }
    }.build()

    /** MinusCircle (Remove from Playlist) */
    val MinusCircle: ImageVector = ImageVector.Builder(
        name = "PhosphorMinusCircle",
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
            moveTo(12f, 22f)
            arcTo(10f, 10f, 0f, true, true, 22f, 12f)
            arcTo(10f, 10f, 0f, false, true, 12f, 22f)
            close()
            moveTo(8f, 12f)
            lineTo(16f, 12f)
        }
    }.build()

    /** Check (Checkmark) */
    val Check: ImageVector = ImageVector.Builder(
        name = "PhosphorCheck",
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
            moveTo(4f, 12f)
            lineTo(9f, 17f)
            lineTo(20f, 6f)
        }
    }.build()

    /** DotsSixVertical (Reorder Drag Handle) */
    val DotsSixVertical: ImageVector = ImageVector.Builder(
        name = "PhosphorDotsSixVertical",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(9f, 6f)
            arcTo(1.2f, 1.2f, 0f, true, true, 9f, 6.01f)
            moveTo(9f, 12f)
            arcTo(1.2f, 1.2f, 0f, true, true, 9f, 12.01f)
            moveTo(9f, 18f)
            arcTo(1.2f, 1.2f, 0f, true, true, 9f, 18.01f)
            moveTo(15f, 6f)
            arcTo(1.2f, 1.2f, 0f, true, true, 15f, 6.01f)
            moveTo(15f, 12f)
            arcTo(1.2f, 1.2f, 0f, true, true, 15f, 12.01f)
            moveTo(15f, 18f)
            arcTo(1.2f, 1.2f, 0f, true, true, 15f, 18.01f)
        }
    }.build()

    /** Gear (Settings TopBar Icon) */
    val Gear: ImageVector = ImageVector.Builder(
        name = "PhosphorGear",
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
            moveTo(12f, 15f)
            arcTo(3f, 3f, 0f, true, false, 12f, 9f)
            arcTo(3f, 3f, 0f, false, false, 12f, 15f)
            close()
            moveTo(19.4f, 15f)
            arcTo(1.65f, 1.65f, 0f, false, false, 19.73f, 16.85f)
            lineTo(20.06f, 17.18f)
            arcTo(2f, 2f, 0f, false, true, 18.65f, 20.06f)
            lineTo(18.32f, 19.73f)
            arcTo(1.65f, 1.65f, 0f, false, false, 16.47f, 19.4f)
            arcTo(1.65f, 1.65f, 0f, false, false, 15.54f, 20.73f)
            lineTo(15.54f, 21.2f)
            arcTo(2f, 2f, 0f, false, true, 12.72f, 22.06f)
            lineTo(12.46f, 22.06f)
            arcTo(2f, 2f, 0f, false, true, 9.64f, 21.2f)
            lineTo(9.64f, 20.73f)
            arcTo(1.65f, 1.65f, 0f, false, false, 8.71f, 19.4f)
            arcTo(1.65f, 1.65f, 0f, false, false, 6.86f, 19.73f)
            lineTo(6.53f, 20.06f)
            arcTo(2f, 2f, 0f, false, true, 5.12f, 17.18f)
            lineTo(5.45f, 16.85f)
            arcTo(1.65f, 1.65f, 0f, false, false, 5.78f, 15f)
            arcTo(1.65f, 1.65f, 0f, false, false, 4.45f, 14.07f)
            lineTo(3.98f, 14.07f)
            arcTo(2f, 2f, 0f, false, true, 3.12f, 11.25f)
            lineTo(3.12f, 10.99f)
            arcTo(2f, 2f, 0f, false, true, 3.98f, 8.17f)
            lineTo(4.45f, 8.17f)
            arcTo(1.65f, 1.65f, 0f, false, false, 5.78f, 7.24f)
            arcTo(1.65f, 1.65f, 0f, false, false, 5.45f, 5.39f)
            lineTo(5.12f, 5.06f)
            arcTo(2f, 2f, 0f, false, true, 6.53f, 2.18f)
            lineTo(6.86f, 2.51f)
            arcTo(1.65f, 1.65f, 0f, false, false, 8.71f, 2.84f)
            arcTo(1.65f, 1.65f, 0f, false, false, 9.64f, 1.51f)
            lineTo(9.64f, 1.04f)
            arcTo(2f, 2f, 0f, false, true, 12.46f, 0.18f)
            lineTo(12.72f, 0.18f)
            arcTo(2f, 2f, 0f, false, true, 15.54f, 1.04f)
            lineTo(15.54f, 1.51f)
            arcTo(1.65f, 1.65f, 0f, false, false, 16.47f, 2.84f)
            arcTo(1.65f, 1.65f, 0f, false, false, 18.32f, 2.51f)
            lineTo(18.65f, 2.18f)
            arcTo(2f, 2f, 0f, false, true, 20.06f, 5.06f)
            lineTo(19.73f, 5.39f)
            arcTo(1.65f, 1.65f, 0f, false, false, 19.4f, 7.24f)
            arcTo(1.65f, 1.65f, 0f, false, false, 20.73f, 8.17f)
            lineTo(21.2f, 8.17f)
            arcTo(2f, 2f, 0f, false, true, 22.06f, 10.99f)
            lineTo(22.06f, 11.25f)
            arcTo(2f, 2f, 0f, false, true, 21.2f, 14.07f)
            lineTo(20.73f, 14.07f)
            arcTo(1.65f, 1.65f, 0f, false, false, 19.4f, 15f)
            close()
        }
    }.build()

    /** CaretUp (Collapse Category) */
    val CaretUp: ImageVector = ImageVector.Builder(
        name = "PhosphorCaretUp",
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
            moveTo(20f, 15f)
            lineTo(12f, 7f)
            lineTo(4f, 15f)
        }
    }.build()

    /** CaretRight (Selector Row Chevron) */
    val CaretRight: ImageVector = ImageVector.Builder(
        name = "PhosphorCaretRight",
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
            moveTo(9f, 4f)
            lineTo(17f, 12f)
            lineTo(9f, 20f)
        }
    }.build()

    /** Palette (Personalisation Category Icon) */
    val Palette: ImageVector = ImageVector.Builder(
        name = "PhosphorPalette",
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
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, false, false, 3f, 12f)
            arcTo(9f, 9f, 0f, false, false, 12f, 21f)
            arcTo(3.5f, 3.5f, 0f, false, false, 15.5f, 17.5f)
            arcTo(2f, 2f, 0f, false, false, 13.5f, 15.5f)
            lineTo(13f, 15.5f)
            arcTo(2f, 2f, 0f, false, true, 11f, 13.5f)
            arcTo(2f, 2f, 0f, false, true, 13f, 11.5f)
            lineTo(15.5f, 11.5f)
            arcTo(5.5f, 5.5f, 0f, false, false, 21f, 6f)
            arcTo(9f, 9f, 0f, false, false, 12f, 3f)
            close()
            moveTo(6.5f, 11.5f)
            arcTo(1f, 1f, 0f, true, true, 6.5f, 11.51f)
            moveTo(9.5f, 7.5f)
            arcTo(1f, 1f, 0f, true, true, 9.5f, 7.51f)
            moveTo(14.5f, 7.5f)
            arcTo(1f, 1f, 0f, true, true, 14.5f, 7.51f)
        }
    }.build()

    /** Sliders (Music & Playback Category Icon) */
    val Sliders: ImageVector = ImageVector.Builder(
        name = "PhosphorSliders",
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
            moveTo(4f, 21f)
            lineTo(4f, 14f)
            moveTo(4f, 10f)
            lineTo(4f, 3f)
            moveTo(12f, 21f)
            lineTo(12f, 12f)
            moveTo(12f, 8f)
            lineTo(12f, 3f)
            moveTo(20f, 21f)
            lineTo(20f, 16f)
            moveTo(20f, 12f)
            lineTo(20f, 3f)
            moveTo(1f, 14f)
            lineTo(7f, 14f)
            moveTo(9f, 8f)
            lineTo(15f, 8f)
            moveTo(17f, 16f)
            lineTo(23f, 16f)
        }
    }.build()

    /** Database (Data & Storage Category Icon) */
    val Database: ImageVector = ImageVector.Builder(
        name = "PhosphorDatabase",
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
            moveTo(12f, 3f)
            arcTo(9f, 3f, 0f, true, false, 12f, 9f)
            arcTo(9f, 3f, 0f, false, false, 12f, 3f)
            close()
            moveTo(3f, 6f)
            lineTo(3f, 18f)
            arcTo(9f, 3f, 0f, false, false, 21f, 18f)
            lineTo(21f, 6f)
            moveTo(3f, 12f)
            arcTo(9f, 3f, 0f, false, false, 21f, 12f)
        }
    }.build()

    /** Info (App Info Category Icon) */
    val Info: ImageVector = ImageVector.Builder(
        name = "PhosphorInfo",
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
            moveTo(12f, 22f)
            arcTo(10f, 10f, 0f, true, false, 12f, 2f)
            arcTo(10f, 10f, 0f, false, false, 12f, 22f)
            close()
            moveTo(12f, 16f)
            lineTo(12f, 12f)
            moveTo(12f, 8f)
            lineTo(12f, 8.01f)
        }
    }.build()

    /** DownloadSimple (Offline download action icon) */
    val DownloadSimple: ImageVector = ImageVector.Builder(
        name = "PhosphorDownloadSimple",
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
            moveTo(4f, 20f)
            lineTo(20f, 20f)
            moveTo(12f, 4f)
            lineTo(12f, 16f)
            moveTo(6f, 10f)
            lineTo(12f, 16f)
            lineTo(18f, 10f)
        }
    }.build()

    /** CheckCircle (Downloaded indicator icon) */
    val CheckCircle: ImageVector = ImageVector.Builder(
        name = "PhosphorCheckCircle",
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
            moveTo(12f, 22f)
            arcTo(10f, 10f, 0f, true, false, 12f, 2f)
            arcTo(10f, 10f, 0f, false, false, 12f, 22f)
            close()
            moveTo(8.5f, 12.5f)
            lineTo(11f, 15f)
            lineTo(16f, 9.5f)
        }
    }.build()

    /** Globe (Language / Internationalization) */
    val Globe: ImageVector = ImageVector.Builder(
        name = "PhosphorGlobe",
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
            moveTo(12f, 22f)
            arcTo(10f, 10f, 0f, true, false, 12f, 2f)
            arcTo(10f, 10f, 0f, false, false, 12f, 22f)
            close()
            moveTo(2.5f, 9f)
            lineTo(21.5f, 9f)
            moveTo(2.5f, 15f)
            lineTo(21.5f, 15f)
            moveTo(12f, 2f)
            arcTo(15f, 15f, 0f, false, false, 12f, 22f)
            arcTo(15f, 15f, 0f, false, false, 12f, 2f)
            close()
        }
    }.build()

    /** Clock (Search History / Timestamp) */
    val Clock: ImageVector = ImageVector.Builder(
        name = "PhosphorClock",
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
            moveTo(12f, 22f)
            arcTo(10f, 10f, 0f, true, false, 12f, 2f)
            arcTo(10f, 10f, 0f, false, false, 12f, 22f)
            close()
            moveTo(12f, 6f)
            lineTo(12f, 12f)
            lineTo(16.5f, 14.5f)
        }
    }.build()

    /** X (Close / Delete action) */
    val X: ImageVector = ImageVector.Builder(
        name = "PhosphorX",
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
            moveTo(18f, 6f)
            lineTo(6f, 18f)
            moveTo(6f, 6f)
            lineTo(18f, 18f)
        }
    }.build()

    /** SlidersHorizontal (Equalizer / Tuner) */
    val SlidersHorizontal: ImageVector = ImageVector.Builder(
        name = "PhosphorSlidersHorizontal",
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
            moveTo(4f, 6f)
            lineTo(10f, 6f)
            moveTo(14f, 6f)
            lineTo(20f, 6f)
            moveTo(4f, 12f)
            lineTo(6f, 12f)
            moveTo(10f, 12f)
            lineTo(20f, 12f)
            moveTo(4f, 18f)
            lineTo(14f, 18f)
            moveTo(18f, 18f)
            lineTo(20f, 18f)
            moveTo(10f, 4f)
            lineTo(10f, 8f)
            moveTo(6f, 10f)
            lineTo(6f, 14f)
            moveTo(14f, 16f)
            lineTo(14f, 20f)
        }
    }.build()

    /** BatteryCharging (Battery Optimization) */
    val BatteryCharging: ImageVector = ImageVector.Builder(
        name = "PhosphorBatteryCharging",
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
            moveTo(2f, 8f)
            arcTo(2f, 2f, 0f, false, true, 4f, 6f)
            lineTo(18f, 6f)
            arcTo(2f, 2f, 0f, false, true, 20f, 8f)
            lineTo(20f, 16f)
            arcTo(2f, 2f, 0f, false, true, 18f, 18f)
            lineTo(4f, 18f)
            arcTo(2f, 2f, 0f, false, true, 2f, 16f)
            close()
            moveTo(22f, 10f)
            lineTo(22f, 14f)
            moveTo(11.5f, 9f)
            lineTo(9f, 12.5f)
            lineTo(12.5f, 12.5f)
            lineTo(10.5f, 15f)
        }
    }.build()

    /** Folder (Storage / Directory) */
    val Folder: ImageVector = ImageVector.Builder(
        name = "PhosphorFolder",
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
            moveTo(3f, 6f)
            arcTo(1.5f, 1.5f, 0f, false, true, 4.5f, 4.5f)
            lineTo(9.5f, 4.5f)
            lineTo(12f, 7f)
            lineTo(19.5f, 7f)
            arcTo(1.5f, 1.5f, 0f, false, true, 21f, 8.5f)
            lineTo(21f, 18f)
            arcTo(1.5f, 1.5f, 0f, false, true, 19.5f, 19.5f)
            lineTo(4.5f, 19.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 3f, 18f)
            close()
        }
    }.build()

    /** Headphones (Audio Output / Headset) */
    val Headphones: ImageVector = ImageVector.Builder(
        name = "PhosphorHeadphones",
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
            moveTo(3f, 13f)
            arcTo(9f, 9f, 0f, true, true, 21f, 13f)
            // Left cup
            moveTo(3f, 13f)
            lineTo(3f, 18f)
            arcTo(2f, 2f, 0f, false, false, 5f, 20f)
            lineTo(6f, 20f)
            arcTo(2f, 2f, 0f, false, false, 8f, 18f)
            lineTo(8f, 13f)
            arcTo(2f, 2f, 0f, false, false, 6f, 11f)
            lineTo(5f, 11f)
            arcTo(2f, 2f, 0f, false, false, 3f, 13f)
            close()
            // Right cup
            moveTo(21f, 13f)
            lineTo(21f, 18f)
            arcTo(2f, 2f, 0f, false, true, 19f, 20f)
            lineTo(18f, 20f)
            arcTo(2f, 2f, 0f, false, true, 16f, 18f)
            lineTo(16f, 13f)
            arcTo(2f, 2f, 0f, false, true, 18f, 11f)
            lineTo(19f, 11f)
            arcTo(2f, 2f, 0f, false, true, 21f, 13f)
            close()
        }
    }.build()

    /** Bluetooth (Audio Output) */
    val Bluetooth: ImageVector = ImageVector.Builder(
        name = "PhosphorBluetooth",
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
            moveTo(6.5f, 6.5f)
            lineTo(17.5f, 17.5f)
            lineTo(12f, 22f)
            lineTo(12f, 2f)
            lineTo(17.5f, 6.5f)
            lineTo(6.5f, 17.5f)
        }
    }.build()

    /** DeviceMobile (Phone Speaker) */
    val DeviceMobile: ImageVector = ImageVector.Builder(
        name = "PhosphorDeviceMobile",
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
            moveTo(6.5f, 3f)
            arcTo(1.5f, 1.5f, 0f, false, false, 5f, 4.5f)
            lineTo(5f, 19.5f)
            arcTo(1.5f, 1.5f, 0f, false, false, 6.5f, 21f)
            lineTo(17.5f, 21f)
            arcTo(1.5f, 1.5f, 0f, false, false, 19f, 19.5f)
            lineTo(19f, 4.5f)
            arcTo(1.5f, 1.5f, 0f, false, false, 17.5f, 3f)
            close()
            moveTo(11f, 17.5f)
            lineTo(13f, 17.5f)
        }
    }.build()

    /** Earbuds (TWS / In-ear Bluetooth) */
    val Earbuds: ImageVector = ImageVector.Builder(
        name = "PhosphorEarbuds",
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
            // Left earbud
            moveTo(7f, 6f)
            arcTo(3f, 3f, 0f, false, false, 4f, 9f)
            arcTo(3f, 3f, 0f, false, false, 7f, 12f)
            lineTo(7f, 18f)
            arcTo(1f, 1f, 0f, false, false, 8f, 19f)
            arcTo(1f, 1f, 0f, false, false, 9f, 18f)
            lineTo(9f, 9f)
            arcTo(3f, 3f, 0f, false, false, 7f, 6f)
            close()
            // Right earbud
            moveTo(17f, 6f)
            arcTo(3f, 3f, 0f, false, true, 20f, 9f)
            arcTo(3f, 3f, 0f, false, true, 17f, 12f)
            lineTo(17f, 18f)
            arcTo(1f, 1f, 0f, false, true, 16f, 19f)
            arcTo(1f, 1f, 0f, false, true, 15f, 18f)
            lineTo(15f, 9f)
            arcTo(3f, 3f, 0f, false, true, 17f, 6f)
            close()
        }
    }.build()
}
