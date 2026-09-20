package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class UserRequestedBatchThreeSpellingFixesTest {

    @Test
    fun testBatchThreeRequestedWordsAndParadigms() {
        val testCases = listOf(
            // 1. toone -> tune and pronoun paradigms
            "तूने" to "tune",
            "तू" to "tu",
            "तुझे" to "tujhe",
            "तुझको" to "tujhko",
            
            // 2. kitanon -> kitnon and quantifier paradigms
            "कितनों" to "kitnon",
            "जितनों" to "jitnon",
            "इतनों" to "itnon",
            "उतनों" to "utnon",
            "कितना" to "kitna",
            "कितनी" to "kitni",
            "कितने" to "kitne",
            
            // 3. jaaoon, lagaoon, manaaoon and 1st-person subjunctive verbs
            "जाऊँ" to "jaaun",
            "जाऊं" to "jaaun",
            "लगाऊँ" to "lagaun",
            "लगाऊं" to "lagaun",
            "मनाऊँ" to "manaun",
            "मनाऊं" to "manaun",
            "आऊँ" to "aaun",
            "आऊं" to "aaun",
            "गाऊँ" to "gaaun",
            "गाऊं" to "gaaun",
            "करूँ" to "karun",
            "करूं" to "karun",
            "रहूँ" to "rahun",
            "कहूँ" to "kahun",
            "सुनूँ" to "sunun",
            "देखूँ" to "dekhun",
            "मिलूँ" to "milun",
            
            // 4. sanaaton -> sannaton and geminate nn words
            "सन्नाटों" to "sannaton",
            "सन्नाटा" to "sannata",
            "सन्नाटे" to "sannate",
            "तमन्ना" to "tamanna",
            "पन्ना" to "panna"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for input '$input'", expected, actual)
        }
    }
}
