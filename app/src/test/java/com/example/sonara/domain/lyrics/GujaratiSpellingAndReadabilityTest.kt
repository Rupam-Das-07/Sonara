package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class GujaratiSpellingAndReadabilityTest {

    @Test
    fun testGujaratiCoreVocabulary() {
        val testCases = listOf(
            // Pronouns & Basic words
            "તમે" to "tame",
            "તમારું" to "tamaru",
            "તમારે" to "tamare",
            "તારા" to "tara",
            "તારો" to "taaro",
            "તારું" to "taaru",
            "તારી" to "taari",
            "મને" to "mane",
            "મારું" to "maaru",
            "મારો" to "maaro",
            "મારી" to "maari",
            "મારા" to "maara",
            "હું" to "hu",
            "અમે" to "ame",
            "અમારું" to "amaaru",
            "આપણે" to "aapne",
            "આપણું" to "aapnu",
            "કેમ" to "kem",
            "સારું" to "saaru",
            "નથી" to "nathi",
            "છે" to "chhe",
            "છો" to "chho",
            "હતું" to "hatu",
            "હશે" to "hashe",

            // Folk, Garba & Romance
            "વ્હાલમ" to "vhalam",
            "વ્હાલા" to "vhala",
            "વાત" to "vaat",
            "વાતો" to "vaato",
            "સાથ" to "saath",
            "સાથે" to "saathe",
            "ગરબા" to "garba",
            "દાંડિયા" to "dandiya",
            "ઢોલ" to "dhol",
            "ઢોલીડા" to "dholida",
            "ખલાસી" to "khalasi",
            "મોતી" to "moti",
            "દરિયો" to "dariyo",
            "મોગલ" to "mogal",
            "માડી" to "maadi",
            "ખમ્મા" to "khamma",
            "ચાલો" to "chalo",
            "આવો" to "aavo",
            "જોવો" to "jovo",
            "કહો" to "kaho",
            "સાંભળો" to "sambhalo",
            "મળ્યા" to "malya",
            "દિલ" to "dil",
            "પ્રેમ" to "prem",
            "જીવન" to "jeevan",
            "સપનું" to "sapnu",
            "આંખ" to "aankh",
            "આંખો" to "aankho",
            "રાત" to "raat",
            "દિવસ" to "divas",
            "પાણી" to "paani",
            "કમળ" to "kamal",
            "છોગાળા" to "chogada",
            "કમરિયા" to "kamariya"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Gujarati '$input'", expected, actual)
        }
    }

    @Test
    fun testGujaratiIconicSongLines() {
        val lines = listOf(
            "ગોતી લો ગોતી લો ખલાસી રે" to "goti lo goti lo khalasi re",
            "મોતી મારે ન જોવે રે" to "moti maare na jove re",
            "છોગાળા તારા ઓરે છબીલા તારા" to "chogada tara ore chhabila tara"
        )

        for ((input, expected) in lines) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for lyric '$input'", expected, actual)
        }
    }
}
