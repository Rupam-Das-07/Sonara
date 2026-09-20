package com.example.sonara.data.remote.backend

import com.example.sonara.BuildConfig

/**
 * Authoritative configuration for the independent Sonara backend.
 *
 * BASE_URL points to the standalone sonara-backend Node.js server (port 3002)
 * in development, or the production HTTPS reverse proxy in release.
 * Injected at build time via BuildConfig.BASE_URL.
 *
 * Development topology:
 *   Android Emulator → 10.0.2.2:3002 (host machine loopback alias)
 *   Physical device  → host machine's LAN IP (e.g. http://192.168.x.x:3002)
 *
 * Production topology:
 *   Android Client   → https://<production-endpoint> (Nginx reverse proxy with TLS)
 *
 * Required services (must be running for Android to work):
 *   sonara-backend     :3002   (Node.js — this is what Android connects to)
 *   Python YTMusic     :5000   (shared infrastructure — internal only)
 *   Python yt-dlp      :5001   (shared infrastructure — internal only)
 *
 * Android must NEVER connect directly to :5000 or :5001.
 *
 * NOTE: No server secrets (API keys, auth tokens) belong here or anywhere in Android.
 */
object SonaraBackendConfig {

    /**
     * Base URL of the independent Sonara backend.
     * Injected per build variant via BuildConfig.BASE_URL.
     */
    val BASE_URL: String = BuildConfig.BASE_URL

    /** Connect timeout in milliseconds. Generous for yt-dlp cold extraction. */
    const val CONNECT_TIMEOUT_MS = 8_000

    /** Read timeout in milliseconds. Stream extraction can take several seconds. */
    const val READ_TIMEOUT_MS = 30_000

    /** Search timeout in milliseconds. */
    const val SEARCH_TIMEOUT_MS = 12_000

    /**
     * Discovery timeout in milliseconds — Home modules (featured artists,
     * playlist catalog, related recommendations). Generous enough for cold
     * artist-image resolution and YTMusic related lookups, but shorter than the
     * stream read timeout. Heavier discovery calls (playlist detail, quick picks)
     * use READ_TIMEOUT_MS because they may run multiple upstream searches.
     */
    const val DISCOVERY_TIMEOUT_MS = 20_000
}

