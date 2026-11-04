package com.example.sonara.domain.discovery

import com.example.sonara.domain.model.FeaturedArtist
import com.example.sonara.domain.model.HistoryItem
import com.example.sonara.domain.model.PlaybackOrigin
import kotlin.math.exp

/**
 * Local, on-device artist affinity scoring used to RE-RANK — never to re-populate —
 * the backend's editorial/chart-backed Featured Artists roster.
 *
 * Design contract:
 *  - The backend roster stays authoritative for WHICH artists are candidates and for all
 *    artist metadata (name, genre, artwork, browseId). This class only changes ORDER.
 *  - An artist the backend did not return is NEVER inserted, no matter how heavily the
 *    user listens to them.
 *  - Nothing is persisted. Affinity is derived from the existing `playback_history`
 *    rows on demand, and no listening data leaves the device.
 *
 * It is deliberately pure and deterministic (no Compose, no networking, no Media3, no
 * Room/backend types, no wall-clock reads — `nowMs` is always supplied by the caller) so
 * that it is cheap to unit-test and easy to re-tune later.
 *
 * The tunables below are the agreed INITIAL specification, not immutable architecture.
 */
object ArtistAffinityCalculator {

    /** Only listening events newer than this participate in scoring. */
    const val HISTORY_WINDOW_DAYS: Long = 30L

    /**
     * Exponential recency decay constant, in days: `weight = exp(-daysSincePlay / 14)`.
     *
     * NOTE: this is the decay *constant* from the specification's formula, which gives a
     * half-life of `14 * ln 2` ≈ 9.7 days (a literal 14-day half-life would instead be
     * `exp(-days * ln2 / 14)`). The specified formula is implemented verbatim; this
     * constant is the single place to change if a true 14-day half-life is preferred.
     */
    const val RECENCY_DECAY_DAYS: Double = 14.0

    /** Modest quality multiplier for a play that reached the "completed" bar. */
    const val COMPLETED_QUALITY_WEIGHT: Float = 1.5f

    /** Quality multiplier for a qualifying but non-completed play. */
    const val BASE_QUALITY_WEIGHT: Float = 1.0f

    /**
     * Minimum qualifying in-window events before an artist earns ANY boost. Guards against
     * a single incidental play re-ordering the editorial roster (cold-start discipline).
     */
    const val MIN_QUALIFYING_EVENTS: Int = 2

    /**
     * How many recent history rows to sample for one affinity pass. Bounded so the pass is
     * a single, cheap query + in-memory fold; the 30-day window does the real filtering.
     */
    const val HISTORY_SAMPLE_LIMIT: Int = 500

    /**
     * How far in the FUTURE a stored timestamp may sit and still be treated as a real listen.
     *
     * Why a tolerance exists at all: `listenedAt` is stamped with `System.currentTimeMillis()`
     * when the session is written, and `nowMs` is read from the same device clock when the shelf
     * is built. With a stable clock the difference is therefore never positive. A small positive
     * skew means only that the wall clock stepped BACKWARDS between the write and the read — an
     * NTP correction — and the row is in truth a just-recorded listen that must not be discarded.
     *
     * Why five minutes: it comfortably absorbs an NTP step (normally sub-second, larger only
     * after a long offline period) while being 0.0116% of [HISTORY_WINDOW_DAYS] and ~0.025% of
     * [RECENCY_DECAY_DAYS], so it cannot meaningfully extend eligibility or shift ranking. It is
     * also orders of magnitude smaller than a genuine clock misconfiguration (hours to months),
     * which is exactly what must be rejected.
     */
    const val FUTURE_TOLERANCE_MS: Long = 5 * 60 * 1000L

    private const val MS_PER_DAY: Double = 24.0 * 60.0 * 60.0 * 1000.0
    private val HISTORY_WINDOW_MS: Long = HISTORY_WINDOW_DAYS * 24L * 60L * 60L * 1000L

    /**
     * Playback origins that may influence personalization at all (Phase 2D).
     *
     * Only deliberate, user-chosen playback counts: a track the user picked out of a Songs
     * search, or one they had already liked. Everything else — Home, radio autoplay, playlists,
     * video search, OTHER, and the fail-closed LEGACY_UNKNOWN — is excluded, so listening the
     * user never chose cannot reshape the shelf.
     */
    val ELIGIBLE_ORIGINS: Set<PlaybackOrigin> =
        setOf(PlaybackOrigin.SEARCH_SONGS, PlaybackOrigin.LIKED_SONGS)

    /**
     * The single source of truth for provenance eligibility, shared with
     * [FeaturedArtistSelector] so ORDERING and MEMBERSHIP can never drift apart.
     *
     * Fails closed in both directions: rows written before Phase 2A carry
     * [PlaybackOrigin.LEGACY_UNKNOWN] and are ineligible, and they are never relabelled or
     * given a fabricated origin. The resulting cold start is deliberate — on an upgrade,
     * personalization simply has no signal until new provenance-stamped sessions accumulate,
     * and until then the editorial roster is returned unchanged.
     */
    fun isEligibleForPersonalization(item: HistoryItem): Boolean =
        item.sourceSection in ELIGIBLE_ORIGINS

    /**
     * The single source of truth for timestamp eligibility, shared with [FeaturedArtistSelector]
     * so ORDERING and MEMBERSHIP can never drift apart (FA-001).
     *
     * @return the age to score the event with, or `null` when the row must not participate at
     * all — neither in affinity nor in the event counts that gate [MIN_QUALIFYING_EVENTS] and
     * [FeaturedArtistSelector.MIN_PERSONAL_CANDIDATE_EVENTS]. A rejected row is dropped outright
     * rather than merely scored low, because a low score would still let it buy admission.
     *
     * Boundaries, chosen to preserve the behaviour that was already in place:
     *  - `listenedAt == nowMs - HISTORY_WINDOW_MS` (exactly at the window edge) is INCLUDED,
     *    matching the previous exclusive `age > window` test.
     *  - `listenedAt == nowMs + FUTURE_TOLERANCE_MS` (exactly at the tolerance edge) is INCLUDED
     *    and scored as age 0, matching the previous clamp for a just-recorded listen.
     *  - Anything beyond either edge is rejected.
     *
     * Overflow: the two bounds are derived from [nowMs], a wall-clock value, so they cannot wrap;
     * [listenedAt] is only ever COMPARED until it has been bounded, so `Long.MIN_VALUE` and
     * `Long.MAX_VALUE` fall out as `null` without any subtraction being performed on them.
     */
    fun scoringAgeMsOrNull(nowMs: Long, listenedAt: Long): Long? {
        val oldestAllowed = nowMs - HISTORY_WINDOW_MS
        val newestAllowed = nowMs + FUTURE_TOLERANCE_MS

        if (listenedAt > newestAllowed) return null
        if (listenedAt < oldestAllowed) return null

        // Within tolerance a future timestamp is a just-recorded listen: clamp it to age 0 so it
        // keeps maximum recency, exactly as before.
        return (nowMs - listenedAt).coerceAtLeast(0L)
    }

    /**
     * Public artist slug, e.g. "Beyoncé" -> "beyonce", retaining native script marks.
     * Slugs are lossy routing identifiers, NOT equality proof; match display names instead.
     */
    fun slugify(name: String): String = ArtistIdentity.slugify(name)

    /**
     * Compatibility API for name-derived keys, never authoritative IDs. Only the explicit
     * Rahman alias is separator-tolerant. Empty/invalid names cannot participate in matching.
     */
    fun identityKey(slug: String): String? = ArtistIdentity.nameKey(slug)

    /**
     * Finds qualifying strict name buckets for [artistId] (legacy parameter name).
     *
     * The argument must be a display name or strict name-derived key, not an opaque ID.
     * Alias buckets are summed only AFTER independently meeting the history threshold.
     */
    fun lookupAffinity(affinity: Map<String, Float>, artistId: String): Float? {
        val key = ArtistIdentity.nameKey(artistId) ?: return null
        var found = false
        var total = 0f
        for ((nameKey, score) in affinity) {
            if (ArtistIdentity.nameKey(nameKey) == key) {
                found = true
                total += score
            }
        }
        return if (found) total else null
    }

    /**
     * Folds listening history into `strict raw name key -> affinity` scores. Common Latin
     * names still use whitespace-to-hyphen keys, but accents and punctuation are not lost.
     *
     * For every event inside [HISTORY_WINDOW_DAYS] whose provenance satisfies
     * [isEligibleForPersonalization]:
     *   `score += exp(-daysSincePlay / RECENCY_DECAY_DAYS) * (completed ? 1.5 : 1.0)`
     *
     * Artists with fewer than [MIN_QUALIFYING_EVENTS] ELIGIBLE in-window events are omitted
     * entirely, so callers treat them as having no affinity. Ineligible events cannot help an
     * artist reach that threshold and cannot raise the score of one that already has. The same
     * is true of rows rejected by [scoringAgeMsOrNull] — stale, or implausibly future-dated.
     *
     * @param nowMs caller-supplied "now" so the result is deterministic and testable.
     */
    fun calculateAffinity(history: List<HistoryItem>, nowMs: Long): Map<String, Float> {
        if (history.isEmpty()) return emptyMap()

        val scores = HashMap<String, Float>()
        val eventCounts = HashMap<String, Int>()

        for (item in history) {
            // Phase 2D: ineligible provenance contributes NOTHING — not to the score, and not
            // to the event count that gates MIN_QUALIFYING_EVENTS below.
            if (!isEligibleForPersonalization(item)) continue

            // Shared timestamp policy: rejects stale rows AND implausibly future-dated ones, so
            // neither can score nor count toward MIN_QUALIFYING_EVENTS below.
            val ageMs = scoringAgeMsOrNull(nowMs, item.listenedAt) ?: continue

            val nameKey = ArtistIdentity.rawNameKey(item.track.artist) ?: continue

            val recencyWeight = exp(-(ageMs / MS_PER_DAY) / RECENCY_DECAY_DAYS).toFloat()
            val qualityWeight =
                if (item.completed) COMPLETED_QUALITY_WEIGHT else BASE_QUALITY_WEIGHT

            scores[nameKey] = (scores[nameKey] ?: 0f) + recencyWeight * qualityWeight
            eventCounts[nameKey] = (eventCounts[nameKey] ?: 0) + 1
        }

        if (scores.isEmpty()) return emptyMap()
        return scores.filterKeys { (eventCounts[it] ?: 0) >= MIN_QUALIFYING_EVENTS }
    }

    /**
     * Returns the backend [roster] re-ordered by local affinity, highest first.
     *
     * Guarantees:
     *  - The returned list is a permutation of [roster] — same artists, same metadata.
     *    Nothing is added, removed, or mutated (a NEW list is returned; [roster] is
     *    left untouched).
     *  - Artists with no affinity score sort as 0.
     *  - The sort is STABLE, so artists with equal affinity (including the all-zero
     *    cold-start case) keep the backend's editorial order.
     *  - On empty history, no scores, or no roster artist matching the user's history,
     *    [roster] is returned unchanged rather than fabricating personalization.
     */
    fun personalize(
        roster: List<FeaturedArtist>,
        history: List<HistoryItem>,
        nowMs: Long
    ): List<FeaturedArtist> {
        if (roster.isEmpty() || history.isEmpty()) return roster

        val affinity = calculateAffinity(history, nowMs)
        if (affinity.isEmpty()) return roster
        val ambiguousKeys = ArtistIdentity.ambiguousEditorialKeys(roster)
        return roster.sortedByDescending {
            if (ArtistIdentity.nameKey(it.name) in ambiguousKeys) 0f
            else lookupAffinity(affinity, it.name) ?: 0f
        }
    }
}
