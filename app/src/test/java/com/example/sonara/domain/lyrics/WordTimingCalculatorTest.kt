package com.example.sonara.domain.lyrics

import com.example.sonara.domain.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingCalculatorTest {

    @Test
    fun `word duration calculation includes consonants and vowels`() {
        // "Sonara" -> 6 chars (6 * 50ms = 300ms) + 3 vowels: o, a, a (3 * 120ms = 360ms) -> total 660ms
        val duration = WordTimingCalculator.getNaturalWordDurationMs("Sonara")
        assertEquals(660L, duration)
    }

    @Test
    fun `punctuation adds breath gap to word duration sequence`() {
        // "Hello," has comma -> breath gap of 150ms between "Hello," and next word
        val words = WordTimingCalculator.calculateForLine(
            lineText = "Hello, world",
            lineStartMs = 1000L,
            availableDurationMs = 5000L
        )

        assertEquals(2, words.size)
        val firstWord = words[0]
        val secondWord = words[1]

        assertEquals("Hello,", firstWord.text)
        assertEquals("world", secondWord.text)
        assertEquals(1000L, firstWord.startMs)
        // Check that second word starts 150ms after first word ends (breath gap)
        assertEquals(firstWord.endMs + 150L, secondWord.startMs)
    }

    @Test
    fun `sustained Latin vowels add progressive duration bonuses`() {
        val normal = WordTimingCalculator.getNaturalWordDurationMs("no")
        val sustained = WordTimingCalculator.getNaturalWordDurationMs("nooo")

        // "no" (2 chars, 1 vowel): 100 + 120 = 220ms
        // "nooo" (4 chars, 3 vowels + sustained bonus): 200 + 360 + sustained bonus = 560 + 200 = 760ms
        assertTrue("Sustained vowels must have substantially greater duration", sustained > normal + 400L)
    }

    @Test
    fun `Indic long vowels add singing duration weight`() {
        val devanagariShort = WordTimingCalculator.getNaturalWordDurationMs("कमल")
        val devanagariLong = WordTimingCalculator.getNaturalWordDurationMs("काला")

        assertTrue("Indic long vowels must receive additional duration weight", devanagariLong > devanagariShort)
    }

    @Test
    fun `compression ratio caps word durations to available line duration`() {
        // Line with 10 words, but only 500ms available
        val lineText = "This is a very long line with many words in it"
        val words = WordTimingCalculator.calculateForLine(
            lineText = lineText,
            lineStartMs = 1000L,
            availableDurationMs = 500L
        )

        assertTrue(words.isNotEmpty())
        val lastWord = words.last()
        assertTrue("Last word endMs (${lastWord.endMs}) must be within available window (<= 1500ms)", lastWord.endMs <= 1505L)
    }

    @Test
    fun `calculate populates words on all LyricLines with start and end boundaries`() {
        val line1 = LyricLine(timestampMs = 1000L, text = "Tum hi ho", romanizedText = "Tum hi ho")
        val line2 = LyricLine(timestampMs = 4000L, text = "Ab tum hi ho", romanizedText = "Ab tum hi ho")

        val result = WordTimingCalculator.calculate(listOf(line1, line2))

        assertEquals(2, result.size)
        assertEquals(3, result[0].words.size)
        assertEquals(4, result[1].words.size)

        // Verify line 1 boundaries
        val firstLineWords = result[0].words
        assertEquals("Tum", firstLineWords[0].text)
        assertEquals(1000L, firstLineWords[0].startMs)
        assertEquals("hi", firstLineWords[1].text)
        assertEquals("ho", firstLineWords[2].text)
        assertTrue(firstLineWords[2].endMs <= 4000L)
    }
}
