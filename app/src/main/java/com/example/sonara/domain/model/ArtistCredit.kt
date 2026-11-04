package com.example.sonara.domain.model

/**
 * One credited artist on a track, as supplied by the backend's structured
 * `artists` array (`GET /api/v1/search` → TrackDTO.artists).
 *
 * WHY THIS EXISTS
 * ---------------
 * Featured Artists personalization must attribute a listen to a VERIFIED artist
 * identity. The flat [Track.artist] display string cannot provide that: a credit like
 * "Hans Zimmer & Benjamin Wallfisch" is indistinguishable, by string inspection, from
 * a single act whose name happens to contain a separator ("AC/DC",
 * "Simon & Garfunkel", "Sleeping With Sirens"). Splitting it would either invent
 * artists or silently drop real ones, so affinity attribution reads these structured
 * credits instead and never parses the display string.
 *
 * @property name Display name exactly as credited upstream, trimmed. Never blank —
 *                entries without a usable name are dropped at the parse boundary.
 * @property id   Upstream channel/browse id when supplied, otherwise "". An empty id
 *                means "name-only credit": still a real credited artist, but without
 *                an authoritative identifier, so callers that require a verified id
 *                must check for it explicitly.
 */
data class ArtistCredit(
    val name: String,
    val id: String = ""
)

/**
 * Round-trip codec that stores a credit list in ONE Room column.
 *
 * The database declares no `@TypeConverters` anywhere, so rather than introduce that
 * machinery for a single field this keeps the encoding as a pure, directly testable
 * function pair. The format is a flat record list using the ASCII separator control
 * characters, which cannot occur in real artist metadata:
 *
 *     name US id RS name US id        (US = U+001F, RS = U+001E)
 *
 * Both are stripped from inputs on encode, so a hostile or corrupt value can never
 * forge extra records. Decoding is total: any unparseable fragment is skipped rather
 * than throwing, because a malformed cached row must not break playback history.
 */
object ArtistCreditCodec {

    private const val UNIT = '\u001F'
    private const val RECORD = '\u001E'

    /** Encodes to a single column value. An empty list encodes to "". */
    fun encode(credits: List<ArtistCredit>): String = credits
        .asSequence()
        .map { ArtistCredit(sanitize(it.name), sanitize(it.id)) }
        .filter { it.name.isNotEmpty() }
        .joinToString(RECORD.toString()) { "${it.name}$UNIT${it.id}" }

    /** Decodes a column value. Blank, malformed or empty input yields an empty list. */
    fun decode(raw: String?): List<ArtistCredit> {
        if (raw.isNullOrEmpty()) return emptyList()
        val credits = mutableListOf<ArtistCredit>()
        for (record in raw.split(RECORD)) {
            if (record.isEmpty()) continue
            val parts = record.split(UNIT)
            val name = parts.getOrNull(0)?.trim().orEmpty()
            if (name.isEmpty()) continue
            val id = parts.getOrNull(1)?.trim().orEmpty()
            credits.add(ArtistCredit(name = name, id = id))
        }
        return credits
    }

    private fun sanitize(value: String): String =
        value.replace(UNIT.toString(), "").replace(RECORD.toString(), "").trim()
}
