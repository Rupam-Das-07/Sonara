package com.example.sonara.domain.lyrics

/**
 * High-precision Indic Script Romanization Engine.
 * 100% faithful Kotlin translation of the Web Melodify transliteration engine (Audit 02 §4).
 * Supports Devanagari, Bengali, Gurmukhi, Gujarati, Tamil, Telugu, Kannada, and Malayalam.
 */
object RomanizationEngine {

    const val VERSION = 5

    data class ScriptRange(val name: String, val start: Int, val end: Int)

    private val SCRIPT_RANGES = listOf(
        ScriptRange("devanagari", 0x0900, 0x097F),
        ScriptRange("bengali", 0x0980, 0x09FF),
        ScriptRange("gurmukhi", 0x0A00, 0x0A7F),
        ScriptRange("gujarati", 0x0A80, 0x0AFF),
        ScriptRange("tamil", 0x0B80, 0x0BFF),
        ScriptRange("telugu", 0x0C00, 0x0C7F),
        ScriptRange("kannada", 0x0C80, 0x0CFF),
        ScriptRange("malayalam", 0x0D00, 0x0D7F)
    )

    private fun detectScript(char: Char): ScriptRange? {
        val code = char.code
        for (range in SCRIPT_RANGES) {
            if (code in range.start..range.end) {
                return range
            }
        }
        return null
    }

    private data class ScriptBlock(val script: ScriptRange?, val text: String)

    private fun parseIntoBlocks(text: String): List<ScriptBlock> {
        if (text.isEmpty()) return emptyList()

        val blocks = mutableListOf<ScriptBlock>()
        var currentScript = detectScript(text[0])
        val currentText = StringBuilder().append(text[0])

        for (i in 1 until text.length) {
            val char = text[i]
            val script = detectScript(char)

            if (script == currentScript) {
                currentText.append(char)
            } else {
                blocks.add(ScriptBlock(currentScript, currentText.toString()))
                currentScript = script
                currentText.clear().append(char)
            }
        }

        blocks.add(ScriptBlock(currentScript, currentText.toString()))
        return blocks
    }

    /**
     * Translates native Indic script lyrics into phonetic Latin Romanized text.
     */
    fun transliterate(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""

        val blocks = parseIntoBlocks(text)
        val result = StringBuilder()

        for (block in blocks) {
            if (block.script != null) {
                try {
                    val rawIast = BrahmicToIast.transliterate(block.text, block.script.name)
                    var processed = routeProcessor(rawIast, block.script.name)
                    processed = applyReadabilityOverrides(processed, block.script.name)
                    result.append(processed)
                } catch (e: Exception) {
                    result.append(block.text)
                }
            } else {
                result.append(block.text)
            }
        }

        return result.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun routeProcessor(rawIast: String, scriptName: String): String {
        return when (scriptName) {
            "devanagari" -> processHindi(rawIast)
            "gurmukhi" -> processPunjabi(rawIast)
            "bengali" -> processBengali(rawIast)
            "tamil" -> processTamil(rawIast)
            "telugu" -> processTelugu(rawIast)
            "kannada" -> processKannada(rawIast)
            "malayalam" -> processMalayalam(rawIast)
            "gujarati" -> processGujarati(rawIast)
            else -> rawIast.lowercase()
        }
    }

    // ── BASE PROCESSOR PHONETIC UTILITIES ──

    fun stripMarkers(rawIast: String): String {
        var processed = rawIast

        // Nukta handling across all Indic Unicode scripts
        val nukta = "[\\u093C\\u09BC\\u0A3C\\u0ABC]"
        processed = processed.replace(Regex("ja$nukta([aeiouāīūēōy])"), "z$1")
        processed = processed.replace(Regex("ja$nukta"), "za")
        processed = processed.replace(Regex("j$nukta"), "z")
        processed = processed.replace(Regex("pha$nukta([aeiouāīūēōy])"), "f$1")
        processed = processed.replace(Regex("pha$nukta"), "fa")
        processed = processed.replace(Regex("ph$nukta"), "f")
        processed = processed.replace(Regex("ka$nukta([aeiouāīūēōy])"), "q$1")
        processed = processed.replace(Regex("ka$nukta"), "qa")
        processed = processed.replace(Regex("k$nukta"), "q")
        processed = processed.replace(Regex("ga$nukta([aeiouāīūēōy])"), "gh$1")
        processed = processed.replace(Regex("ga$nukta"), "gha")
        processed = processed.replace(Regex("g$nukta"), "gh")
        processed = processed.replace(Regex("sa$nukta([aeiouāīūēōy])"), "sh$1")
        processed = processed.replace(Regex("sa$nukta"), "sha")
        processed = processed.replace(Regex("s$nukta"), "sh")
        processed = processed.replace(Regex("ḍa$nukta([aeiouāīūēōy])"), "d$1")
        processed = processed.replace(Regex("ḍa$nukta"), "da")
        processed = processed.replace(Regex("ḍ$nukta"), "d")
        processed = processed.replace(Regex("ḍha$nukta([aeiouāīūēōy])"), "dh$1")
        processed = processed.replace(Regex("ḍha$nukta"), "dha")
        processed = processed.replace(Regex("ḍh$nukta"), "dh")
        processed = processed.replace("k͟h", "kh")
        processed = processed.replace("ġ", "gh")
        processed = processed.replace("r̤h", "dh")
        processed = processed.replace(Regex(nukta), "")

        // Strip residual non-Latin Unicode
        processed = processed.replace(Regex("[\\u0980-\\u09FF]"), "")
        processed = processed.replace(Regex("[\\u0A00-\\u0A7F]"), "")

        val consonantReplacements = mapOf(
            "ḍ" to "d", "ṭ" to "t", "ṇ" to "n", "ñ" to "n", "ṅ" to "n",
            "ś" to "sh", "ṣ" to "sh", "ḥ" to "h",
            "ṉ" to "n", "ḷ" to "l", "ḻ" to "zh", "ṟ" to "r", "l̤" to "l", "r̤" to "r",
            "ẏ" to "y",
            "~" to "",
            "ൺ" to "n", "ൻ" to "n", "ർ" to "r", "ൽ" to "l", "ൾ" to "l", "ൿ" to "k"
        )

        for ((key, value) in consonantReplacements) {
            processed = processed.replace(key, value)
            processed = processed.replace(key.uppercase(), value.uppercase())
        }

        // Anusvara & Candrabindu before consonants
        processed = processed.replace(Regex("([aeiouāīūēō])~([bcdfghjklmnpqrstvwxyz])"), "$1n$2")
        processed = processed.replace(Regex("~([bcdfghjklmnpqrstvwxyz])"), "n$1")
        processed = processed.replace("~", "")
        processed = processed.replace(Regex("ṃ([tdkg])"), "n$1")
        processed = processed.replace(Regex("ṃ([pb])"), "m$1")
        processed = processed.replace("ṃ", "n")
        processed = processed.replace("m̐", "n")

        return processed
    }

    fun flattenVowels(text: String): String {
        var processed = text
        val vowelReplacements = mapOf(
            "ā" to "aa", "ī" to "ee", "ū" to "oo", "ṛ" to "ri",
            "ē" to "e", "ō" to "o",
            "è" to "e", "ò" to "o"
        )

        for ((key, value) in vowelReplacements) {
            processed = processed.replace(key, value)
            processed = processed.replace(key.uppercase(), value.uppercase())
        }

        return processed
    }

    fun compressNativeVowels(text: String): String {
        return text.replace(Regex("([a-zA-Z]+)")) { matchResult ->
            var w = matchResult.value
            if (w.endsWith("ee") && w.length > 2) {
                w = w.substring(0, w.length - 2) + "i"
            }
            if (w.endsWith("oo") && w.length <= 3) {
                w = w.substring(0, w.length - 2) + "u"
            }
            if (w.endsWith("aa") && w.length > 3) {
                w = w.substring(0, w.length - 1)
            }
            if (w.equals("saa", ignoreCase = true)) {
                w = "sa"
            }
            w
        }
    }

    // ── 1. HINDI PROCESSOR ──

    private val HINDI_CLOSED_CLASS = mapOf(
        "naheen" to "nahi",
        "nahin" to "nahi",
        "ham" to "hum",
        "kyon" to "kyun",
        "gai" to "gayi",
        "gae" to "gaye",
        "vo" to "woh",
        "vah" to "woh",
        "yah" to "yeh",
        "vahaan" to "wahaan",
        "vahan" to "wahan",
        "vahi" to "wahi",
        "vaheen" to "wahin",
        "vahein" to "wahin",
        "vaise" to "waise",
        "vaisa" to "waisa",
        "vaisi" to "waisi",
        "aakhon" to "aankhon",
        "aakhein" to "aankhein",
        "kaheen" to "kahin",
        "kahein" to "kahin",
        "chaahiye" to "chahiye",
        "chaahe" to "chahe",
        "javaab" to "jawab",
        "jawaab" to "jawab",
        "rahtee" to "rehti",
        "rahti" to "rehti",
        "raheinge" to "rahenge",
        "raheienge" to "rahenge",
        "savaal" to "sawal",
        "sawaal" to "sawal",
        "deevane" to "deewane",
        "deevaane" to "deewane",
        "deevana" to "deewana",
        "deevaana" to "deewana",
        "deevani" to "deewani",
        "deevaani" to "deewani",
        "deewaan" to "deewan",
        "deewaanon" to "deewanon",
        "philhaal" to "filhaal",
        "philhal" to "filhaal",
        "filhal" to "filhaal",
        "tasweer" to "tasveer",
        "nazaron" to "nazron",
        "najaron" to "nazron",
        "nazarein" to "nazrein",
        "najarein" to "nazrein",
        "saamne" to "samne",
        "saamna" to "samna",
        "tumhaare" to "tumhare",
        "tumhaara" to "tumhara",
        "tumhaari" to "tumhari",
        "hamaare" to "humare",
        "hamaara" to "humara",
        "hamaari" to "humari",
        "humaare" to "humare",
        "humaara" to "humara",
        "humaari" to "humari",
        "toone" to "tune",
        "toonei" to "tune",
        "kitanon" to "kitnon",
        "jitanon" to "jitnon",
        "itanon" to "itnon",
        "utanon" to "utnon",
        "kitano" to "kitno",
        "jitano" to "jitno",
        "itano" to "itno",
        "utano" to "utno",
        "sanaaton" to "sannaton",
        "sanaton" to "sannaton",
        "sanaata" to "sannata",
        "sanata" to "sannata",
        "sannaaton" to "sannaton",
        "sannaata" to "sannata",
        "sannaate" to "sannate",
        "jaaoon" to "jaaun",
        "lagaoon" to "lagaun",
        "lagaaoon" to "lagaun",
        "manaaoon" to "manaun",
        "manaoon" to "manaun",
        "aaaoon" to "aaun",
        "aaoon" to "aaun",
        "gaaoon" to "gaaun",
        "gaoon" to "gaaun",
        "chadhiya" to "chadheya",
        "chadhya" to "chadheya",
        "gia" to "gaya",
        "giya" to "gaya",
        "geya" to "gaya",
        "milia" to "mileya",
        "miliya" to "mileya",
        "jad" to "jag",
        "mainoon" to "mainu",
        "tainoon" to "tainu",
        "mainun" to "mainu",
        "tainun" to "tainu",
        "sanun" to "saanu",
        "sanoon" to "saanu",
        "saanoon" to "saanu",
        "tuhanoon" to "tuhanu",
        "ohnoon" to "ohnu",
        "uhnoon" to "uhnu",
        "havale" to "hawale",
        "havala" to "hawala",
        "hawale" to "hawale",
        "hawala" to "hawala",
        "challia" to "challeya",
        "chalia" to "chaleya",
        "chaliya" to "chaleya",
        "challiya" to "challeya",
        "hoia" to "hoya",
        "hoiya" to "hoya",
        "hoya" to "hoya",
        "bhullia" to "bhulleya",
        "bhulia" to "bhuleya",
        "bhuliya" to "bhulleya",
        "bhulliya" to "bhulleya",
        "chodiya" to "chodeya",
        "chodia" to "chodeya",
        "jodiya" to "jodeya",
        "jodia" to "jodeya",
        "taan" to "taan"
    )

    private fun processHindi(rawIast: String): String {
        var text = rawIast

        // A. Suffix & Nasal rules on IAST

        // Source-aware माँ protection: monosyllabic mā~ must stay "maa" (not "maan")
        // Protects माँ (mother) from colliding with मान (honor/maan) after candrabindu rules.
        // The pattern \bmā~ uniquely identifies the word माँ in IAST at this stage.
        text = text.replace(Regex("\\bmā~(?=[^\\w\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "MAA_PROTECT")
        // Source-aware हूँ protection: monosyllabic hū~ must stay "hoon" (not "hun")
        text = text.replace(Regex("\\bhū[~ṃm̐](?=[^\\w\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "HOON_PROTECT")

        text = text.replace(Regex("([aeiouāīūēō])(e~|eṃ|ē~|ēṃ)\\b"), "$1yein")
        text = text.replace(Regex("([āa])(e)\\b"), "$1ye")
        text = text.replace(Regex("([iī])(e)\\b"), "$1ye")
        // 1st-person singular subjunctive verbs (-āū̃ -> -aun, -Cū̃ -> -Cun)
        text = text.replace(Regex("([āa])(ū~|ūṃ|ūm̐|ū)(?=[^\\w\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "$1un")
        text = text.replace(Regex("([bcdfghjklmnpqrstvwxyz\\u00C0-\\u024F\\u1E00-\\u1EFF])(ū~|ūṃ|ūm̐)(?=[^\\w\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "$1un")
        // Protect inflectional plural nasal suffixes (-on, -ein) at word boundaries on nouns (mausam, aurat, daulat, qismat)
        // so noun roots do not undergo illicit penultimate schwa syncope, while preserving verbal future endings (-ēṃge -> -enge).
        text = text.replace(Regex("(ōṃ|oṃ|ō~|o~)(?=[^\\w\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "NASAL_ON")
        text = text.replace(Regex("(ēṃ|eṃ|ē~|e~)(?=[^\\w\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "NASAL_EIN")
        text = text.replace("aiṃ", "ain")

        // Protect root nasal vowels with candrabindu/anusvara before consonants so they act as atomic vowel nuclei in schwa syncope (e.g. goonjti, dhoondhti)
        text = text.replace(Regex("ū[ṃ~m̐]"), "NASAL_OON")

        // Candrabindu in roots and open syllables (e.g. aankh, chaand, haan, yahaan, maan)
        text = text.replace(Regex("([āaīū])~([bcdfghjklmnpqrstvwxyz])"), "$1n$2")
        text = text.replace(Regex("([āaīūēō])~(?=[^\\w\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "$1n")

        // R3: IAST-stage conjunct jñ → gy BEFORE stripMarkers strips ñ → n.
        // Covers: ज्ञान→gyan, विज्ञान→vigyan, अज्ञान→agyan, यज्ञ→yagy.
        // Scope: only the exact two-character IAST sequence "jñ" is matched.
        text = text.replace("jñ", "gy")

        text = stripMarkers(text)
        text = text.replace(Regex("c(?!h)"), "ch")

        // Protect diphthongs ai and au as atomic units so reversed medial schwa deletion
        // does not interpret 'u'/'i' as standalone vowel boundaries or 'a' as a deletable schwa.
        text = text.replace("ai", "DIPH_AI").replace("au", "DIPH_AU")

        // B. Schwa deletion (Right-to-Left)
        val words = text.split(Regex("\\s+"))
        text = words.joinToString(" ") { word ->
            var w = word
            // Word-final schwa deletion (except when trailing āa)
            if (w.length > 2 && w.endsWith("a") && !w.endsWith("āa")) {
                w = w.replace(Regex("(h?[bcdfghjklmnpqrstvwxyz])a$"), "$1")
            }
            if (w.length > 2 && w.endsWith("a")) {
                w = w.replace(Regex("([yv])a$"), "$1")
            }
            w = w.reversed()
            // Medial schwa deletion: V + C + a + C + V (reversed: V + h?C + a + h?C + V)
            // Treats atomic protected diphthongs and nasal vowels as valid vowel triggers
            // while preserving schwa adjacent to true multi-consonant clusters (like nd in zindagi).
            val vowelPat = "[aeiouāīūēōy]|IA_HPID|UA_HPID|NOO_LASAN"
            w = w.replaceFirst(Regex("(($vowelPat)h?[bcdfghjklmnpqrstvwxyz])a(h?[bcdfghjklmnpqrstvwxyz]($vowelPat))"), "$1$3")
            w = w.reversed()
            w
        }

        // Restore protected diphthongs, nasal vowels, and plural suffixes
        text = text.replace("DIPH_AI", "ai").replace("DIPH_AU", "au")
        text = text.replace("NASAL_ON", "on").replace("NASAL_EIN", "ein")
        text = text.replace("NASAL_OON", "oon")

        // C. Flatten non-ASCII vowels to ASCII before regex word-boundary operations
        text = flattenVowels(text)

        // Restore MAA_PROTECT -> "maa" and HOON_PROTECT -> "hoon"
        text = text.replace("MAA_PROTECT", "maa")
        text = text.replace("HOON_PROTECT", "hoon")

        // D. Scoped /h/ Fronting on Validated Grammatical Stems (100% safe on ASCII tokens)
        // Stems expand across standard Hindi verbal and adjectival inflections (-a, -i, -e, -ta, -ti, -te, -kar)
        text = text.replace(Regex("\\bpahl([a-z]*)"), "pehl$1")
        text = text.replace(Regex("\\bgahr([a-z]*)"), "gehr$1")
        text = text.replace(Regex("\\bkah(n|t|kar)([a-z]*)"), "keh$1$2")
        text = text.replace(Regex("\\brah(n|t|kar)([a-z]*)"), "reh$1$2")
        text = text.replace(Regex("\\bbah(n|t|kar)([a-z]*)"), "beh$1$2")

        val hRootReplacements = mapOf(
            "\\bpehra" to "pehra",
            "\\btheharna" to "theharna",
            "\\bcehra" to "chehra",
            "\\bshehnai" to "shehnai",
            "\\bmehsoos" to "mehsoos",
            "\\bmeherbaan" to "meherbaan",
            "\\brehguzar" to "rehguzaar"
        )
        for ((pat, rep) in hRootReplacements) {
            text = text.replace(Regex(pat), rep)
        }
        text = text.replace(Regex("\\b([rks])ah\\b"), "$1eh")
        text = text.replace(Regex("\\bshahar\\b"), "sheher")
        text = text.replace(Regex("\\bzahar\\b"), "zeher")
        text = text.replace(Regex("\\blahar\\b"), "leher")
        text = text.replace(Regex("\\bpahar\\b"), "peher")

        // E. Scoped /w/ Glide Realization in Post-Obstruent Conjuncts & Roots
        text = text.replace(Regex("([bcdfgjkpqrsz]|kh|gh|ch|jh|th|dh|ph|bh|sh)v([aeiou])"), "$1w$2")
        text = text.replace(Regex("\\b([a-z]+)v[a]+l([eai]|aa)\\b"), "$1wal$2")
        text = text.replace(Regex("\\bv[a]+l([eai]|aa)\\b"), "wal$1")
        text = text.replace(Regex("\\bvaad([eai]|aa)?\\b"), "waad$1")
        text = text.replace(Regex("\\bhava"), "hawa")
        text = text.replace(Regex("\\bdava"), "dawa")
        text = text.replace(Regex("\\bdeew?[av][a]+n([a-z]*)\\b"), "deewan$1")
        text = text.replace(Regex("\\bparw?[av][a]+n([a-z]*)\\b"), "parwan$1")
        text = text.replace(Regex("\\bpoochh?([a-z]*)"), "puch$1")
        text = text.replace(Regex("\\bsavaar\\b"), "sawar")
        text = text.replace(Regex("\\bsawaar\\b"), "sawar")
        text = text.replace(Regex("\\bbhanvar"), "bhanwar")
        text = text.replace(Regex("\\bshikvaa?\\b"), "shikwa")
        text = text.replace(Regex("\\btasweer\\b"), "tasveer")
        text = text.replace(Regex("\\bhav[a]+l([a-z]*)"), "hawal$1")

        // F. North Indic Vowel Compression
        text = compressNativeVowels(text)
        text = text.replace(Regex("([a-zA-Z]{3,})aaun\\b"), "$1aun")

        text = text.replace(Regex("shk\\b"), "shq")

        // H. R1: Word-final i-stem verb glide - ie -> iye
        // Covers: लिए liye, किए kiye, जिए jiye, पिए piye. (Removes stiff "lie", "kie").
        // Scope: requires a preceding consonant to prevent corrupting 'aie' -> 'aiye' prematurely.
        // Dropped trailing \b to cover suffixed forms like पिएगा (piyega)
        text = text.replace(Regex("\\b([bcdfghjklmnpqrstvwxyz]+)ie"), "$1iye")

        // I. R2: Word-final subjunctive/conjunctive glide - aae -> aaye
        // Covers: जाए jaaye, आए aaye, पाए paaye, खाए khaaye, गाए gaaye
        // Scope: word-final ONLY; the intermediate form -aae only arises from a+e in Devanagari verbs.
        // Dropped trailing \b to cover suffixed forms like जाएगा (jaayega)
        text = text.replace(Regex("\\b([bcdfghjklmnpqrstvwxyz]*)aae"), "$1aaye")

        // G. Scoped Closed-Class Function Word Normalizer
        val tokens = text.split(Regex("\\s+"))
        return tokens.joinToString(" ") { HINDI_CLOSED_CLASS[it] ?: it }.lowercase()
    }

    // ── 2. BENGALI PROCESSOR ──

    private val BENGALI_CLOSED_CLASS = mapOf(
        "se" to "she",
        "tar" to "tar", "tanr" to "tar", "taanr" to "tar",
        "take" to "take", "tanke" to "take", "taanke" to "take",
        "ebon" to "ebong", "ebang" to "ebong",
        "kena" to "keno", "ken" to "keno",
        "keman" to "kemon", "kemana" to "kemon",
        "kabe" to "kobe",
        "kata" to "koto", "kot" to "koto", "kat" to "koto",
        "kakhano" to "kokhono", "kakhanō" to "kokhono",
        "jadi" to "jodi", "tabe" to "tobe",
        "chila" to "chhilo", "chil" to "chhilo", "chilo" to "chhilo",
        "habe" to "hobe", "halo" to "holo", "halō" to "holo",
        "nay" to "noy", "hay" to "hoy",
        "boshont" to "boshonto", "bashant" to "boshonto", "bashanta" to "boshonto", "basanta" to "boshonto", "bashante" to "boshonte", "basante" to "boshonte",
        "shob" to "shob", "shab" to "shob", "shabai" to "shobai", "shobai" to "shobai",
        "sundara" to "shundor", "shundara" to "shundor", "shundar" to "shundor",
        "andhakara" to "ondhokar", "andhakāra" to "ondhokar", "ondhokar" to "ondhokar", "andhakar" to "ondhokar",
        "sbapna" to "shopno", "shbapn" to "shopno", "shapon" to "shopno", "shopna" to "shopno", "shopn" to "shopno", "shbapne" to "shopne",
        "bandhu" to "bondhu", "bhālo" to "bhalo", "bhālobāsā" to "bhalobasha",
        "mana" to "mon", "man" to "mon", "maner" to "moner",
        "manda" to "mondo", "mand" to "mondo",
        "ananda" to "anondo", "anand" to "anondo",
        "hrdaya" to "hridoy", "hrdaẏa" to "hridoy", "hriday" to "hridoy",
        "parana" to "poran", "paran" to "poran", "poran" to "poran",
        "sagara" to "shagor", "shagara" to "shagor", "shagar" to "shagor",
        "shur" to "shur", "sura" to "shur",
        "sakala" to "shokal", "shakala" to "shokal", "shakal" to "shokal",
        "andhar" to "andhar", "aandhar" to "andhar",
        "jibana" to "jibon", "jiban" to "jibon",
        "marana" to "moron", "maran" to "moron",
        "prthibi" to "prithibi", "pṛthibī" to "prithibi", "desha" to "desh",
        "patha" to "poth", "path" to "poth",
        "brishhti" to "brishti", "brshhti" to "brishti", "brishti" to "brishti", "brishtite" to "brishtite", "brshti" to "brishti", "brshtite" to "brishtite",
        "phula" to "phool", "phul" to "phool", "ful" to "phool",
        "katha" to "kotha", "kathā" to "kotha", "kathay" to "kothay",
        "nadi" to "nodi", "nadī" to "nodi",
        "rabe" to "robe", "nirabe" to "nirobe", "nīrabe" to "nirobe",
        "bamla" to "bangla", "bamlay" to "banglay", "banla" to "bangla", "banlay" to "banglay",
        "gana" to "gaan", "gan" to "gaan",
        "sonara" to "shonar", "shonara" to "shonar", "sonar" to "shonar",
        "daka" to "daak", "dak" to "daak",
        "bhitara" to "bhitor", "bhitar" to "bhitor",
        "ache" to "achhe", "āche" to "achhe",
        "rata" to "raat", "rat" to "raat",
        "hata" to "haat", "hat" to "haat",
        "gacha" to "gaach", "gach" to "gaach",
        "jala" to "jol", "jal" to "jol", "jale" to "jole",
        "jhara" to "jhor", "jhar" to "jhor",
        "chokha" to "chokh",
        "chawa" to "chawa", "pawa" to "pawa", "hawa" to "hawa",
        "kede" to "kende", "ke~de" to "kende",
        "kache" to "kachhe",
        "jakhan" to "jokhon", "takhan" to "tokhon", "ekhan" to "ekhon",
        "jeman" to "jemon", "teman" to "temon", "eman" to "emon",
        "aneka" to "onek", "anek" to "onek",
        "anya" to "onno", "any" to "onno",
        "atita" to "otit", "atit" to "otit",
        "ajana" to "ojana", "ajānā" to "ojana",
        "amara" to "omor", "amar" to "amar",
        "antara" to "ontor", "antar" to "ontor",
        "aparadha" to "oporadh", "aparadh" to "oporadh",
        "khelaghar" to "khelaghor", "khelaghara" to "khelaghor",
        "rang" to "rong", "range" to "ronge", "ranggin" to "rongin",
        "shangshar" to "shongshar", "shanggit" to "shongit", "shanggi" to "shongi",
        "hothat" to "hothat", "hathat" to "hothat", "hothata" to "hothat", "hathata" to "hothat",
        "amra" to "amra", "tomra" to "tomra", "amr" to "amra", "tomr" to "tomra",
        "ekla" to "ekla", "ekala" to "ekla", "hridaye" to "hridoye", "hridoye" to "hridoye"
    )

    private fun processBengali(rawIast: String): String {
        val nukta = "[\\u093C\\u09BC]"
        var text = rawIast.replace(Regex("ḍh$nukta"), "rh")
            .replace(Regex("ḍ$nukta"), "r")
            .replace("r̤h", "rh")
            .replace("r̤", "r")
        text = text.replace(Regex("[\\u0980-\\u09FF]"), "")

        // 1. Pre-replacements on entire string
        text = text.replace("vṛ", "bri").replace("bṛ", "bri")
        text = text.replace("hṛ", "hri").replace("smṛ", "smri")
        text = text.replace("pṛ", "pri").replace("kṛ", "kri").replace("dṛ", "dri").replace("sṛ", "sri")
        text = text.replace("mṛ", "mri").replace("gṛ", "gri").replace("tṛ", "tri").replace("dhṛ", "dhri")
        text = text.replace("ṛh", "rh").replace("ṛ", "ri")

        text = text.replace("saṃg", "shong").replace("raṅg", "rong").replace("saṃ", "shong").replace("raṃ", "rong").replace("raṅ", "rong")
        text = text.replace("ṃ", "ng").replace("ṅ", "ng")

        text = text.replace("ḍh", "dh").replace("ḍ", "d").replace("ṭh", "th").replace("ṭ", "t")
        text = text.replace("ṇ", "n").replace("ñ", "n")
        text = text.replace("v", "b")

        // Sibilants & Conjuncts
        text = text.replace("sth", "STH_CONJ").replace("st", "ST_CONJ")
        text = text.replace("sph", "SPH_CONJ").replace("sp", "SP_CONJ")
        text = text.replace("sk", "SK_CONJ").replace("sn", "SN_CONJ")
        text = text.replace("sm", "SM_CONJ").replace("sr", "SR_CONJ")

        text = text.replace("sbapn", "shopn").replace("sbopn", "shopn")
        text = text.replace("sb", "shw")

        text = text.replace("ś", "sh").replace("ṣ", "sh")
        text = text.replace(Regex("s(?!h)"), "sh")

        text = text.replace("STH_CONJ", "sth").replace("ST_CONJ", "st")
        text = text.replace("SPH_CONJ", "sph").replace("SP_CONJ", "sp")
        text = text.replace("SK_CONJ", "sk").replace("SN_CONJ", "sn")
        text = text.replace("SM_CONJ", "sm").replace("SR_CONJ", "sr")

        text = text.replace(Regex("c(?!h)"), "ch")

        text = text.replace(Regex("([āaīūēō])~([bcdfghjklmnpqrstvwxyz])"), "$1n$2")
        text = text.replace("~", "")

        val words = text.split(Regex("\\s+"))
        val resWords = mutableListOf<String>()

        val vowelMap = mapOf(
            "ā" to "a", "ī" to "i", "ū" to "u", "ṛ" to "ri",
            "ē" to "e", "ō" to "o",
            "è" to "e", "ò" to "o",
            "ṯ" to "t"
        )

        for (word in words) {
            var w = word
            // Antasthya Ya at word start -> j
            if (w.startsWith("y") || w.startsWith("Y")) {
                w = "j" + w.substring(1)
            }

            // Word-level overrides & glides
            if (w == "āmarā" || w == "amra" || w == "āmrā") {
                w = "amrā"
            } else if (w == "tomarā" || w == "tomra" || w == "tomrā") {
                w = "tomrā"
            } else if (w == "haoya" || w == "haoyā") {
                w = "howa"
            } else if (w == "haowa" || w == "haowā" || w == "hāoya" || w == "hāowā") {
                w = "hawa"
            } else if (w == "hāoyaẏa") {
                w = "haway"
            } else if (w == "āshabe" || w == "ashabe" || w == "āsabe" || w == "asabe") {
                w = "ashobe"
            } else if (w == "haṭhāt" || w == "hathat" || w == "haṭhat") {
                w = "hothat"
            } else if (w == "amara") {
                w = "omor"
            } else if (w.endsWith("eoya") || w.endsWith("eoyā")) {
                w = w.replace(Regex("eoy[aā]$"), "ewa")
            } else if (w.endsWith("aoya") || w.endsWith("āoya") || w.endsWith("aoyā") || w.endsWith("āoyā")) {
                w = w.replace(Regex("a?oy[aā]$"), "wa")
            } else if (w.endsWith("oẏa")) {
                w = w.substring(0, w.length - 3) + "oy"
            } else if (w.endsWith("aoyaẏa")) {
                w = w.substring(0, w.length - 6) + "away"
            } else if (w.endsWith("aẏa")) {
                w = w.substring(0, w.length - 3) + "oy"
            } else if (w.endsWith("cheṛe") || w == "chere" || w == "chede" || w.endsWith("chede")) {
                w = "chhere"
            } else if (w == "śunate" || w == "shunate") {
                w = "shunte"
            }

            w = w.replace(Regex("aẏ(?=[a-zA-Zāīūēō])"), "oy")
            w = w.replace("ẏ", "y")

            // Systemic verbs: kar-, bal-, dhar-, mar-, cal-, jhar-
            if (w.matches(Regex("^kar[oieāa]$"))) {
                w = "kor" + w.substring(3)
            } else if (w.matches(Regex("^bal[oieāa]$"))) {
                w = "bol" + w.substring(3)
            } else if (w.matches(Regex("^dhar[oieāa]$"))) {
                w = "dhor" + w.substring(4)
            } else if (w.matches(Regex("^mar[oieāa]$"))) {
                w = "mor" + w.substring(3)
            } else if (w.matches(Regex("^(?:cal|chal)[oieāa]$"))) {
                w = "chol" + w.replace(Regex("^(?:cal|chal)"), "")
            } else if (w.matches(Regex("^(?:jhaṛ|jhar)[oieāa]$"))) {
                w = "jhor" + w.replace(Regex("^(?:jhaṛ|jhar)"), "")
            }

            // Systemic noun O-shifts
            if (w.matches(Regex("^man[eora]$"))) {
                w = "mon" + w.substring(3)
            } else if (w.matches(Regex("^porān[eora]$")) || w.matches(Regex("^parān[eora]$"))) {
                w = "poran" + w.substring(5)
            } else if (w.matches(Regex("^nadī.*"))) {
                w = "nodi" + w.substring(4)
            } else if (w.matches(Regex("^(?:sāgar|shāgar)[eora]$"))) {
                w = "shagor" + w.replace(Regex("^(?:sāgar|shāgar)"), "")
            } else if (w.matches(Regex("^(?:sakāl|shakāl)[eora]$"))) {
                w = "shokal" + w.replace(Regex("^(?:sakāl|shakāl)"), "")
            } else if (w.matches(Regex("^(?:jīban|jiban)[eora]$"))) {
                w = "jibon" + w.replace(Regex("^(?:jīban|jiban)"), "")
            } else if (w.matches(Regex("^(?:maraṇ|maran)[eora]$"))) {
                w = "moron" + w.replace(Regex("^(?:maraṇ|maran)"), "")
            } else if (w.matches(Regex("^bandhur[eora]$"))) {
                w = "bondhur" + w.substring(7)
            } else if (w.matches(Regex("^(?:basant|shasant)[eora]$"))) {
                w = "boshont" + w.replace(Regex("^(?:basant|shasant)"), "")
            } else if (w.matches(Regex("^bhitar[eora]$"))) {
                w = "bhitor" + w.substring(6)
            }

            // Terminal short -a deletion (preserves long -ā and -wa)
            if (w.length > 2 && w.endsWith("a") && !w.endsWith("ā") && !w.endsWith("wa")) {
                w = w.replace(Regex("([bcdfghjklmnpqrstvwxyz]+)a$"), "$1")
            }

            // Vowel flattening
            for ((key, value) in vowelMap) {
                w = w.replace(key, value)
                w = w.replace(key.uppercase(), value.uppercase())
            }
            w = w.replace("t\u0327", "t")

            resWords.add(w)
        }

        return resWords.joinToString(" ") { BENGALI_CLOSED_CLASS[it] ?: it }.lowercase()
    }

    // ── 3. PUNJABI PROCESSOR ──

    private val PUNJABI_CLOSED_CLASS = mapOf(
        "mainoon" to "mainu", "mainun" to "mainu", "tainoon" to "tainu", "tainun" to "tainu",
        "sanoon" to "saanu", "sanun" to "saanu", "saanoon" to "saanu",
        "tuhanoon" to "tuhanu", "tuhanun" to "tuhanu", "tuhaanoon" to "tuhanu", "tuhanu" to "tuhanu", "tuhnu" to "tuhanu",
        "ohnoon" to "ohnu", "ohnun" to "ohnu", "uhnoon" to "uhnu", "uhnu" to "ohnu", "ehnoon" to "ehnu", "ihnoon" to "ehnu", "ehnu" to "ehnu",
        "uhanoon" to "ohnu", "ihanoon" to "ehnu",
        "kioo" to "kyun", "kiun" to "kyun", "kiu" to "kyun", "kyoon" to "kyun",
        "kionki" to "kyunki", "kiunki" to "kyunki", "kyoonki" to "kyunki", "kiuki" to "kyunki",
        "naheen" to "nahi", "nahin" to "nahi", "nai" to "nahi", "naee" to "nahi", "naeen" to "nahi",
        "samhne" to "samne", "sahamne" to "samne", "saamhne" to "samne", "saahamne" to "samne", "saamhane" to "samne",
        "sohni" to "sohni", "sohna" to "sohna", "sohne" to "sohne", "sohneyan" to "sohneyan",
        "chandigarh" to "chandigarh", "chandigarha" to "chandigarh", "chandeegadh" to "chandigarh", "chandigadh" to "chandigarh",
        "punjab" to "punjab", "punjabi" to "punjabi", "panjaab" to "punjab", "panjaabi" to "punjabi", "panjab" to "punjab", "panjabi" to "punjabi", "punjbi" to "punjabi",
        "jatt" to "jatt", "jatta" to "jatt", "gaddi" to "gaddi", "shauk" to "shauk", "shaunk" to "shauk",
        "chadhya" to "chadheya", "chadhiya" to "chadheya", "chadya" to "chadheya", "chadhiaa" to "chadheya",
        "gia" to "gaya", "giya" to "gaya", "geya" to "gaya", "geaa" to "gaya", "giaa" to "gaya",
        "milia" to "mileya", "miliya" to "mileya", "miliaa" to "mileya",
        "challia" to "challeya", "chalia" to "chaleya", "chaliya" to "chaleya", "challiya" to "challeya", "challiaa" to "challeya",
        "chhodia" to "chaddeya", "chhodiya" to "chaddeya", "chhadia" to "chaddeya", "chhaddia" to "chaddeya", "chaddia" to "chaddeya", "chaddiaa" to "chaddeya",
        "vekhia" to "vekheya", "vekhiya" to "vekheya", "vekhiaa" to "vekheya",
        "sunia" to "suneya", "suniya" to "suneya", "suniaa" to "suneya",
        "rakhia" to "rakheya", "rakhiya" to "rakheya", "rakhiaa" to "rakheya", "rakkheya" to "rakheya",
        "dassia" to "dasseya", "dassiya" to "dasseya", "dassiaa" to "dasseya", "dassade" to "dassde", "dasde" to "dassde",
        "samajhia" to "samjheya", "samjhiya" to "samjheya", "samjhiaa" to "samjheya",
        "mangia" to "mangeya", "mangiya" to "mangeya", "mangiaa" to "mangeya",
        "labbhia" to "labbheya", "labbhiya" to "labbheya", "labbhiaa" to "labbheya", "labhiaa" to "labbheya", "labheya" to "labbheya",
        "hoia" to "hoya", "hoiya" to "hoya", "hoiaa" to "hoya", "hoiaaa" to "hoya", "hoeya" to "hoya",
        "kolo" to "kol", "kolon" to "kol", "kol" to "kol",
        "vich" to "vich", "vicch" to "vich",
        "naal" to "naal", "nal" to "naal",
        "duniya" to "duniya", "duniyan" to "duniya", "duneeaa" to "duniya",
        "zeher" to "zeher", "zahar" to "zeher", "zahira" to "zeher",
        "sheher" to "sheher", "shahar" to "sheher", "shahira" to "sheher", "shahir" to "sheher",
        "baghair" to "baghair", "bagair" to "bagair", "baghaira" to "baghair",
        "piaar" to "pyaar", "piyaar" to "pyaar",
        "mundiaan" to "mundeyaan", "mundian" to "mundeyaan", "mundiyan" to "mundeyaan", "mundeyan" to "mundeyaan", "mundeyaan" to "mundeyaan",
        "kudian" to "kudiyan", "kudiaan" to "kudiyan", "kudeean" to "kudiyan", "kudeeaan" to "kudiyan",
        "akhan" to "akkhan", "akhiaan" to "akkhan", "akhian" to "akkhan", "ankhan" to "akkhan", "akhaan" to "akkhan", "akkh" to "akh",
        "hathan" to "hatthan", "hathiaan" to "hatthan", "hathaan" to "hatthan", "hatth" to "hath",
        "taan" to "taan", "tan" to "taan",
        "hor" to "hor", "kujh" to "kujh", "kujj" to "kujh",
        "changa" to "changa", "changey" to "change", "changi" to "changi",
        "rahi" to "reh", "kahi" to "keh", "bahi" to "beh", "sahi" to "seh",
        "mittran" to "mittraan", "mittraan" to "mittraan",
        "gabbharoo" to "gabhroo", "gabbhroo" to "gabhroo", "gabhroo" to "gabhroo",
        "chashama" to "chashma", "chashma" to "chashma",
        "saambha" to "saambh", "sanbh" to "saambh",
        "havaale" to "hawale", "havale" to "hawale",
        "havaala" to "hawala", "havala" to "hawala",
        "challda" to "chalda", "challdaa" to "chalda",
        "naan" to "naan"
    )

    private fun processPunjabi(rawIast: String): String {
        var text = rawIast

        // 1. Adhak gemination on following consonants (with aspirates)
        text = text.replace(Regex("ੱkh", RegexOption.IGNORE_CASE), "kkh")
            .replace(Regex("ੱgh", RegexOption.IGNORE_CASE), "ggh")
            .replace(Regex("ੱth", RegexOption.IGNORE_CASE), "tth")
            .replace(Regex("ੱdh", RegexOption.IGNORE_CASE), "ddh")
            .replace(Regex("ੱch", RegexOption.IGNORE_CASE), "cch")
            .replace(Regex("ੱjh", RegexOption.IGNORE_CASE), "jjh")
            .replace(Regex("ੱph", RegexOption.IGNORE_CASE), "pph")
            .replace(Regex("ੱbh", RegexOption.IGNORE_CASE), "bbh")
            .replace(Regex("ੱ([bcdfghjklmnpqrstvwxyz\\u00C0-\\u024F\\u1E00-\\u1EFF])", RegexOption.IGNORE_CASE), "$1$1")
        text = text.replace("ੱ", "")
        text = text.replace("r̤h", "dh").replace("r̤", "d")

        // 2. Specific Gurmukhi nasal & diphthong patterns before stripMarkers
        text = text.replace("sāṃbha", "saambh")
        text = text.replace(Regex("īāṃ(?=[^a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "iyan")
            .replace(Regex("īā(?=[^a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "iya")
            .replace(Regex("īe(?=[^a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "iye")
            .replace(Regex("iāṃ(?=[^a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "eyaan")
            .replace(Regex("iā(?=[^a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "eya")
            .replace(Regex("u[ṃ~m̐](?=[^a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "u")
            .replace(Regex("nā[ṃ~m̐]"), "naan")
            .replace(Regex("āṃ(?=[^a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "an")

        // Gurmukhi Pronoun & Named Entity stems
        text = text.replace(Regex("tuhānū[ṃ~m̐]?"), "tuhanu")
            .replace(Regex("uhanū[ṃ~m̐]?"), "ohnu")
            .replace(Regex("ihanū[ṃ~m̐]?"), "ehnu")
            .replace(Regex("naī[ṃ~m̐]?"), "nahi")
            .replace(Regex("caṃḍīgar̤ha?"), "chandigarh")
            .replace(Regex("paṃjāba?"), "punjab")
            .replace(Regex("paṃjābī"), "punjabi")
            .replace(Regex("sā[mha]+ṇe"), "samne")

        val nukta = "[\\u093C\\u0A3C]"
        text = text.replace(Regex("sa$nukta"), "sha")
            .replace(Regex("s$nukta"), "sh")
            .replace(Regex("ga$nukta"), "gha")
            .replace(Regex("g$nukta"), "gh")
            .replace(Regex(nukta), "")

        text = stripMarkers(text)
        text = text.replace(Regex("c(?!h)"), "ch")
        text = text.replace("chch", "ch").replace("cch", "ch")

        // Consonant collapse (preserves kkh, tth, bbh, collapses duplicate sibilants)
        text = text.replace(Regex("([bcdfghjklmnpqrstvwxyz])\\1{2,}"), "$1$1")
        text = text.replace(Regex("(sh)\\1"), "$1")

        val words = text.split(Regex("\\s+"))
        val resWords = mutableListOf<String>()
        for (word in words) {
            var w = word
            // Final schwa deletion on simple consonants
            if (w.length > 2 && w.endsWith("a") && !w.endsWith("ā") && !w.endsWith("aa") && !w.endsWith("ya") && !w.endsWith("wa")) {
                w = w.replace(Regex("([bcdfghjklmnpqrstvwxyz]+)a$"), "$1")
            }
            // Medial schwa deletion (protects long vowels and specific stems)
            if (!w.startsWith("tuhan") && !w.startsWith("punjab")) {
                w = w.reversed()
                w = w.replaceFirst(Regex("([aeiouāīūēōy][bcdfghjklmnpqrstvwxyz]+)a([bcdfghjklmnpqrstvwxyz]+[aeiouāīūēōy])"), "$1$2")
                w = w.reversed()
            }
            resWords.add(w)
        }

        text = resWords.joinToString(" ")

        // Conjunct /w/
        text = text.replace(Regex("([bcdfghjklmnpqrstz])v([aeiouāīū])"), "$1w$2")

        text = flattenVowels(text)

        // Vowel contractions & participles
        text = text.replace(Regex("\\bhoi[aā]\\b"), "hoya")
            .replace(Regex("\\bhoia\\b"), "hoya")
            .replace(Regex("\\bhoeya\\b"), "hoya")
            .replace(Regex("\\bgiā\\b"), "gaya")
            .replace(Regex("\\bgia\\b"), "gaya")
            .replace("piaar", "pyaar")
            .replace("tainoon", "tainu").replace("mainoon", "mainu").replace("saanoon", "saanu")
            .replace("zahir", "zeher").replace("shahar", "sheher")
            .replace(Regex("\\btoon\\b"), "tu")
            .replace(Regex("\\bnoon\\b"), "nu")
            .replace(Regex("\\bkiun\\b"), "kyun")
            .replace(Regex("\\bkiu\\b"), "kyun")
            .replace("sohani", "sohni").replace("sohana", "sohna").replace("sohane", "sohne")
            .replace("samhne", "samne").replace("sahamne", "samne")
            .replace("challda", "chalda")

        // North Indic Vowel Compression
        val compWords = text.split(Regex("\\s+")).map { w ->
            var cw = w
            if (cw.endsWith("ee") && cw.length > 2) {
                cw = cw.substring(0, cw.length - 2) + "i"
            }
            if (cw.endsWith("aa") && cw.length > 3 && !cw.endsWith("yaa") && !cw.endsWith("waa") && cw != "naan" && cw != "taan" && cw != "daan") {
                cw = cw.substring(0, cw.length - 1)
            }
            cw
        }
        text = compWords.joinToString(" ")

        // Token overrides
        val tokens = text.split(Regex("\\s+"))
        return tokens.joinToString(" ") { PUNJABI_CLOSED_CLASS[it.lowercase()] ?: it }.lowercase()
    }

    // ── 4. GUJARATI PROCESSOR ──

    private val GUJARATI_CLOSED_CLASS = mapOf(
        "che" to "chhe", "cho" to "chho", "chai" to "chhai",
        "chogala" to "chogada", "chogalaa" to "chogada", "chogada" to "chogada", "chhogaala" to "chogada",
        "tame" to "tame", "tamare" to "tamare", "tamaru" to "tamaru", "tamara" to "tamara", "tamari" to "tamari", "tamaare" to "tamare", "tamaaru" to "tamaru",
        "tara" to "tara", "tari" to "tari", "taro" to "taaro", "taru" to "taaru", "taaru" to "taaru", "taara" to "tara",
        "mane" to "mane", "maru" to "maaru", "maaru" to "maaru", "maro" to "maaro", "maaro" to "maaro",
        "mari" to "maari", "maari" to "maari", "mara" to "maara", "maara" to "maara",
        "hu" to "hu", "hoon" to "hu", "hun" to "hu",
        "ame" to "ame", "amaru" to "amaaru", "amaro" to "amaaro", "amari" to "amaari", "amaaru" to "amaaru",
        "aapne" to "aapne", "aapnu" to "aapnu", "aapna" to "aapna", "aapane" to "aapne", "aapanu" to "aapnu", "aapanun" to "aapnu",
        "kem" to "kem", "saaru" to "saaru", "saru" to "saaru", "saarun" to "saaru",
        "nathi" to "nathi", "nthi" to "nathi",
        "hatu" to "hatu", "hata" to "hata", "hati" to "hati", "hashe" to "hashe", "hatun" to "hatu",
        "vhalam" to "vhalam", "vhala" to "vhala", "vhali" to "vhali", "vhaalam" to "vhalam", "vhaala" to "vhala",
        "vaat" to "vaat", "vaato" to "vaato", "vat" to "vaat",
        "saath" to "saath", "saathe" to "saathe", "sath" to "saath", "sathe" to "saathe",
        "garba" to "garba", "garaba" to "garba", "dandiya" to "dandiya", "daandiya" to "dandiya", "daandiyaa" to "dandiya",
        "dhol" to "dhol", "dholida" to "dholida", "dholeeda" to "dholida",
        "khalasi" to "khalasi", "khalaasi" to "khalasi", "moti" to "moti", "dariyo" to "dariyo",
        "mogal" to "mogal", "madi" to "maadi", "maadi" to "maadi", "khamma" to "khamma",
        "chalo" to "chalo", "chaalo" to "chalo", "aavo" to "aavo", "jovo" to "jovo", "kaho" to "kaho",
        "sambhalo" to "sambhalo", "saambhalo" to "sambhalo", "malya" to "malya", "malyo" to "malyo", "mali" to "mali", "malyaa" to "malya",
        "dil" to "dil", "prem" to "prem", "jeevan" to "jeevan", "jivan" to "jeevan",
        "sapnu" to "sapnu", "sapna" to "sapna", "sapanu" to "sapnu", "sapanun" to "sapnu", "sapnun" to "sapnu",
        "aankh" to "aankh", "aankho" to "aankho", "ankh" to "aankh", "ankho" to "aankho",
        "raat" to "raat", "divas" to "divas", "saanj" to "saanj", "savaar" to "savaar",
        "paani" to "paani", "kamal" to "kamal", "kamariya" to "kamariya", "kamariyo" to "kamariyo", "kamriya" to "kamariya", "kamriyaa" to "kamariya",
        "radha" to "radha", "krishna" to "krishna", "kanha" to "kanha", "kaano" to "kaano",
        "bhai" to "bhai", "ben" to "ben", "dost" to "dost", "dosti" to "dosti",
        "saanwariyo" to "saanvariyo", "saanvariyo" to "saanvariyo",
        "chhabeela" to "chhabila", "chhabila" to "chhabila",
        "tamaarun" to "tamaru", "tamaare" to "tamare", "taarun" to "taaru", "maarun" to "maaru"
    )

    private fun processGujarati(rawIast: String): String {
        var text = rawIast

        // 1. Gujarati Specific phonotactics
        text = text.replace("l̤", "l")
        text = text.replace("r̤h", "dh").replace("r̤", "d")

        // Word-final neuter anusvara on -uṃ -> -u
        text = text.replace(Regex("u[ṃ~m̐](?=[^a-zA-Z\\u00C0-\\u024F\\u1E00-\\u1EFF]|$)"), "u")
            .replace("iyā", "iya")
            .replace("yā", "ya")

        // Gujarati ch/chh differentiation (ચ -> ch, છ -> chh)
        text = text.replace("ch", "chh")
        text = text.replace(Regex("c(?!h)"), "ch")

        val nukta = "[\\u093C\\u0ABC]"
        text = text.replace(Regex(nukta), "")

        text = stripMarkers(text)

        // Consonant collapse
        text = text.replace(Regex("([bcdfghjklmnpqrstvwxyz])\\1{2,}"), "$1$1")
        text = text.replace(Regex("(sh)\\1"), "$1")

        val words = text.split(Regex("\\s+"))
        val resWords = mutableListOf<String>()
        for (word in words) {
            var w = word
            // Final schwa deletion on simple consonants
            if (w.length > 2 && w.endsWith("a") && !w.endsWith("ā") && !w.endsWith("aa") && !w.endsWith("ya") && !w.endsWith("wa")) {
                w = w.replace(Regex("([bcdfghjklmnpqrstvwxyz]+)a$"), "$1")
            }
            // Medial schwa deletion (protects -iy-)
            if (!w.endsWith("iya")) {
                w = w.reversed()
                w = w.replaceFirst(Regex("([aeiouy][bcdfghjklmnpqrstvwxyz]+)a([bcdfghjklmnpqrstvwxyz]+[aeiouy])"), "$1$2")
                w = w.reversed()
            }
            resWords.add(w)
        }

        text = resWords.joinToString(" ")

        // Conjunct /w/
        text = text.replace(Regex("([bcdfghjklmnpqrstz])v([aeiouāīū])"), "$1w$2")

        text = flattenVowels(text)

        // North Indic Vowel Compression
        val compWords = text.split(Regex("\\s+")).map { w ->
            var cw = w
            if (cw.endsWith("ee") && cw.length > 2) {
                cw = cw.substring(0, cw.length - 2) + "i"
            }
            if (cw.endsWith("aa") && cw.length > 3 && !cw.endsWith("yaa") && !cw.endsWith("waa")) {
                cw = cw.substring(0, cw.length - 1)
            }
            cw
        }
        text = compWords.joinToString(" ")

        // Token overrides
        val tokens = text.split(Regex("\\s+"))
        return tokens.joinToString(" ") { GUJARATI_CLOSED_CLASS[it.lowercase()] ?: it }.lowercase()
    }

    // ── 5. TAMIL PROCESSOR ──

    private val TAMIL_CLOSED_CLASS = mapOf(
        "dhamiḻ" to "tamizh", "thamizh" to "tamizh", "tamil" to "tamizh", "thamil" to "tamizh",
        "kaadhal" to "kaadhal", "kadhal" to "kaadhal", "kaadhali" to "kaadhali", "kadhali" to "kaadhali",
        "uyir" to "uyir", "uyire" to "uyire",
        "kanne" to "kanne", "kan" to "kan", "kangal" to "kangal", "kanneer" to "kanneer",
        "nee" to "nee", "naan" to "naan", "un" to "un", "unnai" to "unnai", "en" to "en", "ennai" to "ennai",
        "naam" to "naam", "namma" to "namma", "nammal" to "nammal",
        "vaa" to "vaa", "po" to "po", "vaanam" to "vaanam", "nila" to "nila", "nilaa" to "nila", "nilave" to "nilave",
        "kaatru" to "kaatru", "paadal" to "paadal", "paattu" to "paattu", "isai" to "isai",
        "endru" to "endru", "ondru" to "ondru", "nandri" to "nandri", "vetri" to "vetri", "vettri" to "vetri",
        "konjam" to "konjam", "nenjam" to "nenjam", "manam" to "manam", "manasu" to "manasu",
        "anbu" to "anbu", "aasai" to "aasai", "asai" to "aasai",
        "azhagu" to "azhagu", "mazhai" to "mazhai", "vaazhga" to "vaazhga", "vazhga" to "vaazhga",
        "roja" to "roja", "rojaa" to "roja", "malare" to "malare", "poove" to "poove",
        "thevadhai" to "dhevadhai", "kolaveri" to "kolaveri", "rowdy" to "rowdy", "baby" to "baby", "vaathi" to "vaathi",
        "rendaa" to "rendhaa", "rendhaa" to "rendhaa"
    )

    private fun processTamil(rawIast: String): String {
        var text = rawIast
        text = applyTamilAllophony(text)
        text = stripMarkers(text)
        text = flattenVowels(text)
        text = text.replace("thth", "th")
        text = text.replace("chch", "ch")
        text = text.replace("kangal̤", "kangal")

        val tokens = text.split(Regex("\\s+"))
        return tokens.joinToString(" ") { TAMIL_CLOSED_CLASS[it.lowercase()] ?: it }.lowercase()
    }

    private fun applyTamilAllophony(text: String): String {
        var result = text
        // ṉṟ -> ndr
        result = result.replace("ṉṟ", "NDR")
        // ṟṟ -> tr
        result = result.replace("ṟṟ", "TR")
        // ḍhḍh -> tt
        result = result.replace("ḍhḍh", "TT")
        result = result.replace("ḍh", "d")

        // Nasal assimilation
        result = result.replace("ṅgh", "ng")
        result = result.replace("ṅk", "ng")
        result = result.replace("ṇgh", "ng")
        result = result.replace("ñjh", "nj")

        // Intervocalic / Post-approximant g
        result = result.replace(Regex("([aeiouāīūēōèòḻ])gh"), "$1g")
        result = result.replace("ghgh", "kk")
        result = result.replace("gh", "k")
        result = result.replace("kk", "k")

        result = result.replace("jhjh", "CH_GEM")
        result = result.replace("jh", "s")
        result = result.replace("CH_GEM", "ch")

        // Word-initial voicings vs intervocalics
        result = result.replace(Regex("(^|\\s)dh"), "$1th")
        result = result.replace(Regex("(^|\\s)bh"), "$1p")
        result = result.replace("bh", "b")
        result = result.replace("NDR", "ndr")
        result = result.replace("TR", "tr")
        result = result.replace("TT", "tt")
        return result
    }

    // ── 6. TELUGU PROCESSOR ──

    private val TELUGU_CLOSED_CLASS = mapOf(
        "samajavaragamana" to "samajavaragamana", "saamajavaragamanaa" to "samajavaragamana", "saamajavaragamana" to "samajavaragamana",
        "buttabomma" to "buttabomma", "buttabòmma" to "buttabomma", "buttabommaa" to "buttabomma",
        "nuvvu" to "nuvvu", "nenu" to "nenu", "naatu" to "naatu", "natu" to "naatu",
        "vachinde" to "vachinde", "vachindhe" to "vachinde", "vacchende" to "vachinde",
        "srivalli" to "srivalli", "shreevalli" to "srivalli", "gunde" to "gunde",
        "hrudayam" to "hrudayam", "hridayam" to "hrudayam", "hrdayam" to "hrudayam",
        "prema" to "prema", "jeevitham" to "jeevitham", "jeevitam" to "jeevitham",
        "sangeetham" to "sangeetham", "sangeetam" to "sangeetham",
        "enduku" to "enduku", "yenduku" to "enduku", "ekkada" to "ekkada", "yekkada" to "ekkada",
        "cheliya" to "cheliya", "cheliyaa" to "cheliya", "sakhiya" to "sakhiya", "sakhiyaa" to "sakhiya", "vennela" to "vennela",
        "kannulu" to "kannulu", "kallu" to "kallu", "maata" to "maata", "mata" to "maata",
        "praanam" to "praanam", "pranam" to "praanam", "shwaasa" to "shwaasa", "shwasa" to "shwaasa",
        "choopu" to "choopu", "chupu" to "choopu", "pilla" to "pilla", "pillaa" to "pilla",
        "radha" to "radha", "raadha" to "radha", "krishna" to "krishna",
        "andham" to "andham", "andam" to "andham", "sontham" to "sontham", "sontam" to "sontham", "bandham" to "bandham",
        "kaalam" to "kaalam", "kalam" to "kaalam", "lokam" to "lokam",
        "kalisi" to "kalisi", "raavali" to "raavali", "raavaali" to "raavali", "ravali" to "raavali", "povaali" to "povaali", "povali" to "povaali",
        "undi" to "undi", "undhi" to "undi", "unnaavu" to "unnaavu", "unnavu" to "unnaavu",
        "gaali" to "gaali", "gali" to "gaali", "neeru" to "neeru", "niru" to "neeru",
        "aakaasham" to "aakaasham", "aakasham" to "aakaasham", "theeram" to "theeram", "teeram" to "theeram", "tiram" to "theeram"
    )

    private fun processTelugu(rawIast: String): String {
        var text = rawIast
        // Dravidian Nasal Assimilation
        text = text.replace("ṃḍ", "nd").replace("ṃṭ", "nt")
        text = text.replace("ṃth", "nth").replace("ṃt", "nth")
        text = text.replace("ṃdh", "ndh").replace("ṃd", "nd")
        text = text.replace("ṃk", "nk").replace("ṃg", "ng")
        text = text.replace("ṃp", "mp").replace("ṃb", "mb")
        text = text.replace("ṃ", "m")

        text = text.replace("l̤l̤", "ll").replace("l̤", "l")
        text = text.replace("hṛ", "hru")
        text = text.replace("śrī", "sri").replace("śvā", "shwaa").replace("śva", "shwa")
        text = text.replace(Regex("iyā\\b"), "iya")

        text = stripMarkers(text)
        text = text.replace(Regex("c(?!h)"), "ch")
        text = text.replace("chch", "ch")
        text = flattenVowels(text)

        val tokens = text.split(Regex("\\s+"))
        return tokens.joinToString(" ") { TELUGU_CLOSED_CLASS[it.lowercase()] ?: it }.lowercase()
    }

    // ── 7. KANNADA PROCESSOR ──

    private fun processKannada(rawIast: String): String {
        var text = rawIast
        text = text.replace("ṃḍ", "nd").replace("ṃṭ", "nt")
        text = text.replace("ṃth", "nth").replace("ṃt", "nth")
        text = text.replace("ṃdh", "ndh").replace("ṃd", "nd")
        text = text.replace("ṃk", "nk").replace("ṃg", "ng")
        text = text.replace("ṃp", "mp").replace("ṃb", "mb")
        text = text.replace("ṃ", "m")

        text = stripMarkers(text)
        text = text.replace(Regex("c(?!h)"), "ch")
        text = flattenVowels(text)
        return text.lowercase()
    }

    // ── 8. MALAYALAM PROCESSOR ──

    private fun processMalayalam(rawIast: String): String {
        var text = rawIast
        text = text.replace("ṃḍ", "nd").replace("ṃṭ", "nt")
        text = text.replace("ṃth", "nth").replace("ṃt", "nth")
        text = text.replace("ṃdh", "ndh").replace("ṃd", "nd")
        text = text.replace("ṃk", "nk").replace("ṃg", "ng")
        text = text.replace("ṃp", "mp").replace("ṃb", "mb")
        text = text.replace(Regex("ṃ$", RegexOption.MULTILINE), "m")
        text = text.replace(Regex("ṃ(\\s)"), "m$1")
        text = text.replace("ññ", "nj")
        text = text.replace("ñ", "nj")
        text = text.replace(Regex("t(?!t)(?![hṭ])"), "th")

        text = stripMarkers(text)
        text = text.replace(Regex("c(?!h)"), "ch")
        text = flattenVowels(text)
        text = text.replace("thth", "th")
        return text.lowercase()
    }

    // ── READABILITY OVERRIDES ──

    private fun applyReadabilityOverrides(text: String, scriptName: String): String {
        var processed = text
        if (scriptName == "gurmukhi") {
            processed = processed.replace("piaar", "pyaar")
            processed = processed.replace("mundiaan", "mundeyaan")
            processed = processed.replace("tainoon", "tainu")
        }
        return processed
    }
}
