package com.example.sonara.feature.home.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.sonara.core.ui.theme.SonaraTheme
import java.util.Calendar

/**
 * HomeGreeting — Simple contextual time-of-day greeting for the Home screen.
 *
 * Phase 5E §4: Contextual greeting ("Good morning", etc.).
 * Flushes with content edge. No subtitles, no journal stamps, no decoration.
 */
@Composable
fun HomeGreeting(
    modifier: Modifier = Modifier
) {
    val colors = SonaraTheme.colors
    val typography = SonaraTheme.typography

    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 0..4 -> "Good night"
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
    }

    Text(
        text = greeting,
        style = typography.greeting,
        color = colors.primaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth()
    )
}
