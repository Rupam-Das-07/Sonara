package com.example.sonara.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTitleFormatterTest {

    @Test
    fun `clean studio title remains unchanged`() {
        assertEquals("Kesariya", MediaTitleFormatter.format("Kesariya", "Arijit Singh"))
    }

    @Test
    fun `removes confident artist prefix and promotional label`() {
        assertEquals(
            "Believer",
            MediaTitleFormatter.format(
                "Imagine Dragons - Believer (Official Music Video)",
                "Imagine Dragons"
            )
        )
    }

    @Test
    fun `preserves meaningful version and hyphenated title`() {
        val title = "Love Story - Live Acoustic Version"
        assertEquals(title, MediaTitleFormatter.format(title))
        assertEquals(
            "Blinding Lights (Live Acoustic Version)",
            MediaTitleFormatter.format("The Weeknd - Blinding Lights (Live Acoustic Version)", "The Weeknd")
        )
    }

    @Test
    fun `keeps global scripts readable`() {
        assertEquals("கேசரியா", MediaTitleFormatter.format("கேசரியா", "அரிஜித் சிங்"))
        assertEquals("夜に駆ける", MediaTitleFormatter.format("夜に駆ける", "YOASOBI"))
    }

    @Test
    fun `empty and blank input use safe fallback`() {
        assertEquals("", MediaTitleFormatter.format(""))
        assertEquals("   ", MediaTitleFormatter.format("   "))
    }

    @Test
    fun `long title is truncated at a word boundary`() {
        val formatted = MediaTitleFormatter.format(
            "This is a deliberately long title that should be truncated at a meaningful word boundary before it becomes too wide"
        )
        assertTrue(formatted.length <= 91)
        assertTrue(formatted.endsWith("…"))
    }

    @Test
    fun `formats animated video promotional variants correctly`() {
        // 1. Exact reproduction with spaces inside brackets
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Levitating ( Official Animated Music Video )", "Dua Lipa")
        )

        // 2. Standard Official Animated Music Video
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Levitating (Official Animated Music Video)", "Dua Lipa")
        )

        // 3. Official Animated Video
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Levitating (Official Animated Video)", "Dua Lipa")
        )

        // 4. Animated Music Video
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Levitating (Animated Music Video)", "Dua Lipa")
        )

        // 5. Animated Video
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Levitating (Animated Video)", "Dua Lipa")
        )

        // 6. Official Animation Video
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Levitating (Official Animation Video)", "Dua Lipa")
        )

        // 7. Square bracket notation
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Levitating [Official Animated Video]")
        )

        // 8. Post-hyphen animated promotional suffix
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Dua Lipa - Levitating - Official Animated Music Video", "Dua Lipa")
        )
        assertEquals(
            "Levitating",
            MediaTitleFormatter.format("Levitating - Animated Video")
        )
    }

    @Test
    fun `strictly preserves animated and acoustic version distinctions`() {
        // 9. Preservation of Animated Version
        assertEquals(
            "Song (Animated Version)",
            MediaTitleFormatter.format("Song (Animated Version)")
        )

        // 10. Preservation of Animated Remix
        assertEquals(
            "Song (Animated Remix)",
            MediaTitleFormatter.format("Song (Animated Remix)")
        )

        // 11. Preservation of Acoustic Animated Video
        assertEquals(
            "Song (Acoustic Animated Video)",
            MediaTitleFormatter.format("Song (Acoustic Animated Video)")
        )
    }
}
