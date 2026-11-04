package com.example.sonara.data.remote.mapper

/**
 * Normalizes remote artwork URLs, upgrading resolution ONLY where the provider's URL
 * contract makes it a server-side resize of the same asset.
 *
 * Identity and availability outrank resolution (Phase 2C): a transform may never point at a
 * different asset, and may never turn a working URL into one that does not exist. The rules
 * below are therefore limited to CDNs whose size token is a rendering parameter applied to
 * one stored image.
 *
 * `i.ytimg.com` is deliberately excluded — see the comment at the end of [upgrade].
 */
object ArtworkUrlUpgrader {

    fun upgrade(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null

        var url = rawUrl.trim()

        // 1. Google / YouTube Music CDN (lh3.googleusercontent.com) — `=w<W>-h<H>` is a
        //    server-side resize of the same image.
        if (url.contains("googleusercontent.com")) {
            url = url.replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544-l90-rj")
            // Only a URL carrying NO parameter block can be given one. Google's CDN accepts a
            // single `=` block, so appending to an existing one (e.g. an `=s120-c` avatar form)
            // produced `=s120-c=w544-h544-l90-rj`, which does not resolve.
            if (!url.contains("=")) {
                url = "$url=w544-h544-l90-rj"
            }
            return url
        }

        // 2. JioSaavn CDN (c.saavncdn.com) — `<N>x<N>` variants are the same cover art.
        if (url.contains("saavncdn.com")) {
            return url.replace(Regex("\\b\\d+x\\d+\\b"), "500x500")
        }

        // 3. Apple Music / iTunes CDN (mzstatic.com) — `<N>x<N>bb` is the same cover art.
        if (url.contains("mzstatic.com")) {
            return url.replace(Regex("/\\d+x\\d+bb\\.jpg$"), "/600x600bb.jpg")
                .replace(Regex("/\\d+x\\d+bb\\.png$"), "/600x600bb.png")
        }

        // 4. i.ytimg.com is returned untouched, by design.
        //    `maxresdefault.jpg` is a SEPARATE stored file that exists only when the source
        //    video was published at a high enough resolution — it is not a resize of
        //    `hqdefault.jpg`. Rewriting one to the other is a guess at a different asset, and
        //    when it is absent the request 404s and the surface falls back to its glyph, losing
        //    artwork that was previously rendering.
        //    The previous rewrite also CORRUPTED already-maxres URLs: the pattern allowed the
        //    bare alternative `default`, and `maxresdefault.jpg` ends with `default.jpg`, so
        //    `.../maxresdefault.jpg` became `.../maxresmaxresdefault.jpg`. The backend emits
        //    exactly that URL for YouTube video results, so their artwork could never load.
        return url
    }
}
