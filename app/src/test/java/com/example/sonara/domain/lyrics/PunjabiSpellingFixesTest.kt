package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class PunjabiSpellingFixesTest {

    @Test
    fun testPunjabiRequestedWordsGurmukhi() {
        val testCases = listOf(
            "ਚੜ੍ਹਿਆ" to "chadheya",
            "ਗਿਆ" to "gaya",
            "ਮਿਲਿਆ" to "mileya",
            "ਮੈਨੂੰ" to "mainu",
            "ਤੈਨੂੰ" to "tainu",
            "ਸਾਨੂੰ" to "saanu",
            "ਹਵਾਲੇ" to "hawale",
            "ਹਵਾਲਾ" to "hawala",
            "ਚੱਲਿਆ" to "challeya",
            "ਹੋਇਆ" to "hoya",
            "ਤਾਂ" to "taan",
            "ਭੁੱਲਿਆ" to "bhulleya",
            "ਛੱਡਿਆ" to "chaddeya",
            "ਜੋੜਿਆ" to "jodeya"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Gurmukhi input '$input'", expected, actual)
        }
    }

    @Test
    fun testPunjabiRequestedWordsDevanagari() {
        val testCases = listOf(
            "चढ़िया" to "chadheya",
            "गिया" to "gaya",
            "मिलिया" to "mileya",
            "मैनूं" to "mainu",
            "तैनूं" to "tainu",
            "हवाले" to "hawale",
            "हवाला" to "hawala",
            "चल्लिया" to "challeya",
            "होइया" to "hoya",
            "तां" to "taan",
            "भुल्लिया" to "bhulleya",
            "छोड़िया" to "chodeya",
            "जोड़िया" to "jodeya"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Devanagari input '$input'", expected, actual)
        }
    }
}
