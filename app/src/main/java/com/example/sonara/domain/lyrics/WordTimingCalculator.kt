package com.example.sonara.domain.lyrics

import com.example.sonara.domain.model.LyricLine
import com.example.sonara.domain.model.LyricWord

/**
 * Natural Inertia-Based Word Timing Engine.
 * 100% faithful Kotlin port of the Sonara Web timing processor (lyricsProcessor.js).
 *
 * Estimates human singing cadence per word:
 * - 50ms per consonant (BASE_CHAR_DURATION = 0.05)
 * - 120ms per standard vowel (BASE_VOWEL_DURATION = 0.12)
 * - 150ms dead air after punctuation (.,!?;:) (BREATH_GAP = 0.15)
 * - Sustained Latin vowel bonuses (e.g. "aaa", "ooo")
 * - Indic long vowels (ा, ी, ू, ै, ौ, etc.) +100ms weight
 * - Decouples words from arbitrary instrumental gaps (compresses when necessary, never stretches)
 */
object WordTimingCalculator {

    const val TIMING_ENGINE_VERSION = 4

    private const val BASE_CHAR_DURATION_MS = 50L
    private const val BASE_VOWEL_DURATION_MS = 120L
    private const val BREATH_GAP_MS = 150L

    private val INDIC_VOWEL_REGEX = Regex(
        "[ऄ-औा-ौॎ-ॏॕ-ॗॠ-ॣॲ-ॷ" +
        "অ-ঔা-ৌৗৠ-ৣਅ-ਔਾ-ੌੰ-ੱ" +
        "અ-ઔા-ૌૠ-ૣஅ-ஔா-ௌௗఅ-ఔ" +
        "ా-ౌౕ-ౖౠ-ౣಅ-ಔಾ-ೌೕ-ೖೠ-ೣ" +
        "അ-ഔാ-ൌൗൠ-ൣ]"
    )

    private val INDIC_LONG_VOWEL_REGEX = Regex(
        "[आईऊऐऔाीूैौআঈঊঐঔাীূৈৌ" +
        "ਆਈਊਐਔਾੀੂੈੌઆઈઊઐઔાીૂૈૌ" +
        "ஆஈஊஐஔாீூைௌఆఈఊఐఔాీూైౌ" +
        "ಆಈಊಐಔಾೀೂೈೌആഈഊഐഔാീൂൈൌ]"
    )

    private val LATIN_VOWEL_REGEX = Regex("[aeiouyAEIOUY]")
    private val SUSTAINED_LATIN_VOWEL_REGEX = Regex("([aeiouyAEIOUY])\\1+")
    private val PUNCTUATION_GAP_REGEX = Regex("[.,!?…;:]$")
    private val CLEAN_WORD_REGEX = Regex("[.,!?()\\[\\]{}\"';:\\-…]")

    fun calculate(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return emptyList()

        return lines.mapIndexed { index, line ->
            val nextTimeMs = if (index + 1 < lines.size) lines[index + 1].timestampMs else line.timestampMs + 15_000L
            val availableMs = (nextTimeMs - line.timestampMs).coerceAtLeast(100L)

            val calculatedWords = calculateForLine(line.text, line.timestampMs, availableMs)
            val calculatedRomanizedWords = if (!line.romanizedText.isNullOrBlank()) {
                calculateForLine(line.romanizedText, line.timestampMs, availableMs)
            } else {
                emptyList()
            }

            line.copy(
                words = calculatedWords,
                romanizedWords = calculatedRomanizedWords
            )
        }
    }

    fun calculateForLine(
        lineText: String,
        lineStartMs: Long,
        availableDurationMs: Long
    ): List<LyricWord> {
        val wordTokens = lineText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (wordTokens.isEmpty()) return emptyList()

        val naturalDurationsMs = wordTokens.map { getNaturalWordDurationMs(it) }
        val punctuationGapsMs = wordTokens.map { if (PUNCTUATION_GAP_REGEX.containsMatchIn(it)) BREATH_GAP_MS else 0L }

        val totalNaturalMs = naturalDurationsMs.sum()
        val totalGapsMs = punctuationGapsMs.sum()
        val totalRequiredMs = totalNaturalMs + totalGapsMs

        val compressionRatio = if (totalRequiredMs > availableDurationMs && totalRequiredMs > 0) {
            availableDurationMs.toDouble() / totalRequiredMs.toDouble()
        } else {
            1.0
        }

        var accumulatedMs = lineStartMs
        return wordTokens.mapIndexed { idx, wordText ->
            val durationMs = (naturalDurationsMs[idx] * compressionRatio).toLong().coerceAtLeast(10L)
            val gapMs = (punctuationGapsMs[idx] * compressionRatio).toLong()

            val start = accumulatedMs
            val end = accumulatedMs + durationMs

            accumulatedMs = end + gapMs

            LyricWord(
                text = wordText,
                startMs = start,
                endMs = end
            )
        }
    }

    fun getNaturalWordDurationMs(word: String): Long {
        val cleaned = word.replace(CLEAN_WORD_REGEX, "")
        if (cleaned.isEmpty()) return 0L

        val charCount = cleaned.length
        val latinVowels = LATIN_VOWEL_REGEX.findAll(cleaned).count()
        val indicVowels = INDIC_VOWEL_REGEX.findAll(cleaned).count()
        val vowelCount = latinVowels + indicVowels

        var durationMs = (charCount * BASE_CHAR_DURATION_MS) + (vowelCount * BASE_VOWEL_DURATION_MS)

        // Sustained Vowel Awareness (consecutive identical Latin vowels)
        SUSTAINED_LATIN_VOWEL_REGEX.findAll(cleaned).forEach { match ->
            val extraVowels = match.value.length - 1
            val bonusPerVowelMs = (100 + (extraVowels * 50)).toLong()
            durationMs += extraVowels * bonusPerVowelMs
        }

        // Indic Long Vowels
        val longIndicCount = INDIC_LONG_VOWEL_REGEX.findAll(cleaned).count()
        if (longIndicCount > 0) {
            durationMs += longIndicCount * 100L
        }

        return durationMs
    }
}
