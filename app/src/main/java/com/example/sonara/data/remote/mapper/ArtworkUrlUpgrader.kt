package com.example.sonara.data.remote.mapper

/**
 * Normalizes and upgrades remote artwork URLs to high-resolution square assets.
 * Implements Audit 03 §8.1 high-res rewriting rules.
 */
object ArtworkUrlUpgrader {

    fun upgrade(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null

        var url = rawUrl.trim()

        // 1. Google / YouTube Music CDN (lh3.googleusercontent.com)
        if (url.contains("googleusercontent.com")) {
            url = url.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj")
            if (!url.contains("=w544-h544")) {
                url = "$url=w544-h544-l90-rj"
            }
            return url
        }

        // 2. JioSaavn CDN (c.saavncdn.com)
        if (url.contains("saavncdn.com")) {
            return url.replace(Regex("\\b\\d+x\\d+\\b"), "500x500")
        }

        // 3. Apple Music / iTunes CDN (mzstatic.com)
        if (url.contains("mzstatic.com")) {
            return url.replace(Regex("/\\d+x\\d+bb\\.jpg$"), "/600x600bb.jpg")
                .replace(Regex("/\\d+x\\d+bb\\.png$"), "/600x600bb.png")
        }

        // 4. YouTube CDN (i.ytimg.com)
        if (url.contains("i.ytimg.com")) {
            return url.replace(Regex("(hqdefault|mqdefault|sddefault|default)\\.jpg$"), "maxresdefault.jpg")
        }

        return url
    }
}
