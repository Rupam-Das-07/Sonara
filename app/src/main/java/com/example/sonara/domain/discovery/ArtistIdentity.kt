package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import java.text.Normalizer
import java.util.Locale

/** Conservative display-name matching. Public slugs are lossy and are NOT equality proof. */
object ArtistIdentity {
    /** Strict normalized name, with only the explicitly supported Rahman spelling alias. */
    fun nameKey(name: String?): String? = rawNameKey(name)?.let {
        when (it) {
            "a.r.-rahman", "a-r-rahman", "a.-r.-rahman" -> "ar-rahman"
            else -> it
        }
    }

    /** Keep alias buckets separate until each has independently passed its event threshold. */
    internal fun rawNameKey(name: String?): String? {
        if (name == null) return null
        var hasLetterOrNumber = false
        var offset = 0
        while (offset < name.length) {
            val cp = name.codePointAt(offset)
            val type = Character.getType(cp)
            if (cp in 0xD800..0xDFFF ||
                (type == Character.CONTROL.toInt() && !isWhitespace(cp)) ||
                (type == Character.FORMAT.toInt() && !isJoiner(cp))
            ) return null
            if (isLetterOrNumber(cp)) hasLetterOrNumber = true
            offset += Character.charCount(cp)
        }
        if (!hasLetterOrNumber) return null

        val normalized = nfc(nfc(name).lowercase(Locale.ROOT))
        val result = StringBuilder()
        var pendingSpace = false
        offset = 0
        while (offset < normalized.length) {
            val cp = normalized.codePointAt(offset)
            if (isWhitespace(cp)) {
                pendingSpace = result.isNotEmpty()
            } else {
                if (pendingSpace) result.append('-')
                result.appendCodePoint(cp)
                pendingSpace = false
            }
            offset += Character.charCount(cp)
        }
        return result.toString()
    }

    /** Legacy Latin slugs, retaining letters, marks and joining characters in other scripts. */
    fun slugify(name: String?): String {
        if (rawNameKey(name) == null) return ""
        val decomposed = Normalizer.normalize(name!!, Normalizer.Form.NFD)
        val stripped = StringBuilder()
        var latinBase = false
        var offset = 0
        while (offset < decomposed.length) {
            val cp = decomposed.codePointAt(offset)
            if (!isMark(cp)) {
                latinBase = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
            }
            if (!(latinBase && cp in 0x0300..0x036F)) stripped.appendCodePoint(cp)
            offset += Character.charCount(cp)
        }
        val normalized = nfc(nfc(stripped.toString()).lowercase(Locale.ROOT))
        val result = StringBuilder()
        var separator = false
        offset = 0
        while (offset < normalized.length) {
            val cp = normalized.codePointAt(offset)
            if (isLetterOrNumber(cp) || isMark(cp) || isJoiner(cp)) {
                if (separator && result.isNotEmpty()) result.append('-')
                result.appendCodePoint(cp)
                separator = false
            } else {
                separator = true
            }
            offset += Character.charCount(cp)
        }
        return result.toString()
    }

    /** A name alone cannot choose between distinct authoritative editorial identities. */
    internal fun ambiguousEditorialKeys(roster: List<FeaturedArtist>): Set<String> = roster
        .mapNotNull { artist -> nameKey(artist.name)?.let { it to artist.id } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.toSet().size > 1 }
        .keys

    private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    private fun isLetterOrNumber(cp: Int): Boolean = Character.isLetter(cp) ||
        when (Character.getType(cp)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun isJoiner(cp: Int): Boolean = cp == 0x200C || cp == 0x200D

    private fun isWhitespace(cp: Int): Boolean = cp in 0x0009..0x000D ||
        cp == 0x0020 || cp == 0x0085 || cp == 0x00A0 || cp == 0x1680 ||
        cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F ||
        cp == 0x205F || cp == 0x3000
}
