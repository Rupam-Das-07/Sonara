package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class BengaliSpellingAndReadabilityTest {

    @Test
    fun testBengaliPronounsAndClosedClass() {
        val cases = listOf(
            "আমি" to "ami",
            "তুমি" to "tumi",
            "সে" to "she",
            "আমরা" to "amra",
            "তোমরা" to "tomra",
            "তারা" to "tara",
            "আমার" to "amar",
            "তোমার" to "tomar",
            "তার" to "tar",
            "আমাদের" to "amader",
            "তোমাদের" to "tomader",
            "তাদের" to "tader",
            "আমায়" to "amay",
            "তোমায়" to "tomay",
            "তাকে" to "take",
            "আমাকে" to "amake",
            "তোমাকে" to "tomake",
            "তোর" to "tor",
            "তুই" to "tui",
            "তোকে" to "toke",
            "তিনি" to "tini",
            "তাঁর" to "tar",
            "তাঁকে" to "take",
            "এটা" to "eta",
            "ওটা" to "ota",
            "এই" to "ei",
            "ওই" to "oi",
            "মোদের" to "moder",
            "মোর" to "mor",
            "মোরে" to "more"
        )

        for ((input, expected) in cases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Bengali pronoun/closed class '$input'", expected, actual)
        }
    }

    @Test
    fun testBengaliSongVocabularyAndEmotions() {
        val cases = listOf(
            "ভালোবাসা" to "bhalobasha",
            "ভালোবাসি" to "bhalobashi",
            "ভালোবেসে" to "bhalobeshe",
            "ভালো" to "bhalo",
            "মন্দ" to "mondo",
            "মন" to "mon",
            "মনে" to "mone",
            "মনের" to "moner",
            "হৃদয়" to "hridoy",
            "হৃদয়ে" to "hridoye",
            "পরান" to "poran",
            "পরাণে" to "porane",
            "প্রাণ" to "pran",
            "প্রাণে" to "prane",
            "গান" to "gaan",
            "গানে" to "gane",
            "সুর" to "shur",
            "সুরে" to "shure",
            "কথা" to "kotha",
            "কথায়" to "kothay",
            "চোখ" to "chokh",
            "চোখে" to "chokhe",
            "মুখ" to "mukh",
            "মুখে" to "mukhe",
            "বুক" to "buk",
            "বুকে" to "buke",
            "হাত" to "haat",
            "হাতে" to "hate",
            "পা" to "pa",
            "পায়ে" to "paye",
            "আকাশ" to "akash",
            "আকাশে" to "akashe",
            "বাতাস" to "batash",
            "বাতাসে" to "batashe",
            "মেঘ" to "megh",
            "মেঘে" to "meghe",
            "বৃষ্টি" to "brishti",
            "বৃষ্টিতে" to "brishtite",
            "ঝড়" to "jhor",
            "ঝড়ে" to "jhore",
            "নদী" to "nodi",
            "নদীতে" to "nodite",
            "সাগর" to "shagor",
            "সাগরে" to "shagore",
            "জল" to "jol",
            "জলে" to "jole",
            "আগুন" to "agun",
            "আগুনে" to "agune",
            "আলো" to "alo",
            "আলোয়" to "aloy",
            "আঁধার" to "andhar",
            "আঁধারে" to "andhare",
            "রাত" to "raat",
            "রাতে" to "rate",
            "সকাল" to "shokal",
            "সকালে" to "shokale",
            "পাখি" to "pakhi",
            "ফুল" to "phool",
            "ফুলে" to "phule",
            "পাতা" to "pata",
            "গাছ" to "gaach",
            "জীবন" to "jibon",
            "জীবনে" to "jibone",
            "মরণ" to "moron",
            "মরণে" to "morone",
            "স্বপ্ন" to "shopno",
            "স্বপ্নে" to "shopne",
            "আশা" to "asha",
            "আশায়" to "ashay",
            "স্মৃতি" to "smriti",
            "বন্ধু" to "bondhu",
            "বন্ধুরে" to "bondhure",
            "সুন্দর" to "shundor",
            "বসন্ত" to "boshonto",
            "বসন্তে" to "boshonte",
            "রং" to "rong",
            "রঙ" to "rong",
            "রঙে" to "ronge",
            "রঙ্গিন" to "rongin",
            "সংসার" to "shongshar",
            "সংগীত" to "shongit",
            "সংগী" to "shongi",
            "আনন্দ" to "anondo",
            "শান্তি" to "shanti",
            "হাসি" to "hashi",
            "খেলাঘর" to "khelaghor"
        )

        for ((input, expected) in cases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Bengali vocabulary '$input'", expected, actual)
        }
    }

    @Test
    fun testBengaliVerbsAndInflections() {
        val cases = listOf(
            "চলো" to "cholo",
            "চলি" to "choli",
            "চলে" to "chole",
            "চলা" to "chola",
            "বলো" to "bolo",
            "বলি" to "boli",
            "বলে" to "bole",
            "বলা" to "bola",
            "করো" to "koro",
            "করি" to "kori",
            "করে" to "kore",
            "করা" to "kora",
            "ধরো" to "dhoro",
            "ধরি" to "dhori",
            "ধরে" to "dhore",
            "ধরা" to "dhora",
            "মরো" to "moro",
            "মরি" to "mori",
            "মরে" to "more",
            "মরা" to "mora",
            "দেখো" to "dekho",
            "দেখি" to "dekhi",
            "দেখে" to "dekhe",
            "দেখা" to "dekha",
            "শোনো" to "shono",
            "শুনি" to "shuni",
            "শুনে" to "shune",
            "শোনা" to "shona",
            "বোঝো" to "bojho",
            "বুঝি" to "bujhi",
            "বুঝে" to "bujhe",
            "বোঝা" to "bojha",
            "জানো" to "jano",
            "জানি" to "jani",
            "জেনে" to "jene",
            "জানা" to "jana",
            "চেনো" to "cheno",
            "চিনি" to "chini",
            "চিনে" to "chine",
            "চেনা" to "chena",
            "যাও" to "jao",
            "যাই" to "jai",
            "যায়" to "jay",
            "যাওয়া" to "jawa",
            "আসো" to "asho",
            "আসি" to "ashi",
            "আসা" to "asha",
            "পাও" to "pao",
            "পাই" to "pai",
            "পায়" to "pay",
            "পাওয়া" to "pawa",
            "চাও" to "chao",
            "চাই" to "chai",
            "চায়" to "chay",
            "চাওয়া" to "chawa",
            "দেওয়া" to "dewa",
            "নেওয়া" to "newa",
            "হওয়া" to "howa",
            "খাওয়া" to "khawa",
            "গাওয়া" to "gawa",
            "ভুলে" to "bhule",
            "কেঁদে" to "kende",
            "হেসে" to "heshe",
            "ভেসে" to "bheshe",
            "ডুবে" to "dube",
            "উড়ে" to "ure",
            "ছেড়ে" to "chhere",
            "নীরবে" to "nirobe",
            "রবে" to "robe",
            "গাই" to "gai"
        )

        for ((input, expected) in cases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Bengali verb '$input'", expected, actual)
        }
    }

    @Test
    fun testBengaliIconicLyricsAndPhrases() {
        val cases = listOf(
            "একলা চলো রে" to "ekla cholo re",
            "তুমি রবে নীরবে" to "tumi robe nirobe",
            "আমি বাংলায় গান গাই" to "ami banglay gaan gai",
            "আমার সোনার বাংলা" to "amar shonar bangla",
            "তোমার খোলা হাওয়া" to "tomar khola hawa",
            "যদি তোর ডাক শুনে কেউ না আসে" to "jodi tor daak shune keu na ashe",
            "বুকের ভিতর" to "buker bhitor",
            "অনেক সাধের মন" to "onek shadher mon",
            "অন্ধকার রাতে" to "ondhokar rate"
        )

        for ((input, expected) in cases) {
            val actual = RomanizationEngine.transliterate(input)
            assertEquals("Failed for Bengali phrase '$input'", expected, actual)
        }
    }
}
