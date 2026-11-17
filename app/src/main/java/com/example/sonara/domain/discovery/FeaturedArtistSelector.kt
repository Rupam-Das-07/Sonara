package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.HistoryItem

/**
 * Builds the Featured Artists set from BOTH the backend editorial roster AND the
 * artists the user actually listens to.
 *
 * THE BUG THIS FIXES
 * ------------------
 * [ArtistAffinityCalculator.personalize] can only reorder the artists the backend
 * returned. Personal listening therefore affected ORDER but never MEMBERSHIP, which
 * produced two user-visible failures:
 *
 *  1. The most-streamed artist on the device could never appear if the editorial /
 *     chart roster did not happen to contain them (the "Hans Zimmer" case).
 *  2. Artists with zero qualifying local history occupied the shelf purely because
 *     the chart roster returned them.
 *
 * This selector adds the missing step — candidate EXPANSION — while deliberately
 * preserving the editorial/discovery character of the shelf:
 *
 *     editorial candidates (backend)
 *              +  qualifying personal candidates (local history)
 *              -> dedupe
 *              -> affinity ranking
 *              -> bounded, balanced top [TARGET_SIZE]
 *
 * WHAT IS DELIBERATELY NOT CHANGED
 * --------------------------------
 *  - [ArtistAffinityCalculator]'s window, exponential recency decay,
 *    completed-session weighting and [ArtistAffinityCalculator.MIN_QUALIFYING_EVENTS]
 *    ordering threshold remain the single source of affinity truth, and its
 *    re-rank-only primitive is reused verbatim whenever no personal candidate
 *    qualifies.
 *  - Nothing is persisted and nothing leaves the device. Personal candidates are
 *    built entirely from local rows; no history, play count or affinity score is ever
 *    sent anywhere, and this selector performs NO network work at all.
 *  - The UI contract is unchanged: the result is still a `List<FeaturedArtist>`.
 *
 * Like the calculator, this is a pure deterministic object — `nowMs` is always
 * supplied by the caller — so every rule below is directly unit-testable.
 */
object FeaturedArtistSelector {

    /**
     * Size of the shelf. Matches the backend roster target and the Home row's own
     * `take(8)`, so the visible result is bounded exactly as it is today.
     */
    const val TARGET_SIZE: Int = 8

    /**
     * Maximum slots personal artists may occupy. Personal candidates can now expand
     * to occupy the entire shelf (up to [TARGET_SIZE]) when sufficient qualifying
     * personal listening exists.
     */
    const val MAX_PERSONAL_SLOTS: Int = TARGET_SIZE

    /**
     * Qualifying in-window listening sessions an artist needs before they may ENTER
     * the shelf on personal grounds alone.
     *
     * Deliberately stricter than [ArtistAffinityCalculator.MIN_QUALIFYING_EVENTS]
     * (which governs ORDERING): granting membership is a stronger action than nudging
     * position, so it demands a stronger signal. Every `playback_history` row already
     * represents a session that passed the 15-second qualification bar, so this means
     * three genuine listens inside the window — a brief or accidental play can never
     * promote an artist onto the shelf.
     */
    const val MIN_PERSONAL_CANDIDATE_EVENTS: Int = 3

    /**
     * Credit strings that name more than one artist ("A, B", "A & B", "A feat. B").
     *
     * Such a string is NOT a single artist: inserting it would render a tile labelled
     * with a combined credit and would break the name-based artist drill-through. They
     * are therefore excluded from MEMBERSHIP. They still contribute to affinity exactly
     * as they do today, so ordering behaviour is unchanged.
     *
     * Precision notes, each driven by a real name that the naive form misclassified:
     *  - "and" is listed alongside "&" so that "Hall & Oates" and "Hall and Oates" are
     *    treated identically; without it, a credit like "Sonu Nigam and Alka Yagnik"
     *    would have been inserted as a single combined tile.
     *  - "/" only separates when whitespace touches it (" / "), so "AC/DC" stays a
     *    single artist.
     *  - "x" and "vs" only separate when surrounded by whitespace (" x ", " vs "), so
     *    "X Ambassadors", "Malcolm X" and "Charli XCX" stay single artists while
     *    "Martin Garrix x Bebe Rexha" is still excluded.
     *
     * It otherwise fails safe: a genuinely single act whose name contains a true
     * separator ("Simon & Garfunkel", "Mumford and Sons", "Sleeping With Sirens") is
     * simply never auto-featured, rather than being featured incorrectly. That is the
     * deliberate trade — a silent omission beats a wrong, unnavigable tile.
     */
    private val MULTI_ARTIST_CREDIT = Regex(
        """[,;&]|\s/|/\s|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\band\b|\bwith\b|\sx\s|\svs\.?\s""",
        RegexOption.IGNORE_CASE
    )

    /** True when a history credit string names exactly one artist. */
    private fun isSingleArtistCredit(artist: String): Boolean =
        artist.isNotBlank() && !MULTI_ARTIST_CREDIT.containsMatchIn(artist)

    /**
     * One artist derived from local history that is strong enough to be considered for
     * membership.
     *
     * @property nameKey     Strict name key; raw alias buckets qualify independently.
     * @property displayName The spelling from the most recent qualifying session.
     * @property events      Qualifying in-window sessions observed.
     */
    private data class PersonalCandidate(
        val nameKey: String,
        val displayName: String,
        val events: Int
    )

    /**
     * Scans history for artists that clear [MIN_PERSONAL_CANDIDATE_EVENTS] ELIGIBLE sessions
     * inside [ArtistAffinityCalculator.HISTORY_WINDOW_DAYS].
     *
     * The provenance policy, window and strict name rules are all shared with
     * [ArtistAffinityCalculator] rather than redefined, so the two stay in lockstep. Results
     * are returned in first-seen order; because `getRecentHistory` is ordered newest-first,
     * that is recency order, which makes the downstream tie-breaking deterministic.
     */
    private fun collectPersonalCandidates(
        history: List<HistoryItem>,
        nowMs: Long
    ): List<PersonalCandidate> {
        val counts = LinkedHashMap<String, Int>()
        val names = HashMap<String, String>()

        for (item in history) {
            // Phase 2D: membership obeys exactly the same provenance policy as ordering, from
            // the one shared predicate, so an artist can never be admitted on listening that
            // would not have been allowed to re-rank them.
            if (!ArtistAffinityCalculator.isEligibleForPersonalization(item)) continue

            // Identical timestamp policy to the calculator, from the one shared helper, so a row
            // can never be admitted on a timestamp that would not have been allowed to rank it.
            // Only eligibility matters here — membership counts sessions, it does not weight them.
            if (ArtistAffinityCalculator.scoringAgeMsOrNull(nowMs, item.listenedAt) == null) continue

            val artist = item.track.artist
            if (!isSingleArtistCredit(artist)) continue

            val nameKey = ArtistIdentity.rawNameKey(artist) ?: continue

            counts[nameKey] = (counts[nameKey] ?: 0) + 1
            // First write wins = most recent spelling, since history is newest-first.
            if (!names.containsKey(nameKey)) names[nameKey] = artist.trim()
        }

        return counts.entries
            .filter { it.value >= MIN_PERSONAL_CANDIDATE_EVENTS }
            .map { PersonalCandidate(it.key, names[it.key] ?: it.key, it.value) }
    }

    /**
     * Returns the Featured Artists shelf for this device.
     *
     * Behaviour, stated as rules:
     *
     *  1. **Cold start / no qualifying affinity** — hide the shelf until the user has
     *     accumulated qualifying personal listening. Once eligible, personal artists
     *     lead the shelf.
     *  2. **Personal Primacy & Full Expansion** — qualifying personal artists (clearing
     *     [MIN_PERSONAL_CANDIDATE_EVENTS]) can occupy up to all [TARGET_SIZE] (8) positions.
     *     Editorial presets must never displace or dilute qualifying personal artists.
     *  3. **Editorial Backfill** — editorial candidates fill only positions that cannot
     *     be filled by qualifying personal candidates (e.g. 8 - K slots). If 8 or more
     *     qualifying personal artists exist, zero editorial backfill is added.
     *  4. **Dedupe** — an artist present in both personal listening and editorial roster
     *     appears ONCE, preserves richer editorial metadata (portrait, genre, browseId),
     *     and retains their earned personal affinity ranking.
     *  5. **Ranking** — personal candidates are ordered by local affinity, descending.
     *     Editorial backfill follows them, preserving editorial order (with any partial-history
     *     editorial artists prioritized).
     *
     * @param editorial backend roster; authoritative for metadata, backfill only.
     * @param history   local playback history, newest first.
     * @param nowMs     caller-supplied "now" so the result is deterministic.
     */
    fun select(
        editorial: List<FeaturedArtist>,
        history: List<HistoryItem>,
        nowMs: Long
    ): List<FeaturedArtist> {
        if (history.isEmpty()) return emptyList()

        val affinity = ArtistAffinityCalculator.calculateAffinity(history, nowMs)
        if (affinity.isEmpty()) return emptyList()

        val editorialKeys = editorial.mapNotNull { ArtistIdentity.nameKey(it.name) }.toSet()
        val ambiguousKeys = ArtistIdentity.ambiguousEditorialKeys(editorial)

        val candidates = collectPersonalCandidates(history, nowMs)
            .filter { ArtistIdentity.nameKey(it.nameKey) !in editorialKeys }
            // Rank by the same affinity the shelf is ordered by. Candidates arrive in
            // recency order and the sort is stable, so equal scores break deterministically.
            .sortedByDescending { ArtistAffinityCalculator.lookupAffinity(affinity, it.nameKey) ?: 0f }
            // Collapse two spellings of ONE artist ("AR Rahman" + "A.R. Rahman") into a
            // single candidate. Raw alias buckets qualify independently, so without this the
            // same person could occupy two tiles — the exact duplicate the editorial
            // dedupe above prevents, but between personal candidates. distinctBy keeps
            // the first, which after the sort is the strongest.
            .distinctBy { ArtistIdentity.nameKey(it.nameKey) }
            .take(TARGET_SIZE)

        // Editorial IDs are opaque and immutable. Disambiguate only generated local IDs,
        // including collisions between two different names with the same lossy slug.
        val slugs = candidates.map { ArtistIdentity.slugify(it.displayName) }
        val slugCounts = slugs.groupingBy { it }.eachCount()
        val editorialIds = editorial.map { it.id }.toSet()
        val reservedIds = (editorialIds + slugs).toMutableSet()
        val personalOnly = candidates.mapIndexed { index, candidate ->
            val slug = slugs[index]
            val id = if (slug in editorialIds || slugCounts.getValue(slug) > 1) {
                val key = requireNotNull(ArtistIdentity.nameKey(candidate.nameKey))
                val hex = key.toByteArray(Charsets.UTF_8).joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                }
                var uniqueId = "artist-name-$hex"
                while (uniqueId in reservedIds) uniqueId += "-local"
                reservedIds.add(uniqueId)
                uniqueId
            } else slug
            FeaturedArtist(
                id = id,
                name = candidate.displayName,
                genre = "",          // honestly unknown locally; the chart path also emits ""
                imageUrl = null,     // null => the tile renders a monogram (a designed state)
                browseId = null      // drill-through resolves by name, which the backend supports
            )
        }

        // Nothing to add: fall through to the existing re-rank so the no-expansion path stays consistent.
        if (personalOnly.isEmpty()) {
            return ArtistAffinityCalculator.personalize(editorial, history, nowMs)
        }

        // If no editorial candidates exist (backend empty/offline), return all qualifying personal candidates.
        if (editorial.isEmpty()) {
            return personalOnly.take(TARGET_SIZE)
        }

        // Prioritize personal candidates: they take up to personalOnly.size slots.
        // Editorial candidates only fill positions that cannot be filled by qualifying personal candidates (TARGET_SIZE - personalOnly.size).
        val backfillSlotsNeeded = TARGET_SIZE - personalOnly.size
        val editorialBackfill = if (backfillSlotsNeeded > 0) {
            val personalKeys = personalOnly.mapNotNull { ArtistIdentity.nameKey(it.name) }.toSet()
            editorial
                .filter { ArtistIdentity.nameKey(it.name) !in personalKeys }
                .sortedByDescending {
                    if (ArtistIdentity.nameKey(it.name) in ambiguousKeys) 0f
                    else ArtistAffinityCalculator.lookupAffinity(affinity, it.name) ?: 0f
                }
                .take(backfillSlotsNeeded)
        } else {
            emptyList()
        }

        return (editorialBackfill + personalOnly)
            .sortedByDescending {
                if (ArtistIdentity.nameKey(it.name) in ambiguousKeys) 0f
                else ArtistAffinityCalculator.lookupAffinity(affinity, it.name) ?: 0f
            }
            .take(TARGET_SIZE)
    }
}
