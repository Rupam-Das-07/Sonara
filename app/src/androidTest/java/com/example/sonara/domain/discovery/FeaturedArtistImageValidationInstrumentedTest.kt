package com.example.sonara.domain.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FA-007 — Deterministic Android runtime verification for Featured Artist portrait URLs
 * and Coil image-loading error containment on actual hardware.
 *
 * Verifies:
 * 1. [FeaturedArtistImage.trustedHttpsUrl] rejects untrusted/malformed inputs on Android 14 Bionic.
 * 2. Coil encapsulates rejected/null data in an [ErrorResult] without unhandled exceptions.
 */
@RunWith(AndroidJUnit4::class)
class FeaturedArtistImageValidationInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun androidRuntimeRejectionContract() {
        // --- Valid HTTPS Google CDN URLs ---
        val validGoogleUserContent = "https://lh3.googleusercontent.com/avatar=w540-h540-p-l90-rj"
        assertEquals(
            "Valid lh3.googleusercontent.com URL must be preserved",
            validGoogleUserContent,
            FeaturedArtistImage.trustedHttpsUrl(validGoogleUserContent)
        )

        val validGgpht = "https://yt3.ggpht.com/ytc/abc"
        assertEquals(
            "Valid yt3.ggpht.com URL must be preserved",
            validGgpht,
            FeaturedArtistImage.trustedHttpsUrl(validGgpht)
        )

        // --- Invalid cases ---

        // 1. Lookalike hosts
        assertNull(
            "Lookalike domain evilgoogleusercontent.com must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("https://evilgoogleusercontent.com/avatar")
        )
        assertNull(
            "Lookalike domain notggpht.com must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("https://notggpht.com/avatar")
        )

        // 2. HTTP scheme (cleartext)
        assertNull(
            "Cleartext HTTP URL must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("http://yt3.ggpht.com/avatar")
        )

        // 3. Embedded credentials
        assertNull(
            "URL with userInfo must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("https://user:pass@yt3.ggpht.com/avatar")
        )

        // 4. Explicit non-default port
        assertNull(
            "URL with custom port :8443 must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("https://yt3.ggpht.com:8443/avatar")
        )
        assertNull(
            "URL with explicit port :443 must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("https://yt3.ggpht.com:443/avatar")
        )

        // 5. Malformed URLs
        assertNull(
            "Malformed URL scheme-relative must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("://malformed-url")
        )
        assertNull(
            "Plain non-URL string must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("not a url")
        )

        // 6. Empty and blank input
        assertNull(
            "Empty string must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("")
        )
        assertNull(
            "Whitespace string must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("   ")
        )
        assertNull(
            "Tab and newline string must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("\t\r\n")
        )

        // 7. Literal 'null' in mixed case
        assertNull(
            "Literal lowercase 'null' must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("null")
        )
        assertNull(
            "Literal titlecase 'Null' must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("Null")
        )
        assertNull(
            "Literal uppercase 'NULL' must be rejected",
            FeaturedArtistImage.trustedHttpsUrl("NULL")
        )
        assertNull(
            "Padded mixed-case ' nuLL ' must be rejected",
            FeaturedArtistImage.trustedHttpsUrl(" nuLL ")
        )

        // 8. Null input
        assertNull(
            "Null input must return null",
            FeaturedArtistImage.trustedHttpsUrl(null)
        )
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    @Test
    fun coilErrorHandlingOnRejectedUrl() = runBlocking {
        // 1. Boundary contract: an untrusted or lookalike URL is rejected to null
        val lookalikeUrl = "https://evilgoogleusercontent.com/avatar.jpg"
        val sanitized = FeaturedArtistImage.trustedHttpsUrl(lookalikeUrl)
        assertNull("Untrusted URL must be mapped to null before Coil invocation", sanitized)

        // 2. Coil error containment: executing a request with null data does not throw,
        // but returns an ErrorResult with an explanatory throwable
        val loader = ImageLoader.Builder(context)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()

        try {
            val request = ImageRequest.Builder(context)
                .data(sanitized)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()

            val result = loader.execute(request)

            assertTrue(
                "Coil must return ErrorResult for null request data, got ${result::class.simpleName}",
                result is ErrorResult
            )
            val errorResult = result as ErrorResult
            assertNotNull("ErrorResult must contain a cause throwable", errorResult.throwable)
        } finally {
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            loader.shutdown()
        }
    }
}
