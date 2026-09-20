package com.example.sonara.data.remote.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkUrlUpgraderTest {

    @Test
    fun `null or blank url returns null`() {
        assertNull(ArtworkUrlUpgrader.upgrade(null))
        assertNull(ArtworkUrlUpgrader.upgrade(""))
        assertNull(ArtworkUrlUpgrader.upgrade("   "))
    }

    @Test
    fun `Google and YTM urls upgraded to 544x544`() {
        val original = "https://lh3.googleusercontent.com/test=w120-h120-l90-rj"
        val expected = "https://lh3.googleusercontent.com/test=w544-h544-l90-rj"
        assertEquals(expected, ArtworkUrlUpgrader.upgrade(original))
    }

    @Test
    fun `JioSaavn urls upgraded to 500x500`() {
        val original = "https://c.saavncdn.com/123/album-150x150.jpg"
        val expected = "https://c.saavncdn.com/123/album-500x500.jpg"
        assertEquals(expected, ArtworkUrlUpgrader.upgrade(original))
    }

    @Test
    fun `Apple Music and iTunes urls upgraded to 600x600`() {
        val original = "https://is1-ssl.mzstatic.com/image/thumb/Music/123.jpg/100x100bb.jpg"
        val expected = "https://is1-ssl.mzstatic.com/image/thumb/Music/123.jpg/600x600bb.jpg"
        assertEquals(expected, ArtworkUrlUpgrader.upgrade(original))
    }

    @Test
    fun `YouTube thumbnail urls upgraded to maxresdefault`() {
        val original = "https://i.ytimg.com/vi/abc/hqdefault.jpg"
        val expected = "https://i.ytimg.com/vi/abc/maxresdefault.jpg"
        assertEquals(expected, ArtworkUrlUpgrader.upgrade(original))
    }
}
