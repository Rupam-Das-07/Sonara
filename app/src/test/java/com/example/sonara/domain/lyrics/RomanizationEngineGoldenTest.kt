package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.InputStreamReader

class RomanizationEngineGoldenTest {

    private data class TestCase(val original: String, val expected: String, val language: String)

    private fun parseTestCases(resourcePath: String): List<TestCase> {
        val stream = javaClass.classLoader?.getResourceAsStream(resourcePath)
        assertNotNull("$resourcePath not found in test resources", stream)

        val jsonText = InputStreamReader(stream!!, Charsets.UTF_8).use { it.readText() }
        val testCases = mutableListOf<TestCase>()

        // Robust regex parser for json test fixtures without relying on Android SDK stubs
        val objectRegex = Regex("\\{([^}]+)\\}")
        val fieldRegex = Regex("\"(\\w+)\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")

        for (match in objectRegex.findAll(jsonText)) {
            val body = match.groupValues[1]
            var original = ""
            var expected = ""
            var language = "Unknown"

            for (fieldMatch in fieldRegex.findAll(body)) {
                val key = fieldMatch.groupValues[1]
                val value = fieldMatch.groupValues[2].replace("\\\"", "\"")
                when (key) {
                    "original" -> original = value
                    "expected" -> expected = value
                    "language" -> language = value
                }
            }

            if (original.isNotEmpty() && expected.isNotEmpty()) {
                testCases.add(TestCase(original, expected, language))
            }
        }

        return testCases
    }

    @Test
    fun `verify all top readability test cases`() {
        val cases = parseTestCases("golden/top_readability_cases.json")
        assertEquals("Expected 48 top readability cases", 48, cases.size)

        var passed = 0
        for (test in cases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] Transliteration mismatch for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("Verified $passed / ${cases.size} top readability golden cases.")
    }

    @Test
    fun `verify benchmark test cases produce pure Latin transliteration`() {
        val cases = parseTestCases("golden/benchmark.json")
        assertEquals("Expected 106 benchmark cases", 106, cases.size)

        var passed = 0
        for (test in cases) {
            val actual = RomanizationEngine.transliterate(test.original)
            org.junit.Assert.assertTrue(
                "Transliteration for '${test.original}' should not be empty",
                actual.isNotBlank()
            )
            val hasIndic = actual.any { it.code in 0x0900..0x0D7F }
            org.junit.Assert.assertTrue(
                "Transliterated output '$actual' should not contain Indic script characters",
                !hasIndic
            )
            passed++
        }
        println("Verified $passed / ${cases.size} benchmark golden cases.")
    }

    @Test
    fun `verify precomposed nuktas, candra vowels and special characters regression`() {
        val regressionCases = listOf(
            // Precomposed vs Decomposed KHHA (ख़ / ख़ / ਖ਼ / ਖ਼)
            TestCase("ख़बर", "khabar", "Hindi Precomposed KHHA"),
            TestCase("ख़बर", "khabar", "Hindi Decomposed KHA+Nukta"),
            TestCase("ਖ਼ਬਰ", "khabar", "Punjabi Precomposed KHHA"),
            TestCase("ਖ਼ਬਰ", "khabar", "Punjabi Decomposed KHA+Nukta"),

            // Precomposed vs Decomposed GHHA (ग़ / ग़ / ਗ਼ / ਗ਼)
            TestCase("ग़ज़ल", "ghazal", "Hindi Precomposed GHHA+ZA"),
            TestCase("ग़ज़ल", "ghazal", "Hindi Decomposed GA+Nukta+JA+Nukta"),
            TestCase("ग़म", "gham", "Hindi Precomposed GHHA"),
            TestCase("ग़म", "gham", "Hindi Decomposed GA+Nukta"),
            TestCase("ਗ਼ਮ", "gham", "Punjabi Precomposed GHHA"),
            TestCase("ਗ਼ਮ", "gham", "Punjabi Decomposed GA+Nukta"),

            // Precomposed vs Decomposed RHHA (ढ़ / ढ़)
            TestCase("सीढ़ी", "seedhi", "Hindi Precomposed RHHA"),
            TestCase("सीढ़ी", "seedhi", "Hindi Decomposed DHA+Nukta"),

            // Candra Vowels for English Loanwords (ॉ, ॅ, ऍ, ऑ)
            TestCase("डॉक्टर", "doktar", "Hindi Candra O Doctor"),
            TestCase("कॉलेज", "kolej", "Hindi Candra O College"),
            TestCase("ऑल", "ol", "Hindi Independent Candra O All"),
            TestCase("स्टॉप", "stop", "Hindi Candra O Stop"),
            TestCase("बॉस", "bos", "Hindi Candra O Boss"),

            // Bengali Khanda-Ta (ৎ)
            TestCase("হঠাৎ", "hothat", "Bengali Khanda-Ta Hothat")
        )

        var passed = 0
        for (test in regressionCases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] Regression mismatch for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("Verified $passed / ${regressionCases.size} precomposed nukta and candra vowel regression cases.")
    }

    @Test
    fun `verify GO rules and real-world multi-language lyrics suite`() {
        val goCases = listOf(
            // Hindi / Urdu /h/ Fronting in Closed Roots
            TestCase("गहरा हुआ", "gehra hua", "Hindi /h/ Fronting Gehra"),
            TestCase("पहला पहला प्यार", "pehla pehla pyaar", "Hindi /h/ Fronting Pehla"),
            TestCase("कहना ही क्या", "kehna hi kya", "Hindi /h/ Fronting Kehna"),
            TestCase("बहती हवा", "behti hawa", "Hindi /h/ Fronting Behti"),
            TestCase("रह नहीं सकते", "reh nahi sakte", "Hindi /h/ Fronting Reh"),
            TestCase("शहर में", "sheher mein", "Hindi /h/ Fronting Sheher"),
            TestCase("ज़हर", "zeher", "Hindi /h/ Fronting Zeher"),
            TestCase("लहर", "leher", "Hindi /h/ Fronting Leher"),

            // Hindi /w/ Glide in Conjuncts and Colloquial Roots
            TestCase("ख़्वाबों के परिंदे", "khwaabon ke parinde", "Hindi Conjunct W Khwab"),
            TestCase("शिकवा", "shikwa", "Hindi Conjunct W Shikwa"),
            TestCase("दीवाना सनम", "deewana sanam", "Hindi Colloquial Root Deewana"),
            TestCase("परवाना", "parwana", "Hindi Colloquial Root Parwana"),
            TestCase("सवार", "sawar", "Hindi Colloquial Root Sawar"),
            TestCase("दवा", "dawa", "Hindi Colloquial Root Dawa"),

            // Hindi Suffixes and Candrabindu
            TestCase("अदाएँ हैं", "adaayein hain", "Hindi Feminine Plural -yein"),
            TestCase("हवाएँ", "hawaayein", "Hindi Feminine Plural -yein"),
            TestCase("दुआएँ", "duaayein", "Hindi Feminine Plural -yein"),
            TestCase("आँखों में", "aankhon mein", "Hindi Oblique Plural -on"),
            TestCase("बाहों में", "baahon mein", "Hindi Oblique Plural -on"),
            TestCase("राहें", "raahein", "Hindi Oblique Plural -ein"),
            TestCase("बातें", "baatein", "Hindi Oblique Plural -ein"),
            TestCase("चाँद", "chaand", "Hindi Candrabindu Chaand"),
            TestCase("साँस", "saans", "Hindi Candrabindu Saans"),

            // Hindi Closed-Class Function Words
            TestCase("नहीं", "nahi", "Hindi Closed-Class Nahi"),
            TestCase("हम", "hum", "Hindi Closed-Class Hum"),
            TestCase("क्यों", "kyun", "Hindi Closed-Class Kyun"),
            TestCase("हो गई", "ho gayi", "Hindi Closed-Class Gayi"),
            TestCase("खो गए", "kho gaye", "Hindi Closed-Class Gaye"),
            TestCase("वो", "woh", "Hindi Closed-Class Woh"),
            TestCase("यह", "yeh", "Hindi Closed-Class Yeh"),

            // Punjabi Gurmukhi
            TestCase("ਤੈਨੂੰ ਕਾਲਾ ਚਸ਼ਮਾ ਜਚਦਾ ਐ", "tainu kaala chashma jachda ai", "Punjabi Tainu"),
            TestCase("ਕੁੜੀ ਨਮਕੀਨ ਬੜੀ ਲੱਗਦੀ ਐ", "kudi namkeen badi laggdi ai", "Punjabi Kudi"),
            TestCase("ਮਿੱਤਰਾਂ ਦਾ ਨਾਂ ਚੱਲਦਾ", "mittraan daa naan chalda", "Punjabi Mittran"),
            TestCase("ਸੋਹਣੀਏ", "sohniye", "Punjabi Sohniye"),

            // Bengali
            TestCase("আমি তোমায় ভালোবাসি", "ami tomay bhalobashi", "Bengali Bhalobashi"),
            TestCase("আমার পরাণ যাহা চায়", "amar poran jaha chay", "Bengali Poran"),

            // Tamil
            TestCase("காதல் ரோஜாவே எங்கே நீ எங்கே", "kaadhal rojaave enge nee enge", "Tamil Kaadhal"),

            // Telugu Dravidian Nasal Assimilation
            TestCase("గుండెల్లో ఏముందో", "gundello emundo", "Telugu Gundello Retroflex Assimilation"),
            TestCase("నీవే నీవే నా ప్రాణము", "neeve neeve naa praanamu", "Telugu Neeve Praanamu"),

            // Malayalam
            TestCase("ഞാൻ നിന്നെ സ്നേഹിക്കുന്നു", "njaan ninne snehikkunnu", "Malayalam Njaan"),
            TestCase("കുഞ്ഞേ കുഞ്ഞേ", "kunje kunje", "Malayalam Kunje"),

            // Kannada
            TestCase("ಪರಮಾತ್ಮ ನೀನೆ ಚೆಲುವಿನ ತಾರೆ", "paramaatma neene cheluvina taare", "Kannada Paramaatma")
        )

        var passed = 0
        for (test in goCases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] GO rule mismatch for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("Verified $passed / ${goCases.size} GO rule test cases.")
    }

    @Test
    fun `verify counterexample protections and NO-GO safeguards`() {
        val safeguardCases = listOf(
            // /h/ Open Syllable Counterexamples (Fronting MUST NOT occur)
            TestCase("रहा", "raha", "Hindi /h/ Open Syllable Raha"),
            TestCase("रही", "rahi", "Hindi /h/ Open Syllable Rahi"),
            TestCase("रहे", "rahe", "Hindi /h/ Open Syllable Rahe"),
            TestCase("कहा", "kaha", "Hindi /h/ Open Syllable Kaha"),
            TestCase("कहो", "kaho", "Hindi /h/ Open Syllable Kaho"),
            TestCase("पहाड़", "pahaad", "Hindi /h/ Open Syllable Pahad"),
            TestCase("कहानी", "kahaani", "Hindi /h/ Open Syllable Kahani"),
            TestCase("बहाना", "bahaana", "Hindi /h/ Open Syllable Bahana"),
            TestCase("बहादुर", "bahaadur", "Hindi /h/ Open Syllable Bahadur"),
            TestCase("महल", "mahal", "Hindi /h/ Open Syllable Mahal"),
            TestCase("महान", "mahaan", "Hindi /h/ Open Syllable Mahan"),
            TestCase("सहसा", "sahsa", "Hindi /h/ Sanskrit Adverb Sahasa"),

            // V/W Sanskrit Tatsama Protections ('v' MUST be preserved)
            TestCase("विश्वास", "vishwaas", "Hindi Tatsama Vishwas"),
            TestCase("विचार", "vichaar", "Hindi Tatsama Vichaar"),
            TestCase("कविता", "kavita", "Hindi Tatsama Kavita"),
            TestCase("जीवन", "jeevan", "Hindi Tatsama Jeevan"),
            TestCase("पवित्र", "pavitr", "Hindi Tatsama Pavitra"),
            TestCase("विदाई", "vidaai", "Hindi Tatsama Vidai"),
            TestCase("विजय", "vijay", "Hindi Tatsama Vijay"),
            TestCase("विकास", "vikaas", "Hindi Tatsama Vikas"),
            TestCase("वरदान", "vardaan", "Hindi Tatsama Vardan"),
            TestCase("देवता", "devta", "Hindi Tatsama Devta"),
            TestCase("வாழ்வு", "vaazhvu", "Tamil Preserve V Vaazhvu"),

            // Verbal/Adjectival Non-Nasal 'ए' (MUST NOT become -yein)
            TestCase("गए", "gaye", "Hindi Verb Non-Nasal Gaye"),
            TestCase("हुए", "hue", "Hindi Verb Non-Nasal Huye"),
            TestCase("नए", "naye", "Hindi Adjective Non-Nasal Naye"),
            TestCase("आए", "aaye", "Hindi Verb Non-Nasal Aaye"),

            // South Indian Vowel Length Protections (NO Vowel Compression)
            TestCase("காதல்", "kaadhal", "Tamil Vowel Length Kaadhal"),
            TestCase("நீவே", "neeve", "Telugu Vowel Length Neeve"),
            TestCase("ಪ್ರಾಣಮು", "praanamu", "Telugu Vowel Length Praanamu")
        )

        var passed = 0
        for (test in safeguardCases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] Safeguard failure for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("Verified $passed / ${safeguardCases.size} counterexample protection test cases.")
    }

    /**
     * Regression tests for R1, R2, R3 and माँ/मान disambiguation.
     * These cover ONLY rules approved after adversarial validation.
     * No song-specific or arbitrary word patches are present.
     */
    @Test
    fun `verify R1 i-stem verb glide ie to iye`() {
        val r1Cases = listOf(
            // R1 positive cases: word-final consonant+ie → consonant+iye
            TestCase("लिए", "liye", "Hindi R1 Liye"),
            TestCase("के लिए", "ke liye", "Hindi R1 Ke Liye"),
            TestCase("किए", "kiye", "Hindi R1 Kiye"),
            TestCase("जिए", "jiye", "Hindi R1 Jiye"),
            TestCase("दिए", "diye", "Hindi R1 Diye"),
            TestCase("पिए", "piye", "Hindi R1 Piye"),
            TestCase("खिए", "khiye", "Hindi R1 Khiye"),
            TestCase("प्यार के लिए जीते हैं", "pyaar ke liye jeete hain", "Hindi R1 Full Line Liye"),

            // R1 non-regression: these must NOT be touched (Latin-block bypass or different morphology)
            // English words with -ie suffix never enter the transliterator — verified safe by block segmentation.
            // Devanagari words not ending in consonant+ie must be preserved:
            TestCase("मेरी", "meri", "Hindi R1 Non-Regression Meri"),
            TestCase("खुशी", "khushi", "Hindi R1 Non-Regression Khushi"),
            TestCase("ज़िंदगी", "zindagi", "Hindi R1 Non-Regression Zindagi")
        )
        var passed = 0
        for (test in r1Cases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] R1 regression failure for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("R1: $passed / ${r1Cases.size} ie→iye regression cases passed.")
    }

    @Test
    fun `verify R2 subjunctive-conjunctive aae to aaye`() {
        val r2Cases = listOf(
            // R2 positive cases: word-final -aae → -aaye (subjunctive/conjunctive verb forms)
            TestCase("जाए", "jaaye", "Hindi R2 Jaaye"),
            TestCase("आए", "aaye", "Hindi R2 Aaye"),
            TestCase("पाए", "paaye", "Hindi R2 Paaye"),
            TestCase("खाए", "khaaye", "Hindi R2 Khaaye"),
            TestCase("गाए", "gaaye", "Hindi R2 Gaaye"),
            TestCase("लाए", "laaye", "Hindi R2 Laaye"),

            // R2 must not affect these (already handled by existing rules):
            TestCase("आए", "aaye", "Hindi R2 Standalone Aaye"),

            // Existing safeguard: गए must remain gaye (already in HINDI_CLOSED_CLASS)
            TestCase("गए", "gaye", "Hindi R2 Non-Regression Gaye")
        )
        var passed = 0
        for (test in r2Cases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] R2 regression failure for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("R2: $passed / ${r2Cases.size} aae→aaye regression cases passed.")
    }

    @Test
    fun `verify R3 IAST conjunct jña to gy before stripMarkers`() {
        val r3Cases = listOf(
            // R3 positive cases: ज्ञ → gy (IAST jñ → gy before stripMarkers converts ñ→n)
            TestCase("ज्ञान", "gyaan", "Hindi R3 Gyan"),
            TestCase("विज्ञान", "vigyaan", "Hindi R3 Vigyan"),
            TestCase("अज्ञान", "agyaan", "Hindi R3 Agyan"),
            TestCase("यज्ञ", "yagy", "Hindi R3 Yagya"),

            // R3 non-regression: ञ in conjuncts other than jñ must NOT be affected
            // ञ्ज → nj (anjali, panjaab) must remain correct
            TestCase("अंजलि", "anjali", "Hindi R3 Non-Regression Anjali Nj"),
            TestCase("पंजाब", "panjaab", "Hindi R3 Non-Regression Punjab Nj")
        )
        var passed = 0
        for (test in r3Cases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] R3 regression failure for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("R3: $passed / ${r3Cases.size} jñ→gy regression cases passed.")
    }

    @Test
    fun `verify maa maan disambiguation source-aware protection`() {
        val maaCases = listOf(
            // माँ (mother) must stay "maa" — monosyllabic, candrabindu-nasalized ā
            TestCase("माँ", "maa", "Hindi Maa Mother Protection"),
            TestCase("मेरी माँ", "meri maa", "Hindi Maa In Phrase"),
            TestCase("माँ के लिए", "maa ke liye", "Hindi Maa With Liye"),

            // मान (honor) must stay "maan" — bisyllabic māna, no candrabindu
            TestCase("मान", "maan", "Hindi Maan Honor Preserved"),
            TestCase("मान-सम्मान", "maan-sammaan", "Hindi Maan In Compound"),

            // Both must be distinct — the core regression
            // माँ → maa, मान → maan; these are different words
            TestCase("माँ का मान", "maa kaa maan", "Hindi Maa Maan Distinction")
        )
        var passed = 0
        for (test in maaCases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] माँ/मान disambiguation failure for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("माँ/मान: $passed / ${maaCases.size} disambiguation regression cases passed.")
    }

    @Test
    fun `verify diphthong protection in schwa deletion for ai and au`() {
        val diphthongCases = listOf(
            TestCase("मौसमों", "mausamon", "Hindi Diphthong Mausamon"),
            TestCase("मौसम", "mausam", "Hindi Diphthong Mausam"),
            TestCase("औरत", "aurat", "Hindi Diphthong Aurat"),
            TestCase("औरतें", "auratein", "Hindi Diphthong Auratein"),
            TestCase("औरतों", "auraton", "Hindi Diphthong Auraton"),
            TestCase("पैदा", "paida", "Hindi Diphthong Paida"),
            TestCase("मैदान", "maidaan", "Hindi Diphthong Maidaan"),
            TestCase("हैरान", "hairaan", "Hindi Diphthong Hairaan"),
            TestCase("दौलत", "daulat", "Hindi Diphthong Daulat"),
            TestCase("रौनक", "raunak", "Hindi Diphthong Raunak"),
            TestCase("फ़ैसला", "faisla", "Hindi Diphthong Faisla"),
            TestCase("हौसला", "hausla", "Hindi Diphthong Hausla")
        )
        var passed = 0
        for (test in diphthongCases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] Diphthong protection failure for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("Diphthongs: $passed / ${diphthongCases.size} protection cases passed.")
    }

    @Test
    fun `verify inflectional h-fronting across grammatical stems and counterexamples`() {
        val hFrontingCases = listOf(
            // पहला stem
            TestCase("पहला", "pehla", "Hindi H-Fronting Pehla"),
            TestCase("पहली", "pehli", "Hindi H-Fronting Pehli"),
            TestCase("पहले", "pehle", "Hindi H-Fronting Pehle"),

            // गहरा stem
            TestCase("गहरा", "gehra", "Hindi H-Fronting Gehra"),
            TestCase("गहरी", "gehri", "Hindi H-Fronting Gehri"),
            TestCase("गहरे", "gehre", "Hindi H-Fronting Gehre"),

            // कहना stem
            TestCase("कहना", "kehna", "Hindi H-Fronting Kehna"),
            TestCase("कहता", "kehta", "Hindi H-Fronting Kehta"),
            TestCase("कहती", "kehti", "Hindi H-Fronting Kehti"),
            TestCase("कहते", "kehte", "Hindi H-Fronting Kehte"),
            TestCase("कहकर", "kehkar", "Hindi H-Fronting Kehkar"),

            // रहना stem
            TestCase("रहना", "rehna", "Hindi H-Fronting Rehna"),
            TestCase("रहता", "rehta", "Hindi H-Fronting Rehta"),
            TestCase("रहती", "rehti", "Hindi H-Fronting Rehti"),
            TestCase("रहते", "rehte", "Hindi H-Fronting Rehte"),
            TestCase("रहकर", "rehkar", "Hindi H-Fronting Rehkar"),

            // बहना stem
            TestCase("बहना", "behna", "Hindi H-Fronting Behna"),
            TestCase("बहता", "behta", "Hindi H-Fronting Behta"),
            TestCase("बहती", "behti", "Hindi H-Fronting Behti"),
            TestCase("बहते", "behte", "Hindi H-Fronting Behte"),

            // Counterexamples (Must NOT front)
            TestCase("कहानी", "kahaani", "Hindi H Safeguard Kahaani"),
            TestCase("कहा", "kaha", "Hindi H Safeguard Kaha"),
            TestCase("कहो", "kaho", "Hindi H Safeguard Kaho"),
            TestCase("रहा", "raha", "Hindi H Safeguard Raha"),
            TestCase("रहे", "rahe", "Hindi H Safeguard Rahe"),
            TestCase("रहो", "raho", "Hindi H Safeguard Raho"),
            TestCase("बहा", "baha", "Hindi H Safeguard Baha"),
            TestCase("बहार", "bahaar", "Hindi H Safeguard Bahaar"),
            TestCase("बहुत", "bahut", "Hindi H Safeguard Bahut"),
            TestCase("सहारा", "sahaara", "Hindi H Safeguard Sahaara")
        )
        var passed = 0
        for (test in hFrontingCases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] /h/-fronting failure for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("/h/-fronting: $passed / ${hFrontingCases.size} inflectional & safeguard cases passed.")
    }

    @Test
    fun `verify user specific requested words and distal pronouns`() {
        val userCases = listOf(
            TestCase("निकलते", "nikalte", "Hindi User nikalte"),
            TestCase("ही", "hi", "Hindi User hi"),
            TestCase("सब", "sab", "Hindi User sab"),
            TestCase("मिलती", "milti", "Hindi User milti"),
            TestCase("रोक", "rok", "Hindi User rok"),
            TestCase("वहाँ", "wahaan", "Hindi User wahaan"),
            TestCase("वहाँ से", "wahaan se", "Hindi User wahaan se"),
            TestCase("वही", "wahi", "Hindi User wahi"),
            TestCase("वहीं", "wahin", "Hindi User wahin"),
            TestCase("वैसे", "waise", "Hindi User waise"),
            TestCase("सब कुछ", "sab kuch", "Hindi User sab kuch")
        )
        var passed = 0
        for (test in userCases) {
            val actual = RomanizationEngine.transliterate(test.original)
            assertEquals(
                "[${test.language}] Failure for '${test.original}'",
                test.expected.lowercase().trim(),
                actual.lowercase().trim()
            )
            passed++
        }
        println("User words: $passed / ${userCases.size} cases verified.")
    }
}

