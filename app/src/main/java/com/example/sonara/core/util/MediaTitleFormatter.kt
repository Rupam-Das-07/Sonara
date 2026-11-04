package com.example.sonara.core.util

/**
 * Intelligent presentation-layer formatter for YouTube video and music titles.
 *
 * Provides generalized structural normalization across diverse YouTube upload conventions:
 * - Western artist-prefixed releases (`Artist - Song Title (Metadata)`)
 * - Global/Indian cinema & soundtrack releases (`Song Name - Suffix | Movie | Star | Studio | Composer`)
 * - Pipe-separated metadata tag collections (`Song Title | Film | Star | Music Director`)
 * - Post-hyphen promotional format suffixes (`Song Name - Lyric Video`, `Song - Official Audio`)
 * - Featured-artist credits (`(feat. Artist)`, `[ft. Artist]`, trailing `feat. Artist`)
 * - Movie, film, and soundtrack attributions (`[From F1® The Movie]`, `(From "Top Gun")`)
 * - Promotional & quality badges (`(Official Music Video)`, `(Lyric Video)`, `(4K 60fps)`)
 *
 * Strictly preserves meaningful musical version distinctions across all delimiters:
 * Live, Acoustic, Remix, Remastered, Instrumental, Slowed + Reverb, Nightcore, Extended Version,
 * Radio Edit, Unplugged, Session, Demo, VIP, Pt./Part, Vol./Volume, and Vault.
 *
 * Deterministic, lightweight, and purely in-memory.
 */
object MediaTitleFormatter {

    private val DURATION_PREFIX_REGEX = Regex(
        """^[0-9]+(?:\.[0-9]+)?\s*(?:hours?|hrs?|mins?|minutes?|sec|seconds?)\s*(?:of\s+)?[-:–—|]?\s*""",
        RegexOption.IGNORE_CASE
    )

    private val STREAM_PREFIX_REGEX = Regex(
        """^24/7\s*(?:live\s+stream\s*)?[-:–—|]?\s*""",
        RegexOption.IGNORE_CASE
    )

    // Preserved musical version keywords.
    // If any segment, parenthetical block, or bracketed block contains one of these, it is strictly preserved.
    private val PRESERVED_VERSION_REGEX = Regex(
        """\b(?:live(?:\s+at|\s+in|\s+from)?|acoustic|remaster(?:ed)?|remix(?:ed)?|instrumental|slowed|reverb|nightcore|extended(?:\s+version|\s+mix)?|radio\s+edit|unplugged|cover|orchestral?|symphon(?:ic|y)|version|session|demo|vip|pt\.?|part|vol\.?|volume|vault)\b""",
        RegexOption.IGNORE_CASE
    )

    // Generalized promotional / media-format suffixes occurring after hyphens or in pipe segments
    // (e.g. "Lyric Video", "Official Video", "Video Song", "Lyrical", "Full Video", "Audio", etc.)
    private val PROMOTIONAL_SUFFIX_PATTERNS = listOf(
        Regex("""^(?:official\s+)?(?:(?:animated|animation)\s+)?(?:music\s+)?(?:lyric\s+video|lyrics?\s+video|lyric|lyrics?|lyrical(?:\s+video)?|video\s+song|music\s+video|video\s+clip|clip\s+officiel|video\s+oficial|video|audio|visualizer|teaser|trailer|first\s+single|promo(?:\s+video)?|full\s+(?:video|song|audio|track))(?:\s*[-–—|:]?\s*(?:hd|hq|4k|\d+k|1080p|720p|uhd|60fps|audio|video))*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:hd|hq|4k|\d+k|1080p|720p|uhd|60fps)(?:\s+(?:video|audio|lyric\s+video|music\s+video|video\s+song))*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?video\s+(?:hd|4k|\d+k|1080p|60fps)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?(?:hd|4k|\d+k|1080p|60fps)\s+video$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?audio(?:\s+(?:hd|hq|version))*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:no[\s-]*ads|100%[\s-]*no[\s-]*ads|ad[\s-]*free|copyright[\s-]*free.*|royalty[\s-]*free.*)$""", RegexOption.IGNORE_CASE)
    )

    // Featured-artist patterns inside brackets/parentheses (e.g. "(feat. Doja Cat)", "[ft. Artist]")
    private val FEATURED_ARTIST_BLOCK_PATTERNS = listOf(
        Regex("""^(?:feat\.?|ft\.?|featuring|with|w/)\s+.+$""", RegexOption.IGNORE_CASE)
    )

    // Movie, film, series, and soundtrack attribution patterns inside brackets/parentheses
    private val MEDIA_ATTRIBUTION_BLOCK_PATTERNS = listOf(
        Regex("""^from\s+.*(?:movie|film|motion\s+picture|series|soundtrack|ost|album).*$""", RegexOption.IGNORE_CASE),
        Regex("""^from\s+["'“‘].+?["'”’](?:\s+.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^from\s+[\p{L}\p{N}\s®™\-_',.!:;&]+$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:music\s+)?from\s+(?:the\s+)?(?:original\s+|official\s+)?(?:motion\s+picture|soundtrack|film|movie|series|album|tv\s+series).*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:the\s+)?(?:original\s+|official\s+)?(?:motion\s+picture\s+)?(?:soundtrack|ost)(?:\s+.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^.*(?:\bost\b|soundtrack).*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?theme\s+song(?:\s+.*)?$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?title\s+track(?:\s+.*)?$""", RegexOption.IGNORE_CASE)
    )

    // Purely promotional parenthetical/bracketed labels that carry no musical or version meaning
    private val PROMOTIONAL_BLOCK_PATTERNS = listOf(
        Regex("""^(?:official\s+)?(?:(?:animated|animation)\s+)?(?:music\s+)?video(?:\s+(?:hd|4k|\d+k|60fps|version))*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?video\s+(?:hd|4k|\d+k|60fps)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?(?:hd|4k|\d+k|60fps)\s+video$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?audio(?:\s+(?:hd|hq|version))*$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?(?:lyrics?|lyric\s+video)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?visualizer$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?clip\s+officiel$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?video\s+oficial$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?video\s+clip$""", RegexOption.IGNORE_CASE),
        Regex("""^music\s+video$""", RegexOption.IGNORE_CASE),
        Regex("""^lyric\s+video$""", RegexOption.IGNORE_CASE),
        Regex("""^lyrics?$""", RegexOption.IGNORE_CASE),
        Regex("""^video$""", RegexOption.IGNORE_CASE),
        Regex("""^audio$""", RegexOption.IGNORE_CASE),
        Regex("""^visualizer$""", RegexOption.IGNORE_CASE),
        Regex("""^official$""", RegexOption.IGNORE_CASE),
        Regex("""^official\s+(?:hd|4k|\d+k|60fps)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:hd|hq|4k|4k\s*60fps|1080p|1080p\s*60fps|720p|uhd|60fps)$""", RegexOption.IGNORE_CASE),
        Regex("""^high[\s-]*quality$""", RegexOption.IGNORE_CASE),
        Regex("""^hq[\s-]*audio$""", RegexOption.IGNORE_CASE),
        Regex("""^best[\s-]*quality$""", RegexOption.IGNORE_CASE),
        Regex("""^no[\s-]*ads$""", RegexOption.IGNORE_CASE),
        Regex("""^100%[\s-]*no[\s-]*ads$""", RegexOption.IGNORE_CASE),
        Regex("""^ad[\s-]*free$""", RegexOption.IGNORE_CASE),
        Regex("""^w/\s*lyrics$""", RegexOption.IGNORE_CASE),
        Regex("""^with\s*lyrics$""", RegexOption.IGNORE_CASE),
        Regex("""^explicit$""", RegexOption.IGNORE_CASE),
        Regex("""^clean(?:\s+version)?$""", RegexOption.IGNORE_CASE),
        Regex("""^full\s+(?:song|video|audio|track)$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?(?:performance|dance|choreography)\s+video$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:official\s+)?(?:teaser|trailer)$""", RegexOption.IGNORE_CASE)
    )

    // Trailing unparenthesized featured artists (e.g. "Song Title feat. Artist", "Track ft. Artist")
    private val TRAILING_FEATURED_ARTIST_REGEX = Regex(
        """\s+(?:feat\.?|ft\.?|featuring)\s+[^\-_|()\[\]]+$""",
        RegexOption.IGNORE_CASE
    )

    private val SPACED_HYPHEN_SEPARATORS = listOf(" - ", " – ", " — ")

    private val NON_ARTIST_PREFIX_PATTERN = Regex(
        """^(?:episode|ep\.?|part|pt\.?|chapter|ch\.?|season|s\d+e\d+|track|#\d+|top\s+\d+|how\s+to|why\s+|what\s+|review\b)""",
        RegexOption.IGNORE_CASE
    )

    private val KNOWN_DISTRIBUTOR_OR_CHANNEL_PATTERN = Regex(
        """(?:\brecords\b|\bmusic\b|\bentertainment\b|\bvevo\b|\bmedia\b|\bproductions?\b|\bstudios?\b|\bpictures\b|\bofficial\b)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Formats a raw YouTube video title into a clean presentation title.
     *
     * @param title The raw video title from YouTube.
     * @param artist Optional artist or channel name associated with the track.
     * @return Normalized presentation title.
     */
    fun format(title: String, artist: String? = null): String {
        if (title.isBlank()) return title
        var current = title.trim()

        // 1. Remove duration or stream prefixes (e.g. "4 HOURS of...", "24/7...")
        current = stripDurationOrStreamPrefix(current)

        // 2. Decompose pipe-separated metadata structures (e.g. "One Name - Lyric Video | Jailer 2 | ...")
        current = stripPipeMetadata(current, artist)

        // 3. Disambiguate hyphen roles (Artist - Title vs Title - Promotional Suffix vs Title - Meaningful Version)
        current = disambiguateHyphens(current, artist)

        // 4. Remove unnecessary metadata labels in brackets / parentheses
        //    (promotional labels, movie attributions, featured artists)
        //    while strictly preserving musical version distinctions.
        current = stripMetadataBrackets(current)

        // 5. Remove trailing unparenthesized featured artist credits if any remain
        current = stripTrailingFeaturedArtist(current)

        // 6. Clean up whitespace and dangling punctuation artifacts
        current = cleanPunctuationAndWhitespace(current)

        // 7. Handle exceptionally long descriptive titles at word boundaries
        current = truncateAtWordBoundaryIfNeeded(current, maxChars = 90)

        return current.ifBlank { title.trim() }
    }

    private fun stripDurationOrStreamPrefix(title: String): String {
        val durationMatch = DURATION_PREFIX_REGEX.find(title)
        if (durationMatch != null && durationMatch.range.first == 0) {
            val remainder = title.substring(durationMatch.range.last + 1).trim()
            if (remainder.length >= 4) return remainder
        }
        val streamMatch = STREAM_PREFIX_REGEX.find(title)
        if (streamMatch != null && streamMatch.range.first == 0) {
            val remainder = title.substring(streamMatch.range.last + 1).trim()
            if (remainder.length >= 4) return remainder
        }
        return title
    }

    /**
     * Decomposes pipe-separated metadata structures.
     *
     * In YouTube music uploads (particularly soundtrack and global releases), the primary title
     * appears in the initial segment, while subsequent segments carry upload metadata (movie name,
     * cast credits, studio, director, composer) or format tokens.
     *
     * Meaningful musical version distinctions occurring in secondary pipe segments (e.g. "Live at Wembley")
     * are strictly preserved and attached to the candidate title.
     */
    private fun stripPipeMetadata(title: String, artist: String?): String {
        if (!title.contains("|")) return title
        val rawParts = title.split("|").map(String::trim).filter(String::isNotEmpty)
        if (rawParts.size <= 1) return title

        val cleanArtist = cleanArtistName(artist)

        // Case A: If parts[0] matches the channel/artist name and there are subsequent parts,
        // then parts[0] is the artist credit, and parts[1] is the candidate song title.
        val candidateTitle: String
        val secondaryParts: List<String>
        if (!cleanArtist.isNullOrBlank() && isConfidentArtistMatch(rawParts[0], cleanArtist) && rawParts.size > 1) {
            candidateTitle = rawParts[1]
            secondaryParts = rawParts.drop(2)
        } else {
            candidateTitle = rawParts[0]
            secondaryParts = rawParts.drop(1)
        }

        // Case B: Scan secondary parts for meaningful musical version distinctions to preserve
        // (e.g. "Live at Wembley", "Acoustic Version", "Remix")
        val preservedVersions = secondaryParts.filter { part ->
            isMeaningfulVersionBlock(part)
        }

        // Case C: If only two parts exist and the secondary part is neither promotional nor metadata nor version,
        // treat it conservatively as an uncertain two-part title and preserve the whole structure.
        if (rawParts.size == 2 && preservedVersions.isEmpty()) {
            val secondary = secondaryParts.first()
            val isKnownMetadata = isPromotionalSuffix(secondary) ||
                isRemovableMetadataBlock(secondary) ||
                (!cleanArtist.isNullOrBlank() && isConfidentArtistMatch(secondary, cleanArtist))

            if (!isKnownMetadata) {
                return title
            }
        }

        var result = candidateTitle
        if (preservedVersions.isNotEmpty()) {
            val versionAnnotation = preservedVersions.joinToString(", ")
            result = if (result.endsWith(")")) {
                "$result ($versionAnnotation)"
            } else {
                "$result ($versionAnnotation)"
            }
        }

        return result
    }

    /**
     * Disambiguates hyphen roles:
     * 1. Trailing promotional suffix: "Song Name - Lyric Video" -> "Song Name"
     * 2. Artist prefix: "Artist Name - Song Name" -> "Song Name"
     * 3. Meaningful version suffix: "Song Name - Live Acoustic Version" -> preserved
     * 4. Uncertain hyphen: preserved as-is
     */
    private fun disambiguateHyphens(title: String, artist: String?): String {
        var current = title
        val cleanArtist = cleanArtistName(artist)

        // Step 1: Detect and remove post-hyphen promotional suffixes (e.g. "- Lyric Video", "- Official Audio")
        current = stripPostHyphenPromotionalSuffix(current)

        // Step 2: Detect and remove redundant artist prefix (e.g. "Artist - Song Name")
        current = stripRedundantArtistPrefix(current, cleanArtist)

        return current
    }

    /**
     * Strips post-hyphen promotional format suffixes from the end of a title.
     * e.g. "One Name - Lyric Video" -> "One Name"
     *      "Song Name - Official Audio" -> "Song Name"
     *
     * Strictly preserves meaningful musical version suffixes (e.g. "- Live Acoustic Version").
     */
    private fun stripPostHyphenPromotionalSuffix(title: String): String {
        for (sep in SPACED_HYPHEN_SEPARATORS) {
            val lastIdx = title.lastIndexOf(sep)
            if (lastIdx <= 0) continue

            val left = title.substring(0, lastIdx).trim()
            val right = title.substring(lastIdx + sep.length).trim()

            if (left.length < 2) continue

            // If right-hand segment is a recognized promotional suffix, retain left
            if (isPromotionalSuffix(right)) {
                return left
            }
        }
        return title
    }

    /**
     * Checks whether text matches a recognized promotional or media-format suffix.
     * Returns false if the text contains any meaningful musical version keyword.
     */
    private fun isPromotionalSuffix(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // Meaningful musical versions must NEVER be classified as promotional suffixes
        if (isMeaningfulVersionBlock(trimmed)) return false
        return PROMOTIONAL_SUFFIX_PATTERNS.any { it.matches(trimmed) }
    }

    /**
     * Strips redundant artist prefix if the left side of a hyphen confidently represents the artist.
     */
    private fun stripRedundantArtistPrefix(title: String, cleanArtist: String?): String {
        for (sep in SPACED_HYPHEN_SEPARATORS) {
            val idx = title.indexOf(sep)
            if (idx <= 0) continue

            val candidatePrefix = title.substring(0, idx).trim()
            val remainder = title.substring(idx + sep.length).trim()
            if (remainder.length < 2) continue

            // Meaningful musical version on right should be preserved with song title
            if (isMeaningfulVersionBlock(remainder) && cleanArtist.isNullOrBlank()) {
                continue
            }

            // 1. Confident artist match against provided channel/artist name
            if (!cleanArtist.isNullOrBlank() && isConfidentArtistMatch(candidatePrefix, cleanArtist)) {
                val unquoted = stripOuterQuotes(remainder)
                if (unquoted.length >= 2) return unquoted
            }

            // 2. Known distributor, label, or missing artist with plausible music artist prefix
            val isChannelOrNull = cleanArtist.isNullOrBlank() ||
                KNOWN_DISTRIBUTOR_OR_CHANNEL_PATTERN.containsMatchIn(cleanArtist)

            if (isChannelOrNull && isPlausibleMusicArtistPrefix(candidatePrefix, title)) {
                val unquoted = stripOuterQuotes(remainder)
                if (unquoted.length >= 2) return unquoted
            }
        }
        return title
    }

    private fun cleanArtistName(artist: String?): String? =
        artist?.trim()
            ?.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s*VEVO$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s*Official(?:\s*(?:Channel|Music|Video|Audio))?$""", RegexOption.IGNORE_CASE), "")
            ?.trim()

    private fun isPlausibleMusicArtistPrefix(candidatePrefix: String, fullTitle: String): Boolean {
        if (candidatePrefix.length !in 2..50) return false
        if (!candidatePrefix.any { it.isLetter() }) return false
        if (NON_ARTIST_PREFIX_PATTERN.containsMatchIn(candidatePrefix)) return false
        if (candidatePrefix.contains("|") || candidatePrefix.contains(":") || candidatePrefix.contains("~")) return false
        return fullTitle.contains("(") || fullTitle.contains("[") || candidatePrefix.contains(",") || candidatePrefix.contains("&")
    }

    private fun isConfidentArtistMatch(prefix: String, artist: String): Boolean {
        if (prefix.equals(artist, ignoreCase = true)) return true
        val normPrefix = normalizeForComparison(prefix)
        val normArtist = normalizeForComparison(artist)
        if (normPrefix.isEmpty() || normArtist.isEmpty()) return false
        if (normPrefix == normArtist) return true

        // Allow prefix containing or starting with artist (e.g. "Artist A, Artist B" starting with "Artist A")
        if (normPrefix.startsWith(normArtist) || normArtist.startsWith(normPrefix)) {
            val diff = Math.abs(normPrefix.length - normArtist.length)
            if (diff <= 4) return true
            if (prefix.contains(",") || prefix.contains("&") ||
                prefix.contains(" feat", ignoreCase = true) || prefix.contains(" ft", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    private fun normalizeForComparison(str: String): String =
        str.lowercase()
            .replace(Regex("""\s*-\s*topic$"""), "")
            .replace(Regex("""\s*vevo$"""), "")
            .replace(Regex("""\s*official$"""), "")
            .replace(Regex("""[^a-z0-9]"""), "")

    private fun stripOuterQuotes(str: String): String {
        val s = str.trim()
        val quotePairs = listOf(
            '\'' to '\'',
            '"' to '"',
            '“' to '”',
            '‘' to '’'
        )
        for ((start, end) in quotePairs) {
            if (s.length >= 2 && s.startsWith(start) && s.endsWith(end)) {
                return s.substring(1, s.length - 1).trim()
            }
        }
        return s
    }

    private fun stripMetadataBrackets(title: String): String {
        val blockRegex = Regex("""(\([^()\[\]]*\)|\[[^()\[\]]*\]|\{[^{}]*\})""")
        var current = title
        var iterations = 0
        while (iterations < 3) {
            val previous = current
            current = blockRegex.replace(current) { matchResult ->
                val block = matchResult.value
                val inner = block.substring(1, block.length - 1).trim()

                when {
                    // Tier 1: Strictly preserve meaningful version distinctions
                    isMeaningfulVersionBlock(inner) -> block
                    // Tier 2: Remove confidently identified metadata (feat, promo, movie/soundtrack)
                    isRemovableMetadataBlock(inner) -> ""
                    // Tier 3: Preserve uncertain text rather than deleting potentially meaningful info
                    else -> block
                }
            }
            if (current == previous) break
            iterations++
        }
        return current
    }

    private fun isMeaningfulVersionBlock(inner: String): Boolean {
        if (inner.isBlank()) return false
        // Exclude promotional pseudo-versions like "Clean Version" or "Video Version"
        if (inner.matches(Regex("""^(?:clean|explicit|video|audio|official)\s+version$""", RegexOption.IGNORE_CASE))) {
            return false
        }
        return PRESERVED_VERSION_REGEX.containsMatchIn(inner)
    }

    private fun isRemovableMetadataBlock(inner: String): Boolean {
        if (inner.isBlank()) return true
        val trimmed = inner.trim()
        return FEATURED_ARTIST_BLOCK_PATTERNS.any { it.matches(trimmed) } ||
            MEDIA_ATTRIBUTION_BLOCK_PATTERNS.any { it.matches(trimmed) } ||
            PROMOTIONAL_BLOCK_PATTERNS.any { it.matches(trimmed) }
    }

    private fun stripTrailingFeaturedArtist(title: String): String {
        val match = TRAILING_FEATURED_ARTIST_REGEX.find(title) ?: return title
        val before = title.substring(0, match.range.first).trim()
        return if (before.length >= 2) before else title
    }

    private fun cleanPunctuationAndWhitespace(title: String): String {
        var s = title
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Strip dangling leading/trailing punctuation
        s = s.trimEnd('-', '–', '—', ':', '|', '~', ',', ';', '/', ' ')
        s = s.trimStart('-', '–', '—', ':', '|', '~', ',', ';', '/', ' ')
        return stripOuterQuotes(s)
    }

    private fun truncateAtWordBoundaryIfNeeded(title: String, maxChars: Int): String {
        if (title.length <= maxChars) return title

        // Look for the last space/punctuation between (maxChars - 25) and maxChars
        val searchStart = (maxChars - 25).coerceAtLeast(10)
        val slice = title.substring(searchStart, maxChars)
        val relativeBreak = slice.lastIndexOfAny(charArrayOf(' ', ',', '-', '–', '—', '|', ';'))

        val breakIndex = if (relativeBreak >= 0) {
            searchStart + relativeBreak
        } else {
            maxChars
        }

        return title.substring(0, breakIndex).trimEnd(' ', ',', '-', '–', '—', '|', ';') + "…"
    }
}
