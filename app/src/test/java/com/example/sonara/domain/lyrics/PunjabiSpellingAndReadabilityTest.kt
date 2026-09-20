package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class PunjabiSpellingAndReadabilityTest {

    @Test
    fun testPunjabiCoreVocabulary() {
        val testCases = listOf(
            // Pronouns & Function words
            "ਮੈਨੂੰ" to "mainu",
            "ਤੈਨੂੰ" to "tainu",
            "ਸਾਨੂੰ" to "saanu",
            "ਤੁਹਾਨੂੰ" to "tuhanu",
            "ਉਹਨੂੰ" to "ohnu",
            "ਇਹਨੂੰ" to "ehnu",
            "ਕੋਲ਼" to "kol",
            "ਵਿੱਚ" to "vich",
            "ਵਿਚ" to "vich",
            "ਨਾਲ" to "naal",
            "ਸਾਮ੍ਹਣੇ" to "samne",
            "ਸਾਹਮਣੇ" to "samne",
            "ਕਿਉਂ" to "kyun",
            "ਕਿਉਂਕਿ" to "kyunki",
            "ਨਹੀਂ" to "nahi",
            "ਨਈਂ" to "nahi",

            // Participles / Verbs
            "ਹੋਇਆ" to "hoya",
            "ਗਿਆ" to "gaya",
            "ਮਿਲਿਆ" to "mileya",
            "ਚੜ੍ਹਿਆ" to "chadheya",
            "ਚੱਲਿਆ" to "challeya",
            "ਛੱਡਿਆ" to "chaddeya",
            "ਸੁਣਿਆ" to "suneya",
            "ਰੱਖਿਆ" to "rakheya",
            "ਦੱਸਿਆ" to "dasseya",
            "ਵੇਖਿਆ" to "vekheya",
            "ਸਮਝਿਆ" to "samjheya",
            "ਮੰਗਿਆ" to "mangeya",
            "ਲੱਭਿਆ" to "labbheya",
            "ਦੱਸਦੇ" to "dassde",
            "ਕਰਦੇ" to "karde",
            "ਰਹਿ" to "reh",
            "ਕਹਿ" to "keh",

            // Nouns & Adjectives
            "ਕੁੜੀ" to "kudi",
            "ਕੁੜੀਆਂ" to "kudiyan",
            "ਕੁੜੀਏ" to "kudiye",
            "ਮੁੰਡਾ" to "munda",
            "ਮੁੰਡੇ" to "munde",
            "ਮੁੰਡਿਆਂ" to "mundeyaan",
            "ਸੋਹਣੀ" to "sohni",
            "ਸੋਹਣਾ" to "sohna",
            "ਸੋਹਣੇ" to "sohne",
            "ਸੋਹਣਿਆਂ" to "sohneyaan",
            "ਅੱਖ" to "akh",
            "ਅੱਖਾਂ" to "akkhan",
            "ਹੱਥ" to "hath",
            "ਹੱਥਾਂ" to "hatthan",
            "ਦਿਲ" to "dil",
            "ਪਿਆਰ" to "pyaar",
            "ਯਾਰ" to "yaar",
            "ਯਾਰਾਂ" to "yaaran",
            "ਰੱਬ" to "rabb",
            "ਜੱਟ" to "jatt",
            "ਗੱਡੀ" to "gaddi",
            "ਸ਼ੌਂਕ" to "shauk",
            "ਦੁਨੀਆ" to "duniya",
            "ਜ਼ਹਿਰ" to "zeher",
            "ਸ਼ਹਿਰ" to "sheher",
            "ਬਗ਼ੈਰ" to "baghair",
            "ਚੰਡੀਗੜ੍ਹ" to "chandigarh",
            "ਪੰਜਾਬ" to "punjab",
            "ਪੰਜਾਬੀ" to "punjabi"
        )

        for ((input, expected) in testCases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Punjabi '$input'", expected, actual)
        }
    }

    @Test
    fun testPunjabiIconicSongLines() {
        val lines = listOf(
            "ਕੋਲ਼ ਐ ਤੂੰ ਸਾਂਭ ਲੈ ਦਿਲ ਮੇਰੇ ਨੂੰ" to "kol ai tu saambh lai dil mere nu",
            "ਮੇਰੀਆਂ ਅੱਖਾਂ ਦੇ ਸਾਮ੍ਹਣੇ ਰਹਿ" to "meriyan akkhan de samne reh",
            "ਦਿਲ ਮੇਰਾ ਕੱਢ ਕੇ ਲੈ ਗਿਆ" to "dil mera kaddh ke lai gaya"
        )

        for ((input, expected) in lines) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for lyric '$input'", expected, actual)
        }
    }
}
