package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class UserRequestedBatchTwoSpellingFixesTest {

    @Test
    fun testBatchTwoDevanagariWords() {
        val testCases = listOf(
            "दीवाने" to "deewane",
            "दीवाना" to "deewana",
            "दीवानी" to "deewani",
            "पूछो" to "pucho",
            "पूछा" to "pucha",
            "पूछना" to "puchna",
            "फिलहाल" to "filhaal",
            "फ़िलहाल" to "filhaal",
            "तस्वीर" to "tasveer",
            "नज़रों" to "nazron",
            "नजरों" to "nazron",
            "सामने" to "samne",
            "तुम्हारे" to "tumhare",
            "तुम्हारा" to "tumhara",
            "तुम्हारी" to "tumhari",
            "हमारे" to "humare",
            "हमारा" to "humara",
            "हमारी" to "humari"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for input '$input'", expected, actual)
        }
    }
}
