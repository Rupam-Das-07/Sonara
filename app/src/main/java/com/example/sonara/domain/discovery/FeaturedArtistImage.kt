package com.example.sonara.domain.discovery

import java.net.URI

/**
 * Validation for Featured Artist portrait URLs returned by backend image endpoints.
 *
 * That endpoint is backed by Google's artist imagery, so only HTTPS URLs whose host
 * is a Google image CDN itself or a real subdomain are accepted. Explicit ports are
 * rejected so the Android roster boundary matches the backend policy. The explicit
 * host boundary avoids accepting lookalikes such as `evilgoogleusercontent.com`.
 */
object FeaturedArtistImage {
    private val TRUSTED_GOOGLE_IMAGE_ROOTS = setOf("googleusercontent.com", "ggpht.com")

    fun trustedHttpsUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            ?: return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val trustedHost = TRUSTED_GOOGLE_IMAGE_ROOTS.any { root ->
            host == root || host.endsWith(".$root")
        }
        if (!trustedHost || !uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.port != -1) {
            return null
        }
        return value
    }
}
