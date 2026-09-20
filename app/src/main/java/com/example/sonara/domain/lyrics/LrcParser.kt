package com.example.sonara.domain.lyrics

import com.example.sonara.domain.model.LyricLine

/**
 * Pure Kotlin parser for LRC lyrics format.
 * Implements Audit 02 §3.2 and §5 specifications.
 * Supports multi-timestamp expansion, metadata header filtering, and chronological sorting.
 */
object LrcParser {

    private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]")
    private val HEADER_TAG_REGEX = Regex("^\\[(ti|ar|al|by|offset|length|re|ve):.*\\]$", RegexOption.IGNORE_CASE)

    /**
     * Parses raw LRC string into a sorted list of LyricLine objects.
     */
    fun parse(rawLrc: String?): List<LyricLine> {
        if (rawLrc.isNullOrBlank()) return emptyList()

        val parsedLines = mutableListOf<LyricLine>()

        rawLrc.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || HEADER_TAG_REGEX.matches(trimmed)) {
                return@forEach
            }

            // Find all timestamps in the line (e.g. [00:12.34][00:56.78]Chorus)
            val timestampMatches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (timestampMatches.isEmpty()) return@forEach

            // Extract the lyric text after all timestamp tags
            val text = trimmed.replace(TIMESTAMP_REGEX, "").trim()
            val sanitizedText = decodeHtmlEntities(text)

            for (match in timestampMatches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = match.groupValues.getOrNull(3) ?: ""
                val millis = when (fracStr.length) {
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10
                    3 -> fracStr.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val totalMs = (minutes * 60 + seconds) * 1000 + millis
                parsedLines.add(LyricLine(timestampMs = totalMs, text = sanitizedText))
            }
        }

        return parsedLines.sortedBy { it.timestampMs }
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
