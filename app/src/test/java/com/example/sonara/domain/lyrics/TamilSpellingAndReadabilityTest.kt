package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TamilSpellingAndReadabilityTest {

    @Test
    fun testTamilCoreVocabulary() {
        val testCases = listOf(
            "தமிழ்" to "tamizh",
            "அழகு" to "azhagu",
            "மழை" to "mazhai",
            "வாழ்க" to "vaazhga",
            "காதல்" to "kaadhal",
            "காதலி" to "kaadhali",
            "அன்பு" to "anbu",
            "ஆசை" to "aasai",
            "உயிர்" to "uyir",
            "உயிரே" to "uyire",
            "மனம்" to "manam",
            "மனசு" to "manasu",
            "நெஞ்சம்" to "nenjam",
            "கண்" to "kan",
            "கண்ணே" to "kanne",
            "கண்கள்" to "kangal",
            "கண்ணீர்" to "kanneer",
            "நீ" to "nee",
            "நான்" to "naan",
            "உன்" to "un",
            "உன்னை" to "unnai",
            "என்" to "en",
            "என்னை" to "ennai",
            "வா" to "vaa",
            "போ" to "po",
            "வானம்" to "vaanam",
            "நிலா" to "nila",
            "நிலவே" to "nilave",
            "காற்று" to "kaatru",
            "பாடல்" to "paadal",
            "பாட்டு" to "paattu",
            "இசை" to "isai",
            "என்று" to "endru",
            "ஒன்று" to "ondru",
            "நன்றி" to "nandri",
            "வெற்றி" to "vetri",
            "கொஞ்சம்" to "konjam",
            "ரோஜா" to "roja",
            "மலரே" to "malare",
            "பூவே" to "poove"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Tamil '$input'", expected, actual)
        }
    }

    @Test
    fun testTamilIconicSongLines() {
        val lines = listOf(
            "முன்பே வா என் அன்பே வா" to "munbe vaa en anbe vaa",
            "பூவே பூவே பெண் பூவே" to "poove poove pen poove"
        )

        for ((input, expected) in lines) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for lyric '$input'", expected, actual)
        }
    }
}
