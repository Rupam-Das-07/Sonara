package com.example.sonara.domain.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistIdentityTest {
    @Test
    fun `native names and marks survive name matching and public slugs`() {
        for (name in listOf("अरिजीत सिंह", "অরিজিৎ সিং", "क", "कि", "क़", "क्\u200Dष", "ক্\u200Cষ")) {
            assertEquals(name.replace(' ', '-'), ArtistIdentity.nameKey(name))
            assertEquals(name.replace(' ', '-'), ArtistAffinityCalculator.slugify(name))
        }
        assertEquals(ArtistIdentity.nameKey("क़"), ArtistIdentity.nameKey("क़"))
        assertEquals("क़", ArtistIdentity.slugify("क़"))
        assertEquals(ArtistIdentity.nameKey("Beyoncé"), ArtistIdentity.nameKey("Beyonce\u0301"))
        assertEquals("beyonce", ArtistIdentity.slugify("Beyonce\u0301"))
    }

    @Test
    fun `matching preserves punctuation accents native distinctions and joiners`() {
        for ((left, right) in listOf(
            "AB" to "A-B", "AC/DC" to "AC-DC", "Beyonce" to "Beyoncé",
            "क" to "कि", "क" to "क़", "कि" to "क़",
            "क्\u200Dष" to "क्ष", "ক্\u200Cষ" to "ক্ষ",
            "A.B" to "A-B", "A--B" to "A-B"
        )) {
            assertNotEquals("$left must differ from $right", ArtistIdentity.nameKey(left), ArtistIdentity.nameKey(right))
        }
        assertEquals(ArtistIdentity.nameKey("A B"), ArtistIdentity.nameKey("A-B"))
        assertEquals("a.r.-rahman", ArtistIdentity.rawNameKey("A.R. Rahman"))
        for (name in listOf("AR Rahman", "A.R. Rahman", "A-R-Rahman", "A. R. Rahman")) {
            assertEquals("ar-rahman", ArtistIdentity.nameKey(name))
        }
        assertNotEquals(ArtistIdentity.nameKey("ARRahman"), ArtistIdentity.nameKey("AR Rahman"))
    }

    @Test
    fun `exact Unicode whitespace set is trimmed and collapsed`() {
        val whitespace = (0x0009..0x000D).toList() + listOf(0x0020, 0x0085, 0x00A0, 0x1680) +
            (0x2000..0x200A).toList() + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)
        for (cp in whitespace) {
            val space = cp.toChar().toString()
            assertEquals("a-b", ArtistIdentity.nameKey("${space}A${space}${space}B$space"))
        }
    }

    @Test
    fun `invalid nullable empty symbolic and malformed names never have a key or slug`() {
        for (name in listOf(null, "", " \u00A0", "!!!", "😀", "\u0301", "\u093F",
            "A\u0000B", "A\u001FB", "A\u007FB", "A\u200BB", "A\uFEFFB", "A\u202EB",
            "A\uD800", "\uDC00A")) {
            assertNull("invalid name: $name", ArtistIdentity.nameKey(name))
            assertEquals("invalid slug: $name", "", ArtistIdentity.slugify(name))
        }
        assertEquals("𐐨-१२", ArtistIdentity.nameKey("𐐀 १२"))
        assertEquals("ⅷ", ArtistIdentity.nameKey("Ⅷ"))
        assertEquals("a😀b", ArtistIdentity.nameKey("A😀B"))
    }

    @Test
    fun `public slug compatibility is limited to Latin accents and separators`() {
        assertEquals("beyonce", ArtistIdentity.slugify("Beyoncé"))
        assertEquals("a-r-rahman", ArtistIdentity.slugify("A.R. Rahman"))
        assertEquals("ac-dc", ArtistIdentity.slugify("AC/DC"))
        assertEquals("α\u0301", java.text.Normalizer.normalize(ArtistIdentity.slugify("ά"), java.text.Normalizer.Form.NFD))
        assertEquals("ﬃ", ArtistIdentity.slugify("ﬃ")) // NFD, never compatibility folding
        assertEquals("क\u0301", ArtistIdentity.slugify("क\u0301")) // non-Latin attached accent
    }
}
