package com.example.sonara.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for DownloadFileFormat and DownloadLocationMode domain models.
 */
class DownloadFormatPreferenceTest {

    // ─── DownloadFileFormat ────────────────────────────────────────────────────

    @Test
    fun `AUTO, WEBM, M4A, and MP4 are currently available native formats`() {
        val available = DownloadFileFormat.availableFormats()
        assertEquals(4, available.size)
        assertTrue(available.contains(DownloadFileFormat.AUTO))
        assertTrue(available.contains(DownloadFileFormat.WEBM))
        assertTrue(available.contains(DownloadFileFormat.M4A))
        assertTrue(available.contains(DownloadFileFormat.MP4))
    }

    @Test
    fun `M4A IS currently available as native audio container`() {
        assertTrue("M4A must be available as native AAC audio container",
            DownloadFileFormat.M4A.isCurrentlyAvailable)
    }

    @Test
    fun `MP4 IS currently available as native audio-only container`() {
        assertTrue("MP4 must be available as native audio-only container",
            DownloadFileFormat.MP4.isCurrentlyAvailable)
        assertEquals("audio/mp4", DownloadFileFormat.MP4.mimeType)
        assertEquals("MP4 Audio (.mp4)", DownloadFileFormat.MP4.displayLabel)
    }

    @Test
    fun `WEBM IS currently available`() {
        assertTrue(DownloadFileFormat.WEBM.isCurrentlyAvailable)
    }

    @Test
    fun `AUTO IS currently available`() {
        assertTrue(DownloadFileFormat.AUTO.isCurrentlyAvailable)
    }

    @Test
    fun `WEBM has correct file extension`() {
        assertEquals("webm", DownloadFileFormat.WEBM.fileExtension)
    }

    @Test
    fun `M4A has correct file extension`() {
        assertEquals("m4a", DownloadFileFormat.M4A.fileExtension)
    }

    @Test
    fun `MP4 has correct file extension`() {
        assertEquals("mp4", DownloadFileFormat.MP4.fileExtension)
    }

    @Test
    fun `fromName deserializes WEBM, AUTO, and MP4 correctly`() {
        assertEquals(DownloadFileFormat.WEBM, DownloadFileFormat.fromName("WEBM"))
        assertEquals(DownloadFileFormat.AUTO, DownloadFileFormat.fromName("AUTO"))
        assertEquals(DownloadFileFormat.MP4, DownloadFileFormat.fromName("MP4"))
        assertEquals(DownloadFileFormat.M4A, DownloadFileFormat.fromName("M4A"))
    }

    @Test
    fun `fromName returns AUTO for unknown format`() {
        assertEquals(DownloadFileFormat.AUTO, DownloadFileFormat.fromName("UNKNOWN"))
        assertEquals(DownloadFileFormat.AUTO, DownloadFileFormat.fromName(""))
        assertEquals(DownloadFileFormat.AUTO, DownloadFileFormat.fromName("mp3")) // not an enum name
    }

    @Test
    fun `fromName is case-sensitive to enum name`() {
        // Enum names are uppercase; lowercase should fall back to AUTO
        assertEquals(DownloadFileFormat.AUTO, DownloadFileFormat.fromName("webm"))
        assertEquals(DownloadFileFormat.M4A, DownloadFileFormat.fromName("M4A"))
        assertEquals(DownloadFileFormat.MP4, DownloadFileFormat.fromName("MP4"))
    }

    @Test
    fun `all supported file formats have distinct non-blank extensions`() {
        val formats = listOf(DownloadFileFormat.WEBM, DownloadFileFormat.M4A, DownloadFileFormat.MP4)
        val extensions = formats.map { it.fileExtension }.toSet()
        assertEquals(3, extensions.size)
    }

    // ─── DownloadLocationMode ─────────────────────────────────────────────────

    @Test
    fun `APP_PRIVATE is the default location mode`() {
        assertEquals(DownloadLocationMode.APP_PRIVATE, DownloadLocationMode.fromName("APP_PRIVATE"))
    }

    @Test
    fun `USER_SELECTED exists and is distinct from APP_PRIVATE`() {
        val mode = DownloadLocationMode.fromName("USER_SELECTED")
        assertEquals(DownloadLocationMode.USER_SELECTED, mode)
        assertFalse(mode == DownloadLocationMode.APP_PRIVATE)
    }

    @Test
    fun `fromName returns APP_PRIVATE for unknown mode`() {
        assertEquals(DownloadLocationMode.APP_PRIVATE, DownloadLocationMode.fromName("UNKNOWN"))
        assertEquals(DownloadLocationMode.APP_PRIVATE, DownloadLocationMode.fromName(""))
    }
}
