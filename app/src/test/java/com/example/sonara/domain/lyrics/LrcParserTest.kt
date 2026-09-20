package com.example.sonara.domain.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parse empty or blank string returns empty list`() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   \n  ").isEmpty())
    }

    @Test
    fun `parse standard timestamped lines returns sorted list`() {
        val lrc = """
            [00:15.50] Second line
            [00:05.00] First line
            [01:00.00] Third line
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)
        assertEquals(5000L, lines[0].timestampMs)
        assertEquals("First line", lines[0].text)
        assertEquals(15500L, lines[1].timestampMs)
        assertEquals("Second line", lines[1].text)
        assertEquals(60000L, lines[2].timestampMs)
        assertEquals("Third line", lines[2].text)
    }

    @Test
    fun `parse multi-timestamp lines expands into distinct sorted entries`() {
        val lrc = """
            [00:10.00][00:30.00] Repeated Chorus
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].timestampMs)
        assertEquals("Repeated Chorus", lines[0].text)
        assertEquals(30000L, lines[1].timestampMs)
        assertEquals("Repeated Chorus", lines[1].text)
    }

    @Test
    fun `filters out metadata header tags`() {
        val lrc = """
            [ti:Song Title]
            [ar:Artist Name]
            [al:Album Name]
            [by:LRC Author]
            [00:01.00] Real Lyric Line
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals("Real Lyric Line", lines[0].text)
    }

    @Test
    fun `decodes html entities in lyric text`() {
        val lrc = "[00:02.00] You&#39;re &amp; I &quot;Rock&quot; &lt;3"
        val lines = LrcParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals("You're & I \"Rock\" <3", lines[0].text)
    }
}
