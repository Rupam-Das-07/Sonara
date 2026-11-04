package com.example.sonara.data.remote.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2C — artwork IDENTITY and availability contract for [ArtworkUrlUpgrader].
 *
 * These cases are derived from the Phase 2C requirements, not from the implementation:
 *
 *   AC1 an already-`maxresdefault` i.ytimg.com URL is never corrupted
 *   AC2 no guessed resolution-specific ytimg variant is substituted for the provider's URL
 *   AC3 a googleusercontent URL never gains a second `=` parameter block
 *   AC4 unknown-provider and signed URLs pass through byte-identical
 *   AC5 documented same-asset CDN resizes (saavncdn, mzstatic, google `=w-h`) still apply
 *   AC6 blank/absent input yields null so the surface uses its existing placeholder
 *   AC7 [property] the transform never changes host or the ytimg filename, never blanks a
 *       usable URL, and is idempotent
 *
 * Why the ytimg rules are "preserve" rather than "upgrade": `maxresdefault.jpg` is a separate
 * stored file that exists only for sufficiently high-resolution sources, unlike the CDN size
 * tokens which are server-side resizes of one asset. Rewriting between them is a guess at a
 * different asset, and the Phase 2C rule is that a generic/guessed `maxresdefault.jpg` must
 * never masquerade as verified artwork.
 */
class ArtworkIdentityTest {

    // ---------------------------------------------------------------- AC1 [regression]

    @Test
    fun `an already maxres ytimg url is returned unchanged`() {
        // The backend emits exactly this URL for YouTube video results. The previous rewrite
        // matched the trailing `default.jpg` of `maxresdefault.jpg` and produced
        // `maxresmaxresdefault.jpg`, a guaranteed 404, so these results never showed artwork.
        val url = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"
        assertEquals(url, ArtworkUrlUpgrader.upgrade(url))
    }

    @Test
    fun `no ytimg url is ever rewritten to a doubled filename`() {
        val urls = listOf(
            "https://i.ytimg.com/vi/abc/maxresdefault.jpg",
            "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            "https://i.ytimg.com/vi/abc/mqdefault.jpg",
            "https://i.ytimg.com/vi/abc/sddefault.jpg",
            "https://i.ytimg.com/vi/abc/default.jpg"
        )
        for (url in urls) {
            val out = ArtworkUrlUpgrader.upgrade(url)
            assertTrue("$url must not double the filename, got $out", out?.contains("maxresmaxres") != true)
        }
    }

    // ------------------------------------------------------------------- AC2 [identity]

    @Test
    fun `ytimg variants are preserved rather than guessed upward`() {
        for (variant in listOf("hqdefault", "mqdefault", "sddefault", "default", "maxresdefault")) {
            val url = "https://i.ytimg.com/vi/abc/$variant.jpg"
            assertEquals(
                "a resolution-specific ytimg variant must be preserved, never substituted",
                url,
                ArtworkUrlUpgrader.upgrade(url)
            )
        }
    }

    @Test
    fun `the img youtube com host is also left alone`() {
        val url = "https://img.youtube.com/vi/abc/maxresdefault.jpg"
        assertEquals(url, ArtworkUrlUpgrader.upgrade(url))
    }

    // ---------------------------------------------------------------------- AC3 [input]

    @Test
    fun `a googleusercontent url with an existing parameter block is not double appended`() {
        // `=s120-c` is the avatar form. Appending a second block yielded
        // `=s120-c=w544-h544-l90-rj`, which does not resolve.
        val url = "https://lh3.googleusercontent.com/ytc/abc=s120-c"
        val out = ArtworkUrlUpgrader.upgrade(url)
        assertEquals(url, out)
        assertTrue("only one parameter block may ever be present", out!!.count { it == '=' } == 1)
    }

    @Test
    fun `a googleusercontent url with a query string is not double appended`() {
        val url = "https://lh3.googleusercontent.com/abc?v=2"
        assertEquals(url, ArtworkUrlUpgrader.upgrade(url))
    }

    @Test
    fun `a bare googleusercontent url still receives one parameter block`() {
        assertEquals(
            "https://lh3.googleusercontent.com/ytc/abc=w544-h544-l90-rj",
            ArtworkUrlUpgrader.upgrade("https://lh3.googleusercontent.com/ytc/abc")
        )
    }

    // -------------------------------------------------------------- AC4 [security/input]

    @Test
    fun `unknown provider and signed urls pass through byte identical`() {
        val urls = listOf(
            "https://cdn.example.com/a.jpg?sig=abc&exp=123",
            "https://img.example.org/cover-150x150.jpg",
            "https://example.test/path/600x600bb.jpg",
            "https://host.invalid/art.png"
        )
        for (url in urls) {
            assertEquals("an unrecognised provider's URL must not be transformed", url, ArtworkUrlUpgrader.upgrade(url))
        }
    }

    // --------------------------------------------------------------------- AC5 [regression]

    @Test
    fun `documented same asset cdn resizes still apply`() {
        assertEquals(
            "https://c.saavncdn.com/x/album-500x500.jpg",
            ArtworkUrlUpgrader.upgrade("https://c.saavncdn.com/x/album-150x150.jpg")
        )
        assertEquals(
            "https://is1.mzstatic.com/image/thumb/a/600x600bb.jpg",
            ArtworkUrlUpgrader.upgrade("https://is1.mzstatic.com/image/thumb/a/100x100bb.jpg")
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj",
            ArtworkUrlUpgrader.upgrade("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj")
        )
    }

    // -------------------------------------------------------------------- AC6 [boundary]

    @Test
    fun `blank input yields null so the existing placeholder is used`() {
        assertNull(ArtworkUrlUpgrader.upgrade(null))
        assertNull(ArtworkUrlUpgrader.upgrade(""))
        assertNull(ArtworkUrlUpgrader.upgrade("   "))
        assertNull(ArtworkUrlUpgrader.upgrade("\t\n"))
    }

    // -------------------------------------------------------------------- AC7 [property]

    @Test
    fun `property - host and ytimg filename are stable, usable urls stay usable, transform is idempotent`() {
        val corpus = buildList {
            for (host in listOf(
                "https://i.ytimg.com/vi/abc",
                "https://img.youtube.com/vi/abc",
                "https://lh3.googleusercontent.com/abc",
                "https://yt3.ggpht.com/ytc/abc",
                "https://c.saavncdn.com/x/album",
                "https://is1.mzstatic.com/image/thumb/a",
                "https://cdn.example.com/art"
            )) {
                for (suffix in listOf(
                    "", "/maxresdefault.jpg", "/hqdefault.jpg", "/default.jpg",
                    "-150x150.jpg", "/100x100bb.jpg", "=w120-h120-l90-rj",
                    "=w544-h544-l90-rj", "=s120-c", "?sig=x&exp=1", ".jpg"
                )) {
                    add(host + suffix)
                }
            }
        }

        var checked = 0
        for (url in corpus) {
            val out = ArtworkUrlUpgrader.upgrade(url)
            assertNotNull("a non-blank URL must never become null: $url", out)
            checked++

            // Host is identity: a transform may never point at another origin.
            val host = url.substringAfter("://").substringBefore("/").substringBefore("?")
            assertTrue(
                "host must be preserved for $url (got $out)",
                out!!.substringAfter("://").substringBefore("/").substringBefore("?") == host
            )

            // Idempotence: re-resolving a stored URL must not drift.
            assertEquals("upgrade must be idempotent for $url", out, ArtworkUrlUpgrader.upgrade(out))

            // A YouTube thumbnail filename is a distinct asset, never rewritten.
            if (host == "i.ytimg.com" || host == "img.youtube.com") {
                assertEquals("ytimg URLs must be untouched: $url", url, out)
            }

            // Only ever one parameter block.
            assertTrue("at most one '=' block for $url (got $out)", out.count { it == '=' } <= url.count { it == '=' }.coerceAtLeast(1))
        }

        assertEquals("the corpus must actually have been exercised", 77, checked)
    }
}
