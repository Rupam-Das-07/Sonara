package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class UserRequestedSpellingFixesTest {

    @Test
    fun testUserRequestedSpellingFixesFromDevanagari() {
        val testCases = listOf(
            "रहती" to "rehti",
            "गूंजती" to "goonjti",
            "गूँजती" to "goonjti",
            "ढूंढती" to "dhoondhti",
            "ढूँढती" to "dhoondhti",
            "कहीं" to "kahin",
            "रहेंगे" to "rahenge",
            "चाहिए" to "chahiye",
            "जवाब" to "jawab",
            "चाहे" to "chahe"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for input '$input'", expected, actual)
        }
    }

    @Test
    fun testCrucialRegressionsPreserved() {
        val regressionCases = listOf(
            "ज़िन्दगी" to "zindagi",
            "ज़िंदगी" to "zindagi",
            "मौसमों" to "mausamon",
            "औरतों" to "auraton",
            "औरतें" to "auratein",
            "पहला" to "pehla",
            "पहली" to "pehli",
            "पहले" to "pehle",
            "गहरा" to "gehra",
            "कहना" to "kehna",
            "कहा" to "kaha",
            "कहानियां" to "kahaaniyaan",
            "वहाँ" to "wahaan",
            "वही" to "wahi",
            "वहीं" to "wahin",
            "वैसे" to "waise"
        )

        for ((input, expected) in regressionCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Regression failed for '$input'", expected, actual)
        }
    }
}
