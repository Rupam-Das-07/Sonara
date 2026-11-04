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
    fun `YouTube thumbnail urls are preserved, not guessed upward`() {
        // TEST FIX (Phase 2C): this previously asserted hqdefault -> maxresdefault. The Phase 2C
        // rule is "Never allow a generic or guessed maxresdefault.jpg URL to masquerade as
        // verified artwork. Verify candidate availability before treating a resolution-specific
        // variant as usable." maxresdefault.jpg is a separate stored file that is absent for many
        // videos, so the rewrite could replace a working thumbnail with a 404.
        val original = "https://i.ytimg.com/vi/abc/hqdefault.jpg"
        assertEquals(original, ArtworkUrlUpgrader.upgrade(original))
    }

    @Test
    fun `an already maxres YouTube url is not corrupted`() {
        // Regression: the old pattern allowed the bare alternative `default`, and
        // `maxresdefault.jpg` ends with `default.jpg`, so this URL — the one the backend
        // actually emits for video results — became `maxresmaxresdefault.jpg`.
        val original = "https://i.ytimg.com/vi/abc/maxresdefault.jpg"
        assertEquals(original, ArtworkUrlUpgrader.upgrade(original))
    }
}
