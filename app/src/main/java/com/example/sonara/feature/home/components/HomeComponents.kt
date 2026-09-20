package com.example.sonara.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.sonara.core.ui.theme.SonaraTheme

/**
 * Editorial section header — compact title with optional quiet trailing action.
 *
 * Phase 5E §11: Strong section title, quiet trailing action (e.g. "More →" or "Refresh").
 * No enclosing pills, no decorative lines, no bloated button padding.
 */
@Composable
fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography
    val dimensions = SonaraTheme.dimensions

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = typography.sectionTitle,
            color = colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (actionLabel != null && onAction != null) {
            Row(
                modifier = Modifier
                    .clip(SonaraTheme.shapes.small)
                    .clickable(onClick = onAction)
                    .padding(horizontal = dimensions.spaceXs, vertical = dimensions.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensions.spaceXs)
            ) {
                if (actionIcon != null) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        tint = colors.secondaryText,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = actionLabel,
                    style = typography.caption,
                    color = colors.secondaryText
                )
            }
        }
    }
}

/**
 * Artwork container with guaranteed truthful typographic fallback over neutral Petrol/Bone.
 */
@Composable
fun HomeArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = SonaraTheme.shapes.medium,
    fallback: @Composable () -> Unit
) {
    val colors = SonaraTheme.colors
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            fallback()
        }
    }
}

/**
 * Typographic initials fallback for a person (Featured Artists).
 */
@Composable
fun Monogram(
    name: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = SonaraTheme.typography.display
) {
    val colors = SonaraTheme.colors
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = monogramInitials(name),
            style = textStyle,
            color = colors.primaryText,
            maxLines = 1
        )
    }
}

/**
 * Single-glyph fallback for tracks/playlists.
 */
@Composable
fun GlyphFallback(
    modifier: Modifier = Modifier,
    glyph: String = "♪",
    tint: Color = SonaraTheme.colors.accent,
    fontSize: TextUnit = 22.sp
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = glyph,
            color = tint,
            fontSize = fontSize
        )
    }
}

private fun monogramInitials(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val parts = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (parts.size) {
        1 -> parts[0].take(2).uppercase()
        else -> "${parts[0].take(1)}${parts.last().take(1)}".uppercase()
    }
}
