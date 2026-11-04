package com.example.sonara.feature.player

import androidx.compose.ui.graphics.Color
import com.example.sonara.feature.player.components.formatTrackTitleWithViewCount
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTitleWithViewCountTest {

    private val primary = Color(0xFFE2E8F0)
    private val secondary = Color(0xFF94A3B8)

    @Test
    fun `both title and count present appends inline separator`() {
        val result = formatTrackTitleWithViewCount(
            title = "Bohemian Rhapsody",
            formattedCount = "1.2M views",
            primaryColor = primary,
            secondaryColor = secondary
        )
        assertEquals("Bohemian Rhapsody · 1.2M views", result.text)
    }

    @Test
    fun `blank title with valid count displays count without leading separator`() {
        val emptyTitleResult = formatTrackTitleWithViewCount(
            title = "",
            formattedCount = "1.2M views",
            primaryColor = primary,
            secondaryColor = secondary
        )
        assertEquals("1.2M views", emptyTitleResult.text)

        val whitespaceTitleResult = formatTrackTitleWithViewCount(
            title = "   ",
            formattedCount = "1.2M views",
            primaryColor = primary,
            secondaryColor = secondary
        )
        assertEquals("1.2M views", whitespaceTitleResult.text)
    }

    @Test
    fun `title with null or blank count renders title normally without separator or trailing space`() {
        val nullCountResult = formatTrackTitleWithViewCount(
            title = "Bohemian Rhapsody",
            formattedCount = null,
            primaryColor = primary,
            secondaryColor = secondary
        )
        assertEquals("Bohemian Rhapsody", nullCountResult.text)

        val blankCountResult = formatTrackTitleWithViewCount(
            title = "Bohemian Rhapsody",
            formattedCount = "   ",
            primaryColor = primary,
            secondaryColor = secondary
        )
        assertEquals("Bohemian Rhapsody", blankCountResult.text)
    }

    @Test
    fun `both title and count blank or null returns clean title`() {
        val result = formatTrackTitleWithViewCount(
            title = "",
            formattedCount = null,
            primaryColor = primary,
            secondaryColor = secondary
        )
        assertEquals("", result.text)
    }
}
