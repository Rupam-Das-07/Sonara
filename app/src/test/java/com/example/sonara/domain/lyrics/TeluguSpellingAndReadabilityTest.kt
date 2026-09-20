package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class TeluguSpellingAndReadabilityTest {

    @Test
    fun testTeluguCoreVocabulary() {
        val testCases = listOf(
            "సామజవరగమనా" to "samajavaragamana",
            "బుట్టబొమ్మా" to "buttabomma",
            "నువ్వు" to "nuvvu",
            "నేను" to "nenu",
            "నాటు" to "naatu",
            "వచ్చిందే" to "vachinde",
            "శ్రీవల్లి" to "srivalli",
            "గుండె" to "gunde",
            "హృదయం" to "hrudayam",
            "ప్రేమ" to "prema",
            "జీవితం" to "jeevitham",
            "సంగీతం" to "sangeetham",
            "ఎందుకు" to "enduku",
            "ఎక్కడ" to "ekkada",
            "చెలియా" to "cheliya",
            "సఖియా" to "sakhiya",
            "వెన్నెల" to "vennela",
            "కన్నులు" to "kannulu",
            "కళ్ళు" to "kallu",
            "మాట" to "maata",
            "ప్రాణం" to "praanam",
            "శ్వాਸ" to "shwaasa",
            "చూపు" to "choopu",
            "పిల్లా" to "pilla",
            "రాధ" to "radha",
            "కృష్ణ" to "krishna",
            "అందం" to "andham",
            "సొంతం" to "sontham",
            "బంధం" to "bandham",
            "కాలం" to "kaalam",
            "లోకం" to "lokam",
            "కలిసి" to "kalisi",
            "రావాలి" to "raavali",
            "పోవాలి" to "povaali",
            "ఉంది" to "undi",
            "ఉన్నావు" to "unnaavu",
            "గాలి" to "gaali",
            "నీరు" to "neeru",
            "ఆకాశం" to "aakaasham",
            "తీరం" to "theeram"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Telugu '$input'", expected, actual)
        }
    }

    @Test
    fun testTeluguIconicSongLines() {
        val lines = listOf(
            "బుట్టబొమ్మా బుట్టబొమ్మా" to "buttabomma buttabomma",
            "ఇంకేం కావాలే చాలే ఇది చాలే" to "inkem kaavaale chaale idi chaale"
        )

        for ((input, expected) in lines) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for lyric '$input'", expected, actual)
        }
    }
}
