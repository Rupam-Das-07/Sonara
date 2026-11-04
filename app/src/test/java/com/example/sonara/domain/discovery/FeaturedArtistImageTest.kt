package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturedArtistImageTest {
    @Test
    fun `trusted image URL requires HTTPS Googleusercontent host boundary`() {
        assertEquals(
            "https://lh3.googleusercontent.com/a/b",
            FeaturedArtistImage.trustedHttpsUrl(" https://lh3.googleusercontent.com/a/b ")
        )
        assertEquals(
            "https://googleusercontent.com/a",
            FeaturedArtistImage.trustedHttpsUrl("https://googleusercontent.com/a")
        )
        assertEquals(
            "https://yt3.ggpht.com/a",
            FeaturedArtistImage.trustedHttpsUrl("https://yt3.ggpht.com/a")
        )
        assertNull(FeaturedArtistImage.trustedHttpsUrl("https://evilgoogleusercontent.com/a"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("http://lh3.googleusercontent.com/a"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("https://lh3.googleusercontent.com.evil.test/a"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("https://user:pass@lh3.googleusercontent.com/a"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("https://lh3.googleusercontent.com:8443/a"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("not a URL"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("https://example.com/avatar.jpg"))
        assertNull(FeaturedArtistImage.trustedHttpsUrl("null"))
    }

    @Test
    fun `rejected roster artwork keeps the artist and selects the monogram contract`() {
        val artist = FeaturedArtist(
            id = "untrusted",
            name = "Untrusted Artist",
            imageUrl = FeaturedArtistImage.trustedHttpsUrl("https://example.com/avatar.jpg")
        )

        assertEquals("Untrusted Artist", artist.name)
        assertNull(artist.imageUrl)
    }

    @Test
    fun `enrichment preserves order membership and metadata while handling misses`() = runTest {
        val artists = listOf(
            FeaturedArtist("editorial", "Editorial", genre = "Pop", imageUrl = "https://existing/image", browseId = "browse"),
            FeaturedArtist("personal-a", "Beyoncé", genre = "", browseId = null),
            FeaturedArtist("personal-b", "नमस्ते", genre = "", browseId = null)
        )

        val result = enrichFeaturedArtistImages(artists) { name ->
            when (name) {
                "Beyoncé" -> Result.success("https://lh3.googleusercontent.com/beyonce")
                "नमस्ते" -> Result.failure(IllegalStateException("miss"))
                else -> error("unexpected lookup")
            }
        }

        assertEquals(listOf("editorial", "personal-a", "personal-b"), result.map { it.id })
        assertEquals(artists[0], result[0])
        assertEquals("Beyoncé", result[1].name)
        assertEquals("personal-a", result[1].id)
        assertEquals("", result[1].genre)
        assertEquals("https://lh3.googleusercontent.com/beyonce", result[1].imageUrl)
        assertEquals(artists[2], result[2])
    }

    @Test
    fun `one thrown lookup does not lose later candidates`() = runTest {
        val artists = listOf(
            FeaturedArtist("a", "First"),
            FeaturedArtist("b", "Second")
        )
        val result = enrichFeaturedArtistImages(artists) { name ->
            if (name == "First") error("network") else Result.success("https://lh3.googleusercontent.com/second")
        }

        assertEquals(artists[0], result[0])
        assertEquals("https://lh3.googleusercontent.com/second", result[1].imageUrl)
    }

    @Test
    fun `cancellation is propagated rather than treated as a portrait miss`() = runTest {
        val artists = listOf(FeaturedArtist("a", "Artist"))

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                enrichFeaturedArtistImages(artists) { throw CancellationException("cancelled") }
            }
        }
    }
}
